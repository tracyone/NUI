package com.nui.launcher.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nui.launcher.R
import com.nui.launcher.UiTheme
import com.nui.launcher.music.MusicHost
import com.nui.launcher.voice.NuiTts

/** 桌面设置：右侧滑入面板（CarPlay 风格，dock 栏始终可见） */
class SettingsDialog(context: Context) : Dialog(context) {

    private lateinit var recordList: LinearLayout
    private lateinit var seekLyricAlpha: SeekBar
    private lateinit var tvLyricAlphaValue: TextView
    private lateinit var tvLyricTitle: TextView
    private lateinit var tabMusic: TextView
    private lateinit var tabSteering: TextView
    private lateinit var tabAppearance: TextView
    private lateinit var tabVoice: TextView
    private lateinit var tabMap: TextView
    private lateinit var tabApps: TextView
    private lateinit var tabAbout: TextView
    private lateinit var panelMusic: View
    private lateinit var panelSteering: View
    private lateinit var panelAppearance: View
    private lateinit var panelVoice: View
    private lateinit var panelMap: View
    private lateinit var panelApps: View
    private lateinit var panelAbout: View
    private lateinit var edMapLaunch: EditText
    private lateinit var edMapReturn: EditText
    private lateinit var checkSystem: TextView
    private lateinit var checkDark: TextView
    private lateinit var checkLight: TextView
    private lateinit var tvOptSystem: TextView
    private lateinit var tvOptDark: TextView
    private lateinit var tvOptLight: TextView
    private lateinit var checkDockEdge: TextView
    private lateinit var checkDockFloat: TextView
    private lateinit var tvOptDockEdge: TextView
    private lateinit var tvOptDockFloat: TextView
    private lateinit var optStatusBar: View
    private lateinit var tvOptStatusBar: TextView
    private lateinit var checkStatusBar: TextView
    private lateinit var optSystemDock: View
    private lateinit var tvOptSystemDock: TextView
    private lateinit var checkSystemDock: TextView
    private lateinit var tvSystemDockHint: TextView
    private lateinit var tvDockIconTitle: TextView
    private lateinit var seekDockIcon: SeekBar
    private lateinit var tvDockIconValue: TextView
    private lateinit var seekUiDpi: SeekBar
    private lateinit var tvUiDpiValue: TextView
    private lateinit var seekAppIcon: SeekBar
    private lateinit var tvAppIconValue: TextView
    private lateinit var tvAppIconTitle: TextView
    private lateinit var tvOptVoiceFemale: TextView
    private lateinit var checkVoiceFemale: TextView
    private lateinit var tvOptVoiceMale: TextView
    private lateinit var checkVoiceMale: TextView

    /** 系统 TTS 男声可用性：null=引擎未就绪（未知），false=不支持（置灰），true=支持 */
    private var voiceMaleSupported: Boolean? = null

    /** 系统 TTS 引擎可用性：null=未知，false=不可用，true=可用（用于 applySettingsTheme 统一上色） */
    private var voiceSystemAvailable: Boolean? = null

    /** 引擎初始化轮询计数（上限 12 次 × 500ms = 6s，超时按不可用处理） */
    private var voiceSupportRetries = 0
    private lateinit var optWallpaperDay: View
    private lateinit var optWallpaperNight: View
    private lateinit var tvWallpaperDayStatus: TextView
    private lateinit var tvWallpaperNightStatus: TextView
    private lateinit var tvWallpaperHint: TextView

    /** 壁纸槽位是否已设置（由 MainActivity 注入，读 WallpaperController 状态） */
    var wallpaperHasCustom: ((com.nui.launcher.WallpaperController.Slot) -> Boolean)? = null

    /** 点击壁纸槽位（由 MainActivity 注入，弹出该槽位的壁纸菜单） */
    var onWallpaperPick: ((com.nui.launcher.WallpaperController.Slot) -> Unit)? = null

    private var currentTab = 0

    /** 弹窗窗口焦点监听（用于系统栏标志重设，API<26 用 ViewTreeObserver） */
    private var windowFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    init {
        setContentView(R.layout.activity_settings)
        setCanceledOnTouchOutside(true)

        findViewById<View>(R.id.btnBack).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { startEdit(null) }
        recordList = findViewById(R.id.recordList)
        seekLyricAlpha = findViewById(R.id.seekLyricAlpha)
        tvLyricAlphaValue = findViewById(R.id.tvLyricAlphaValue)
        tvLyricTitle = findViewById(R.id.tvLyricTitle)
        tabMusic = findViewById(R.id.tabMusic)
        tabSteering = findViewById(R.id.tabSteering)
        tabAppearance = findViewById(R.id.tabAppearance)
        tabVoice = findViewById(R.id.tabVoice)
        tabMap = findViewById(R.id.tabMap)
        tabApps = findViewById(R.id.tabApps)
        tabAbout = findViewById(R.id.tabAbout)
        panelMusic = findViewById(R.id.panelMusic)
        panelSteering = findViewById(R.id.panelSteering)
        panelAppearance = findViewById(R.id.panelAppearance)
        panelVoice = findViewById(R.id.panelVoice)
        panelMap = findViewById(R.id.panelMap)
        panelApps = findViewById(R.id.panelApps)
        panelAbout = findViewById(R.id.panelAbout)
        edMapLaunch = findViewById(R.id.edMapLaunch)
        edMapReturn = findViewById(R.id.edMapReturn)
        findViewById<View>(R.id.optRestart).setOnClickListener { restartDesktop() }
        // 左侧菜单可滚动时底部渐隐提示（DPI 大时"应用/关于"在屏外，提示用户下滑）
        findViewById<android.widget.ScrollView>(R.id.leftMenuScroll).setOnScrollChangeListener { _, _, _, _, _ ->
            updateLeftMenuFade()
        }
        // 右侧面板可滚动时底部渐隐提示（外观/方向盘等设置多时超出可视区）
        findViewById<android.widget.ScrollView>(R.id.rightPanelScroll).setOnScrollChangeListener { _, _, _, _, _ ->
            updateRightPanelFade()
        }
        checkSystem = findViewById(R.id.checkSystem)
        checkDark = findViewById(R.id.checkDark)
        checkLight = findViewById(R.id.checkLight)
        tvOptSystem = findViewById(R.id.tvOptSystem)
        tvOptDark = findViewById(R.id.tvOptDark)
        tvOptLight = findViewById(R.id.tvOptLight)
        checkDockEdge = findViewById(R.id.checkDockEdge)
        checkDockFloat = findViewById(R.id.checkDockFloat)
        tvOptDockEdge = findViewById(R.id.tvOptDockEdge)
        tvOptDockFloat = findViewById(R.id.tvOptDockFloat)
        optStatusBar = findViewById(R.id.optStatusBar)
        tvOptStatusBar = findViewById(R.id.tvOptStatusBar)
        checkStatusBar = findViewById(R.id.checkStatusBar)
        optSystemDock = findViewById(R.id.optSystemDock)
        tvOptSystemDock = findViewById(R.id.tvOptSystemDock)
        checkSystemDock = findViewById(R.id.checkSystemDock)
        tvSystemDockHint = findViewById(R.id.tvSystemDockHint)
        tvDockIconTitle = findViewById(R.id.tvDockIconTitle)
        seekDockIcon = findViewById(R.id.seekDockIcon)
        tvDockIconValue = findViewById(R.id.tvDockIconValue)
        seekUiDpi = findViewById(R.id.seekUiDpi)
        tvUiDpiValue = findViewById(R.id.tvUiDpiValue)
        seekAppIcon = findViewById(R.id.seekAppIcon)
        tvAppIconValue = findViewById(R.id.tvAppIconValue)
        tvAppIconTitle = findViewById(R.id.tvAppIconTitle)
        tvOptVoiceFemale = findViewById(R.id.tvOptVoiceFemale)
        checkVoiceFemale = findViewById(R.id.checkVoiceFemale)
        tvOptVoiceMale = findViewById(R.id.tvOptVoiceMale)
        checkVoiceMale = findViewById(R.id.checkVoiceMale)
        // 外观 → 壁纸（白天 / 晚上）
        optWallpaperDay = findViewById(R.id.optWallpaperDay)
        optWallpaperNight = findViewById(R.id.optWallpaperNight)
        tvWallpaperDayStatus = findViewById(R.id.tvWallpaperDayStatus)
        tvWallpaperNightStatus = findViewById(R.id.tvWallpaperNightStatus)
        tvWallpaperHint = findViewById(R.id.tvWallpaperHint)
        findViewById<View>(R.id.optHiddenApps).setOnClickListener { showHiddenAppsDialog() }

        tabMusic.setOnClickListener { selectTab(0) }
        tabSteering.setOnClickListener { selectTab(1) }
        tabAppearance.setOnClickListener { selectTab(2) }
        tabVoice.setOnClickListener { selectTab(3) }
        tabMap.setOnClickListener { selectTab(4) }
        tabApps.setOnClickListener { selectTab(5) }
        tabAbout.setOnClickListener { selectTab(6) }
        selectTab(0)

        // 地图：两个延迟配置，输入即时保存
        edMapLaunch.setOnEditorActionListener { _, _, _ -> saveMapConfig(); true }
        edMapLaunch.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveMapConfig() }
        edMapReturn.setOnEditorActionListener { _, _, _ -> saveMapConfig(); true }
        edMapReturn.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveMapConfig() }

        findViewById<View>(R.id.optSystem).setOnClickListener {
            UiTheme.setMode(context, UiTheme.Mode.SYSTEM)
            renderAppearance()
            applySettingsTheme()
            (context as? com.nui.launcher.MainActivity)?.refreshForThemeChange()
        }
        findViewById<View>(R.id.optDark).setOnClickListener {
            UiTheme.setMode(context, UiTheme.Mode.DARK)
            renderAppearance()
            applySettingsTheme()
            (context as? com.nui.launcher.MainActivity)?.refreshForThemeChange()
        }
        findViewById<View>(R.id.optLight).setOnClickListener {
            UiTheme.setMode(context, UiTheme.Mode.LIGHT)
            renderAppearance()
            applySettingsTheme()
            (context as? com.nui.launcher.MainActivity)?.refreshForThemeChange()
        }
        findViewById<View>(R.id.optFollowMap).setOnClickListener {
            UiTheme.setMode(context, UiTheme.Mode.FOLLOW_MAP)
            renderAppearance()
            applySettingsTheme()
            (context as? com.nui.launcher.MainActivity)?.refreshForThemeChange()
        }
        findViewById<View>(R.id.optDockEdge).setOnClickListener {
            UiTheme.setDockStyle(context, UiTheme.DockStyle.EDGE)
            renderAppearance()
        }
        findViewById<View>(R.id.optDockFloat).setOnClickListener {
            UiTheme.setDockStyle(context, UiTheme.DockStyle.FLOAT)
            renderAppearance()
        }
        // 显示顶部状态栏（独立开关）：切换后立即生效
        optStatusBar.setOnClickListener {
            UiTheme.setShowStatusBar(context, !UiTheme.showStatusBar(context))
            renderAppearance()
            applySettingsTheme()
            (context as? com.nui.launcher.MainActivity)?.refreshForThemeChange()
        }
        // 显示系统 Dock（底部导航栏，独立开关）：切换后立即让 NUI 退出/进入沉浸
        optSystemDock.setOnClickListener {
            UiTheme.setShowSystemDock(context, !UiTheme.showSystemDock(context))
            renderAppearance()
            applySettingsTheme()
            (context as? com.nui.launcher.MainActivity)?.refreshForThemeChange()
        }
        // 语音：女声 / 男声（通用离线语音服务，零下载）
        findViewById<View>(R.id.optVoiceFemale).setOnClickListener {
            android.util.Log.i("NuiTts", "switch_click gender=female")
            NuiTts.setVoiceGender(context, NuiTts.VOICE_FEMALE)
            NuiTts(context).switchVoice(NuiTts.VOICE_FEMALE)
            renderVoice()
        }
        findViewById<View>(R.id.optVoiceMale).setOnClickListener {
            android.util.Log.i("NuiTts", "switch_click gender=male")
            NuiTts.setVoiceGender(context, NuiTts.VOICE_MALE)
            NuiTts(context).switchVoice(NuiTts.VOICE_MALE)
            renderVoice()
        }
        // 男声能力检测：引擎未就绪时先预热，就绪后刷新可用性
        NuiTts(context).warmUp()
        refreshVoiceSupport()
        renderVoice()
        renderMap()
        renderAbout()
        // 外观 → 壁纸：白天 / 晚上（点击弹菜单，由宿主接管选图）
        optWallpaperDay.setOnClickListener { onWallpaperPick?.invoke(com.nui.launcher.WallpaperController.Slot.DAY) }
        optWallpaperNight.setOnClickListener { onWallpaperPick?.invoke(com.nui.launcher.WallpaperController.Slot.NIGHT) }
        refreshWallpaper()
        tvDockIconValue.text = "${(UiTheme.dockIconScale(context) * 100).toInt()}%"
        seekDockIcon.apply {
            progress = ((UiTheme.dockIconScale(context) - 0.6f) / 0.8f * 80).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val scale = 0.6f + progress / 80f * 0.8f
                        UiTheme.setDockIconScale(context, scale)
                        tvDockIconValue.text = "${(scale * 100).toInt()}%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        tvAppIconValue.text = "${(UiTheme.appIconScale(context) * 100).toInt()}%"
        seekAppIcon.apply {
            progress = ((UiTheme.appIconScale(context) - 0.6f) / 0.8f * 80).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val scale = 0.6f + progress / 80f * 0.8f
                        UiTheme.setAppIconScale(context, scale)
                        tvAppIconValue.text = "${(scale * 100).toInt()}%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        // 界面 DPI：0=跟随系统；1~200 映射 160~359。改动写入后提示重启生效
        fun updateUiDpiLabel(dpi: Int) {
            tvUiDpiValue.text = if (dpi <= 0) context.getString(R.string.ui_dpi_follow_system) else "$dpi dpi"
        }
        updateUiDpiLabel(UiTheme.uiDpi(context))
        seekUiDpi.apply {
            max = 200
            progress = UiTheme.uiDpi(context).let { if (it <= 0) 0 else it - 159 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val dpi = if (progress <= 0) 0 else progress + 159
                    UiTheme.setUiDpi(context, dpi)
                    updateUiDpiLabel(dpi)
                    if (dpi > 0) {
                        com.nui.launcher.NuiToast.show(context, "重启桌面后生效", Toast.LENGTH_SHORT)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        renderAppearance()
        applySettingsTheme()

        tvLyricAlphaValue.text = "${MusicHost.lyricBgAlpha(context)}%"
        seekLyricAlpha.apply {
            max = 100
            progress = MusicHost.lyricBgAlpha(context)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        MusicHost.setLyricBgAlpha(context, progress)
                        tvLyricAlphaValue.text = "$progress%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        // 音乐外观：经典 / 黑胶唱片
        findViewById<View>(R.id.optMusicClassic).setOnClickListener {
            MusicHost.setMusicStyle(context, MusicHost.MusicStyle.CLASSIC)
            renderMusicStyle()
        }
        findViewById<View>(R.id.optMusicVinyl).setOnClickListener {
            MusicHost.setMusicStyle(context, MusicHost.MusicStyle.VINYL)
            renderMusicStyle()
        }
        renderMusicStyle()
        render()
    }

    override fun show() {
        super.show()
        window?.apply {
            setGravity(Gravity.END or Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 右侧面板：宽度占除 dock 栏外的区域，高度全屏
            val dm = context.resources.displayMetrics
            val dockWidth = (100 * dm.density).toInt()
            setLayout(dm.widthPixels - dockWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // 系统栏策略与主桌面一致：状态栏 / 系统 Dock 分别跟随设置开关，
        // 否则打开设置弹窗时系统栏（含车机底部 Dock）总是显示出来
        applySystemDock()
    }

    /** 按 UiTheme 两个开关设置弹窗窗口的系统栏显隐；系统栏显示时根布局让位（保留原有 24dp padding） */
    private fun applySystemDock() {
        val w = window ?: return
        val showStatus = UiTheme.showStatusBar(context)
        val showDock = UiTheme.showSystemDock(context)
        val dm = context.resources.displayMetrics
        val base = (24 * dm.density).toInt()

        if (showStatus) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        if (!showStatus && !showDock) {
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        var vis = 0
        if (!showStatus) {
            vis = vis or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (!showDock) {
            vis = vis or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        w.decorView.systemUiVisibility = vis

        findViewById<View>(R.id.settingsRoot).setOnApplyWindowInsetsListener { v, insets ->
            val top = base + (if (showStatus) insets.getSystemWindowInsetTop() else 0)
            val bottom = base + (if (showDock) insets.getSystemWindowInsetBottom() else 0)
            v.setPadding(base, top, base, bottom)
            insets
        }
        w.decorView.requestApplyInsets()
        // 弹窗获得焦点后重设（部分设备焦点变化后会恢复系统栏）
        windowFocusListener?.let { w.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                var fv = 0
                if (!showStatus) {
                    fv = fv or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
                if (!showDock) {
                    fv = fv or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                w.decorView.systemUiVisibility = fv
            }
        }
        windowFocusListener = focusListener
        w.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
    }

    private fun selectTab(index: Int) = selectTab(index, UiTheme.isDark(context))

    private fun selectTab(index: Int, dark: Boolean) {
        currentTab = index
        val p = UiTheme.settingsPalette(dark)
        val tabs = listOf(tabMusic, tabSteering, tabAppearance, tabVoice, tabMap, tabApps, tabAbout)
        val panels = listOf(panelMusic, panelSteering, panelAppearance, panelVoice, panelMap, panelApps, panelAbout)
        tabs.forEachIndexed { i, tab ->
            val isSel = i == index
            tab.isSelected = isSel
            tab.setTextColor(if (isSel) Color.WHITE else p.label)
            panels[i].visibility = if (isSel) View.VISIBLE else View.GONE
        }
        if (index == 5) renderApps()
    }

    /** 应用管理：列出已隐藏的应用 */
    private fun renderApps() {
        val hidden = com.nui.launcher.HiddenApps.hiddenSet(context)
        findViewById<View>(R.id.optHiddenApps).visibility = View.VISIBLE
        if (hidden.isEmpty()) {
            com.nui.launcher.NuiToast.show(context, context.getString(R.string.no_hidden_apps), Toast.LENGTH_SHORT)
        }
    }

    /** 弹出已隐藏应用列表，点击恢复 */
    private fun showHiddenAppsDialog() {
        val hidden = com.nui.launcher.HiddenApps.hiddenSet(context).toMutableList()
        if (hidden.isEmpty()) {
            com.nui.launcher.NuiToast.show(context, context.getString(R.string.no_hidden_apps), Toast.LENGTH_SHORT)
            return
        }
        val pm = context.packageManager
        val items = hidden.mapNotNull { pkg ->
            runCatching {
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                label to pkg
            }.getOrNull()
        }
        if (items.isEmpty()) {
            com.nui.launcher.NuiToast.show(context, context.getString(R.string.no_hidden_apps), Toast.LENGTH_SHORT)
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.manage_hidden_apps)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                val pkg = items[which].second
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.restore_confirm_title, items[which].first))
                    .setMessage(R.string.restore)
                    .setPositiveButton(R.string.restore) { _, _ ->
                        com.nui.launcher.HiddenApps.unhide(context, pkg)
                        com.nui.launcher.NuiToast.show(
                            context, context.getString(R.string.restore_done, items[which].first), Toast.LENGTH_SHORT
                        )
                    }
                    .setNegativeButton(R.string.back, null)
                    .let { showDialog(it) }
            }
            .setNegativeButton(R.string.back, null)
            .let { showDialog(it) }
    }

    /** 关于：版本号取实际安装版本，与 versionName 保持一致 */
    private fun renderAbout() {
        val ver = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
        findViewById<TextView>(R.id.tvAboutVersion).text = ver
    }

    /** 左侧菜单底部渐隐+箭头：内容超出可视区（可向下滚动）时显示，提示还有更多分类 */
    private fun updateLeftMenuFade() {
        val scroll = findViewById<android.widget.ScrollView>(R.id.leftMenuScroll) ?: return
        val fade = findViewById<View>(R.id.leftMenuFade) ?: return
        val dark = UiTheme.isDark(context)
        val p = UiTheme.settingsPalette(dark)
        val bg = p.bg
        fade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, bg)
        )
        findViewById<TextView>(R.id.leftMenuFadeArrow)?.setTextColor(p.label)
        scroll.post {
            fade.visibility = if (scroll.canScrollVertically(1)) View.VISIBLE else View.GONE
        }
    }

    /** 右侧面板底部渐隐+箭头：内容超出可视区（可向下滚动）时显示 */
    private fun updateRightPanelFade() {
        val scroll = findViewById<android.widget.ScrollView>(R.id.rightPanelScroll) ?: return
        val fade = findViewById<View>(R.id.rightPanelFade) ?: return
        val p = UiTheme.settingsPalette(UiTheme.isDark(context))
        fade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, p.bg)
        )
        findViewById<TextView>(R.id.rightPanelFadeArrow)?.setTextColor(p.label)
        scroll.post {
            fade.visibility = if (scroll.canScrollVertically(1)) View.VISIBLE else View.GONE
        }
    }

    /** 重启 NUI 桌面：清任务栈重新拉起进程 */
    private fun restartDesktop() {
        dismiss()
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) context.startActivity(i)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** 读取地图延迟配置填充输入框 */
    private fun renderMap() {
        edMapLaunch.setText(UiTheme.mapLaunchDelaySec(context).toString())
        edMapReturn.setText(UiTheme.mapReturnDelaySec(context).toString())
    }

    /** 保存输入框中的地图延迟配置 */
    private fun saveMapConfig() {
        val launch = edMapLaunch.text.toString().toIntOrNull()
        val ret = edMapReturn.text.toString().toIntOrNull()
        if (launch != null) UiTheme.setMapLaunchDelaySec(context, launch)
        if (ret != null) UiTheme.setMapReturnDelaySec(context, ret)
    }

    private fun applySettingsTheme() = applySettingsTheme(UiTheme.isDark(context))

    private fun applySettingsTheme(dark: Boolean) {
        val p = UiTheme.settingsPalette(dark)
        val dp = context.resources.displayMetrics.density
        findViewById<View>(R.id.settingsRoot).setBackgroundColor(p.bg)
        findViewById<TextView>(R.id.titleSettings).setTextColor(p.label)
        findViewById<ImageButton>(R.id.btnBack).imageTintList = ColorStateList.valueOf(p.label)
        findViewById<View>(R.id.contentCard).background = GradientDrawable().apply {
            setColor(p.card); cornerRadius = 14 * dp
        }
        findViewById<TextView>(R.id.groupTitleMusic).setTextColor(p.value)
        findViewById<TextView>(R.id.groupTitleSteering).setTextColor(p.value)
        findViewById<TextView>(R.id.groupTitleAppearance).setTextColor(p.value)
        findViewById<View>(R.id.dividerMusic).setBackgroundColor(p.divider)
        findViewById<View>(R.id.dividerSteering).setBackgroundColor(p.divider)
        findViewById<View>(R.id.dividerAdd).setBackgroundColor(p.divider)
        findViewById<View>(R.id.dividerAppearance).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvLyricAlphaValue).setTextColor(p.value)
        findViewById<TextView>(R.id.lyricHint).setTextColor(p.value)
        tvLyricTitle.setTextColor(p.label)
        findViewById<TextView>(R.id.tvOptMusicClassic).setTextColor(p.label)
        findViewById<TextView>(R.id.tvOptMusicVinyl).setTextColor(p.label)
        findViewById<TextView>(R.id.checkMusicClassic).setTextColor(p.accent)
        findViewById<TextView>(R.id.checkMusicVinyl).setTextColor(p.accent)
        tvOptSystem.setTextColor(p.label)
        tvOptDark.setTextColor(p.label)
        tvOptLight.setTextColor(p.label)
        findViewById<TextView>(R.id.tvOptFollowMap).setTextColor(p.label)
        tvOptDockEdge.setTextColor(p.label)
        tvOptDockFloat.setTextColor(p.label)
        tvOptStatusBar.setTextColor(p.label)
        checkStatusBar.setTextColor(p.accent)
        tvOptSystemDock.setTextColor(p.label)
        checkSystemDock.setTextColor(p.accent)
        tvSystemDockHint.setTextColor(p.value)
        tvDockIconTitle.setTextColor(p.label)
        tvAppIconTitle.setTextColor(p.label)
        findViewById<TextView>(R.id.groupTitleDock).setTextColor(p.value)
        findViewById<View>(R.id.dividerDock).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.groupTitleVoice).setTextColor(p.value)
        findViewById<View>(R.id.dividerVoice).setBackgroundColor(p.divider)
        tvOptVoiceFemale.setTextColor(p.label)
        // 地图
        findViewById<TextView>(R.id.groupTitleMap).setTextColor(p.value)
        findViewById<View>(R.id.dividerMap).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvMapLaunch).setTextColor(p.label)
        findViewById<TextView>(R.id.tvMapReturn).setTextColor(p.label)
        findViewById<TextView>(R.id.tvMapHint).setTextColor(p.value)
        edMapLaunch.setTextColor(p.label)
        edMapReturn.setTextColor(p.label)
        // 输入框背景跟随深浅色（浅色=浅灰底深字，深色=深灰底白字，否则深色下看不清）
        val editBg = GradientDrawable().apply {
            setColor(p.btnBg); cornerRadius = 8 * dp
        }
        edMapLaunch.background = editBg
        edMapReturn.background = editBg
        // 关于
        findViewById<TextView>(R.id.groupTitleAbout).setTextColor(p.value)
        findViewById<View>(R.id.dividerAbout).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvAboutAuthorLabel).setTextColor(p.label)
        findViewById<TextView>(R.id.tvAboutVersionLabel).setTextColor(p.label)
        findViewById<TextView>(R.id.tvAboutAuthor).setTextColor(p.value)
        findViewById<TextView>(R.id.tvAboutVersion).setTextColor(p.value)
        findViewById<TextView>(R.id.tvOptRestart).setTextColor(p.label)
        updateLeftMenuFade()
        updateRightPanelFade()
        // 应用
        findViewById<TextView>(R.id.groupTitleApps).setTextColor(p.value)
        findViewById<View>(R.id.dividerApps).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvOptHiddenApps).setTextColor(p.label)
        tvOptVoiceFemale.setTextColor(if (voiceSystemAvailable == false) p.value else p.label)
        findViewById<View>(R.id.optVoiceFemale).isEnabled = voiceSystemAvailable != false
        findViewById<View>(R.id.optVoiceFemale).alpha = if (voiceSystemAvailable == false) 0.4f else 1f
        tvOptVoiceMale.setTextColor(if (voiceSystemAvailable == false || voiceMaleSupported == false) p.value else p.label)
        findViewById<View>(R.id.optVoiceMale).isEnabled = voiceSystemAvailable != false && voiceMaleSupported != false
        findViewById<View>(R.id.optVoiceMale).alpha = if (voiceSystemAvailable == false || voiceMaleSupported == false) 0.4f else 1f
        findViewById<TextView>(R.id.tvVoiceUnavailable).visibility =
            if (voiceSystemAvailable == false) View.VISIBLE else View.GONE
        // 外观 → 壁纸
        findViewById<TextView>(R.id.groupTitleWallpaper).setTextColor(p.value)
        findViewById<View>(R.id.dividerWallpaper).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvOptWallpaperDay).setTextColor(p.label)
        findViewById<TextView>(R.id.tvOptWallpaperNight).setTextColor(p.label)
        tvWallpaperDayStatus.setTextColor(p.value)
        tvWallpaperNightStatus.setTextColor(p.value)
        tvWallpaperHint.setTextColor(p.value)
        checkVoiceFemale.setTextColor(p.accent)
        checkVoiceMale.setTextColor(p.accent)
        checkSystem.setTextColor(p.accent)
        checkDark.setTextColor(p.accent)
        checkLight.setTextColor(p.accent)
        checkDockEdge.setTextColor(p.accent)
        checkDockFloat.setTextColor(p.accent)
        seekLyricAlpha.progressTintList = ColorStateList.valueOf(p.accent)
        seekLyricAlpha.thumbTintList = ColorStateList.valueOf(p.accent)
        seekDockIcon.progressTintList = ColorStateList.valueOf(p.accent)
        seekDockIcon.thumbTintList = ColorStateList.valueOf(p.accent)
        seekAppIcon.progressTintList = ColorStateList.valueOf(p.accent)
        seekAppIcon.thumbTintList = ColorStateList.valueOf(p.accent)
        seekUiDpi.progressTintList = ColorStateList.valueOf(p.accent)
        seekUiDpi.thumbTintList = ColorStateList.valueOf(p.accent)
        findViewById<Button>(R.id.btnAdd).background = RippleDrawable(
            ColorStateList.valueOf(p.ripple), null,
            GradientDrawable().apply { setColor(p.accent); cornerRadius = 12 * dp },
        )
        selectTab(currentTab, dark)
        render(dark)
    }

    private fun renderAppearance() {
        val m = UiTheme.mode(context)
        checkSystem.visibility = if (m == UiTheme.Mode.SYSTEM) View.VISIBLE else View.GONE
        checkDark.visibility = if (m == UiTheme.Mode.DARK) View.VISIBLE else View.GONE
        checkLight.visibility = if (m == UiTheme.Mode.LIGHT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.checkFollowMap).visibility = if (m == UiTheme.Mode.FOLLOW_MAP) View.VISIBLE else View.GONE
        val ds = UiTheme.dockStyle(context)
        checkDockEdge.visibility = if (ds == UiTheme.DockStyle.EDGE) View.VISIBLE else View.GONE
        checkDockFloat.visibility = if (ds == UiTheme.DockStyle.FLOAT) View.VISIBLE else View.GONE
        checkStatusBar.visibility = if (UiTheme.showStatusBar(context)) View.VISIBLE else View.GONE
        checkSystemDock.visibility = if (UiTheme.showSystemDock(context)) View.VISIBLE else View.GONE
    }

    private fun renderVoice() {
        val g = NuiTts.voiceGender(context)
        checkVoiceFemale.visibility = if (g == NuiTts.VOICE_FEMALE) View.VISIBLE else View.GONE
        checkVoiceMale.visibility = if (g == NuiTts.VOICE_MALE) View.VISIBLE else View.GONE
    }

    private fun renderMusicStyle() {
        val s = MusicHost.musicStyle(context)
        findViewById<View>(R.id.checkMusicClassic).visibility =
            if (s == MusicHost.MusicStyle.CLASSIC) View.VISIBLE else View.GONE
        findViewById<View>(R.id.checkMusicVinyl).visibility =
            if (s == MusicHost.MusicStyle.VINYL) View.VISIBLE else View.GONE
    }

    /** 男声能力检测：系统 TTS 无男声音色时置灰不可点；若当前已选男声则回退女声（不播报）。
     *  引擎不可用（无 TTS 引擎/绑定失败）时男女声都置灰并显示提示。 */
    private fun refreshVoiceSupport() {
        val available = NuiTts(context).isSystemAvailable()
        voiceSystemAvailable = available
        val optFemale = findViewById<View>(R.id.optVoiceFemale)
        val tvFemale = findViewById<TextView>(R.id.tvOptVoiceFemale)
        val optMale = findViewById<View>(R.id.optVoiceMale)
        val tvMale = findViewById<TextView>(R.id.tvOptVoiceMale)
        val tvUnavailable = findViewById<TextView>(R.id.tvVoiceUnavailable)
        val sp = UiTheme.settingsPalette(UiTheme.isDark(context))

        when (available) {
            // 引擎不可用：男女声全部置灰 + 红色提示
            false -> {
                voiceMaleSupported = false
                optFemale.isEnabled = false; optFemale.alpha = 0.4f
                tvFemale.setTextColor(sp.value)
                optMale.isEnabled = false; optMale.alpha = 0.4f
                tvMale.setTextColor(sp.value)
                tvUnavailable.visibility = View.VISIBLE
                if (NuiTts.voiceGender(context) == NuiTts.VOICE_MALE) {
                    NuiTts.setVoiceGender(context, NuiTts.VOICE_FEMALE)
                }
                renderVoice()
            }
            // 引擎初始化中：暂不置灰，轮询等待（最多 ~6s），超时按不可用处理
            null -> {
                if (voiceSupportRetries++ < 12) {
                    findViewById<View>(R.id.optVoiceMale).postDelayed({ refreshVoiceSupport() }, 500)
                } else {
                    voiceMaleSupported = false
                    optFemale.isEnabled = false; optFemale.alpha = 0.4f
                    tvFemale.setTextColor(sp.value)
                    optMale.isEnabled = false; optMale.alpha = 0.4f
                    tvMale.setTextColor(sp.value)
                    tvUnavailable.visibility = View.VISIBLE
                }
            }
            // 引擎可用：按音色支持情况置灰男声
            true -> {
                voiceMaleSupported = NuiTts(context).supportsVoice(NuiTts.VOICE_MALE)
                tvUnavailable.visibility = View.GONE
                optFemale.isEnabled = true; optFemale.alpha = 1f
                tvFemale.setTextColor(sp.label)
                if (voiceMaleSupported == false) {
                    optMale.isEnabled = false; optMale.alpha = 0.4f
                    tvMale.setTextColor(sp.value)
                    if (NuiTts.voiceGender(context) == NuiTts.VOICE_MALE) {
                        NuiTts.setVoiceGender(context, NuiTts.VOICE_FEMALE)
                        renderVoice()
                    }
                } else {
                    optMale.isEnabled = true; optMale.alpha = 1f
                    tvMale.setTextColor(sp.label)
                }
            }
        }
    }

    /** 刷新壁纸槽位状态文字（默认/已设置），宿主选图返回后调用 */
    fun refreshWallpaper() {
        val daySet = wallpaperHasCustom?.invoke(com.nui.launcher.WallpaperController.Slot.DAY) ?: false
        val nightSet = wallpaperHasCustom?.invoke(com.nui.launcher.WallpaperController.Slot.NIGHT) ?: false
        tvWallpaperDayStatus.text = if (daySet) context.getString(R.string.wallpaper_set) else context.getString(R.string.wallpaper_default)
        tvWallpaperNightStatus.text = if (nightSet) context.getString(R.string.wallpaper_set) else context.getString(R.string.wallpaper_default)
    }

    private fun render() = render(UiTheme.isDark(context))

    private fun render(dark: Boolean) {
        val p = UiTheme.settingsPalette(dark)
        val dp = context.resources.displayMetrics.density
        recordList.removeAllViews()
        val records = KeyMapConfig.load(context)
        if (records.isEmpty()) {
            val empty = TextView(context).apply {
                text = "暂无映射，点击下方按钮添加"
                setTextColor(p.value)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            }
            recordList.addView(empty)
        }
        records.forEach { rec ->
            val v = LayoutInflater.from(context).inflate(R.layout.item_keymap, recordList, false)
            v.findViewById<TextView>(R.id.tvKeyCode).apply {
                text = "KeyCode: ${rec.keyCode}"
                setTextColor(p.label)
            }
            v.findViewById<TextView>(R.id.tvAction).apply {
                text = KeyMapConfig.actionName(rec.action)
                setTextColor(p.accent)
            }
            v.findViewById<View>(R.id.btnEdit).apply {
                background = RippleDrawable(
                    ColorStateList.valueOf(p.ripple), null,
                    GradientDrawable().apply { setColor(p.btnBg); cornerRadius = 12 * dp },
                )
                setOnClickListener { startEdit(rec) }
            }
            v.findViewById<View>(R.id.btnDelete).apply {
                background = RippleDrawable(
                    ColorStateList.valueOf(p.ripple), null,
                    GradientDrawable().apply { setColor(p.btnBg); cornerRadius = 12 * dp },
                )
                setOnClickListener {
                    KeyMapConfig.remove(context, rec)
                    render()
                }
            }
            recordList.addView(v)
        }
    }

    private fun startEdit(existing: KeyMapRecord?) {
        var keyCode = existing?.keyCode ?: -1
        var action = existing?.action

        val dp = context.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val p = UiTheme.settingsPalette(context)
        // 每行独立 RippleDrawable 实例：共享同一实例会导致点击波纹状态互相串（点 keycode 行效果显示在操作行）
        fun fieldBg() = RippleDrawable(
            ColorStateList.valueOf(p.ripple), null,
            GradientDrawable().apply { setColor(p.btnBg); cornerRadius = 12 * dp },
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val tvKey = TextView(context).apply {
            textSize = 18f
            setTextColor(p.label)
            background = fieldBg()
            setPadding(pad, pad, pad, pad)
        }
        val tvAction = TextView(context).apply {
            textSize = 18f
            setTextColor(p.label)
            background = fieldBg()
            setPadding(pad, pad, pad, pad)
        }
        fun refreshTexts() {
            tvKey.text = context.getString(R.string.key_code) +
                (if (keyCode >= 0) "：$keyCode" else "")
            tvAction.text = context.getString(R.string.action_to_run) +
                (action?.let { "：" + KeyMapConfig.actionName(it) } ?: "")
        }
        refreshTexts()
        tvKey.setOnClickListener {
            showKeyCodeInput(keyCode) { code -> keyCode = code; refreshTexts() }
        }
        tvAction.setOnClickListener {
            val names = KeyMapConfig.ACTIONS.values.toList()
            val ids = KeyMapConfig.ACTIONS.keys.toList()
            dialogBuilder()
                .setTitle(R.string.select_action)
                .setItems(names.toTypedArray()) { _, which ->
                    action = ids[which]
                    refreshTexts()
                }
                .let { showDialog(it) }
        }
        root.addView(tvKey)
        root.addView(tvAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })

        dialogBuilder()
            .setTitle(if (existing == null) R.string.add_key_mapping else R.string.edit)
            .setView(root)
            .setPositiveButton(R.string.save) { _, _ ->
                if (keyCode >= 0 && action != null) {
                    KeyMapConfig.add(context, KeyMapRecord(keyCode, action!!))
                    render()
                }
            }
            .setNegativeButton(R.string.back, null)
            .let { showDialog(it) }
    }

    private fun dialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(
        context,
        if (UiTheme.isDark(context)) android.R.style.Theme_Material_Dialog
        else android.R.style.Theme_Material_Light_Dialog,
    )

    private fun showKeyCodeInput(current: Int, onKey: (Int) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val p = UiTheme.settingsPalette(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val et = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "如 88 / 87 / 275"
            setText(if (current >= 0) current.toString() else "")
            setTextColor(p.label)
            setHintTextColor(p.value)
        }
        root.addView(et)
        dialogBuilder()
            .setTitle(R.string.key_code)
            .setView(root)
            .setNeutralButton(R.string.listening_key) { _, _ ->
                listenKey(onKey)
            }
            .setPositiveButton(R.string.save) { _, _ ->
                et.text.toString().trim().toIntOrNull()?.let(onKey)
            }
            .setNegativeButton(R.string.back, null)
            .let { showDialog(it) }
        et.requestFocus()
    }

    /** 统一弹窗显示：显式设置按钮/标题文字颜色（浅色=深字、深色=浅字），
     *  避免车机 ROM / Force Dark 覆盖系统主题导致浅色模式下按钮文字发浅看不清。 */
    private fun showDialog(builder: android.app.AlertDialog.Builder) {
        val dlg = builder.show()
        val dark = UiTheme.isDark(context)
        val btnColor = if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(btnColor)
    }

    private fun listenKey(onKey: (Int) -> Unit) {
        val p = UiTheme.settingsPalette(context)
        // 主题必须跟随 NUI 深浅色（车机系统 UI 常为深色，用系统默认主题会深底深字看不清）
        val dlg = object : Dialog(
            context,
            if (UiTheme.isDark(context)) android.R.style.Theme_Material_Dialog
            else android.R.style.Theme_Material_Light_Dialog,
        ) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dismiss()
                    onKey(event.keyCode)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val tv = TextView(context).apply {
            text = context.getString(R.string.listening_key)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(p.label)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        dlg.setContentView(tv)
        dlg.setCancelable(true)
        dlg.show()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
