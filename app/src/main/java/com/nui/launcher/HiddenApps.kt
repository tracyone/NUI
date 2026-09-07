package com.nui.launcher

import android.content.Context

/**
 * 应用隐藏管理：用户长按应用列表中的应用选择"隐藏"后，
 * 该应用从应用列表/桌面网格中移除，可在 桌面设置 → 应用 → 管理隐藏应用 中恢复。
 * 持久化在 SharedPreferences（包名集合）。
 */
object HiddenApps {
    private const val PREFS = "nui_hidden_apps"
    private const val KEY = "hidden_pkgs"

    fun hiddenSet(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    fun isHidden(context: Context, pkg: String): Boolean = pkg in hiddenSet(context)

    fun hide(context: Context, pkg: String) {
        val set = hiddenSet(context).toMutableSet().apply { add(pkg) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, set).apply()
    }

    fun unhide(context: Context, pkg: String) {
        val set = hiddenSet(context).toMutableSet().apply { remove(pkg) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, set).apply()
    }
}
