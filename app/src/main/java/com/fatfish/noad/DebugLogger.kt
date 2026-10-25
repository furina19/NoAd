package com.fatfish.noad

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断日志
 *
 * 用于排查"某个广告为什么没被跳过"：
 * 记录扫描到的候选按钮、点击结果，以及疑似跳过按钮的文本节点。
 * 默认关闭，只在主界面手动开启，日志仅存本机 filesDir，不联网。
 */
object DebugLogger {

    private const val PREFS = "noad_debug"
    private const val KEY_ENABLED = "debug_enabled"
    private const val LOG_FILE = "noad_debug.log"
    private const val MAX_SIZE = 256 * 1024L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (!enabled) clear(context)
    }

    fun log(context: Context, message: String) {
        if (!isEnabled(context)) return
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.length() > MAX_SIZE) file.delete()
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$time] $message\n")
        } catch (_: Exception) {
            // 日志失败不影响主流程
        }
    }

    fun read(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists() && file.length() > 0) {
                file.readText()
            } else {
                "（暂无日志）\n\n先点「开始记录」，然后去触发广告，\n再回来查看～"
            }
        } catch (e: Exception) {
            "读取日志失败：${e.message}"
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, LOG_FILE).delete()
        } catch (_: Exception) {
        }
    }
}