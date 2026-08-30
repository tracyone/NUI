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
    fun isSystemDark(ctx: Context): Boolean = isSystemDark(ctx.resources.configuration)

    /** 基于 Configuration 判断系统深浅（onConfigurationChanged 时 resources 可能未同步，用 newConfig 判断） */
    fun isSystemDark(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 最终是否深色：手动指定优先，否则跟随系统（用 config 判断系统，适用于配置变化回调） */
    fun isDark(ctx: Context, config: Configuration): Boolean = when (mode(ctx)) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> isSystemDark(config)
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

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    /** 设置页配色集（iOS 风格，深浅两套） */
    data class SettingsPalette(
        val bg: Int,          // 根背景
        val card: Int,        // 卡片背景
        val label: Int,       // 主文字
        val value: Int,       // 次文字 / 分组标题
        val divider: Int,
        val accent: Int,      // 选中/强调色
        val btnBg: Int,       // 次要按钮底
        val ripple: Int,      // 行按压波纹
    )

    private val SETTINGS_LIGHT = SettingsPalette(
        bg = 0xFFF2F2F7.toInt(),
        card = 0xFFFFFFFF.toInt(),
        label = 0xFF1C1C1E.toInt(),
        value = 0xFF8E8E93.toInt(),
        divider = 0xFFE5E5EA.toInt(),
        accent = 0xFF007AFF.toInt(),
        btnBg = 0xFFE9E9EB.toInt(),
        ripple = 0x22000000.toInt(),
    )

    private val SETTINGS_DARK = SettingsPalette(
        bg = 0xFF000000.toInt(),
        card = 0xFF1C1C1E.toInt(),
        label = 0xFFF2F2F7.toInt(),
        value = 0xFF98989E.toInt(),
        divider = 0xFF2C2C2E.toInt(),
        accent = 0xFF0A84FF.toInt(),
        btnBg = 0xFF2C2C2E.toInt(),
        ripple = 0x33FFFFFF.toInt(),
    )

    fun settingsPalette(ctx: Context): SettingsPalette =
        if (isDark(ctx)) SETTINGS_DARK else SETTINGS_LIGHT

    fun settingsPalette(dark: Boolean): SettingsPalette =
        if (dark) SETTINGS_DARK else SETTINGS_LIGHT
}
