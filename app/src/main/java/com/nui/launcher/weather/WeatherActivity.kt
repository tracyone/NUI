package com.nui.launcher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nui.launcher.NuiToast
import com.nui.launcher.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 全屏天气页：
 * - 全屏动态天气背景（[WeatherSurfaceView] 绘制晴/雨/雪/云/雷/雾动画）
 * - 当前实况（大温度 + 天气 + 体感/湿度/风）+ 逐小时 + 逐日预报
 * - 重大天气预警横幅
 * - 语音播报（TTS）：首次成功获取到天气后自动播报；后续刷新发现新的重大天气突发时自动播报
 */
class WeatherActivity : AppCompatActivity() {

    private lateinit var surface: WeatherSurfaceView
    private lateinit var weatherFetcher: WeatherFetcher
    private lateinit var voice: WeatherVoice

    private var tvCity: TextView? = null
    private var tvDate: TextView? = null
    private var tvTemp: TextView? = null
    private var tvDesc: TextView? = null
    private var tvMeta: TextView? = null
    private var alertBanner: LinearLayout? = null
    private var alertText: TextView? = null
    private var hourlyRow: LinearLayout? = null
    private var dailyCol: LinearLayout? = null
    private var tvStatus: TextView? = null

    private var lastInfo: WeatherFetcher.WeatherInfo? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L)
        }
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Int) = v * density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(buildUi())

        voice = WeatherVoice(this)

        weatherFetcher = WeatherFetcher(this)
        weatherFetcher.onWeatherReady = { info ->
            lastInfo = info
            render(info)
            voice.onWeather(info)
        }
        loadLocationAndFetch()
    }

    // ===================== UI 构建 =====================

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // 全屏特效背景
        surface = WeatherSurfaceView(this)
        surface.effect = "clear"
        surface.isDay = true
        root.addView(surface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 顶部渐变遮罩，保证顶部文字可读
        val topScrim = View(this)
        topScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x66000000.toInt(), 0x00000000.toInt())
        )
        root.addView(topScrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(120),
            Gravity.TOP))

        // 底部渐变遮罩，保证预报区可读
        val bottomScrim = View(this)
        bottomScrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x99000000.toInt(), 0x00000000.toInt())
        )
        root.addView(bottomScrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(300),
            Gravity.BOTTOM))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        column.addView(buildTopBar())
        column.addView(buildAlertBanner())
        column.addView(buildCurrent(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f))
        column.addView(buildForecast(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f))

        return root
    }

    private fun textView(
        text: String,
        sizeSp: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = android.graphics.Typeface.create(
            "sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        this.gravity = gravity
        includeFontPadding = false
    }

    private fun topButton(iconRes: Int, contentDesc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            background = roundedBg(0x33000000.toInt(), dp(14))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    private fun roundedBg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(1, 0x22FFFFFF.toInt())
        }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(topButton(R.drawable.ic_back, "返回") { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))

        val loc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        tvCity = textView("定位中…", 22f, bold = true)
        tvDate = textView("", 13f, 0xCCFFFFFF.toInt())
        loc.addView(tvCity)
        loc.addView(tvDate, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        bar.addView(loc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 语音播报按钮：文字圆角按钮
        val voiceBtn = TextView(this).apply {
            text = "🔊 播报"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBg(0x33000000.toInt(), dp(22))
            setOnClickListener { replayVoice() }
        }
        bar.addView(voiceBtn)
        return bar
    }

    private fun buildAlertBanner(): LinearLayout {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBg(0xCCB3261E.toInt(), dp(14))
            visibility = View.GONE
        }
        val tag = textView("⚠", 18f, bold = true)
        banner.addView(tag)
        alertText = textView("", 15f, bold = true).apply {
            setPadding(dp(10), 0, 0, 0)
        }
        banner.addView(alertText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        alertBanner = banner
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        banner.layoutParams = lp
        return banner
    }

    private fun buildCurrent(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        tvTemp = textView("--°", 88f, bold = true, gravity = Gravity.CENTER)
        tvDesc = textView("正在获取天气…", 28f, gravity = Gravity.CENTER).apply {
            setTextColor(0xF2FFFFFF.toInt())
        }
        tvMeta = textView("", 16f, 0xCCFFFFFF.toInt(), gravity = Gravity.CENTER)
        tvStatus = textView("首次获取成功后将自动语音播报天气", 12f, 0x99FFFFFF.toInt(), gravity = Gravity.CENTER)

        box.addView(tvTemp)
        box.addView(tvDesc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        box.addView(tvMeta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        box.addView(tvStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return box
    }

    private fun buildForecast(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
            background = roundedBg(0x4D0B1426.toInt(), dp(20))
        }

        val title = textView("逐小时预报", 13f, 0xCCFFFFFF.toInt())
        panel.addView(title)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val hRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hourlyRow = hRow
        scroll.addView(hRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })

        val title2 = textView("未来 5 天", 13f, 0xCCFFFFFF.toInt())
        panel.addView(title2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })

        dailyCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(dailyCol, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        return panel
    }

    private fun renderHourly(info: WeatherFetcher.WeatherInfo) {
        val row = hourlyRow ?: return
        row.removeAllViews()
        info.hourly.take(12).forEach { p ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }
            item.addView(textView(p.time, 11f, 0xCCFFFFFF.toInt(), gravity = Gravity.CENTER))
            item.addView(textView(weatherFetcher.codeToIcon(p.weatherCode), 16f, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            item.addView(textView("${p.temperature.toInt()}°", 13f, bold = true, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            if (p.precipProb >= 30) {
                item.addView(textView("${p.precipProb}%", 9f, 0xFF9AD6FF.toInt(), gravity = Gravity.CENTER),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
            }
            row.addView(item)
        }
    }

    private fun renderDaily(info: WeatherFetcher.WeatherInfo) {
        val col = dailyCol ?: return
        col.removeAllViews()
        val weekdayFmt = SimpleDateFormat("EEEE", Locale.CHINA)
        info.daily.forEachIndexed { index, d ->
            val label = if (index == 0) "今天" else weekdayName(weekdayFmt, d.date, index)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val day = textView(label, 13f, bold = index == 0)
            row.addView(day, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))

            val icon = textView(weatherFetcher.codeToIcon(d.weatherCode), 14f, gravity = Gravity.CENTER)
            row.addView(icon, LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT))

            val prob = textView(if (d.precipProb >= 30) "${d.precipProb}%" else "", 12f, 0xFF9AD6FF.toInt())
            row.addView(prob, LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT))

            val temps = textView("${d.minTemp.toInt()}° / ${d.maxTemp.toInt()}°", 13f, bold = index == 0, gravity = Gravity.END)
            row.addView(temps, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.gravity = Gravity.CENTER_VERTICAL
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
    }

    private fun weekdayName(fmt: SimpleDateFormat, dateStr: String, index: Int): String {
        return runCatching {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr) ?: return "周${index + 1}"
            fmt.format(d)
        }.getOrDefault("周${index + 1}")
    }

    // ===================== 数据渲染 =====================

    private fun render(info: WeatherFetcher.WeatherInfo) {
        tvCity?.text = info.city
        tvTemp?.text = "${info.temperature.toInt()}°"
        tvDesc?.text = "${info.icon}  ${info.description}"
        val windDir = weatherFetcher.windDirectionText(info.windDirection)
        tvMeta?.text = "体感 ${info.apparentTemperature.toInt()}°   ·   湿度 ${info.humidity}%   ·   ${windDir}风 ${windLevel(info.windSpeed)}级"
        tvStatus?.text = if (info.severe) "检测到 ${info.alerts.size} 项天气预警" else "天气正常 · 无预警"
        tvStatus?.setTextColor(if (info.severe) 0xFFFFD0B0.toInt() else 0x99FFFFFF.toInt())

        // 预警横幅
        val banner = alertBanner
        val atv = alertText
        if (info.severe) {
            banner?.visibility = View.VISIBLE
            atv?.text = info.alerts.joinToString("；") { "${it.title}：${it.message}" }
        } else {
            banner?.visibility = View.GONE
        }

        // 特效背景
        surface.effect = WeatherSurfaceView.effectFor(info.weatherCode)
        surface.isDay = info.isDay == 1

        renderHourly(info)
        renderDaily(info)
        updateClock()
    }

    private fun windLevel(speed: Float): Int {
        var level = 0
        val thresholds = floatArrayOf(0.3f, 1.6f, 3.4f, 5.5f, 8f, 10.8f, 13.9f, 17.2f, 20.8f, 24.5f, 28.5f, 32.7f)
        for (t in thresholds) if (speed >= t) level++ else break
        return level
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        val dateFmt = SimpleDateFormat("M月d日 EEEE HH:mm", Locale.CHINA)
        tvDate?.text = dateFmt.format(c.time)
    }

    // ===================== 语音播报（复用 WeatherVoice） =====================

    private fun replayVoice() {
        val info = lastInfo ?: run {
            NuiToast.show(this, "天气数据尚未获取")
            return
        }
        voice.speakSummary(info)
    }

    // ===================== 定位 / 生命周期 =====================

    private fun loadLocationAndFetch() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            applyLastLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
        }
        // 无权限时也先按默认城市（珠海）拉一次
        weatherFetcher.fetch(force = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            applyLastLocation()
        }
    }

    private fun applyLastLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val location = runCatching {
            lm.getProviders(true).asSequence()
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (location != null) {
            val lat = location.latitude
            val lon = location.longitude
            val city = weatherFetcher.nearestCity(lat, lon)
            weatherFetcher.setLocation(lat, lon, city)
            weatherFetcher.fetch(force = true)
        }
    }

    override fun onResume() {
        super.onResume()
        weatherFetcher.start()
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        weatherFetcher.stop()
        handler.removeCallbacks(clockRunnable)
        surface.pauseAnimation()
    }

    override fun onDestroy() {
        weatherFetcher.stop()
        handler.removeCallbacks(clockRunnable)
        if (::voice.isInitialized) voice.shutdown()
        super.onDestroy()
    }
}
