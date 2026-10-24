package com.fatfish.noad

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台保活服务
 *
 * 使用前台通知保持应用在后台运行，
 * 防止被系统回收，确保无障碍服务持续工作。
 *
 * 通知采用最低优先级（IMPORTANCE_MIN），
 * 在状态栏几乎不可见，默默守护。
 */
class KeepAliveService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = NoAdApp.CHANNEL_KEEP_ALIVE
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果服务被杀死，系统会尝试重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("无广告运行中")
                .setContentText("正在默默帮你跳过广告～")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("无广告运行中")
                .setContentText("正在默默帮你跳过广告～")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }

        startForeground(NOTIFICATION_ID, notification)
    }
}