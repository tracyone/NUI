package com.nui.launcher

import android.content.Context

/** 桌面深浅模式：两套配色 + 模式存取（默认深色） */
object UiTheme {
    enum class Mode { DARK, LIGHT }

    private const val PREFS = "nui_ui"
    private const val KEY_MODE = "theme_mode"

    /** 当前是否深色模式（默认深色） */
    fun isDark(ctx: Context): Boolean = mode(ctx) == Mode.DARK

    fun mode(ctx: Context): Mode =
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, 1) == 1
        ) Mode.DARK else Mode.LIGHT

    fun setMode(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, if (dark) 1 else 0).apply()
    }

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
