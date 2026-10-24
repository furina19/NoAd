package com.fatfish.noad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启广播接收器
 *
 * 接收系统开机完成广播，自动启动保活服务。
 * 保活服务启动后，无障碍服务也会随之保持活跃。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                startServices(context)
            }
        }
    }

    private fun startServices(context: Context) {
        // 启动保活前台服务
        val keepAliveIntent = Intent(context, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(keepAliveIntent)
        } else {
            context.startService(keepAliveIntent)
        }
    }
}