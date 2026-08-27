package com.nui.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/**
 * Dock 栏快捷方式持久化：
 * - 以 "\n" 分隔的包名串存储顺序列表（SharedPreferences）
 * - loadApps 跳过已卸载的应用
 */
object DockConfig {
    private const val PREFS = "nui_dock"
    private const val KEY = "packages"

    fun packages(context: Context): List<String> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return s.split('\n').filter { it.isNotEmpty() }
    }

    fun add(context: Context, pkg: String) {
        val list = packages(context).toMutableList()
        if (pkg !in list) list += pkg
        save(context, list)
    }

    fun remove(context: Context, pkg: String) {
        save(context, packages(context).toMutableList().also { it.remove(pkg) })
    }

    private fun save(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, packages.joinToString("\n")) }
    }

    /** 加载为 AppModel，跳过已卸载的包 */
    fun loadApps(context: Context): List<AppModel> {
        val pm = context.packageManager
        val fallback = pm.defaultActivityIcon
        return packages(context).mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            AppModel(
                label = label,
                packageName = pkg,
                icon = IconUtils.getIconWithFallback(pm, pkg, fallback),
                launchIntent = launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}
