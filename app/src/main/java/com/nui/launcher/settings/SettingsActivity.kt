package com.nui.launcher.settings

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nui.launcher.R
import com.nui.launcher.music.MusicHost

/** 桌面设置（iOS 分组风格）：音乐（悬浮歌词背景不透明度）+ 方向盘按键映射 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var recordList: LinearLayout
    // 缓存 view 引用，避免回调中重复 findViewById（拖动滑块时可能返回 null）
    private lateinit var seekLyricAlpha: SeekBar
    private lateinit var tvLyricAlphaValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { startEdit(null) }
        recordList = findViewById(R.id.recordList)
        seekLyricAlpha = findViewById(R.id.seekLyricAlpha)
        tvLyricAlphaValue = findViewById(R.id.tvLyricAlphaValue)

        // 悬浮歌词背景不透明度（0-100，默认 80，拖动实时保存）
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

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        recordList.removeAllViews()
        val records = KeyMapConfig.load(this)
        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无映射，点击下方按钮添加"
                setTextColor(getColor(R.color.settings_value))
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            }
            recordList.addView(empty)
        }
        records.forEach { rec ->
            val v = layoutInflater.inflate(R.layout.item_keymap, recordList, false)
            v.findViewById<TextView>(R.id.tvKeyCode).text = "KeyCode: ${rec.keyCode}"
            v.findViewById<TextView>(R.id.tvAction).text = KeyMapConfig.actionName(rec.action)
            v.findViewById<View>(R.id.btnEdit).setOnClickListener { startEdit(rec) }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener {
                KeyMapConfig.remove(this, rec)
                render()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val tvKey = TextView(this).apply {
            textSize = 18f
            setTextColor(getColor(R.color.settings_label))
            background = getDrawable(R.drawable.bg_settings_btn)
            setPadding(pad, pad, pad, pad)
        }
        val tvAction = TextView(this).apply {
            textSize = 18f
            setTextColor(getColor(R.color.settings_label))
            background = getDrawable(R.drawable.bg_settings_btn)
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
            AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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

    /** 手动输入 keycode，也可点"监听按键"物理捕获 */
    private fun showKeyCodeInput(current: Int, onKey: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "如 88 / 87 / 275"
            setText(if (current >= 0) current.toString() else "")
            setTextColor(getColor(R.color.settings_label))
            setHintTextColor(getColor(R.color.settings_value))
        }
        root.addView(et)
        AlertDialog.Builder(this)
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
