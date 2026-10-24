package com.fatfish.noad

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * 无广告 - 应用入口
 * 初始化通知渠道（保活前台服务需要）
 */
class NoAdApp : Application() {

    companion object {
        const val CHANNEL_KEEP_ALIVE = "noad_keep_alive"
        const val CHANNEL_SKIP_REPORT = "noad_skip_report"

        lateinit var instance: NoAdApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 保活通道 —— 最低重要性，尽量不打扰用户
            val keepAliveChannel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "后台运行",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "用于维持无广告在后台运行"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            // 拦截报告通道 —— 每次拦截广告时提示
            val skipReportChannel = NotificationChannel(
                CHANNEL_SKIP_REPORT,
                "广告拦截",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "广告跳过通知"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            nm.createNotificationChannel(keepAliveChannel)
            nm.createNotificationChannel(skipReportChannel)
        }
    }
}