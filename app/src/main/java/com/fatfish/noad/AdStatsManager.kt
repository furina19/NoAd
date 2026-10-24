package com.fatfish.noad

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 广告拦截统计管理器
 *
 * 使用 SharedPreferences 存储，轻量零开销
 * 记录：总拦截次数、每个应用的拦截次数、最后一次拦截时间
 */
object AdStatsManager {

    private const val PREFS_NAME = "noad_stats"
    private const val KEY_TOTAL_COUNT = "total_skip_count"
    private const val KEY_APP_PREFIX = "app_skip_"
    private const val KEY_LAST_TIME = "last_skip_time"
    private const val KEY_LAST_APP = "last_skip_app"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ 写入 ============

    /**
     * 记录一次成功的广告跳过
     * @param packageName 被跳过的广告所属应用的包名
     */
    fun recordSkip(context: Context, packageName: String) {
        val p = prefs(context)
        val editor = p.edit()

        // 总次数 +1
        val total = p.getInt(KEY_TOTAL_COUNT, 0) + 1
        editor.putInt(KEY_TOTAL_COUNT, total)

        // 该应用次数 +1
        val appCount = p.getInt(KEY_APP_PREFIX + packageName, 0) + 1
        editor.putInt(KEY_APP_PREFIX + packageName, appCount)

        // 记录最后跳过信息
        editor.putLong(KEY_LAST_TIME, System.currentTimeMillis())
        editor.putString(KEY_LAST_APP, packageName)

        editor.apply()
    }

    // ============ 读取 ============

    /** 获取总拦截次数 */
    fun getTotalCount(context: Context): Int =
        prefs(context).getInt(KEY_TOTAL_COUNT, 0)

    /** 获取所有被拦截过的应用及次数，按次数降序排列 */
    fun getAppStats(context: Context): List<AppSkipStat> {
        val p = prefs(context)
        val pm = context.packageManager
        val stats = mutableListOf<AppSkipStat>()

        p.all.forEach { (key, value) ->
            if (key.startsWith(KEY_APP_PREFIX) && value is Int && value > 0) {
                val pkg = key.removePrefix(KEY_APP_PREFIX)
                val appName = try {
                    val appInfo: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    pkg // 找不到就用包名
                }
                stats.add(AppSkipStat(pkg, appName, value))
            }
        }

        return stats.sortedByDescending { it.count }
    }

    /** 获取最后一次跳过的应用名 */
    fun getLastSkipApp(context: Context): String {
        val p = prefs(context)
        val pkg = p.getString(KEY_LAST_APP, null) ?: return "暂无"
        return try {
            val appInfo: ApplicationInfo = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /** 获取最后一次跳过时间 */
    fun getLastSkipTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_TIME, 0L)

    /** 清空所有统计 */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ============ 数据类 ============

    data class AppSkipStat(
        val packageName: String,
        val appName: String,
        val count: Int
    )
}