package com.nui.launcher.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nui.launcher.R
import com.nui.launcher.UiTheme
import com.nui.launcher.music.MusicHost
import com.nui.launcher.voice.NuiTts

/** 桌面设置：左侧分类（音乐 / 方向盘 / 外观）+ 右侧详情（iOS 风格） */
class SettingsActivity : AppCompatActivity() {

    private lateinit var recordList: LinearLayout
    // 缓存 view 引用，避免回调中重复 findViewById（拖动滑块时可能返回 null）
    private lateinit var seekLyricAlpha: SeekBar
    private lateinit var tvLyricAlphaValue: TextView
    private lateinit var tvLyricTitle: TextView
    private lateinit var tabMusic: TextView
    private lateinit var tabSteering: TextView
    private lateinit var tabAppearance: TextView
    private lateinit var tabVoice: TextView
    private lateinit var tabMap: TextView
    private lateinit var tabAbout: TextView
    private lateinit var panelMusic: View
    private lateinit var panelSteering: View
    private lateinit var panelAppearance: View
    private lateinit var panelVoice: View
    private lateinit var panelMap: View
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
    private lateinit var seekAppIcon: SeekBar
    private lateinit var tvAppIconValue: TextView
    private lateinit var tvAppIconTitle: TextView
    private lateinit var tvOptVoiceFemale: TextView
    private lateinit var checkVoiceFemale: TextView
    private lateinit var tvOptVoiceMale: TextView
    private lateinit var checkVoiceMale: TextView
    private lateinit var optWallpaperDay: View
    private lateinit var optWallpaperNight: View
    private lateinit var tvWallpaperDayStatus: TextView
    private lateinit var tvWallpaperNightStatus: TextView
    private lateinit var tvWallpaperHint: TextView
    private lateinit var wallpaper: com.nui.launcher.WallpaperController
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
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
        tabAbout = findViewById(R.id.tabAbout)
        panelMusic = findViewById(R.id.panelMusic)
        panelSteering = findViewById(R.id.panelSteering)
        panelAppearance = findViewById(R.id.panelAppearance)
        panelVoice = findViewById(R.id.panelVoice)
        panelMap = findViewById(R.id.panelMap)
        panelAbout = findViewById(R.id.panelAbout)
        edMapLaunch = findViewById(R.id.edMapLaunch)
        edMapReturn = findViewById(R.id.edMapReturn)
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
        seekAppIcon = findViewById(R.id.seekAppIcon)
        tvAppIconValue = findViewById(R.id.tvAppIconValue)
        tvAppIconTitle = findViewById(R.id.tvAppIconTitle)
        tvOptVoiceFemale = findViewById(R.id.tvOptVoiceFemale)
        checkVoiceFemale = findViewById(R.id.checkVoiceFemale)
        tvOptVoiceMale = findViewById(R.id.tvOptVoiceMale)
        checkVoiceMale = findViewById(R.id.checkVoiceMale)
        optWallpaperDay = findViewById(R.id.optWallpaperDay)
        optWallpaperNight = findViewById(R.id.optWallpaperNight)
        tvWallpaperDayStatus = findViewById(R.id.tvWallpaperDayStatus)
        tvWallpaperNightStatus = findViewById(R.id.tvWallpaperNightStatus)
        tvWallpaperHint = findViewById(R.id.tvWallpaperHint)

        // 左侧分类切换
        tabMusic.setOnClickListener { selectTab(0) }
        tabSteering.setOnClickListener { selectTab(1) }
        tabAppearance.setOnClickListener { selectTab(2) }
        tabVoice.setOnClickListener { selectTab(3) }
        tabMap.setOnClickListener { selectTab(4) }
        tabAbout.setOnClickListener { selectTab(5) }
        selectTab(0)

        // 地图：两个延迟配置，输入即时保存
        edMapLaunch.setOnEditorActionListener { _, _, _ -> saveMapConfig(); true }
        edMapLaunch.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveMapConfig() }
        edMapReturn.setOnEditorActionListener { _, _, _ -> saveMapConfig(); true }
        edMapReturn.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveMapConfig() }

        // 外观：跟随系统 / 深色 / 浅色（切换后立即整页刷新配色）
        findViewById<View>(R.id.optSystem).setOnClickListener {
            UiTheme.setMode(this, UiTheme.Mode.SYSTEM)
            renderAppearance()
            applySettingsTheme()
        }
        findViewById<View>(R.id.optDark).setOnClickListener {
            UiTheme.setMode(this, UiTheme.Mode.DARK)
            renderAppearance()
            applySettingsTheme()
        }
        findViewById<View>(R.id.optLight).setOnClickListener {
            UiTheme.setMode(this, UiTheme.Mode.LIGHT)
            renderAppearance()
            applySettingsTheme()
        }
        // Dock 形态：贴边（矩形）/ 悬浮（圆角）
        findViewById<View>(R.id.optDockEdge).setOnClickListener {
            UiTheme.setDockStyle(this, UiTheme.DockStyle.EDGE)
            renderAppearance()
        }
        findViewById<View>(R.id.optDockFloat).setOnClickListener {
            UiTheme.setDockStyle(this, UiTheme.DockStyle.FLOAT)
            renderAppearance()
        }
        // 显示顶部状态栏（独立开关）
        optStatusBar.setOnClickListener {
            UiTheme.setShowStatusBar(this, !UiTheme.showStatusBar(this))
            renderAppearance()
            applySettingsTheme()
        }
        // 显示系统 Dock（底部导航栏，独立开关）
        optSystemDock.setOnClickListener {
            UiTheme.setShowSystemDock(this, !UiTheme.showSystemDock(this))
            renderAppearance()
            applySettingsTheme()
        }
        // 语音：女声 / 男声（通用离线语音服务，零下载）
        findViewById<View>(R.id.optVoiceFemale).setOnClickListener {
            NuiTts.setVoiceGender(this, NuiTts.VOICE_FEMALE)
            NuiTts(this).switchVoice(NuiTts.VOICE_FEMALE)
            renderVoice()
        }
        findViewById<View>(R.id.optVoiceMale).setOnClickListener {
            NuiTts.setVoiceGender(this, NuiTts.VOICE_MALE)
            NuiTts(this).switchVoice(NuiTts.VOICE_MALE)
            renderVoice()
        }
        renderVoice()
        // 外观 → 壁纸：白天 / 晚上（本 Activity 自持控制器，应用于自身根视图做预览）
        wallpaper = com.nui.launcher.WallpaperController(this, findViewById(R.id.settingsRoot))
        wallpaper.applyOnStart()
        optWallpaperDay.setOnClickListener { wallpaper.showMenu(com.nui.launcher.WallpaperController.Slot.DAY) }
        optWallpaperNight.setOnClickListener { wallpaper.showMenu(com.nui.launcher.WallpaperController.Slot.NIGHT) }
        refreshWallpaper()
        // Dock 图标大小：0.6~1.4，默认 1.0（100%），拖动实时保存
        tvDockIconValue.text = "${(UiTheme.dockIconScale(this) * 100).toInt()}%"
        seekDockIcon.apply {
            progress = ((UiTheme.dockIconScale(this@SettingsActivity) - 0.6f) / 0.8f * 80).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val scale = 0.6f + progress / 80f * 0.8f
                        UiTheme.setDockIconScale(this@SettingsActivity, scale)
                        tvDockIconValue.text = "${(scale * 100).toInt()}%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        // 应用列表图标大小：0.6~1.4，默认 1.0（100%），拖动实时保存
        tvAppIconValue.text = "${(UiTheme.appIconScale(this) * 100).toInt()}%"
        seekAppIcon.apply {
            progress = ((UiTheme.appIconScale(this@SettingsActivity) - 0.6f) / 0.8f * 80).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val scale = 0.6f + progress / 80f * 0.8f
                        UiTheme.setAppIconScale(this@SettingsActivity, scale)
                        tvAppIconValue.text = "${(scale * 100).toInt()}%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        renderAppearance()
        applySettingsTheme()

        // 悬浮歌词背景不透明度（0-100，默认 20，拖动实时保存）
        tvLyricAlphaValue.text = "${MusicHost.lyricBgAlpha(this)}%"
        seekLyricAlpha.apply {
            max = 100
            progress = MusicHost.lyricBgAlpha(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        MusicHost.setLyricBgAlpha(this@SettingsActivity, progress)
                        tvLyricAlphaValue.text = "$progress%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    /** 切换分类：0=音乐，1=方向盘，2=外观 */
    private fun selectTab(index: Int) = selectTab(index, UiTheme.isDark(this))

    private fun selectTab(index: Int, dark: Boolean) {
        currentTab = index
        val p = UiTheme.settingsPalette(dark)
        val tabs = listOf(tabMusic, tabSteering, tabAppearance, tabVoice, tabMap, tabAbout)
        val panels = listOf(panelMusic, panelSteering, panelAppearance, panelVoice, panelMap, panelAbout)
        tabs.forEachIndexed { i, tab ->
            val isSel = i == index
            tab.isSelected = isSel
            tab.setTextColor(if (isSel) Color.WHITE else p.label)
            panels[i].visibility = if (isSel) View.VISIBLE else View.GONE
        }
    }

    /** 关于：版本号取实际安装版本，与 versionName 保持一致 */
    private fun renderAbout() {
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: ""
        findViewById<TextView>(R.id.tvAboutVersion).text = ver
    }

    /** 读取地图延迟配置填充输入框 */
    private fun renderMap() {
        edMapLaunch.setText(UiTheme.mapLaunchDelaySec(this).toString())
        edMapReturn.setText(UiTheme.mapReturnDelaySec(this).toString())
    }

    /** 保存输入框中的地图延迟配置 */
    private fun saveMapConfig() {
        val launch = edMapLaunch.text.toString().toIntOrNull()
        val ret = edMapReturn.text.toString().toIntOrNull()
        if (launch != null) UiTheme.setMapLaunchDelaySec(this, launch)
        if (ret != null) UiTheme.setMapReturnDelaySec(this, ret)
    }

    /** 设置页跟随深浅模式：整页配色动态应用（系统切深浅时自动跟随） */
    private fun applySettingsTheme() = applySettingsTheme(UiTheme.isDark(this))

    private fun applySettingsTheme(dark: Boolean) {
        val p = UiTheme.settingsPalette(dark)
        val dp = resources.displayMetrics.density
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
        tvOptSystem.setTextColor(p.label)
        tvOptDark.setTextColor(p.label)
        tvOptLight.setTextColor(p.label)
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
        tvOptVoiceMale.setTextColor(p.label)
        // 地图
        findViewById<TextView>(R.id.groupTitleMap).setTextColor(p.value)
        findViewById<View>(R.id.dividerMap).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvMapLaunch).setTextColor(p.label)
        findViewById<TextView>(R.id.tvMapReturn).setTextColor(p.label)
        findViewById<TextView>(R.id.tvMapHint).setTextColor(p.value)
        edMapLaunch.setTextColor(p.label)
        edMapReturn.setTextColor(p.label)
        // 关于
        findViewById<TextView>(R.id.groupTitleAbout).setTextColor(p.value)
        findViewById<View>(R.id.dividerAbout).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.tvAboutAuthor).setTextColor(p.value)
        findViewById<TextView>(R.id.tvAboutVersion).setTextColor(p.value)
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
        findViewById<Button>(R.id.btnAdd).background = RippleDrawable(
            ColorStateList.valueOf(p.ripple), null,
            GradientDrawable().apply { setColor(p.accent); cornerRadius = 12 * dp },
        )
        // 刷新当前 tab 高亮/文字色
        selectTab(currentTab, dark)
        render(dark)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettingsTheme(UiTheme.isDark(this, newConfig))
        renderAppearance()
    }

    /** 按当前外观模式 + Dock 形态刷新勾选 */
    private fun renderAppearance() {
        val m = UiTheme.mode(this)
        checkSystem.visibility = if (m == UiTheme.Mode.SYSTEM) View.VISIBLE else View.GONE
        checkDark.visibility = if (m == UiTheme.Mode.DARK) View.VISIBLE else View.GONE
        checkLight.visibility = if (m == UiTheme.Mode.LIGHT) View.VISIBLE else View.GONE
        val ds = UiTheme.dockStyle(this)
        checkDockEdge.visibility = if (ds == UiTheme.DockStyle.EDGE) View.VISIBLE else View.GONE
        checkDockFloat.visibility = if (ds == UiTheme.DockStyle.FLOAT) View.VISIBLE else View.GONE
        checkStatusBar.visibility = if (UiTheme.showStatusBar(this)) View.VISIBLE else View.GONE
        checkSystemDock.visibility = if (UiTheme.showSystemDock(this)) View.VISIBLE else View.GONE
    }

    /** 按当前音色选择刷新勾选 */
    private fun renderVoice() {
        val g = NuiTts.voiceGender(this)
        checkVoiceFemale.visibility = if (g == NuiTts.VOICE_FEMALE) View.VISIBLE else View.GONE
        checkVoiceMale.visibility = if (g == NuiTts.VOICE_MALE) View.VISIBLE else View.GONE
    }

    /** 刷新壁纸槽位状态文字（默认/已设置） */
    private fun refreshWallpaper() {
        tvWallpaperDayStatus.text = if (wallpaper.hasCustom(com.nui.launcher.WallpaperController.Slot.DAY)) getString(R.string.wallpaper_set) else getString(R.string.wallpaper_default)
        tvWallpaperNightStatus.text = if (wallpaper.hasCustom(com.nui.launcher.WallpaperController.Slot.NIGHT)) getString(R.string.wallpaper_set) else getString(R.string.wallpaper_default)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.nui.launcher.WallpaperController.REQ_PICK) {
            if (::wallpaper.isInitialized) wallpaper.onActivityResult(requestCode, resultCode, data)
            refreshWallpaper()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        renderAppearance()
        renderMap()
        renderAbout()
    }

    private fun render() = render(UiTheme.isDark(this))

    private fun render(dark: Boolean) {
        val p = UiTheme.settingsPalette(dark)
        val dp = resources.displayMetrics.density
        recordList.removeAllViews()
        val records = KeyMapConfig.load(this)
        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无映射，点击下方按钮添加"
                setTextColor(p.value)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            }
            recordList.addView(empty)
        }
        records.forEach { rec ->
            val v = layoutInflater.inflate(R.layout.item_keymap, recordList, false)
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
                    KeyMapConfig.remove(this@SettingsActivity, rec)
                    render()
                }
            }
            recordList.addView(v)
        }
    }

    /** 编辑/新增映射：监听 keycode + 选择操作 + 保存 */
    private fun startEdit(existing: KeyMapRecord?) {
        var keyCode = existing?.keyCode ?: -1
        var action = existing?.action

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val p = UiTheme.settingsPalette(this)
        val fieldBg = RippleDrawable(
            ColorStateList.valueOf(p.ripple), null,
            GradientDrawable().apply { setColor(p.btnBg); cornerRadius = 12 * dp },
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val tvKey = TextView(this).apply {
            textSize = 18f
            setTextColor(p.label)
            background = fieldBg
            setPadding(pad, pad, pad, pad)
        }
        val tvAction = TextView(this).apply {
            textSize = 18f
            setTextColor(p.label)
            background = fieldBg
            setPadding(pad, pad, pad, pad)
        }
        fun refreshTexts() {
            tvKey.text = getString(R.string.key_code) +
                (if (keyCode >= 0) "：$keyCode" else "")
            tvAction.text = getString(R.string.action_to_run) +
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
                .show()
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
                    KeyMapConfig.add(this, KeyMapRecord(keyCode, action!!))
                    render()
                }
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    /** 对话框跟随深浅模式 */
    private fun dialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(
        this,
        if (UiTheme.isDark(this)) android.R.style.Theme_Material_Dialog
        else android.R.style.Theme_Material_Light_Dialog,
    )

    /** 手动输入 keycode，也可点"监听按键"物理捕获 */
    private fun showKeyCodeInput(current: Int, onKey: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val p = UiTheme.settingsPalette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val et = android.widget.EditText(this).apply {
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
            .show()
        et.requestFocus()
    }

    /** 监听物理按键：Dialog 拦截 dispatchKeyEvent */
    private fun listenKey(onKey: (Int) -> Unit) {
        val dlg = object : Dialog(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dismiss()
                    onKey(event.keyCode)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val tv = TextView(this).apply {
            text = getString(R.string.listening_key)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.settings_label))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        dlg.setContentView(tv)
        dlg.setCancelable(true)
        dlg.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
