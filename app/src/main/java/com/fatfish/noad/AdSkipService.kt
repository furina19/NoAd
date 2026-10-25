package com.fatfish.noad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 广告跳过无障碍服务 v1.2
 *
 * v1.2 修复：
 *   1. "1S | 跳过"（数字在前）识别 → 支持倒计时前置/后置两种格式
 *   2. 多广告页面误点广告主体 → 评分制 + 尺寸/位置硬约束 + 广告标签黑名单
 *   3. 视频广告跳过按钮延迟出现 → 事件后追加延迟重试扫描
 *   4. 信息流广告的 "×" 关闭按钮 → 支持 × / ✕ 符号识别
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
        )

        // 促销文案特征词（大段文案 = 广告主体，不点）
        private val PROMO_WORDS = listOf(
            "立即下载", "免费领取", "立即领取", "点击进入", "立即购买",
            "惊喜", "优惠", "红包", "秒杀", "限量", "点击到落地页",
            "第三方应用", "立即体验", "立即查看",
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
        private const val MAX_SAME_PKG_SKIP = 3        // 同包名连续跳过上限
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
        root.recycle()

        if (candidates.isEmpty()) return

        // 排序：分数高优先，同分取面积小的（跳过按钮通常更小巧）
        candidates.sortWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.area }
        )

        val best = candidates.first()
        if (performClick(best.node, pkg)) {
            lastSkipTime = System.currentTimeMillis()
            if (pkg == lastSkippedPkg) samePkgSkipCount++ else {
                samePkgSkipCount = 1
                lastSkippedPkg = pkg
            }
        }

        // 回收所有候选节点
        for (c in candidates) c.node.recycle()
    }

    // ========================================================================
    // 候选收集
    // ========================================================================

    /**
     * 广度遍历整棵节点树，收集所有疑似跳过按钮
     */
    private fun collectCandidates(root: AccessibilityNodeInfo): List<Candidate> {
        val result = ArrayList<Candidate>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++

            try {
                if (depth <= MAX_DEPTH) {
                    val score = evaluate(node)
                    if (score >= MIN_SCORE_TO_CLICK) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        result.add(
                            Candidate(node, score, rect.width() * rect.height())
                        )
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

    // ========================================================================
    // 节点评分（核心）
    // ========================================================================

    /**
     * 给一个节点打分：
     *   100 = 强关键词精确匹配 / 倒计时格式
     *   85  = × 符号 + 小尺寸
     *   80  = 弱关键词 + 广告上下文
     *   70  = 弱关键词 + 屏幕角落
     *   0   = 不是跳过按钮
     */
    private fun evaluate(node: AccessibilityNodeInfo): Int {
        if (!node.isVisibleToUser) return 0
        if (!node.isClickable) return 0

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
            // 只在明确广告上下文（附近有"广告"标签）时点击
            // 防止流氓 App 的假 × 把用户带去落地页
            return if (hasAdContext(node)) 85 else 0
        }

        // ====== 3.5 内容描述为"关闭"的小按钮（信息流广告卡片 × 常无文字只有描述） ======
        // 必须配合广告上下文，避免误关正常弹窗/下载进度
        if ((cd.equals("关闭", ignoreCase = true) || cd.equals("close", ignoreCase = true)) &&
            wDp <= 100 && hDp <= 100
        ) {
            return if (hasAdContext(node)) 80 else 0
        }

        // ====== 4. 弱关键词（跳过/skip）需上下文 ======
        var matchedWeak = false
        for (k in WEAK_KEYWORDS) {
            if (label.equals(k, ignoreCase = true)) { matchedWeak = true; break }
            if (label.length <= 8 && label.contains(k, ignoreCase = true)) { matchedWeak = true; break }
        }
        if (matchedWeak) {
            if (hasAdContext(node)) return 85
            if (isCorner(rect)) return 70
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

        val parent = node.parent ?: return false
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

    private fun performClick(node: AccessibilityNodeInfo, pkg: String): Boolean {
        val clicked = try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
        if (clicked) {
            AdStatsManager.recordSkip(this, pkg)
            showSkipNotification(pkg)
        }
        return clicked
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
        val area: Int
    )
}