package com.nui.launcher.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
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
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
    private lateinit var panelMusic: View
    private lateinit var panelSteering: View
    private lateinit var panelAppearance: View
    private lateinit var panelVoice: View
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
    private var currentTab = 0

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
        panelMusic = findViewById(R.id.panelMusic)
        panelSteering = findViewById(R.id.panelSteering)
        panelAppearance = findViewById(R.id.panelAppearance)
        panelVoice = findViewById(R.id.panelVoice)
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

        tabMusic.setOnClickListener { selectTab(0) }
        tabSteering.setOnClickListener { selectTab(1) }
        tabAppearance.setOnClickListener { selectTab(2) }
        tabVoice.setOnClickListener { selectTab(3) }
        selectTab(0)

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
        findViewById<View>(R.id.optDockEdge).setOnClickListener {
            UiTheme.setDockStyle(context, UiTheme.DockStyle.EDGE)
            renderAppearance()
        }
        findViewById<View>(R.id.optDockFloat).setOnClickListener {
            UiTheme.setDockStyle(context, UiTheme.DockStyle.FLOAT)
            renderAppearance()
        }
        // 语音：女声 / 男声（通用离线语音服务，零下载）
        findViewById<View>(R.id.optVoiceFemale).setOnClickListener {
            NuiTts.setVoiceGender(context, NuiTts.VOICE_FEMALE)
            NuiTts(context).switchVoice(NuiTts.VOICE_FEMALE)
            renderVoice()
        }
        findViewById<View>(R.id.optVoiceMale).setOnClickListener {
            NuiTts.setVoiceGender(context, NuiTts.VOICE_MALE)
            NuiTts(context).switchVoice(NuiTts.VOICE_MALE)
            renderVoice()
        }
        renderVoice()
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
    }

    private fun selectTab(index: Int) = selectTab(index, UiTheme.isDark(context))

    private fun selectTab(index: Int, dark: Boolean) {
        currentTab = index
        val p = UiTheme.settingsPalette(dark)
        val tabs = listOf(tabMusic, tabSteering, tabAppearance, tabVoice)
        val panels = listOf(panelMusic, panelSteering, panelAppearance, panelVoice)
        tabs.forEachIndexed { i, tab ->
            val isSel = i == index
            tab.isSelected = isSel
            tab.setTextColor(if (isSel) Color.WHITE else p.label)
            panels[i].visibility = if (isSel) View.VISIBLE else View.GONE
        }
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
        tvOptSystem.setTextColor(p.label)
        tvOptDark.setTextColor(p.label)
        tvOptLight.setTextColor(p.label)
        tvOptDockEdge.setTextColor(p.label)
        tvOptDockFloat.setTextColor(p.label)
        tvDockIconTitle.setTextColor(p.label)
        tvAppIconTitle.setTextColor(p.label)
        findViewById<TextView>(R.id.groupTitleDock).setTextColor(p.value)
        findViewById<View>(R.id.dividerDock).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.groupTitleVoice).setTextColor(p.value)
        findViewById<View>(R.id.dividerVoice).setBackgroundColor(p.divider)
        tvOptVoiceFemale.setTextColor(p.label)
        tvOptVoiceMale.setTextColor(p.label)
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
        selectTab(currentTab, dark)
        render(dark)
    }

    private fun renderAppearance() {
        val m = UiTheme.mode(context)
        checkSystem.visibility = if (m == UiTheme.Mode.SYSTEM) View.VISIBLE else View.GONE
        checkDark.visibility = if (m == UiTheme.Mode.DARK) View.VISIBLE else View.GONE
        checkLight.visibility = if (m == UiTheme.Mode.LIGHT) View.VISIBLE else View.GONE
        val ds = UiTheme.dockStyle(context)
        checkDockEdge.visibility = if (ds == UiTheme.DockStyle.EDGE) View.VISIBLE else View.GONE
        checkDockFloat.visibility = if (ds == UiTheme.DockStyle.FLOAT) View.VISIBLE else View.GONE
    }

    private fun renderVoice() {
        val g = NuiTts.voiceGender(context)
        checkVoiceFemale.visibility = if (g == NuiTts.VOICE_FEMALE) View.VISIBLE else View.GONE
        checkVoiceMale.visibility = if (g == NuiTts.VOICE_MALE) View.VISIBLE else View.GONE
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
        val fieldBg = RippleDrawable(
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
            background = fieldBg
            setPadding(pad, pad, pad, pad)
        }
        val tvAction = TextView(context).apply {
            textSize = 18f
            setTextColor(p.label)
            background = fieldBg
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
                    KeyMapConfig.add(context, KeyMapRecord(keyCode, action!!))
                    render()
                }
            }
            .setNegativeButton(R.string.back, null)
            .show()
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
            .show()
        et.requestFocus()
    }

    private fun listenKey(onKey: (Int) -> Unit) {
        val dlg = object : Dialog(context) {
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
            setTextColor(ContextCompat.getColor(context, R.color.settings_label))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        dlg.setContentView(tv)
        dlg.setCancelable(true)
        dlg.show()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
