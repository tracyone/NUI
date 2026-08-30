package com.nui.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/**
 * Dock 栏快捷方式持久化（固定槽位模式）：
 * - [SLOT_COUNT] 个固定槽位，空槽显示加号，用户点加号填入应用
 * - 全部填满后加号消失；长按已填槽可移除（变回加号即可重新添加）
 * - 以 "\n" 分隔存储，空串代表空槽
 * - loadApps 跳过已卸载的应用（该槽返回 null，渲染为加号）
 */
object DockConfig {
    private const val PREFS = "nui_dock"
    private const val KEY = "slots"
    const val SLOT_COUNT = 6

    fun slots(context: Context): List<String?> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        val parts = s.split('\n')
        return (0 until SLOT_COUNT).map { i -> parts.getOrNull(i)?.takeIf { it.isNotEmpty() } }
    }

    fun filledPackages(context: Context): List<String> = slots(context).mapNotNull { it }

    fun setSlot(context: Context, index: Int, pkg: String?) {
        val list = slots(context).toMutableList()
        list[index] = pkg
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, list.joinToString("\n") { it ?: "" }) }
    }

    /** 加载各槽 AppModel，空槽或已卸载返回 null。
     *  对虚拟特殊包（如 [StockHome.PKG_STOCK_HOME]）直接构造对应 AppModel。 */
    fun loadApps(context: Context): List<AppModel?> {
        val pm = context.packageManager
        val fallback = pm.defaultActivityIcon
        return slots(context).map { pkg ->
            if (pkg == null) return@map null
            if (pkg == StockHome.PKG_STOCK_HOME) return@map runCatching { StockHome.model(context) }.getOrNull()
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@map null
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
