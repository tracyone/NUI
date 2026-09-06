package com.nui.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/**
 * 最近打开的程序跟踪：
 * - 优先 UsageStatsManager 查询系统级最近使用（需 PACKAGE_USAGE_STATS 权限）
 * - 无权限时降级为本地记录（NUI 内启动应用时调用 [noteLaunch]）
 * - 提供 [getRecent] 获取最近使用的应用（排除指定包名）
 */
object RecentApps {
    private const val PREFS = "nui_recent"
    private const val KEY = "recent_packages"
    private const val MAX_RECENT = 20

    /** 记录一次应用启动（NUI 内启动应用时调用） */
    fun noteLaunch(context: Context, pkg: String) {
        if (pkg == context.packageName) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getString(KEY, "")?.split("\n")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
        list.remove(pkg)
        list.add(0, pkg)
        while (list.size > MAX_RECENT) list.removeLast()
        prefs.edit { putString(KEY, list.joinToString("\n")) }
    }

    /** 获取最近使用的应用（排除指定包名和 NUI 自身），优先 UsageStatsManager，降级为本地记录 */
    fun getRecent(context: Context, exclude: Set<String>): AppModel? {
        val pm = context.packageManager
        val fallback = pm.defaultActivityIcon
        // 优先 UsageStatsManager（系统级最近使用，覆盖所有方式启动的应用）
        runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 1000 * 60 * 60 * 24, now)
            if (stats.isNotEmpty()) {
                stats.sortByDescending { it.lastTimeUsed }
                for (s in stats) {
                    val pkg = s.packageName
                    if (pkg in exclude || pkg == context.packageName) continue
                    val model = pkgToModel(context, pkg) ?: continue
                    return model
                }
            }
        }
        // 降级：本地记录（仅 NUI 内启动的应用）
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getString(KEY, "")?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
        for (pkg in list) {
            if (pkg in exclude || pkg == context.packageName) continue
            val model = pkgToModel(context, pkg) ?: continue
            return model
        }
        return null
    }

    private fun pkgToModel(context: Context, pkg: String): AppModel? {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        return AppModel(
            label = label,
            packageName = pkg,
            icon = IconUtils.getIconWithFallback(pm, pkg, pm.defaultActivityIcon),
            launchIntent = launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}
