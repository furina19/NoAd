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

/**
 * 广告跳过无障碍服务 v1.4
 *
 * v1.4 重写——架构从「评分文字制」切换为「返回键优先 + 认控件 + 防连环」：
 *   1. 全屏/插屏广告 → performGlobalAction(GLOBAL_ACTION_BACK)，不找 ×
 *   2. 认控件不认字：包名 + viewId 优先，文字只做最后兜底
 *   3. 防连环：同包名 2s 内 3 次关闭 → 全局静默 3s
 *   4. 防误点：每次点击前重取 rootInActiveWindow + 危险文字拉黑
 *   5. 一页多广告：从右上到左下排序，关一个就重扫再关下一个
 */
class AdSkipService : AccessibilityService() {

    companion object {
        // ============ 广告 SDK 包名特征 ============
        private val AD_SDK_PACKAGES = listOf(
            "com.bytedance.sdk",       // Pangle / ByteDance
            "com.bykv.vk",             // ByteDance Volcano
            "com.byted.pangle",        // Pangle alt
            "com.qq.e",                // Tencent GDT 优量汇
            "com.kwad",               // Kuaishou 快手
            "com.kuaishou",           // Kuaishou alt
            "com.baidu.mobads",        // Baidu
            "com.ss.android",          // 穿山甲 / Toutiao
            "com.sigmob",              // Sigmob
            "com.taobao.ad",           // Alibaba
        )

        // ============ 关闭按钮 viewId 特征 ============
        private val CLOSE_VIEW_ID_PATTERNS = listOf(
            "close", "btn_close", "img_close", "iv_close",
            "tt_splash_skip", "tt_dislike", "tt_close",
            "tt_ad_close", "tt_skip_btn",
            "dislike", "ad_close", "iv_skip", "skip_btn",
            "close_btn", "btn_skip",
        )

        // ============ 系统包名黑名单（绝对不碰） ============
        private val SYSTEM_PACKAGES_EXACT = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher", "com.android.launcher3",
            "com.android.dialer", "com.android.phone",
            "com.android.contacts", "com.android.mms",
            "com.android.documentsui", "com.android.keyguard",
            "com.android.incallui",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.googlequicksearchbox",
            "com.google.android.permissioncontroller",
            "com.google.android.deskclock", "com.android.deskclock",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
        )
        private val SYSTEM_PACKAGES_PREFIX = listOf(
            "com.coloros.", "com.oppo.", "com.xiaomi.", "com.miui.",
            "com.huawei.android.", "com.samsung.android.", "com.vivo.",
            "com.bbk.", "com.oplus.",
        )

        // ============ 危险文字（绝不点击 + 连带祖先拉黑） ============
        private val DANGEROUS_TEXTS = listOf(
            "下载", "安装", "查看详情", "领取", "立即打开",
            "立即下载", "免费领取", "立即领取", "点击进入",
            "立即购买", "立即体验", "立即查看", "点击下载",
            "惊喜", "优惠", "红包", "秒杀", "限量",
        )

        // ============ 文字兜底匹配（优先级最低） ============
        private val SKIP_TEXTS = listOf(
            "跳过广告", "跳過廣告", "关闭广告", "關閉廣告",
            "skip ad", "close ad", "关闭此广告",
            "跳过", "跳過", "skip", "关闭", "close",
        )
        private val CLOSE_SYMBOLS = setOf("×", "✕", "╳", "✖", "❌", "✗")

        // ============ 排除词（不是广告跳过按钮的功能按钮） ============
        private val SKIP_EXCLUDE_WORDS = listOf(
            "跳过教程", "跳過教程", "跳过引导", "跳过介绍",
            "跳过新手", "跳过设置", "跳过此步", "跳过验证",
        )

        // ============ 运行参数 ============
        private const val SCAN_THROTTLE_MS = 350L
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 26
        private const val MAX_CLICKS_PER_SCAN = 4

        // 防连环参数
        private const val CHAIN_WINDOW_MS = 2_000L
        private const val CHAIN_THRESHOLD = 3
        private const val COOLDOWN_MS = 3_000L

        // 尺寸硬约束
        private const val MIN_WIDTH_DP = 20f
        private const val MAX_WIDTH_DP = 360f
        private const val MIN_HEIGHT_DP = 18f
        private const val MAX_HEIGHT_DP = 160f

        // 优先级常量
        private const val PRIORITY_PKG_VIEWID = 1000   // 包名 + viewId 双命中
        private const val PRIORITY_PKG = 800           // 仅包名命中
        private const val PRIORITY_VIEWID = 600        // 仅 viewId 命中
        private const val PRIORITY_TEXT = 300          // 文字兜底
    }

    // ============ 运行时状态 ============
    private val handler = Handler(Looper.getMainLooper())
    private var lastScanTime = 0L
    private var lastEventPkg = ""
    // 防连环：每包名的动作时间戳
    private val pkgActionTimestamps = mutableMapOf<String, MutableList<Long>>()
    private var globalCooldownUntil = 0L

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

        if (pkg == packageName) return
        if (isSystemPackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastScanTime < SCAN_THROTTLE_MS) return
                lastScanTime = now
                lastEventPkg = pkg
                scheduleScans(pkg)
            }
        }
    }

    /**
     * 扫描调度：立即 + 延迟补扫（视频广告跳过按钮常延迟出现）
     */
    private fun scheduleScans(pkg: String) {
        handler.removeCallbacksAndMessages(null)
        processWindow(pkg)
        for (delay in longArrayOf(700L, 1800L, 3200L)) {
            handler.postDelayed({
                if (lastEventPkg == pkg) processWindow(pkg)
            }, delay)
        }
    }

    // ========================================================================
    // 主流程
    // ========================================================================

    private fun processWindow(pkg: String) {
        if (isInCooldown(pkg)) return

        val root = rootInActiveWindow ?: return
        val activePkg = root.packageName?.toString()
        if (activePkg != null && activePkg != pkg) {
            root.recycle()
            return
        }

        // ---- 第 1 条：全屏/插屏广告 → 返回键优先 ----
        if (isAdSdkWindow(root)) {
            root.recycle()
            if (performBack(pkg)) return
            // 返回键无效，重新取根走控件识别兜底
            val root2 = rootInActiveWindow ?: return
            processCandidates(root2, pkg)
            return
        }

        // ---- 第 2~5 条：控件识别 → 排序 → 逐个关闭 ----
        processCandidates(root, pkg)
    }

    /**
     * 判断是否为广告 SDK 独占窗口（全屏/插屏广告）
     * 条件：根包名来自广告 SDK，或节点树中 >30% 节点来自广告 SDK
     */
    private fun isAdSdkWindow(root: AccessibilityNodeInfo): Boolean {
        val rootPkg = root.packageName?.toString() ?: return false
        for (adPkg in AD_SDK_PACKAGES) {
            if (rootPkg.startsWith(adPkg)) return true
        }
        // 抽样子节点：检查广告 SDK 节点占比
        var total = 0
        var adNodes = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && total < 80) {
            val node = stack.removeLast()
            total++
            val pkg = node.packageName?.toString() ?: ""
            for (adPkg in AD_SDK_PACKAGES) {
                if (pkg.startsWith(adPkg)) { adNodes++; break }
            }
            for (i in 0 until minOf(node.childCount, 4)) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return total > 0 && adNodes.toFloat() / total > 0.3f
    }

    /**
     * 执行全局返回键关闭全屏/插屏广告
     */
    private fun performBack(pkg: String): Boolean {
        return try {
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            if (ok) {
                recordAction(pkg)
                AdStatsManager.recordSkip(this, pkg)
                showSkipNotification(pkg, "返回键")
                DebugLogger.log(this, "⬅ 返回键关闭 $pkg")
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    // ========================================================================
    // 候选收集 & 点击（第 2~5 条）
    // ========================================================================

    /**
     * 收集候选 → 按优先级 + 右上到左下排序 → 关一个重扫一个
     */
    private fun processCandidates(root: AccessibilityNodeInfo, pkg: String) {
        val candidates = collectCandidates(root)
        root.recycle()

        if (DebugLogger.isEnabled(this)) {
            DebugLogger.log(this, "扫描 $pkg → 候选 ${candidates.size} 个")
            candidates.take(5).forEach { c ->
                DebugLogger.log(this,
                    "  候选 pri=${c.priority} " +
                    "rect=${c.rect.width()}x${c.rect.height()} " +
                    "pos=(${c.rect.left},${c.rect.top}) " +
                    "label=${labelOf(c.clickNode)}")
            }
            if (candidates.isEmpty()) {
                val root2 = rootInActiveWindow ?: return
                dumpKeywordNodes(root2, pkg)
                root2.recycle()
                // 回收候选以防泄漏（空集时不会进入循环）
                candidates.forEach { try { it.clickNode.recycle() } catch (_: Exception) {} }
                return
            }
        }

        if (candidates.isEmpty()) {
            candidates.forEach { try { it.clickNode.recycle() } catch (_: Exception) {} }
            return
        }

        // 排序：优先级从高到低，同优先级的从右上到左下
        candidates.sortWith(
            compareByDescending<Candidate> { it.priority }
                .thenBy { it.rect.top }
                .thenByDescending { it.rect.right }
        )

        var closedCount = 0
        val closedRects = mutableListOf<Rect>()

        for (candidate in candidates) {
            if (closedCount >= MAX_CLICKS_PER_SCAN) break
            if (isInCooldown(pkg)) break

            // 去重：中心点落入已关闭区域则跳过
            val cx = candidate.rect.exactCenterX()
            val cy = candidate.rect.exactCenterY()
            if (closedRects.any { cr ->
                    cx in cr.left..cr.right && cy in cr.top..cr.bottom
                }) continue

            // ---- 第 4 条：重新取根节点，绝不复用旧节点/旧坐标 ----
            val freshRoot = try { rootInActiveWindow } catch (_: Exception) { null } ?: break
            val freshPkg = freshRoot.packageName?.toString()
            if (freshPkg != null && freshPkg != pkg) {
                freshRoot.recycle()
                break
            }

            // ---- 第 4 条：检查目标区域附近有没有危险文字 ----
            if (hasNearbyDangerText(freshRoot, candidate.rect)) {
                DebugLogger.log(this,
                    "⛔ 附近有危险文字，跳过 label=${labelOf(candidate.clickNode)}")
                freshRoot.recycle()
                try { candidate.clickNode.recycle() } catch (_: Exception) {}
                continue
            }
            freshRoot.recycle()

            // 重新取候选节点的 bounds（旧坐标可能已失效）
            val freshRect = Rect()
            try {
                candidate.clickNode.getBoundsInScreen(freshRect)
            } catch (_: Exception) {
                try { candidate.clickNode.recycle() } catch (_: Exception) {}
                continue
            }
            if (freshRect.width() <= 0 || freshRect.height() <= 0) {
                try { candidate.clickNode.recycle() } catch (_: Exception) {}
                continue
            }

            // 执行关闭
            val ok = performCloseAction(candidate.clickNode, pkg, freshRect)
            try { candidate.clickNode.recycle() } catch (_: Exception) {}

            if (ok) {
                closedCount++
                closedRects.add(freshRect)
                recordAction(pkg)

                // ---- 第 5 条：关一个就 break，让下次事件/重扫处理剩余广告 ----
                break
            }
        }

        // 回收本轮未处理的候选
        for (c in candidates) {
            try { c.clickNode.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 收集所有关闭候选（DFS 遍历整棵节点树）
     */
    private fun collectCandidates(root: AccessibilityNodeInfo): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val density = resources.displayMetrics.density
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++

            try {
                if (depth <= MAX_DEPTH) {
                    val candidate = evaluateNode(node, density)
                    if (candidate != null) {
                        result.add(candidate)
                    }
                }
                if (depth < MAX_DEPTH) {
                    for (i in (0 until node.childCount).reversed()) {
                        node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                    }
                }
            } catch (_: Exception) {
                // 节点遍历中失效 → 跳过
            }
        }
        return result
    }

    /**
     * 评估节点优先级（第 2 条：认控件不认字）
     *
     * Priority 1000 = 包名命中 AD_SDK_PACKAGES + viewId 命中 CLOSE_VIEW_ID_PATTERNS
     *         800  = 仅包名命中
     *         600  = 仅 viewId 命中
     *         300  = 文字兜底（跳过/关闭/×）
     */
    private fun evaluateNode(node: AccessibilityNodeInfo, density: Float): Candidate? {
        if (!node.isVisibleToUser) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null

        val wDp = rect.width() / density
        val hDp = rect.height() / density

        // ---- 尺寸硬约束 ----
        if (wDp < MIN_WIDTH_DP || hDp < MIN_HEIGHT_DP) return null
        if (wDp > MAX_WIDTH_DP || hDp > MAX_HEIGHT_DP) return null

        // ---- 排除非按钮控件 ----
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText") || className.contains("SeekBar") ||
            className.contains("ProgressBar") || className.contains("Switch") ||
            className.contains("ScrollView") || className.contains("RecyclerView") ||
            className.contains("ListView")) return null

        val pkg = node.packageName?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val cd = node.contentDescription?.toString()?.trim() ?: ""

        // ---- 第 4 条：危险文字直接拉黑 ----
        for (danger in DANGEROUS_TEXTS) {
            if (text.contains(danger, ignoreCase = true) ||
                cd.contains(danger, ignoreCase = true)) return null
        }

        // ---- 排除功能按钮 ----
        for (exclude in SKIP_EXCLUDE_WORDS) {
            if (text.contains(exclude, ignoreCase = true)) return null
        }

        // ---- 长文案 = 广告主体 ----
        if (text.length > 24 && cd.length > 24) return null

        // ============ 优先级判定 ============
        var priority = 0

        val pkgHit = AD_SDK_PACKAGES.any { pkg.startsWith(it) }
        val viewIdHit = CLOSE_VIEW_ID_PATTERNS.any { pat ->
            viewId.contains(pat, ignoreCase = true)
        }

        if (pkgHit && viewIdHit) {
            priority = PRIORITY_PKG_VIEWID   // 1000 双重命中
        } else if (pkgHit) {
            priority = PRIORITY_PKG           // 800  仅包名
        } else if (viewIdHit) {
            priority = PRIORITY_VIEWID        // 600  仅 viewId
        } else {
            // ---- 文字兜底（最低优先级 300） ----
            val label = text.ifEmpty { cd }
            if (label.isEmpty()) return null

            val textHit = SKIP_TEXTS.any {
                label.equals(it, ignoreCase = true) ||
                (label.length <= 12 && label.contains(it, ignoreCase = true))
            } || CLOSE_SYMBOLS.contains(label)

            if (!textHit) return null
            priority = PRIORITY_TEXT
        }

        // ============ 确定点击目标 ============
        var clickNode = node
        var gestureOnly = false

        if (!node.isClickable) {
            val ancestor = findClickableAncestor(node, 3)
            if (ancestor != null) {
                val aRect = Rect()
                ancestor.getBoundsInScreen(aRect)
                val aW = aRect.width() / density
                val aH = aRect.height() / density
                if (aW in MIN_WIDTH_DP..MAX_WIDTH_DP && aH in MIN_HEIGHT_DP..MAX_HEIGHT_DP) {
                    clickNode = ancestor
                } else {
                    gestureOnly = true
                }
            } else {
                gestureOnly = true
            }
        }

        val finalRect = Rect()
        clickNode.getBoundsInScreen(finalRect)
        if (finalRect.width() <= 0 || finalRect.height() <= 0) return null

        return Candidate(
            priority = priority,
            rect = finalRect,
            clickNode = clickNode,
            gestureOnly = gestureOnly
        )
    }

    /**
     * 向上找最近的可点击祖先
     */
    private fun findClickableAncestor(
        node: AccessibilityNodeInfo, maxDepth: Int
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
    // 第 3 条：防连环
    // ========================================================================

    /**
     * 记录一次关闭动作，检测是否需要触发冷却
     */
    private fun recordAction(pkg: String) {
        val now = System.currentTimeMillis()
        val list = pkgActionTimestamps.getOrPut(pkg) { mutableListOf() }
        list.add(now)
        list.removeAll { now - it > CHAIN_WINDOW_MS }

        if (list.size >= CHAIN_THRESHOLD) {
            globalCooldownUntil = now + COOLDOWN_MS
            pkgActionTimestamps.clear()
            DebugLogger.log(this, "🧊 防连环冷却 ${COOLDOWN_MS}ms（${pkg} ${CHAIN_THRESHOLD}次/${CHAIN_WINDOW_MS}ms）")
        }
    }

    /**
     * 当前是否在冷却期
     */
    private fun isInCooldown(pkg: String): Boolean {
        if (System.currentTimeMillis() < globalCooldownUntil) {
            return true
        }
        // 冷却期结束，清理
        if (globalCooldownUntil > 0L) {
            globalCooldownUntil = 0L
            pkgActionTimestamps.clear()
        }
        return false
    }

    // ========================================================================
    // 第 4 条：防误点
    // ========================================================================

    /**
     * 在新鲜根节点上检查目标区域附近（60px 范围）是否有危险文字
     */
    private fun hasNearbyDangerText(root: AccessibilityNodeInfo, targetRect: Rect): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 300) {
            val node = stack.removeLast()
            visited++
            try {
                val text = node.text?.toString()?.trim() ?: ""
                val cd = node.contentDescription?.toString()?.trim() ?: ""
                for (danger in DANGEROUS_TEXTS) {
                    if ((text.contains(danger, ignoreCase = true) ||
                         cd.contains(danger, ignoreCase = true)) && text.length <= 24
                    ) {
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        if (isNearby(r, targetRect)) return true
                        // 危险文本在目标区域内 → 就是广告主体 → 拉黑
                        if (r.intersect(targetRect)) return true
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * 两个矩形是否在 60px 范围内
     */
    private fun isNearby(a: Rect, b: Rect): Boolean {
        val gap = 60
        return (a.left - b.right) < gap && (b.left - a.right) < gap &&
               (a.top - b.bottom) < gap && (b.top - a.bottom) < gap
    }

    // ========================================================================
    // 执行关闭
    // ========================================================================

    /**
     * 执行关闭动作：先尝试无障碍 ACTION_CLICK，失败则用坐标手势兜底
     */
    private fun performCloseAction(node: AccessibilityNodeInfo, pkg: String, rect: Rect): Boolean {
        var ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
        if (!ok && rect.width() > 0 && rect.height() > 0) {
            ok = clickByGesture(rect.exactCenterX(), rect.exactCenterY())
        }
        if (ok) {
            AdStatsManager.recordSkip(this, pkg)
            showSkipNotification(pkg, "控件")
            DebugLogger.log(this, "✅ 控件关闭 $pkg rect=${rect.width()}x${rect.height()}")
        }
        return ok
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

    // ========================================================================
    // 通知 & 工具方法
    // ========================================================================

    private fun showSkipNotification(pkg: String, method: String) {
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = android.app.Notification.Builder(
                this, NoAdApp.CHANNEL_SKIP_REPORT
            )
                .setContentTitle("🐟 已跳过广告")
                .setContentText("来自 $appName · $method")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setAutoCancel(true)
                .setOngoing(false)
                .setTimeoutAfter(2500)
                .build()

            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(System.currentTimeMillis().hashCode(), notification)
            } catch (_: Exception) {
            }
        }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String = try {
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        (if (t.isNotEmpty()) t else d).replace("\n", " ")
    } catch (_: Exception) {
        "?"
    }

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg in SYSTEM_PACKAGES_EXACT) return true
        for (prefix in SYSTEM_PACKAGES_PREFIX) {
            if (pkg.startsWith(prefix)) return true
        }
        return false
    }

    /**
     * 调试 dump：输出页面中含关闭关键词的节点
     */
    private fun dumpKeywordNodes(root: AccessibilityNodeInfo, pkg: String) {
        val keywords = listOf("跳", "关", "×", "x", "X", "广告", "ad", "skip", "close")
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
                                "bounds=${r.width()}x${r.height()} " +
                                "class=${node.className?.toString()?.substringAfterLast('.')} " +
                                "viewId=${node.viewIdResourceName}"
                            )
                            dumped++
                            break
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
            }
        }
    }

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
        val priority: Int,                     // 优先级（1000 > 800 > 600 > 300）
        val rect: Rect,                        // 按钮在屏幕上的 bounds
        val clickNode: AccessibilityNodeInfo,  // 实际要点的节点
        val gestureOnly: Boolean = false       // 仅手势坐标点击（节点不可点击）
    )
}