package com.nui.launcher

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.nui.launcher.databinding.ActivityMainBinding
import com.nui.launcher.map.MapHost
import com.nui.launcher.weather.WeatherFetcher
import com.nui.launcher.weather.WeatherActivity
import com.nui.launcher.weather.WeatherSurfaceView
import com.nui.launcher.weather.WeatherVoice
import kotlin.concurrent.thread
import com.nui.launcher.map.MapPickerDialog
import com.nui.launcher.map.MapSources
import com.nui.launcher.music.MusicHost
import com.nui.launcher.nav.NavHost
import com.nui.launcher.UiTheme

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapHost: MapHost
    private lateinit var navHost: NavHost
    private lateinit var musicHost: MusicHost
    private lateinit var wallpaper: WallpaperController
    private lateinit var weatherFetcher: WeatherFetcher
    private var weatherText: android.widget.TextView? = null
    /** 桌面全屏透明天气动画层（叠加在壁纸/界面上方，跟随实时天气） */
    private lateinit var weatherLayer: WeatherSurfaceView
    /** 桌面天气语音播报（首次获取 + 重大天气突发） */
    private lateinit var weatherVoice: WeatherVoice

    /** 当前打开的桌面设置面板（选壁纸返回后刷新状态） */
    private var settingsDialog: com.nui.launcher.settings.SettingsDialog? = null

    /** 桌面天气动画每次展示时长（毫秒），展示结束后淡出隐藏 */
    private val weatherLayerShowMs = 20_000L
    private val mapSources by lazy { MapSources.build(this) }

    // 高德地图昼夜模式广播接收器（FOLLOW_MAP 模式下同步桌面深浅外观）
    private val amapDayNightReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val keyType = intent?.getIntExtra("KEY_TYPE", -1) ?: return
            if (keyType != 10019) return
            val state = intent.getIntExtra("EXTRA_STATE", -1)
            // 兼容不同版本高德的昼夜模式值：文档 37=白天/38=夜晚，实测部分版本 38=白天/40=夜晚
            // 统一判断：偶数为夜晚，奇数为白天（37/39 奇=白天，38/40 偶=夜晚）
            val isDark = state % 2 == 0
            if (UiTheme.mode(this@MainActivity) == UiTheme.Mode.FOLLOW_MAP &&
                UiTheme.mapDark(this@MainActivity) != isDark
            ) {
                UiTheme.setMapDark(this@MainActivity, isDark)
                refreshForThemeChange()
            }
        }
    }

    // 红绿灯倒计时监控（测试版）：红灯倒计时 <=3 秒时语音提醒
    private lateinit var trafficLightMonitor: com.nui.launcher.nav.TrafficLightMonitor

    // Page0 (desktop) 里的 view 引用
    private var desktopMapPanel: MaterialCardView? = null
    private var desktopMapContainer: android.widget.FrameLayout? = null
    private var desktopRightPanel: LinearLayout? = null
    private var desktopBtnSwitchMap: ImageButton? = null
    private var desktopMusicContainer: android.widget.FrameLayout? = null
    private var desktopBtnNavHome: View? = null
    private var desktopBtnNavCompany: View? = null
    private var desktopBtnNavFavorite: View? = null
    private var page0Ready = false
    private var appGridLoaded = false
    private var appGridView: androidx.recyclerview.widget.RecyclerView? = null
    private var appListAdapter: AppListAdapter? = null
    /** 是否从桌面启动了外部 app——按 home 回来时恢复到启动前的 page */
    private var launchedExternalApp = false
    /** 启动外部 app 前所在的 page */
    private var pageBeforeLaunch = 0

    companion object {
        /** 天气全屏动画是否已展示过（进程级静态变量，Activity 重建不重置） */
        private var weatherLayerShown = false
        /** 上次天气预警 key 集合（用于检测新出现的重大天气变化） */
        private var lastAlertKeys = emptySet<String>()
    }

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 2
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val v = when (viewType) {
                0 -> inflater.inflate(R.layout.page_desktop, parent, false)
                else -> inflater.inflate(R.layout.page_app_grid, parent, false)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                0 -> bindDesktop(holder.itemView)
                1 -> bindAppGrid(holder.itemView)
            }
        }
    }

    private fun bindDesktop(v: View) {
        desktopMapPanel = v.findViewById(R.id.mapPanel)
        desktopMapPanel?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncRightPanel() }
        desktopMapContainer = v.findViewById(R.id.mapContainer)
        desktopRightPanel = v.findViewById(R.id.rightPanel)
        desktopBtnSwitchMap = v.findViewById(R.id.btnSwitchMap)
        desktopMusicContainer = v.findViewById(R.id.musicContainer)
        desktopBtnNavHome = v.findViewById(R.id.btnNavHome)
        desktopBtnNavCompany = v.findViewById(R.id.btnNavCompany)
        desktopBtnNavFavorite = v.findViewById(R.id.btnNavFavorite)
        weatherText = v.findViewById(R.id.weatherText)
        weatherText?.setOnClickListener { startActivity(Intent(this, WeatherActivity::class.java)) }
        if (!page0Ready) {
            page0Ready = true
            v.post {
                setupMap(); setupNav(); setupMusic(); setupWallpaper(); syncRightPanel()
                applyDockStyle()
                applyTheme()
            }
        }
    }

    private fun bindAppGrid(v: View) {
        appGridView = v.findViewById(R.id.appGridMain)
        loadAppGrid(v.findViewById(R.id.appGridMain))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemDock()

        // 临时测试：监听高德昼夜模式广播
        registerReceiver(amapDayNightReceiver, android.content.IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"))

        // 红绿灯倒计时监控（测试版）
        trafficLightMonitor = com.nui.launcher.nav.TrafficLightMonitor(this)
        trafficLightMonitor.start()

        binding.viewPager.adapter = PagerAdapter()
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                if (::mapHost.isInitialized) {
                    if (position == 0) mapHost.showFloat() else mapHost.closeFloat()
                }
                syncWeatherLayer(position)
            }
        })

        setupDock()
        applyDockStyle()
        setupPageIndicator()

        // 桌面全屏透明天气动画层 + 语音播报
        setupWeatherLayer()
        weatherVoice = WeatherVoice(this)

        // 天气：初始化并设置回调，获取到天气后更新桌面天气文字/动画层/语音播报
        weatherFetcher = WeatherFetcher(this)
        weatherFetcher.onWeatherReady = { info ->
            weatherText?.text = "${info.city}  ${info.icon}  ${info.temperature.toInt()}°  ${info.description}"
            weatherText?.visibility = android.view.View.VISIBLE
            if (::weatherLayer.isInitialized) {
                weatherLayer.effect = WeatherSurfaceView.effectFor(info.weatherCode)
                weatherLayer.isDay = info.isDay == 1
                // 显示全屏动画的条件：首次获取天气 或 有新出现的重大天气预警
                val currentAlertKeys = info.alerts.map { it.key }.toSet()
                val hasNewAlert = currentAlertKeys.any { it !in lastAlertKeys }
                if (!weatherLayerShown || hasNewAlert) {
                    weatherLayerShown = true
                    lastAlertKeys = currentAlertKeys
                    if (hasNewAlert) {
                        android.util.Log.d("NUI.Weather", "新重大天气预警 ${currentAlertKeys}，显示全屏动画")
                    } else {
                        android.util.Log.d("NUI.Weather", "首次获取天气，显示全屏动画")
                    }
                    showWeatherLayerBriefly()
                } else {
                    android.util.Log.d("NUI.Weather", "无新预警，跳过全屏动画")
                }
            }
            if (::weatherVoice.isInitialized) deliverFirstWeather(info)
        }
        // 尝试获取当前位置，获取到后更新天气查询位置
        tryLoadLocation()
    }

    /** 首次启动的高德自动启动返回完成前，先暂存天气播报，返回桌面后再播（避免与高德前台重叠） */
    private var pendingFirstWeather: WeatherFetcher.WeatherInfo? = null

    /** 天气首次播报入口：高德自动返回未完成则暂存，否则立即播报 */
    private fun deliverFirstWeather(info: WeatherFetcher.WeatherInfo) {
        if (::mapHost.isInitialized && mapHost.isAutoReturnPending) {
            android.util.Log.d("WeatherVoice", "高德返回中，暂存天气播报 ${info.city}")
            pendingFirstWeather = info
        } else {
            android.util.Log.d("WeatherVoice", "直接播报天气 ${info.city}")
            weatherVoice.onWeather(info)
        }
    }

    /** 高德自动返回桌面完成：播报暂存的天气 */
    private fun flushPendingWeatherVoice() {
        android.util.Log.d("WeatherVoice", "高德已返回桌面，flush 暂存天气")
        pendingFirstWeather?.let {
            pendingFirstWeather = null
            if (::weatherVoice.isInitialized) {
                android.util.Log.d("WeatherVoice", "开始播报暂存天气 ${it.city}")
                weatherVoice.onWeather(it)
            }
        }
    }

    /** 桌面天气动画层：透明叠加在最上层，触摸穿透不挡操作；默认隐藏，仅在天气刷新时短暂展示后淡出 */
    private fun setupWeatherLayer() {
        weatherLayer = WeatherSurfaceView(this).apply {
            transparent = true
            effect = "clear"
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            isDay = h in 6..18
            visibility = View.GONE
        }
        binding.root.addView(weatherLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun syncWeatherLayer(position: Int) {
        if (!::weatherLayer.isInitialized) return
        if (position != 0) {
            // 切到应用列表页：取消展示任务并隐藏动画
            weatherLayer.removeCallbacks(weatherLayerFadeRunnable)
            weatherLayer.animate().cancel()
            weatherLayer.visibility = View.GONE
            weatherLayer.pauseAnimation()
        }
        // 切回桌面页保持当前状态（由 showWeatherLayerBriefly 统一管理）
    }

    /** 天气动画短暂展示：显示一段时间后淡出隐藏，不常驻 */
    private fun showWeatherLayerBriefly() {
        if (!::weatherLayer.isInitialized) return
        if (binding.viewPager.currentItem != 0) return
        weatherLayer.removeCallbacks(weatherLayerFadeRunnable)
        weatherLayer.animate().cancel()
        weatherLayer.alpha = 1f
        weatherLayer.visibility = View.VISIBLE
        weatherLayer.postDelayed(weatherLayerFadeRunnable, weatherLayerShowMs)
    }

    private val weatherLayerFadeRunnable = Runnable {
        if (!::weatherLayer.isInitialized) return@Runnable
        weatherLayer.animate().alpha(0f).setDuration(1500).withEndAction {
            if (::weatherLayer.isInitialized) {
                weatherLayer.visibility = View.GONE
                weatherLayer.pauseAnimation()
            }
        }.start()
    }

    /** 检查定位权限并获取最后已知位置，传给天气模块 */
    private fun tryLoadLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
            return
        }
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val location = runCatching {
            lm.getProviders(true).asSequence()
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (location != null) {
            val lat = location.latitude
            val lon = location.longitude
            // 用内置城市经纬度匹配表获取城市名（无需网络逆地理编码）
            val city = weatherFetcher.nearestCity(lat, lon)
            weatherFetcher.setLocation(lat, lon, city)
            weatherFetcher.fetch(force = true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            tryLoadLocation()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (com.nui.launcher.settings.KeyMapExecutor.handle(
                    this, event.keyCode,
                    if (::musicHost.isInitialized) musicHost else null,
                    if (::navHost.isInitialized) navHost else null,
                )) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        wallpaper.onActivityResult(requestCode, resultCode, data)
        // 从图库选壁纸返回后，刷新设置面板中壁纸的状态文字
        if (requestCode == com.nui.launcher.WallpaperController.REQ_PICK) {
            settingsDialog?.refreshWallpaper()
        }
    }

    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        // 启动的不是自己（外部 app），记录标志位和当前 page，按 home 回来时恢复
        if (intent?.component?.packageName != packageName) {
            launchedExternalApp = true
            pageBeforeLaunch = binding.viewPager.currentItem
            intent?.component?.packageName?.let { RecentApps.noteLaunch(this, it) }
            android.util.Log.d("NUI.Main", "startActivity external: ${intent?.component?.packageName} page=$pageBeforeLaunch")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 地图（高德）自动返回：只保持当前 page，不切 page0/page1，也不做外部返回恢复
        if (intent.getBooleanExtra(com.nui.launcher.map.MapHost.EXTRA_AUTO_BACK, false)) {
            launchedExternalApp = false
            android.util.Log.d("NUI.Main", "onNewIntent: EXTRA_AUTO_BACK, keep page ${binding.viewPager.currentItem}")
            return
        }
        if (launchedExternalApp) {
            // 从外部 app 按 home 回来：恢复到启动前的 page
            binding.viewPager.currentItem = pageBeforeLaunch
            launchedExternalApp = false
            android.util.Log.d("NUI.Main", "onNewIntent: back from external -> page $pageBeforeLaunch")
        } else {
            // 在桌面内按 home：在 page0（桌面）和 page1（应用列表）之间切换
            if (binding.viewPager.currentItem == 0) binding.viewPager.currentItem = 1
            else binding.viewPager.currentItem = 0
            android.util.Log.d("NUI.Main", "onNewIntent: home-in-desktop -> page ${binding.viewPager.currentItem}")
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("NUI.Main", "onResume page=${binding.viewPager.currentItem} launchedExternal=$launchedExternalApp")
        if (::weatherFetcher.isInitialized) weatherFetcher.start()
        applyTheme()
        // 设置页可能改了 Dock 形态/图标比例，返回时刷新
        applyDockStyle()
        renderDock()
        if (::mapHost.isInitialized) {
            mapHost.onResume()
            // 根据当前 page 决定悬浮地图显示状态
            if (binding.viewPager.currentItem == 0) mapHost.resumeFloat()
            else mapHost.closeFloat()
        }
        if (::musicHost.isInitialized) {
            musicHost.refresh()
            musicHost.setFloatAreaVisible(true)
        }
        // 设置页可能改了应用列表图标比例，返回时刷新
        appListAdapter?.notifyDataSetChanged()
    }

    /** 系统深浅模式切换（跟随系统模式）时同步重刷桌面配色 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val mode = UiTheme.mode(this)
        if (mode == UiTheme.Mode.SYSTEM) {
            // resources 可能尚未同步，用 newConfig 判断深浅
            applyTheme(UiTheme.isSystemDark(newConfig))
            if (::wallpaper.isInitialized) wallpaper.applyForAppearance(UiTheme.isSystemDark(newConfig))
            if (::musicHost.isInitialized) musicHost.refresh()
        }
        // FOLLOW_MAP 模式下不响应系统深浅变化，只响应高德昼夜模式广播
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("NUI.Main", "onPause")
        if (::weatherFetcher.isInitialized) weatherFetcher.stop()
        if (::weatherVoice.isInitialized) weatherVoice.stop()
        if (::mapHost.isInitialized) {
            mapHost.onPause()
            mapHost.cancelPendingShow()
        }
        if (::musicHost.isInitialized) musicHost.setFloatAreaVisible(false)
    }

    override fun onDestroy() {
        unregisterReceiver(amapDayNightReceiver)
        if (::trafficLightMonitor.isInitialized) trafficLightMonitor.stop()
        if (::weatherVoice.isInitialized) weatherVoice.shutdown()
        if (::weatherLayer.isInitialized) weatherLayer.removeCallbacks(weatherLayerFadeRunnable)
        if (::mapHost.isInitialized) mapHost.onDestroy()
        if (::musicHost.isInitialized) musicHost.onDestroy()
        super.onDestroy()
    }

    private fun setupDock() {
        binding.dockApps.setOnClickListener {
            if (binding.viewPager.currentItem == 0) binding.viewPager.currentItem = 1
            else binding.viewPager.currentItem = 0
        }
        binding.dockApps.setOnLongClickListener {
            if (binding.viewPager.currentItem == 0 && ::mapHost.isInitialized) {
                mapHost.toggleAdjust()
            }
            true
        }
        binding.dockBar.isClickable = true
        binding.dockBar.setOnLongClickListener {
            if (binding.viewPager.currentItem == 0 && ::mapHost.isInitialized) {
                mapHost.toggleAdjust()
            }
            true
        }
        renderDock()
    }

    /** 按深浅模式应用桌面配色（Dock / 右侧面板 / 地图卡片） */
    private fun applyTheme() = applyTheme(UiTheme.isDark(this))

    /** 设置面板切换外观时调用：立即刷新桌面主题/dock/音乐栏 */
    fun refreshForThemeChange() {
        applySystemDock()
        applyTheme()
        applyDockStyle()
        renderDock()
        if (::wallpaper.isInitialized) wallpaper.applyForAppearance(UiTheme.isDark(this))
        if (::musicHost.isInitialized) musicHost.refresh()
    }

    private fun applyTheme(dark: Boolean) {
        val p = UiTheme.palette(dark)
        val density = resources.displayMetrics.density

        // Dock 栏：半透明背景 + 时钟/图标色（圆角随 Dock 形态：贴边矩形 / 悬浮圆角）
        applyDockVisual(dark)
        binding.dockClock.setTextColor(p.textPrimary)
        // dockApps 用现代N标彩色图标，不做 tint 染色
        val itemBg = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF.toInt() else 0x33000000.toInt()),
            null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * density },
        )
        for (i in 0 until binding.dockItems.childCount) {
            val c = binding.dockItems.getChildAt(i)
            c.background = itemBg
            // 空槽加号：跟随深浅模式换色
            if (c.tag == "add") {
                (c as ImageButton).imageTintList = ColorStateList.valueOf(p.dockIconTint)
            }
        }

        // 地图卡片
        // 地图卡片背景全透明：高德浮窗可能比卡片窄，边缘透出壁纸而非黑色背景
        desktopMapPanel?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        desktopBtnSwitchMap?.imageTintList = ColorStateList.valueOf(p.textPrimary)

        // 右侧面板：背景 + 导航图标/文字 + 时钟
        desktopRightPanel?.let { rp ->
            rp.background = GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = 24 * density
                setStroke(1, p.divider)
            }
            rp.findViewById<ImageView>(R.id.navIconHome)?.setColorFilter(p.dockIconTint)
            rp.findViewById<ImageView>(R.id.navIconCompany)?.setColorFilter(p.dockIconTint)
            rp.findViewById<TextView>(R.id.navLabelHome)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.navLabelCompany)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.clockTime)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.clockDate)?.setTextColor(p.textSecondary)
            rp.findViewById<TextView>(R.id.weatherText)?.setTextColor(p.textSecondary)
            rp.findViewById<MaterialCardView>(R.id.musicPanel)?.setCardBackgroundColor(p.mapBg)
        }
    }

    /** Dock 栏背景：颜色随深浅，圆角随形态（贴边矩形 / 悬浮圆角） */
    private fun applyDockVisual(dark: Boolean = UiTheme.isDark(this)) {
        val p = UiTheme.palette(dark)
        val dp = resources.displayMetrics.density
        val edge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        binding.dockBar.background = GradientDrawable().apply {
            setColor(p.dockBg)
            cornerRadius = if (edge) 12 * dp else 28 * dp
            setStroke(1, p.divider)
        }
    }

    /** 按 Dock 形态（贴边矩形 / 圆角悬浮）调整 dock 位置边距 + 宽度 + 地图/应用网格让位 */
    private fun applyDockStyle() {
        val dp = resources.displayMetrics.density
        val edge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        // Dock 宽度固定为 96dp，不随图标比例变化；图标大小调整时只改变图标尺寸，在栏内居中显示
        val dockW = 96f
        val barLp = binding.dockBar.layoutParams as FrameLayout.LayoutParams
        barLp.width = (dockW * dp).toInt()
        if (edge) {
            // 贴边：上下+左边都贴边，矩形贯穿全高（CarPlay 风格）
            barLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            barLp.leftMargin = 0
            barLp.topMargin = 0
            barLp.bottomMargin = 0
            barLp.gravity = android.view.Gravity.START
        } else {
            // 悬浮：圆角竖条，高度与右侧音乐栏一致（match_parent + 上下 8dp margin），垂直居中
            // 注意：不能用 WRAP_CONTENT，因为 dockItems 是 0dp+weight=1，会与父容器 WRAP_CONTENT 形成循环依赖，
            // 导致 dockItems.height 永远为 0，renderDock 无法渲染图标。
            barLp.height = resources.displayMetrics.heightPixels - (16 * dp).toInt()
            barLp.leftMargin = (8 * dp).toInt()
            barLp.topMargin = (8 * dp).toInt()
            barLp.bottomMargin = (8 * dp).toInt()
            barLp.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        binding.dockBar.layoutParams = barLp
        applyDockVisual()

        // 地图卡片：dock 右侧留出 12dp 统一间距（参考氢桌面比例）
        desktopMapPanel?.let { mp ->
            val lp = mp.layoutParams as FrameLayout.LayoutParams
            val left = if (edge) dockW + 12 else 8 + dockW + 12
            if (lp.leftMargin != (left * dp).toInt()) {
                lp.leftMargin = (left * dp).toInt()
                mp.layoutParams = lp
                // dock 形态切换导致地图位置变化，刷新高德浮窗几何（否则边缘露出卡片背景）
                mp.post { mapHost?.refreshFloat() }
            }
        }
        // 应用网格：dock 右侧留出 12dp 统一间距（参考氢桌面比例）
        appGridView?.let { g ->
            val leftPad = if (edge) dockW + 12 else 8 + dockW + 12
            g.setPadding((leftPad * dp.toFloat()).toInt(), g.paddingTop, g.paddingEnd, g.paddingBottom)
        }
    }

    /** 渲染 dock 槽位：固定 4 个槽位（3 固定 + 1 可自定义），在 dockItems 内垂直居中。
     *  空槽显示加号，已填显示应用图标（图标大小随 [UiTheme.dockIconScale]）。 */
    private fun renderDock() {
        binding.dockItems.removeAllViews()
        val dp = resources.displayMetrics.density
        val scale = UiTheme.dockIconScale(this)
        val size = (UiTheme.DEFAULT_DOCK_ICON_DP * dp * scale).toInt()
        val gap = (2 * dp).toInt()   // 图标间距 2dp
        // 固定 4 个槽位：前 3 个固定（地图/音乐/最近）+ 1 个用户可自定义
        val slotCount = DockSlots.FIXED_COUNT + DockConfig.SLOT_COUNT
        val dark = UiTheme.isDark(this)
        val p = UiTheme.palette(this)
        // 注意：每个按钮必须使用独立的 RippleDrawable 实例，共享同一实例会导致点击动画跑到错误的按钮上
        val rippleColor = if (dark) 0x33FFFFFF.toInt() else 0x33000000.toInt()
        val addIcon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_dock_add)
        // 绑定的地图/音乐包名（从 prefs 直接读取，首次渲染即可用，不需要等应用启动）
        val mapPkg = com.nui.launcher.map.MapHost.currentMapPackage(this)
        val musicPkg = getSharedPreferences("nui_music", MODE_PRIVATE).getString("music_app", null)
        val apps = DockSlots.load(this, mapPkg, musicPkg)
        for (i in 0 until slotCount) {
            val app = apps.getOrNull(i)
            val isFixed = i < DockSlots.FIXED_COUNT
            // 每个按钮独立的 RippleDrawable 实例（共享会导致点击动画错位）
            val itemBg = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * dp },
            )
            val btn = android.widget.ImageButton(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                background = itemBg
                if (app != null) {
                    setImageDrawable(app.icon)
                    // 原车桌面图标跟随深浅模式 tint（浅色背景下可见）
                    if (app.packageName == StockHome.PKG_STOCK_HOME) {
                        imageTintList = ColorStateList.valueOf(p.dockIconTint)
                    }
                    setOnClickListener {
                        RecentApps.noteLaunch(this@MainActivity, app.packageName)
                        if (app.packageName == StockHome.PKG_STOCK_HOME) {
                            val ok = StockHome.launch(this@MainActivity)
                            if (!ok) NuiToast.show(this@MainActivity, "未找到其他桌面", Toast.LENGTH_SHORT)
                        } else {
                            runCatching { startActivity(app.launchIntent) }
                        }
                    }
                    // 固定槽位不可移除；用户配置槽位长按移除
                    if (!isFixed) {
                        setOnLongClickListener { confirmRemove(i - DockSlots.FIXED_COUNT, app); true }
                    }
                } else {
                    if (isFixed) {
                        // 固定槽位无应用时显示空（不显示加号，因为不可用户添加）
                        imageTintList = null
                        setImageDrawable(null)
                    } else {
                        setImageDrawable(addIcon)
                        // 加号颜色跟随深浅模式：深色下浅色 +，浅色下深色 +
                        imageTintList = ColorStateList.valueOf(p.dockIconTint)
                        tag = "add"
                        setOnClickListener { openPicker(i - DockSlots.FIXED_COUNT) }
                    }
                }
            }
            binding.dockItems.addView(
                btn,
                LinearLayout.LayoutParams(size, size).apply { topMargin = if (i == 0) 0 else gap },
            )
        }
        // dockApps 底部按钮固定大小 80x48dp（不跟随图标比例，给 dockItems 腾出更多空间）
        val appsLp = binding.dockApps.layoutParams
        val appBtnW = (80 * dp).toInt()
        val appBtnH = (48 * dp).toInt()
        if (appsLp.width != appBtnW || appsLp.height != appBtnH) {
            appsLp.width = appBtnW
            appsLp.height = appBtnH
            binding.dockApps.layoutParams = appsLp
        }
    }

    /** 点空槽加号：弹应用选择器填入指定槽位 */
    private fun openPicker(slot: Int) {
        if (::mapHost.isInitialized) mapHost.closeFloat()
        DockPickerDialog.show(
            context = this,
            exclude = DockConfig.filledPackages(this),
            onPick = { app ->
                DockConfig.setSlot(this, slot, app.packageName)
                renderDock()
                NuiToast.show(this, "已添加 ${app.label}", Toast.LENGTH_SHORT)
            },
            onDismiss = { if (::mapHost.isInitialized) mapHost.showFloat() },
        )
    }

    /** 长按已填槽：确认移除（变回加号即可重新添加） */
    private fun confirmRemove(slot: Int, app: AppModel): Boolean {
        if (::mapHost.isInitialized) mapHost.closeFloat()
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("从 Dock 移除")
            .setMessage("移除 ${app.label}?")
            .setPositiveButton("移除") { _, _ ->
                DockConfig.setSlot(this, slot, null)
                renderDock()
                NuiToast.show(this, "已移除", Toast.LENGTH_SHORT)
            }
            .setNegativeButton("取消", null)
            .create()
        d.setOnDismissListener { if (::mapHost.isInitialized) mapHost.showFloat() }
        d.show()
        return true
    }

    private fun setupPageIndicator() {
        val dots = arrayOfNulls<View>(2)
        for (i in 0..1) {
            val dot = View(this)
            val size = (8 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(size, size).apply {
                setMargins(if (i == 0) 0 else (12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }
            dot.layoutParams = lp
            dot.background = getDrawable(R.drawable.bg_dot_indicator)
            dot.alpha = if (i == 0) 1f else 0.35f
            dots[i] = dot
            binding.pageIndicator.addView(dot)
        }
        binding.pageIndicator.tag = dots
    }

    @Suppress("UNCHECKED_CAST")
    private fun updatePageIndicator(position: Int) {
        val dots = binding.pageIndicator.tag as Array<View>
        for (i in dots.indices) dots[i].alpha = if (i == position) 1f else 0.35f
    }

    private fun setupMap() {
        val mapPanel = desktopMapPanel ?: return
        val mapContainer = desktopMapContainer ?: return
        val rightPanel = desktopRightPanel ?: return
        val btnSwitchMap = desktopBtnSwitchMap ?: return

        mapHost = MapHost(this, mapContainer, mapPanel)
        mapHost.onGeometryChanged = { binding.root.post { syncRightPanel() } }
        mapHost.onFloatShown = { if (::musicHost.isInitialized) musicHost.bringLyricFloatToFront() }
        mapHost.onAutoReturnDone = { flushPendingWeatherVoice() }
        mapHost.onPickMap = {
            mapHost.closeFloat()
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source, autoLaunch = true)
                    NuiToast.show(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT)
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
        mapHost.start(mapSources, autoLaunch = true)

        btnSwitchMap.setOnClickListener {
            mapHost.closeFloat()
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source, autoLaunch = true)
                    NuiToast.show(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT)
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
    }

    private fun setupNav() {
        navHost = NavHost(this, desktopBtnNavHome!!, desktopBtnNavCompany!!)
        navHost.onHideFloat = { mapHost.closeFloat() }
        navHost.onShowFloat = { mapHost.showFloat() }
        navHost.start()
        // 收藏夹按钮：打开高德地图收藏夹
        desktopBtnNavFavorite?.setOnClickListener {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("androidauto://openFavorite?sourceApplication=nui")
                )
                intent.setPackage("com.autonavi.amapauto")
                startActivity(intent)
            } catch (e: Exception) {
                NuiToast.show(this, "未找到高德地图", android.widget.Toast.LENGTH_SHORT)
            }
        }
    }

    private fun setupMusic() {
        musicHost = MusicHost(this, desktopMusicContainer!!)
        musicHost.onHideFloat = { mapHost.closeFloat() }
        musicHost.onShowFloat = { mapHost.showFloat() }
        musicHost.floatBoundsProvider = {
            if (::mapHost.isInitialized) mapHost.floatBounds() else null
        }
        musicHost.start()
    }

    private fun setupWallpaper() {
        wallpaper = WallpaperController(this, binding.root)
        wallpaper.applyOnStart()
        wallpaper.onHideFloat = { mapHost.closeFloat() }
        wallpaper.onShowFloat = { mapHost.showFloat() }
    }

    private fun loadAppGrid(grid: RecyclerView) {
        if (appGridLoaded) return
        appGridLoaded = true
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.setHasFixedSize(true)
        grid.itemAnimator = null

        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(main, 0)
            val fallbackIcon = pm.defaultActivityIcon
            val apps = resolved.map { ri ->
                val pkg = ri.activityInfo.packageName
                AppModel(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = IconUtils.getIconWithFallback(pm, pkg, fallbackIcon),
                    launchIntent = pm.getLaunchIntentForPackage(pkg)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?: Intent(Intent.ACTION_MAIN).setPackage(pkg),
                )
            }.sortedBy { it.label.lowercase() }
            val settingsEntry = AppModel(
                label = getString(R.string.desktop_settings),
                packageName = packageName,
                icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_dock_settings)!!,
                launchIntent = Intent(),
                onClick = {
                    val dlg = com.nui.launcher.settings.SettingsDialog(this)
                    dlg.wallpaperHasCustom = { slot -> wallpaper.hasCustom(slot) }
                    dlg.onWallpaperPick = { slot -> wallpaper.showMenu(slot) }
                    // 设置面板关闭时刷新桌面主题/dock/音乐栏/应用列表（Dialog 关闭不触发 onResume）
                    dlg.setOnDismissListener {
                        settingsDialog = null
                        applyTheme()
                        applyDockStyle()
                        renderDock()
                        appListAdapter?.notifyDataSetChanged()
                        if (::musicHost.isInitialized) musicHost.refresh()
                    }
                    settingsDialog = dlg
                    dlg.show()
                },
            )
            runOnUiThread {
                appListAdapter = AppListAdapter(
                    context = this,
                    apps = listOf(settingsEntry) + apps,
                    onClick = { app ->
                        if (app.onClick != null) app.onClick.invoke()
                        else startActivity(app.launchIntent)
                    },
                )
                grid.adapter = appListAdapter
            }
        }.start()
    }

    /** 右侧面板（音乐栏）宽度随地图右缘动态变化：地图右移 → 音乐栏变窄，太窄则整个消失。
     *  音乐栏右缘固定贴右，左缘 = 地图右缘 + gap；地图可一直右移到音乐栏消失。 */
    private fun syncRightPanel() {
        val rp = desktopRightPanel ?: return
        val mp = desktopMapPanel ?: return
        val density = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels
        val rightMargin = (16 * density).toInt()
        val gap = (12 * density).toInt()
        val minPanel = (180 * density).toInt()          // 音乐栏最小宽度，低于则整体消失
        val availableRight = sw - rightMargin
        // 地图右缘（相对 Page0，全屏坐标系）
        val mlp = mp.layoutParams as FrameLayout.LayoutParams
        val mapRight = mlp.leftMargin + mp.width
        val panelW = availableRight - gap - mapRight
        if (panelW < minPanel) {
            // 音乐栏太窄：隐藏，地图自动扩展到右缘（不留空）
            rp.visibility = android.view.View.GONE
            val targetW = availableRight - mlp.leftMargin
            if (mlp.width < targetW - 2) {
                mlp.width = targetW
                mp.layoutParams = mlp
            }
            // 音乐栏消失后刷新高德浮窗几何：布局可能尚未完成，post 确保取到最新 width
            // （否则浮窗比卡片窄，右边缘露出卡片深色背景）
            mp.post { mapHost?.refreshFloat() }
        } else {
            rp.visibility = android.view.View.VISIBLE
            val lp = rp.layoutParams
            if (lp.width != panelW) {
                lp.width = panelW
                rp.layoutParams = lp
            }
        }
        // 地图右缘上限：音乐栏消失后地图可铺到屏幕右缘-16dp
        if (::mapHost.isInitialized) mapHost.setRightLimit(availableRight)
    }

    /**
     * 系统栏显隐：顶部状态栏与底部系统 Dock（导航栏）两个独立开关组合生效。
     * - 状态栏开：显示顶部状态栏，内容顶部自动让位；关：隐藏，内容延伸铺满顶部。
     * - 系统 Dock 开：显示底部导航栏/Dock（如车机空调快捷控制），内容底部自动让位；关：隐藏，内容延伸铺满底部。
     * - 两者默认均关闭 = 沉浸全屏。
     */
    private fun applySystemDock() {
        val showStatus = UiTheme.showStatusBar(this)
        val showDock = UiTheme.showSystemDock(this)

        if (showStatus) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        // 仅两者都隐藏（沉浸）时让窗口铺满整个屏幕；任一系统栏显示时取消，由系统 insets 自动让位
        if (!showStatus && !showDock) {
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
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
        window.decorView.systemUiVisibility = vis

        // 系统栏显示时显式让位：按状态栏/导航栏 inset 给根布局加 padding，
        // 使左侧 dock、页面、天气层等各窗口动态缩小上移，不遮挡系统 Dock。
        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (UiTheme.showStatusBar(this)) insets.getSystemWindowInsetTop() else 0
            val bottom = if (UiTheme.showSystemDock(this)) insets.getSystemWindowInsetBottom() else 0
            v.setPadding(0, top, 0, bottom)
            insets
        }
        binding.root.requestApplyInsets()
    }

    /** 重新获得焦点时重设系统栏标志（部分设备焦点变化后会恢复系统栏） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !UiTheme.showStatusBar(this) && !UiTheme.showSystemDock(this)) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (hasFocus) binding.root.requestApplyInsets()
    }
}
