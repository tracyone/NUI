package com.nui.launcher

import android.content.Context
import android.content.res.Configuration

/** 桌面深浅模式：跟随系统 / 深色 / 浅色 / 跟随地图，默认跟随地图 */
object UiTheme {
    enum class Mode { SYSTEM, DARK, LIGHT, FOLLOW_MAP }

    /** Dock 栏形态：贴边矩形（CarPlay 风格） / 圆角悬浮 */
    enum class DockStyle { EDGE, FLOAT }

    private const val PREFS = "nui_ui"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_MAP_DARK = "map_dark_state"
    private const val KEY_DOCK_STYLE = "dock_style"
    private const val KEY_DOCK_ICON_SCALE = "dock_icon_scale"
    private const val KEY_APP_ICON_SCALE = "app_icon_scale"
    private const val KEY_SHOW_SYSTEM_DOCK = "show_system_dock"
    private const val KEY_SHOW_STATUS_BAR = "show_status_bar"
    private const val KEY_MAP_LAUNCH_DELAY_SEC = "map_launch_delay_sec"
    private const val KEY_MAP_RETURN_DELAY_SEC = "map_return_delay_sec"
    private const val KEY_UI_DPI = "ui_dpi"
    private const val KEY_MINUS_BIG_CLOCK = "minus_big_clock"
    private const val KEY_AUTO_MINUS = "auto_minus"
    private const val KEY_AUTO_MINUS_MINUTES = "auto_minus_minutes"

    /** 界面 DPI（应用内覆盖，0=跟随系统）。车机 ROM 常报 240dpi 导致 NUI 界面文字偏大/放不下，
     *  通过降低 dpi 让所有 dp/sp 按更小比例渲染，等效"显示缩放"，从根本上解决文字过大。 */
    fun uiDpi(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_UI_DPI, 0)

    fun setUiDpi(ctx: Context, dpi: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_UI_DPI, dpi.coerceIn(0, 360)).apply()
    }

    /** 负一屏是否在底栏之外显示大号时钟（时间+日期）。默认关闭 */
    fun minusBigClock(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MINUS_BIG_CLOCK, false)

    fun setMinusBigClock(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MINUS_BIG_CLOCK, on).apply()
    }

    /** 闲置一段时间后是否自动进入负一屏（类似屏保）。默认关闭 */
    fun autoMinus(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_MINUS, false)

    fun setAutoMinus(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_MINUS, on).apply()
    }

    /** 自动进入负一屏的闲置等待分钟数（1~60，默认 3 分钟） */
    fun autoMinusMinutes(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_AUTO_MINUS_MINUTES, 3)

    fun setAutoMinusMinutes(ctx: Context, minutes: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AUTO_MINUS_MINUTES, minutes.coerceIn(1, 60)).apply()
    }

    /**
     * 应用内界面 DPI 覆盖（等效"显示缩放"）。在 Activity.attachBaseContext 中调用：
     * override fun attachBaseContext(base: Context) {
     *     super.attachBaseContext(UiTheme.overrideUiDpi(base))
     * }
     * dpi<=0（跟随系统）时原样返回 base。
     */
    fun overrideUiDpi(base: Context): Context {
        val dpi = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_DPI, 0)
        if (dpi <= 0) return base
        val config = Configuration(base.resources.configuration)
        config.densityDpi = dpi
        return base.createConfigurationContext(config)
    }

    /**
     * 是否显示系统 Dock（底部导航栏，即"原桌面"的系统级 Dock 栏）。
     * 车机 ROM 常把空调等快捷控制做成底部导航栏/Dock 形态；
     * 与 [showStatusBar] 相互独立。若车机 Dock 属于原 Launcher 私有视图，则该设置不生效
     * （受单前台 Activity 渲染限制）。
     */
    fun showSystemDock(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_SYSTEM_DOCK, false)

    fun setShowSystemDock(ctx: Context, show: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SYSTEM_DOCK, show).apply()
    }

    /** 是否显示顶部状态栏（与底部系统 Dock 独立控制；默认隐藏，沉浸式桌面） */
    fun showStatusBar(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_STATUS_BAR, false)

    fun setShowStatusBar(ctx: Context, show: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_STATUS_BAR, show).apply()
    }

    /** 桌面启动后第几秒自动启动外部地图（高德）；0 = 立即启动。默认 5 秒 */
    fun mapLaunchDelaySec(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAP_LAUNCH_DELAY_SEC, 5)

    fun setMapLaunchDelaySec(ctx: Context, sec: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAP_LAUNCH_DELAY_SEC, sec.coerceIn(0, 60)).apply()
    }

    /** 桌面启动后第几秒返回桌面（绝对时间，非"启动后等几秒"）。
     *  实际高德运行时长 = 返回时刻 - 启动时刻。默认 10 秒（配合启动延迟 5 秒，高德运行 5 秒）。 */
    fun mapReturnDelaySec(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAP_RETURN_DELAY_SEC, 10)

    fun setMapReturnDelaySec(ctx: Context, sec: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAP_RETURN_DELAY_SEC, sec.coerceIn(1, 60)).apply()
    }

    /** 默认 Dock 图标大小（dp），renderDock 按 [dockIconScale] 缩放 */
    const val DEFAULT_DOCK_ICON_DP = 90
    /** 默认应用列表图标大小（dp），AppListAdapter 按 [appIconScale] 缩放 */
    const val DEFAULT_APP_ICON_DP = 72

    fun dockStyle(ctx: Context): DockStyle =
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DOCK_STYLE, 0) == 1)
            DockStyle.FLOAT else DockStyle.EDGE

    fun setDockStyle(ctx: Context, style: DockStyle) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DOCK_STYLE, if (style == DockStyle.FLOAT) 1 else 0).apply()
    }

    /** Dock 图标缩放比例（0.6~1.4，1.0=默认 64dp） */
    fun dockIconScale(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_DOCK_ICON_SCALE, 1f).coerceIn(0.6f, 1.4f)

    fun setDockIconScale(ctx: Context, scale: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_DOCK_ICON_SCALE, scale.coerceIn(0.6f, 1.4f)).apply()
    }

    /** 应用列表图标缩放比例（0.6~1.4，1.0=默认 72dp） */
    fun appIconScale(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_APP_ICON_SCALE, 1f).coerceIn(0.6f, 1.4f)

    fun setAppIconScale(ctx: Context, scale: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_APP_ICON_SCALE, scale.coerceIn(0.6f, 1.4f)).apply()
    }

    /** 当前是否深色：手动指定优先，否则跟随系统或地图 */
    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> isSystemDark(ctx)
        Mode.FOLLOW_MAP -> followMapDark(ctx)
    }

    /** FOLLOW_MAP 实际深浅：高德昼夜数据未收到前（KEY_MAP_DARK 从未写入）先用系统深浅兜底，
     *  避免启动即浅色突兀（车机系统常为深色）；收到高德广播（setMapDark）后跟随地图。 */
    private fun followMapDark(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (sp.contains(KEY_MAP_DARK)) {
            sp.getBoolean(KEY_MAP_DARK, false)
        } else {
            isSystemDark(ctx)
        }
    }

    fun mode(ctx: Context): Mode = when (
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, 3)
    ) {
        1 -> Mode.DARK
        2 -> Mode.LIGHT
        3 -> Mode.FOLLOW_MAP
        else -> Mode.SYSTEM
    }

    fun setMode(ctx: Context, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, when (mode) {
                Mode.SYSTEM -> 0
                Mode.DARK -> 1
                Mode.LIGHT -> 2
                Mode.FOLLOW_MAP -> 3
            }).apply()
    }

    /** 从高德地图昼夜模式广播获取的深色状态（FOLLOW_MAP 模式下使用），默认 false（浅色） */
    fun mapDark(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MAP_DARK, false)

    fun setMapDark(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MAP_DARK, dark).apply()
    }

    /** 系统当前是否为深色模式 */
    fun isSystemDark(ctx: Context): Boolean = isSystemDark(ctx.resources.configuration)

    /** 基于 Configuration 判断系统深浅（onConfigurationChanged 时 resources 可能未同步，用 newConfig 判断） */
    fun isSystemDark(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 最终是否深色：手动指定优先，否则跟随系统或地图（用 config 判断系统，适用于配置变化回调） */
    fun isDark(ctx: Context, config: Configuration): Boolean = when (mode(ctx)) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> isSystemDark(config)
        Mode.FOLLOW_MAP -> followMapDark(ctx, config)
    }

    /** FOLLOW_MAP 实际深浅（config 版本）：高德数据未收到前用系统深浅兜底 */
    private fun followMapDark(ctx: Context, config: Configuration): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (sp.contains(KEY_MAP_DARK)) {
            sp.getBoolean(KEY_MAP_DARK, false)
        } else {
            isSystemDark(config)
        }
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
        panelBg = 0xB3F2F2F7.toInt(),   // 70% 不透明 iOS 浅灰（CarPlay 风格毛玻璃，避免过白）
        dockBg = 0xB3E8E8ED.toInt(),    // 70% 不透明稍深浅灰
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
