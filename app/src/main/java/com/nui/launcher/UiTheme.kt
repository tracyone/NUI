package com.nui.launcher

import android.content.Context
import android.content.res.Configuration

/** 桌面深浅模式：跟随系统 / 深色 / 浅色，默认跟随系统 */
object UiTheme {
    enum class Mode { SYSTEM, DARK, LIGHT }

    private const val PREFS = "nui_ui"
    private const val KEY_MODE = "theme_mode"

    /** 当前是否深色：手动指定优先，否则跟随系统 DayNight */
    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> isSystemDark(ctx)
    }

    fun mode(ctx: Context): Mode = when (
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, 0)
    ) {
        1 -> Mode.DARK
        2 -> Mode.LIGHT
        else -> Mode.SYSTEM
    }

    fun setMode(ctx: Context, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, when (mode) {
                Mode.SYSTEM -> 0
                Mode.DARK -> 1
                Mode.LIGHT -> 2
            }).apply()
    }

    /** 系统当前是否为深色模式 */
    fun isSystemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    /** 配色集 */
    data class Palette(
        val panelBg: Int,        // 右侧面板 / 音乐面板背景
        val dockBg: Int,         // Dock 栏背景
        val mapBg: Int,          // 地图卡片背景
        val textPrimary: Int,    // 主文字
        val textSecondary: Int,  // 次文字
        val divider: Int,
        val dockIconTint: Int,   // Dock 图标 / 面板图标
        val accent: Int,
    )

    private val DARK = Palette(
        panelBg = 0xCC1A1F26.toInt(),
        dockBg = 0xCC1F242C.toInt(),
        mapBg = 0xFF0B1426.toInt(),
        textPrimary = 0xFFECEFF1.toInt(),
        textSecondary = 0xFF9AA0A6.toInt(),
        divider = 0x33FFFFFF.toInt(),
        dockIconTint = 0xFFECEFF1.toInt(),
        accent = 0xFF00C5D3.toInt(),
    )

    private val LIGHT = Palette(
        panelBg = 0xE6FFFFFF.toInt(),   // 半透明白
        dockBg = 0xE6F5F5F7.toInt(),    // 浅色 Dock
        mapBg = 0xFFE8EDF2.toInt(),     // 浅色地图卡片
        textPrimary = 0xFF1C1C1E.toInt(),
        textSecondary = 0xFF6E6E73.toInt(),
        divider = 0x26000000.toInt(),
        dockIconTint = 0xFF1C1C1E.toInt(),
        accent = 0xFF007AFF.toInt(),
    )

    fun palette(ctx: Context): Palette = if (isDark(ctx)) DARK else LIGHT
}
