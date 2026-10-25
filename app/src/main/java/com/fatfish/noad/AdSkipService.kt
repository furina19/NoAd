package com.fatfish.noad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 广告跳过无障碍服务 v1.1
 *
 * 修复三个致命 Bug：
 *   1. 视频广告没反应        → 增加倒计时"跳过 5s"模式匹配
 *   2. 状态栏被误识别为广告   → 排除系统应用 + "关闭"不再单独触发
 *   3. 下载进度被误关闭       → "关闭"必须配合"广告"上下文才触发
 *
 * 新策略：分层关键词 + 广告上下文检测 + 位置启发式
 */
class AdSkipService : AccessibilityService() {

    companion object {
        // ====== 系统包名黑名单 —— 绝对不碰！ ======
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",           // 状态栏/通知栏/快捷开关
            "com.android.settings",           // 系统设置
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.dialer",             // 电话
            "com.android.phone",
            "com.android.contacts",
            "com.android.mms",                // 短信
            "com.android.documentsui",        // 文件选择器
            "com.android.keyguard",           // 锁屏
            "com.android.incallui",           // 通话界面
            "com.google.android.apps.nexuslauncher",
            "com.google.android.googlequicksearchbox",
            "com.google.android.permissioncontroller",
            "com.google.android.deskclock",   // 时钟
            "com.android.deskclock",
            "com.coloros.",                   // ColorOS 系统前缀
            "com.oppo.",
            "com.xiaomi.",
            "com.miui.",
            "com.huawei.android.",
            "com.samsung.android.",
        )

        // ====== 第一梯队：绝对可信的广告跳过关键词（精确匹配即触发） ======
        // 这些词组出现时一定是广告跳过按钮，不需要上下文验证
        private val STRONG_KEYWORDS = listOf(
            "跳过广告", "跳過廣告",
            "关闭广告", "關閉廣告",
            "skip ad", "Skip ad", "Skip Ad", "SKIP AD",
            "close ad", "Close ad", "Close Ad",
            "关闭此广告",
            "不感兴趣",
        )

        // ====== 第二梯队：疑似但需要上下文验证的关键词 ======
        // 这些词单独出现不够可信，必须按钮附近有"广告/ad/推广"等上下文词
        private val WEAK_KEYWORDS = listOf(
            "跳过", "跳過",
            "skip", "Skip", "SKIP",
        )

        // ====== 弹窗广告关闭关键词（一般可信度中等，额外检查尺寸） ======
        private val DIALOG_CLOSE_KEYWORDS = listOf(
            "我知道了",
            "不再提醒",
            "立即体验",
            "朕知道了",
            "知道啦",
            "确定",
            "同意并继续",
            "继续",
        )

        // ====== 内容描述匹配关键词 ======
        private val CD_SKIP_KEYWORDS = listOf(
            "跳过广告", "关闭广告", "skip ad", "close ad",
            "跳过", "关闭广告",
        )

        // ====== 广告上下文词（判断按钮附近是否是广告） ======
        private val AD_CONTEXT_WORDS = listOf(
            "广告", "廣告", "ad", "Ad", "AD",
            "sponsored", "推广", "promoted",
            "免费领取", "立即下载",
        )

        // ====== 倒计时正则：匹配 "跳过 5s"、"跳过3" 等视频广告格式 ======
        private val COUNTDOWN_REGEX = Regex(
            "(跳过|跳過|skip|Skip|SKIP)\\s*\\d+\\s*(s|秒|S)?"
        )

        // 两次跳过最小间隔
        private const val MIN_SKIP_INTERVAL = 1500L
        // 同一包名连续跳过上限
        private const val MAX_SAME_PKG_SKIP = 3

        // 运行时状态
        private var lastSkipTime = 0L
        private var samePkgSkipCount = 0
        private var lastSkippedPkg = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // ====== 排除自己 ======
        if (pkg == packageName) return

        // ====== 排除系统应用（状态栏、设置、拨号等） ======
        if (isSystemPackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastSkipTime < MIN_SKIP_INTERVAL) return
                if (pkg == lastSkippedPkg && samePkgSkipCount >= MAX_SAME_PKG_SKIP) return

                trySkipAd(pkg)
            }
        }
    }

    /**
     * 检查是否为系统包名
     */
    private fun isSystemPackage(pkg: String): Boolean {
        // 精确匹配
        if (pkg in SYSTEM_PACKAGES) return true
        // 前缀匹配（如 com.coloros.xxx）
        for (prefix in SYSTEM_PACKAGES) {
            if (prefix.endsWith(".") && pkg.startsWith(prefix)) return true
        }
        return false
    }

    // ========================================================================
    // 广告检测主逻辑
    // ========================================================================

    private fun trySkipAd(packageName: String) {
        val root = rootInActiveWindow ?: return
        val found = findAndClickSkipButton(root, packageName)
        root.recycle()

        if (found) {
            lastSkipTime = System.currentTimeMillis()
            if (packageName == lastSkippedPkg) {
                samePkgSkipCount++
            } else {
                samePkgSkipCount = 1
                lastSkippedPkg = packageName
            }
        }
    }

    /**
     * 递归查找跳过按钮
     */
    private fun findAndClickSkipButton(node: AccessibilityNodeInfo, pkg: String): Boolean {
        // 先查找直接子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val confidence = evaluateSkipConfidence(child, node)
            if (confidence >= ConfidenceLevel.HIGH && performClick(child, pkg)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        // 递归查孙子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickSkipButton(child, pkg)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    // ========================================================================
    // 跳过按钮评估（核心！）
    // ========================================================================

    /**
     * 置信度等级
     */
    private enum class ConfidenceLevel {
        NONE,    // 不是跳过按钮
        LOW,     // 有点可疑但不确定，不触发
        HIGH,    // 很可能是广告跳过按钮，触发！
    }

    /**
     * 综合评估一个节点的"跳过广告按钮置信度"
     */
    private fun evaluateSkipConfidence(
        node: AccessibilityNodeInfo,
        parent: AccessibilityNodeInfo
    ): ConfidenceLevel {
        // -------- 基础过滤 --------
        // 不可点击也不可聚焦 → 不是按钮
        if (!node.isClickable && !node.isFocusable) return ConfidenceLevel.NONE

        // 必须有可见区域
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return ConfidenceLevel.NONE

        // 尺寸过滤：太小或太大都不像跳过按钮
        if (rect.width() < 25 || rect.height() < 25) return ConfidenceLevel.NONE
        if (rect.width() > 600 || rect.height() > 250) return ConfidenceLevel.NONE

        // 排除 EditText、SeekBar 等明显不是按钮的控件
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText") || className.contains("SeekBar") ||
            className.contains("ProgressBar") || className.contains("Switch")) {
            return ConfidenceLevel.NONE
        }

        val text = node.text?.toString()?.trim() ?: ""
        val cd = node.contentDescription?.toString()?.trim() ?: ""

        // -------- 第一梯队：强关键词精确匹配 --------
        for (keyword in STRONG_KEYWORDS) {
            if (text.equals(keyword, ignoreCase = true)) return ConfidenceLevel.HIGH
            if (cd.equals(keyword, ignoreCase = true)) return ConfidenceLevel.HIGH
        }

        // 模糊匹配强关键词（text 较短，避免长文本误匹配）
        if (text.length in 3..10) {
            for (keyword in STRONG_KEYWORDS) {
                if (text.contains(keyword, ignoreCase = true)) return ConfidenceLevel.HIGH
            }
        }

        // -------- 视频广告倒计时检测："跳过 5s" 等 --------
        if (COUNTDOWN_REGEX.matches(text) || COUNTDOWN_REGEX.matches(cd)) {
            return ConfidenceLevel.HIGH
        }
        // 模糊包含（如 "剩余5秒 跳过广告"）
        if (COUNTDOWN_REGEX.containsMatchIn(text) || COUNTDOWN_REGEX.containsMatchIn(cd)) {
            return ConfidenceLevel.HIGH
        }

        // -------- 第二梯队：弱关键词需要上下文验证 --------
        var matchedWeak = false
        for (keyword in WEAK_KEYWORDS) {
            if (text.equals(keyword, ignoreCase = true)) { matchedWeak = true; break }
            if (cd.equals(keyword, ignoreCase = true)) { matchedWeak = true; break }
            if (text.length in 2..8 && text.contains(keyword, ignoreCase = true)) {
                matchedWeak = true; break
            }
        }

        if (matchedWeak) {
            // 必须验证按钮周围有广告上下文
            if (hasAdContext(node) || hasAdContext(parent)) {
                return ConfidenceLevel.HIGH
            }
            // 文本精确为"跳过"且按钮在屏幕右上角 → 很可能是广告跳过
            if (text == "跳过" && isTopRightCorner(rect)) {
                return ConfidenceLevel.HIGH
            }
            return ConfidenceLevel.LOW // 有"跳过"但没有广告上下文 → 不触发
        }

        // -------- 弹窗广告关闭词 --------
        for (keyword in DIALOG_CLOSE_KEYWORDS) {
            if (text.equals(keyword, ignoreCase = true)) {
                // 弹窗按钮通常是小尺寸
                if (rect.width() in 50..350 && rect.height() in 30..120) {
                    return ConfidenceLevel.HIGH
                }
            }
        }

        // -------- 内容描述匹配 --------
        if (cd.length in 2..12) {
            for (keyword in CD_SKIP_KEYWORDS) {
                if (cd.contains(keyword, ignoreCase = true)) {
                    return ConfidenceLevel.HIGH
                }
            }
        }

        // ====== 注意：单独的"关闭"、"close" 不再触发！ ======
        // 这是之前误触状态栏和下载进度的根本原因

        return ConfidenceLevel.NONE
    }

    // ========================================================================
    // 辅助判断方法
    // ========================================================================

    /**
     * 检查节点及其兄弟节点中是否包含"广告"等上下文关键词
     */
    private fun hasAdContext(node: AccessibilityNodeInfo): Boolean {
        // 检查节点自己的文本
        val text = node.text?.toString() ?: ""
        for (adWord in AD_CONTEXT_WORDS) {
            if (text.contains(adWord, ignoreCase = true)) return true
        }

        // 检查兄弟节点
        val parent = node.parent ?: return false
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            val sibText = sibling.text?.toString() ?: ""
            for (adWord in AD_CONTEXT_WORDS) {
                if (sibText.contains(adWord, ignoreCase = true)) {
                    sibling.recycle()
                    return true
                }
            }
            sibling.recycle()
        }

        return false
    }

    /**
     * 判断按钮是否在屏幕右上角区域
     * （典型广告跳过按钮位置）
     */
    private fun isTopRightCorner(rect: android.graphics.Rect): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        // 右上角：右边 35% + 上边 30% 区域
        return rect.left > screenWidth * 0.55f && rect.top < resources.displayMetrics.heightPixels * 0.35f
    }

    // ========================================================================
    // 执行 & 通知
    // ========================================================================

    private fun performClick(node: AccessibilityNodeInfo, pkg: String): Boolean {
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
                .setTimeoutAfter(3000)
                .build()

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(System.currentTimeMillis().hashCode(), notification)
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy() }
}