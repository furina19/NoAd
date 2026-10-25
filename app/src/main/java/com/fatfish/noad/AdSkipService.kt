package com.fatfish.noad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 广告跳过无障碍服务 v1.3
 *
 * v1.3 重大修复（针对真实广告截图）：
 *   1. 跳过按钮文字节点不可点击 → 向上找可点击父容器（最多3层）
 *   2. WebView/自定义 View 广告点击无效 → dispatchGesture 坐标点击兜底
 *   3. 拒绝点广告主体的同时，允许对"小按钮坐标"精准手势点击
 *
 * 核心设计：先收集所有候选节点 → 评分 → 排序 → 只点分数最高且最"像跳过按钮"的那个
 */
class AdSkipService : AccessibilityService() {

    companion object {
        // ============ 系统包名黑名单（绝对不碰） ============
        private val SYSTEM_PACKAGES_EXACT = setOf(
            "com.android.systemui",              // 状态栏 / 通知栏 / 快捷开关
            "com.android.settings",              // 系统设置
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.dialer",
            "com.android.phone",
            "com.android.contacts",
            "com.android.mms",
            "com.android.documentsui",
            "com.android.keyguard",
            "com.android.incallui",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.googlequicksearchbox",
            "com.google.android.permissioncontroller",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
        )
        private val SYSTEM_PACKAGES_PREFIX = listOf(
            "com.coloros.", "com.oppo.", "com.xiaomi.", "com.miui.",
            "com.huawei.android.", "com.samsung.android.", "com.vivo.",
            "com.bbk.", "com.oplus.",
        )

        // ============ 关键词分组 ============
        // 强关键词：精确出现即 100% 确认是广告跳过
        private val STRONG_KEYWORDS = listOf(
            "跳过广告", "跳過廣告", "关闭广告", "關閉廣告",
            "skip ad", "close ad", "skip ads", "close ads",
            "关闭此广告", "不感兴趣",
        )

        // 弱关键词：需要广告上下文或位置佐证
        private val WEAK_KEYWORDS = listOf("跳过", "跳過", "skip")

        // 弹窗广告常见按钮
        private val DIALOG_KEYWORDS = listOf(
            "我知道了", "不再提醒", "朕知道了", "知道啦", "我知道了哦",
        )

        // 关闭符号（信息流广告卡片角落的 ×）
        private val CLOSE_SYMBOLS = setOf(
            "×", "✕", "╳", "✖", "❌", "✗",
        )

        // 广告标签（绝不能点！点它 = 跳转广告落地页）
        private val AD_LABELS = setOf(
            "广告", "廣告", "AD", "Ad", "ad", "赞助", "推广", "推广内容",
        )

        // 广告上下文词（用于佐证弱关键词）
        private val AD_CONTEXT_WORDS = listOf(
            "广告", "廣告", "推广", "sponsored", "ad",
            "悬浮", "悬浮球", "浮窗",  // 悬浮广告条特征（运动校园等 App 常见）
        )

        // 促销文案特征词（大段文案 = 广告主体，不点）
        private val PROMO_WORDS = listOf(
            "立即下载", "免费领取", "立即领取", "点击进入", "立即购买",
            "惊喜", "优惠", "红包", "秒杀", "限量", "点击到落地页",
            "第三方应用", "立即体验", "立即查看",
        )

        // 正常功能按钮排除词（不是广告，不点）
        private val SKIP_EXCLUDE_WORDS = listOf(
            "跳过教程", "跳過教程", "跳过引导", "跳过介绍", "跳过新手",
            "跳过设置", "跳过此步", "跳过验证",
        )

        // ============ 倒计时正则（视频/开屏广告） ============
        // 格式1："跳过 5s" / "跳过3" / "skip 5 seconds"
        private val RE_COUNTDOWN_AFTER = Regex(
            """^\s*(跳过|跳過|skip)\s*\d+(?:\.\d+)?\s*(?:[sS秒]|seconds?)?\s*$""",
            RegexOption.IGNORE_CASE
        )
        // 格式2："1S | 跳过" / "5s 跳过" / "3|跳過"（数字在前！主人截图中的关键格式）
        private val RE_COUNTDOWN_BEFORE = Regex(
            """^\s*\d+(?:\.\d+)?\s*(?:[sS秒])?\s*(?:[|｜/·:：\-—]\s*)?(跳过|跳過|skip)\s*$""",
            RegexOption.IGNORE_CASE
        )
        // 宽松包含式：文本里同时有倒计时和跳过
        private val RE_COUNTDOWN_LOOSE = Regex(
            """(跳过|跳過|skip).{0,10}\d+\s*[sS秒]|\d+\s*[sS秒].{0,10}(跳过|跳過|skip)""",
            RegexOption.IGNORE_CASE
        )

        // ============ 运行参数 ============
        private const val SCAN_THROTTLE_MS = 350L      // 两次扫描最小间隔
        private const val MIN_SCORE_TO_CLICK = 70      // 点击阈值（分数制满分100）
        private const val MAX_SAME_PKG_SKIP = 5        // 同包名连续跳过上限（一次页面可能多个广告）
        private const val SAME_PKG_WINDOW_MS = 10_000L // 同包名限流窗口
        private const val MAX_NODES = 600              // 单次遍历节点上限
        private const val MAX_DEPTH = 26               // 遍历深度上限
        private const val MIN_WIDTH_DP = 20f
        private const val MAX_WIDTH_DP = 360f
        private const val MIN_HEIGHT_DP = 18f
        private const val MAX_HEIGHT_DP = 160f
    }

    // ============ 运行时状态 ============
    private val handler = Handler(Looper.getMainLooper())
    private var lastScanTime = 0L
    private var lastSkipTime = 0L
    private var samePkgSkipCount = 0
    private var lastSkippedPkg = ""
    private var lastEventPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 200
        }
    }

    // ========================================================================
    // 事件入口
    // ========================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // 排除自己
        if (pkg == packageName) return
        // 排除系统应用（状态栏/设置/下载管理器等）
        if (isSystemPackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 节流
                val now = System.currentTimeMillis()
                if (now - lastScanTime < SCAN_THROTTLE_MS) return
                lastScanTime = now

                lastEventPkg = pkg
                scheduleScans(pkg)
            }
        }
    }

    /**
     * 扫描调度：立即扫一次，再延迟补扫几次
     * （视频广告的跳过按钮经常在 1~3 秒后才出现）
     */
    private fun scheduleScans(pkg: String) {
        handler.removeCallbacksAndMessages(null)
        scanAndClick(pkg)
        for (delay in longArrayOf(700L, 1800L, 3200L)) {
            handler.postDelayed({
                if (lastEventPkg == pkg) scanAndClick(pkg)
            }, delay)
        }
    }

    // ========================================================================
    // 扫描主流程
    // ========================================================================

    private fun scanAndClick(pkg: String) {
        // 同包名限流（防止反复点击死循环）
        if (pkg == lastSkippedPkg) {
            if (System.currentTimeMillis() - lastSkipTime > SAME_PKG_WINDOW_MS) {
                samePkgSkipCount = 0
            }
            if (samePkgSkipCount >= MAX_SAME_PKG_SKIP) return
        }

        val root = rootInActiveWindow ?: return
        // 确认窗口还是这个应用（防止用户已切换 App / 回到桌面后误点）
        val activePkg = root.packageName?.toString()
        if (activePkg != null && activePkg != pkg) {
            root.recycle()
            return
        }

        val candidates = collectCandidates(root)

        if (DebugLogger.isEnabled(this)) {
            DebugLogger.log(this, "扫描 $pkg → 候选 ${candidates.size} 个")
            candidates.sortedWith(
                compareByDescending<Candidate> { it.score }.thenBy { it.area }
            ).take(5).forEach { c ->
                val r = Rect()
                c.node.getBoundsInScreen(r)
                DebugLogger.log(
                    this,
                    "  候选 score=${c.score} gesture=${c.gestureOnly} " +
                    "bounds=${r.width()}x${r.height()} label=${labelOf(c.node)}"
                )
            }
            if (candidates.isEmpty()) {
                dumpKeywordNodes(root, pkg)
            }
        }

        root.recycle()

        if (candidates.isEmpty()) return

        // 排序：分数高优先，同分取面积小的（跳过按钮通常更小巧）
        val sortedCandidates = candidates.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.area }
        )

        val best = sortedCandidates.first()
        if (performClick(best, pkg)) {
            lastSkipTime = System.currentTimeMillis()
            if (pkg == lastSkippedPkg) samePkgSkipCount++ else {
                samePkgSkipCount = 1
                lastSkippedPkg = pkg
            }
            DebugLogger.log(this, "✅ 点击成功 $pkg label=${labelOf(best.node)}")
        } else {
            DebugLogger.log(this, "❌ 点击失败 $pkg label=${labelOf(best.node)}")
        }

        // 回收所有候选节点
        for (c in candidates) c.node.recycle()
    }

    /**
     * 取节点文本（调试用）
     */
    private fun labelOf(node: AccessibilityNodeInfo): String {
        return try {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            (if (t.isNotEmpty()) t else d).replace("\n", " ")
        } catch (_: Exception) {
            "?"
        }
    }

    /**
     * 调试用：dump 页面里含"跳/关/广告/×"等关键词的节点
     * 用于诊断"为什么这个广告没被识别"
     */
    private fun dumpKeywordNodes(root: AccessibilityNodeInfo, pkg: String) {
        val keywords = listOf("跳", "关", "关", "×", "x", "X", "广告", "ad", "skip", "close")
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        var dumped = 0
        DebugLogger.log(this, "  未找到候选，页面关键词节点：")

        while (stack.isNotEmpty() && visited < MAX_NODES && dumped < 15) {
            val node = stack.removeLast()
            visited++
            try {
                val label = labelOf(node)
                if (label.length in 1..30) {
                    for (k in keywords) {
                        if (label.contains(k, ignoreCase = true)) {
                            val r = Rect()
                            node.getBoundsInScreen(r)
                            DebugLogger.log(
                                this,
                                "    「$label」clickable=${node.isClickable} " +
                                "visible=${node.isVisibleToUser} " +
                                "bounds=${r.width()}x${r.height()} " +
                                "class=${node.className?.toString()?.substringAfterLast('.')}"
                            )
                            dumped++
                            break
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    stack.addLast(child)
                }
            } catch (_: Exception) {
            }
        }
    }

    // ========================================================================
    // 候选收集
    // ========================================================================

    /**
     * 广度遍历整棵节点树，收集所有疑似跳过按钮
     * 关键：文本节点本身不可点击时，向上找可点击父容器或降级为坐标点击
     */
    private fun collectCandidates(root: AccessibilityNodeInfo): List<Candidate> {
        val result = ArrayList<Candidate>()
        // 按点击目标身份去重（多个文字节点可能指向同一个父容器）
        val seenTargets = Collections.newSetFromMap(
            IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        )
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++

            try {
                if (depth <= MAX_DEPTH) {
                    val candidate = evaluateNode(node)
                    if (candidate != null && seenTargets.add(candidate.node)) {
                        result.add(candidate)
                    }
                }

                if (depth < MAX_DEPTH) {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        stack.addLast(child to (depth + 1))
                    }
                }
            } catch (_: Exception) {
                // 节点在遍历过程中失效（页面跳转/刷新），跳过即可
            }
        }
        return result
    }

    /**
     * 评估节点并给出点击计划
     *
     * 广告跳过按钮的真实结构往往是：
     *   FrameLayout(可点击) > 背景图 + TextView("跳过 4")(不可点击)
     * 或 WebView 里 JS 绑定的 div（无障碍认为不可点击）
     * 所以：文本匹配后用「可点击祖先」或「坐标手势」来真正点下去
     */
    private fun evaluateNode(node: AccessibilityNodeInfo): Candidate? {
        // 1. 文本/位置评分（用节点自身 bounds 做尺寸检查）
        val score = scoreNode(node)
        if (score < MIN_SCORE_TO_CLICK) return null

        // 2. 确定点击目标
        var clickNode = node
        var gestureOnly = false

        if (!node.isClickable) {
            val ancestor = findClickableAncestor(node, 3)
            if (ancestor != null) {
                val aRect = Rect()
                ancestor.getBoundsInScreen(aRect)
                val density = resources.displayMetrics.density
                val aW = aRect.width() / density
                val aH = aRect.height() / density
                // 祖先尺寸合理（就是按钮大小）→ 直接点祖先
                if (aW in MIN_WIDTH_DP..MAX_WIDTH_DP && aH in MIN_HEIGHT_DP..MAX_HEIGHT_DP) {
                    clickNode = ancestor
                } else {
                    // 祖先太大（整个广告卡片）→ 不点大卡片，改为对文字节点坐标做手势点击
                    gestureOnly = true
                }
            } else {
                // 没有可点击祖先 → 坐标手势点击
                gestureOnly = true
            }
        }

        val rect = Rect()
        clickNode.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null

        return Candidate(
            node = clickNode,
            score = score,
            area = rect.width() * rect.height(),
            gestureOnly = gestureOnly
        )
    }

    /**
     * 向上找最近的可点击祖先
     */
    private fun findClickableAncestor(
        node: AccessibilityNodeInfo,
        maxDepth: Int
    ): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    // ========================================================================
    // 节点评分（核心）
    // ========================================================================

    /**
     * 给一个节点打分（只看文本和尺寸，不要求节点可点击）：
     *   100 = 强关键词精确匹配 / 倒计时格式
     *   85  = × 符号 + 小尺寸
     *   80  = 弱关键词 + 广告上下文
     *   70  = 弱关键词 + 屏幕边缘位置
     *   0   = 不是跳过按钮
     */
    private fun scoreNode(node: AccessibilityNodeInfo): Int {
        if (!node.isVisibleToUser) return 0

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return 0

        val density = resources.displayMetrics.density
        val wDp = rect.width() / density
        val hDp = rect.height() / density

        // ====== 尺寸硬约束（防误点广告主体） ======
        // 跳过按钮绝不会太大：广告图片/视频/卡片都会被这里拦下
        if (wDp < MIN_WIDTH_DP || hDp < MIN_HEIGHT_DP) return 0
        if (wDp > MAX_WIDTH_DP || hDp > MAX_HEIGHT_DP) return 0

        // ====== 排除明显不是按钮的控件 ======
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText") ||
            className.contains("SeekBar") ||
            className.contains("ProgressBar") ||
            className.contains("Switch") ||
            className.contains("ScrollView") ||
            className.contains("RecyclerView") ||
            className.contains("ListView")) {
            return 0
        }

        val text = node.text?.toString()?.trim() ?: ""
        val cd = node.contentDescription?.toString()?.trim() ?: ""
        val label = if (text.isNotEmpty()) text else cd

        if (label.isEmpty()) return 0

        // ====== 黑名单：广告标签（点了会跳转！） ======
        if (AD_LABELS.contains(label)) return 0
        // 单独的 "广告" 变体（如 "广告 ×" 已在上方精确匹配处理）
        val labelNoSpace = label.replace(" ", "")
        if (AD_LABELS.contains(labelNoSpace)) return 0

        // ====== 长文案 = 广告主体，绝不点 ======
        if (label.length > 24) return 0
        for (promo in PROMO_WORDS) {
            if (label.contains(promo, ignoreCase = true)) return 0
        }
        // ====== 正常功能按钮（跳过教程等）排除 ======
        for (exclude in SKIP_EXCLUDE_WORDS) {
            if (label.contains(exclude, ignoreCase = true)) return 0
        }

        // ====== 1. 强关键词 ======
        for (k in STRONG_KEYWORDS) {
            if (label.equals(k, ignoreCase = true)) return 100
            if (label.length <= 12 && label.contains(k, ignoreCase = true)) return 100
        }
        // 内容描述里的强关键词
        if (cd.isNotEmpty()) {
            for (k in STRONG_KEYWORDS) {
                if (cd.equals(k, ignoreCase = true)) return 100
                if (cd.length <= 12 && cd.contains(k, ignoreCase = true)) return 95
            }
        }

        // ====== 2. 倒计时格式（视频/开屏广告） ======
        if (RE_COUNTDOWN_AFTER.matches(label)) return 100
        if (RE_COUNTDOWN_BEFORE.matches(label)) return 100
        if (RE_COUNTDOWN_LOOSE.containsMatchIn(label)) return 95

        // ====== 3. 关闭符号 × ======
        if (CLOSE_SYMBOLS.contains(label)) {
            if (wDp > 120 || hDp > 120) return 0
            // 有广告上下文 → 高置信度
            if (hasAdContext(node)) return 85
            // 位于屏幕边缘/角落 → 悬浮广告/卡片 × 常见位置
            if (isEdgeClosePosition(rect)) return 75
            return 0
        }

        // ====== 3.5 内容描述为"关闭"的小按钮（信息流广告卡片 × 常无文字只有描述） ======
        if ((cd.equals("关闭", ignoreCase = true) || cd.equals("close", ignoreCase = true)) &&
            wDp <= 100 && hDp <= 100
        ) {
            if (hasAdContext(node)) return 80
            if (isCorner(rect)) return 72
            return 0
        }

        // ====== 3.6 文字为"关闭"的小按钮（弹窗/悬浮广告常见，但需严格限制） ======
        if (label.equals("关闭", ignoreCase = true) || label.equals("close", ignoreCase = true)) {
            if (wDp > 120 || hDp > 100) return 0
            if (hasAdContext(node)) return 80
            if (isCorner(rect)) return 72
            return 0  // 不在顶部角落 + 无广告上下文 → 不点（防误关下载/通知）
        }

        // ====== 4. 弱关键词（跳过/skip）需上下文 ======
        var matchedWeak = false
        for (k in WEAK_KEYWORDS) {
            if (label.equals(k, ignoreCase = true)) { matchedWeak = true; break }
            if (label.length <= 8 && label.contains(k, ignoreCase = true)) { matchedWeak = true; break }
        }
        if (matchedWeak) {
            if (hasAdContext(node)) return 85
            if (isEdgeClosePosition(rect)) return 70
            return 0 // 无佐证 → 不点
        }

        // ====== 5. 弹窗广告按钮 ======
        for (k in DIALOG_KEYWORDS) {
            if (label.equals(k, ignoreCase = true) && wDp <= 260) {
                return if (hasAdContext(node)) 80 else 0
            }
        }

        return 0
    }

    // ========================================================================
    // 辅助判断
    // ========================================================================

    /**
     * 检查节点附近是否有"广告"上下文
     * 只看父节点的直接子节点 + 节点自身，避免遍历过深误判
     */
    private fun hasAdContext(node: AccessibilityNodeInfo): Boolean {
        val ownText = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        for (adWord in AD_CONTEXT_WORDS) {
            if (ownText.contains(adWord, ignoreCase = true)) return true
        }

        // 检查父节点的兄弟（第一级）
        val parent = node.parent
        if (parent != null) {
            if (checkSiblingsForAdContext(parent)) return true
            // 检查祖父节点的兄弟（第二级）：悬浮广告条的×和"悬浮球"文案可能隔了两层
            val grandparent = parent.parent
            if (grandparent != null && checkSiblingsForAdContext(grandparent)) return true
        }
        return false
    }

    /**
     * 检查某个节点的所有直接子节点是否包含广告上下文词
     */
    private fun checkSiblingsForAdContext(parent: AccessibilityNodeInfo): Boolean {
        try {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                val sibText = (sibling.text?.toString() ?: "") + " " +
                              (sibling.contentDescription?.toString() ?: "")
                for (adWord in AD_CONTEXT_WORDS) {
                    if (sibText.contains(adWord, ignoreCase = true)) {
                        sibling.recycle()
                        return true
                    }
                }
                sibling.recycle()
            }
        } catch (_: Exception) {
            // 忽略节点已失效的异常
        }
        return false
    }

    /**
     * 是否位于屏幕左上/右上角区域（开屏广告跳过按钮的典型位置）
     */
    private fun isCorner(rect: Rect): Boolean {
        val dm = resources.displayMetrics
        val inTopBand = rect.top < dm.heightPixels * 0.35f
        val inLeftHalf = rect.right < dm.widthPixels * 0.55f
        val inRightHalf = rect.left > dm.widthPixels * 0.45f
        return inTopBand && (inLeftHalf || inRightHalf)
    }

    /**
     * 是否位于「关闭按钮常见位置」：
     *   1. 顶部左右角落（开屏广告的跳过按钮）
     *   2. 屏幕左右边缘、上 70% 区域（信息流广告卡片的 × 可能在页面中部）
     * 排除底部 20% 区域，避免误点浏览器下载进度条的关闭按钮
     */
    private fun isEdgeClosePosition(rect: Rect): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        // 顶部角落
        if (isCorner(rect)) return true

        // 底部 20% 区域排除（下载通知/底部导航按钮）
        if (rect.top > h * 0.8f) return false

        // 右侧边缘（上 70% 区域）：信息流广告卡片 ×
        if (rect.left > w * 0.65f && rect.top < h * 0.7f) return true

        // 左侧边缘（上 70% 区域）
        if (rect.right < w * 0.35f && rect.top < h * 0.7f) return true

        return false
    }

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg in SYSTEM_PACKAGES_EXACT) return true
        for (prefix in SYSTEM_PACKAGES_PREFIX) {
            if (pkg.startsWith(prefix)) return true
        }
        return false
    }

    // ========================================================================
    // 点击 & 通知
    // ========================================================================

    /**
     * 执行点击：先试无障碍 ACTION_CLICK，失败则用坐标手势点击
     * （WebView 广告、自定义 View 的点击事件往往不响应 ACTION_CLICK）
     */
    private fun performClick(candidate: Candidate, pkg: String): Boolean {
        var clicked = false

        // 1. 优先无障碍点击（gestureOnly=false 时）
        if (!candidate.gestureOnly) {
            clicked = try {
                candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {
                false
            }
        }

        // 2. 兜底：坐标手势点击
        if (!clicked) {
            val rect = Rect()
            try {
                candidate.node.getBoundsInScreen(rect)
            } catch (_: Exception) {
                return false
            }
            if (rect.width() > 0 && rect.height() > 0) {
                clicked = clickByGesture(rect.exactCenterX(), rect.exactCenterY())
            }
        }

        if (clicked) {
            AdStatsManager.recordSkip(this, pkg)
            showSkipNotification(pkg)
        }
        return clicked
    }

    /**
     * 通过无障碍手势在指定坐标点击
     */
    private fun clickByGesture(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun showSkipNotification(pkg: String) {
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = android.app.Notification.Builder(this, NoAdApp.CHANNEL_SKIP_REPORT)
                .setContentTitle("🐟 已跳过广告")
                .setContentText("来自 $appName")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setAutoCancel(true)
                .setOngoing(false)
                .setTimeoutAfter(2500)
                .build()

            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(System.currentTimeMillis().hashCode(), notification)
            } catch (_: Exception) {
                // 通知权限被拒时忽略
            }
        }
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ========================================================================
    // 数据类
    // ========================================================================

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val area: Int,
        /** true = 直接坐标手势点击（节点不可点击且祖先太大/不存在） */
        val gestureOnly: Boolean = false
    )
}