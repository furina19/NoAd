package com.fatfish.noad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 广告跳过无障碍服务
 *
 * 原理：
 * 1. 监听窗口内容变化（AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED）
 * 2. 遍历界面上所有可点击节点
 * 3. 匹配"跳过/关闭/Skip"等关键词
 * 4. 自动执行点击操作
 * 5. 记录统计数据
 */
class AdSkipService : AccessibilityService() {

    companion object {
        // 广告跳过关键词（中英文）
        private val SKIP_KEYWORDS = listOf(
            "跳过", "跳過",           // 简体/繁体跳过
            "关闭", "關閉",           // 关闭
            "关闭广告", "關閉廣告",    // 关闭广告
            "跳过广告", "跳過廣告",    // 跳过广告
            "我知道了",               // 我知道了
            "skip", "Skip", "SKIP",  // 英文跳过
            "close", "Close",        // 英文关闭
            "knowledge",             // "我知道了"的变体
            "不再提醒",               // 不再提醒
            "立即体验",               // 立即体验
        )

        // 内容描述关键词（用于 ImageView 等无文字控件）
        private val CD_SKIP_KEYWORDS = listOf(
            "跳过", "关闭", "skip", "close",
            "关闭广告", "跳过广告", "关闭按钮", "×", "x",
        )

        // 两次跳过之间的最小间隔（毫秒），防止连续触发
        private const val MIN_SKIP_INTERVAL = 1500L

        // 上次跳过时间戳
        private var lastSkipTime = 0L
        // 同一包名连续跳过次数（防止死循环）
        private var samePkgSkipCount = 0
        private var lastSkippedPkg = ""
        private const val MAX_SAME_PKG_SKIP = 3
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 配置无障碍服务 —— 只监听必要的类型，降低开销
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300 // 300ms 去抖动
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // 不处理自己应用的界面
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 节流：两次跳过间隔不能太短
                val now = System.currentTimeMillis()
                if (now - lastSkipTime < MIN_SKIP_INTERVAL) return

                // 同一包名连续跳过次数限制（防止某些应用的"跳过"按钮反复出现导致死循环）
                if (pkg == lastSkippedPkg && samePkgSkipCount >= MAX_SAME_PKG_SKIP) return

                trySkipAd(pkg)
            }
        }
    }

    /**
     * 遍历根节点的子树，寻找广告跳过按钮并点击
     */
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
     * 递归查找"跳过"按钮并点击
     * @return 是否找到并点击了跳过按钮
     */
    private fun findAndClickSkipButton(node: AccessibilityNodeInfo, pkg: String): Boolean {
        // 优先查自己的直接子节点
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (isSkipButton(child) && performClick(child, pkg)) {
                    child.recycle()
                    return true
                }
            }
        }

        // 再递归查孙子节点
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndClickSkipButton(child, pkg)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }

        return false
    }

    /**
     * 判断一个节点是否是"跳过"按钮
     */
    private fun isSkipButton(node: AccessibilityNodeInfo): Boolean {
        // 必须是可点击的或可获得焦点的
        if (!node.isClickable && !node.isFocusable) return false

        // 必须有可见区域（不是离屏控件）
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false

        // 太大或太小的控件不太可能是跳过按钮
        if (rect.width() > 500 || rect.height() > 200) return false
        if (rect.width() < 20 || rect.height() < 20) return false

        return matchesAnyKeyword(node)
    }

    /**
     * 检查节点文本/描述是否匹配跳过关键词
     */
    private fun matchesAnyKeyword(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val cd = node.contentDescription?.toString()?.trim() ?: ""
        val combined = "$text $cd"

        // 精确匹配优先（避免误触）
        for (keyword in SKIP_KEYWORDS) {
            if (text.equals(keyword, ignoreCase = true)) return true
            if (cd.equals(keyword, ignoreCase = true)) return true
        }

        // 模糊匹配（包含即可，但文字必须够短）
        if (text.length in 2..8) {
            for (keyword in SKIP_KEYWORDS) {
                if (text.contains(keyword, ignoreCase = true)) return true
            }
        }
        if (cd.length in 2..10) {
            for (keyword in CD_SKIP_KEYWORDS) {
                if (cd.contains(keyword, ignoreCase = true)) return true
            }
        }

        return false
    }

    /**
     * 执行点击操作
     */
    private fun performClick(node: AccessibilityNodeInfo, pkg: String): Boolean {
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            // 记录统计
            AdStatsManager.recordSkip(this, pkg)
            // 发送轻量通知
            showSkipNotification(pkg)
        }
        return clicked
    }

    /**
     * 显示一条低调的拦截通知
     */
    private fun showSkipNotification(pkg: String) {
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = NoAdApp.CHANNEL_SKIP_REPORT
            val notification = android.app.Notification.Builder(this, channelId)
                .setContentTitle("已跳过广告")
                .setContentText("来自 $appName")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setAutoCancel(true)
                .setOngoing(false)
                .setTimeoutAfter(3000) // 3秒后自动消失
                .build()

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(System.currentTimeMillis().hashCode(), notification)
        }
    }

    override fun onInterrupt() {
        // 无障碍服务被中断时不做特殊处理
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}