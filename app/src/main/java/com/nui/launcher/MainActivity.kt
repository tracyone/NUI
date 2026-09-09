package com.nui.launcher

import android.content.Context
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

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiTheme.overrideUiDpi(base))
    }

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
            // 只处理文档标准昼夜模式值：37=白天，38=夜晚
            // 10019 广播还携带其他类型信息（state=0/3/15/20/40/49/50/2001/3025 等），全部忽略保持不动
            val isDark = when (state) {
                37 -> false   // 白天
                38 -> true    // 夜晚
                else -> return
            }
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
    private var desktopNavInfoOverlay: View? = null
    private var navInfoHost: com.nui.launcher.nav.NavInfoHost? = null
    private var page0Ready = false
    private var appGridLoaded = false
    /** 从系统卸载页返回后需重载应用网格 */
    private var pendingReloadOnResume = false
    private var appGridView: androidx.viewpager2.widget.ViewPager2? = null
    /** 分页应用网格：每页一个 6 列 RecyclerView，各页独立的 AppListAdapter（刷新时逐页 notify） */
    private val appGridPageAdapters = mutableMapOf<Int, AppListAdapter>()
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
        desktopNavInfoOverlay = v.findViewById(R.id.navInfoOverlay)
        weatherText = v.findViewById(R.id.weatherText)
        weatherText?.setOnClickListener { startActivity(Intent(this, WeatherActivity::class.java)) }
        if (!page0Ready) {
            page0Ready = true
            v.post {
                setupMap(); setupNav(); setupMusic(); setupWallpaper(); syncRightPanel()
                applyDockStyle()
                applyTheme()
                // mapHost 就绪后按当前开关重算悬浮地图底部边界（onCreate/onResume 时
                // mapHost 尚未初始化，applySystemDock 会跳过 setBottomLimit）
                applySystemDock()
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
                // 手势分流：page0（桌面）保留外层横滑切到应用列表；
                // page1（应用列表）禁用外层横滑，左右滑动全部交给内层应用网格翻页
                binding.viewPager.isUserInputEnabled = position == 0
                if (::mapHost.isInitialized) {
                    // 回桌面页时重新取几何并刷新浮窗（设置页切换系统 Dock 开关时地图卡片离屏，
                    // 几何可能未更新；回来时强制 primeCache + showFloat 用最新几何下发）
                    if (position == 0) mapHost.refreshFloat() else mapHost.closeFloat()
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
            // 导航中天气区域让位给导航卡：天气文字不显示，导航结束由 NavInfoHost 恢复
            if (navInfoHost?.isNavActive() != true) {
                weatherText?.visibility = android.view.View.VISIBLE
            }
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
            // 首播统一由 scheduleFirstWeatherVoice 的 30s 检查处理（有天气播完整版/无天气播问候版）；
            // 30s 检查执行后的每次刷新，交给 WeatherVoice 内部去重（仅新预警播报，不重复首播）
            if (::weatherVoice.isInitialized && firstWeatherCheckDone) {
                weatherVoice.onWeather(info)
            }
        }
        // 尝试获取当前位置，获取到后更新天气查询位置
        tryLoadLocation()
        // 首次启动天气播报统一调度：30 秒后检查是否有天气，有则播完整版（含天气），无则播问候版
        scheduleFirstWeatherVoice()
    }

    /** 首次启动天气播报延迟（ms）：等高德自动启动返回桌面（约10s）并留出操作时间，30 秒后再播 */
    private val FIRST_WEATHER_VOICE_DELAY_MS = 30_000L

    /** 首次启动的高德自动启动返回完成前，先暂存天气播报，返回桌面后再播（避免与高德前台重叠） */
    private var pendingFirstWeather: WeatherFetcher.WeatherInfo? = null

    /** 30 秒首播检查是否已执行（true 后每次天气刷新交给 WeatherVoice 播新预警） */
    private var firstWeatherScheduled = false

    /** 30 秒首播检查已执行标记（区别于 firstWeatherScheduled 的"已调度"） */
    private var firstWeatherCheckDone = false

    /** 天气查询不到时，30s 检查若遇高德返回中，暂存"播问候版"标记，返回后 flush */
    private var pendingGreetingOnly = false

    /** 首次启动天气播报统一入口：30 秒后检查是否有天气数据。
     *  - 有天气：播完整版（问候+日期+天气，走 WeatherVoice.onWeather 首播逻辑）
     *  - 无天气（查询失败/未返回）：播问候+日期（speakGreetingOnly），天气部分跳过
     *  期间高德返回中则暂存，返回完成后 flush 播放（正常 10s 内返回，30s 后基本直接播）。 */
    private fun scheduleFirstWeatherVoice() {
        if (firstWeatherScheduled) return
        firstWeatherScheduled = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            firstWeatherCheckDone = true
            val info = weatherFetcher.current
            if (info != null) {
                if (::mapHost.isInitialized && mapHost.isAutoReturnPending) {
                    android.util.Log.d("WeatherVoice", "高德返回中，暂存天气播报 ${info.city}")
                    pendingFirstWeather = info
                } else {
                    android.util.Log.d("WeatherVoice", "延迟${FIRST_WEATHER_VOICE_DELAY_MS / 1000}s播报天气 ${info.city}")
                    weatherVoice.onWeather(info)
                }
            } else {
                if (::mapHost.isInitialized && mapHost.isAutoReturnPending) {
                    android.util.Log.d("WeatherVoice", "高德返回中，暂存问候播报")
                    pendingGreetingOnly = true
                } else {
                    android.util.Log.d("WeatherVoice", "延迟${FIRST_WEATHER_VOICE_DELAY_MS / 1000}s播报（无天气数据，仅问候）")
                    weatherVoice.speakGreetingOnly()
                }
            }
        }, FIRST_WEATHER_VOICE_DELAY_MS)
    }

    /** 高德自动返回桌面完成：播报暂存的天气 / 问候 */
    private fun flushPendingWeatherVoice() {
        android.util.Log.d("WeatherVoice", "高德已返回桌面，flush 暂存播报")
        pendingFirstWeather?.let {
            pendingFirstWeather = null
            if (::weatherVoice.isInitialized) {
                android.util.Log.d("WeatherVoice", "开始播报暂存天气 ${it.city}")
                weatherVoice.onWeather(it)
            }
        }
        if (pendingGreetingOnly) {
            pendingGreetingOnly = false
            if (::weatherVoice.isInitialized) {
                android.util.Log.d("WeatherVoice", "开始播报暂存问候")
                weatherVoice.speakGreetingOnly()
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
        // 设置页可能改了显示状态栏/系统Dock开关，返回时重新应用系统栏 flags 与悬浮地图底部边界
        applySystemDock()
        if (::weatherFetcher.isInitialized) weatherFetcher.start()
        applyTheme()
        // 回到前台时主动向高德查询导航状态，校准导航卡显示（防止被动广播错过）
        navInfoHost?.queryNavState()
        navInfoHost?.queryDayNight()
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
        // 设置页可能改了应用列表图标比例，返回时刷新（分页：逐页 notify）
        for (a in appGridPageAdapters.values) a.notifyDataSetChanged()
        // 从系统卸载页返回：重载应用网格（被卸载的应用消失）
        if (pendingReloadOnResume) {
            pendingReloadOnResume = false
            appGridLoaded = false
            val grid = appGridView
            if (grid != null) {
                grid.adapter = null
                appGridPageAdapters.clear()
                loadAppGrid(grid)
            }
        }
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
        navInfoHost?.stop()
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
        navInfoHost?.applyTheme()
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
            rp.findViewById<ImageView>(R.id.navIconFavorite)?.setColorFilter(p.dockIconTint)
            rp.findViewById<TextView>(R.id.navLabelHome)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.navLabelCompany)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.navLabelFavorite)?.setTextColor(p.textPrimary)
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
        // 导航信息显示：导航中右上角按钮区切换为导航卡（转向/距离/时间/道路/速度）
        navInfoHost = com.nui.launcher.nav.NavInfoHost(
            this,
            desktopNavInfoOverlay!!,
            listOf(desktopBtnNavHome!!, desktopBtnNavCompany!!, desktopBtnNavFavorite!!),
        )
        navInfoHost?.start()
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

    private fun loadAppGrid(grid: androidx.viewpager2.widget.ViewPager2) {
        if (appGridLoaded) return
        appGridLoaded = true

        // 每页行数：按屏幕可用高度估算（图标 72dp*scale + 名称标签约 24dp），至少 2 行
        val density = resources.displayMetrics.density
        val availH = (resources.displayMetrics.heightPixels / density) - 32f - 48f // 上下 padding
        val iconScale = UiTheme.appIconScale(this)
        val itemH = 72f * iconScale + 24f
        val rows = maxOf(2, (availH / itemH).toInt())
        val perPage = 6 * rows
        android.util.Log.d("NUI.AppGrid", "分页网格: rows=$rows perPage=$perPage availH=$availH")

        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(main, 0)
            val fallbackIcon = pm.defaultActivityIcon
            val hidden = HiddenApps.hiddenSet(this)
            val apps = resolved.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg in hidden) return@mapNotNull null
                AppModel(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    // icon 不在此处加载：AppListAdapter 按需加载（仅显示页加载，LruCache 缓存）
                    hasIcon = IconUtils.hasCustomIcon(pm, pkg, fallbackIcon),
                    launchIntent = pm.getLaunchIntentForPackage(pkg)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?: Intent(Intent.ACTION_MAIN).setPackage(pkg),
                )
            }.sortedWith(
                // 有自定义图标的应用优先（排前面的页），无图标（系统默认图标）靠后；组内按名称
                compareByDescending<AppModel> { it.hasIcon }.thenBy { it.label.lowercase() }
            )
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
                        // 隐藏/恢复应用可能已变化：重载应用网格（而非仅 notifyDataSetChanged，
                        // 否则恢复的应用不会重新出现）
                        appGridLoaded = false
                        val grid = appGridView
                        if (grid != null) {
                            grid.adapter = null
                            appGridPageAdapters.clear()
                            loadAppGrid(grid)
                        }
                        if (::musicHost.isInitialized) musicHost.refresh()
                    }
                    settingsDialog = dlg
                    dlg.show()
                },
            )
            val allApps = listOf(settingsEntry) + apps
            val pages = allApps.chunked(perPage)
            android.util.Log.d("NUI.AppGrid", "应用总数=${allApps.size} 页数=${pages.size}")
            runOnUiThread {
                grid.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    override fun getItemCount() = pages.size
                    override fun onCreateViewHolder(
                        parent: ViewGroup,
                        viewType: Int,
                    ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                        // 每页一个 6 列网格（不参与滚动，翻页由外层 ViewPager2 驱动）
                        val rv = androidx.recyclerview.widget.RecyclerView(parent.context)
                        rv.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        rv.layoutManager = GridLayoutManager(parent.context, 6)
                        rv.setHasFixedSize(true)
                        rv.itemAnimator = null
                        rv.isNestedScrollingEnabled = false
                        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(rv) {}
                    }
                    override fun onBindViewHolder(
                        holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                        position: Int,
                    ) {
                        val rv = holder.itemView as androidx.recyclerview.widget.RecyclerView
                        val pageAdapter = AppListAdapter(
                            context = this@MainActivity,
                            apps = pages[position],
                            onClick = { app ->
                                if (app.onClick != null) app.onClick.invoke()
                                else startActivity(app.launchIntent)
                            },
                            onLongClick = { app -> if (app.onClick == null) showAppMenu(app) },
                        )
                        appGridPageAdapters[position] = pageAdapter
                        rv.adapter = pageAdapter
                    }
                }
            }
        }.start()
    }

    /** 长按应用网格中的应用：卸载 / 隐藏（需确认）；设置入口本身不响应长按 */
    private fun showAppMenu(app: AppModel) {
        val items = arrayOf(
            getString(R.string.uninstall),
            getString(R.string.hide_app),
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmUninstall(app)
                    1 -> confirmHide(app)
                }
            }
            .show()
    }

    private fun confirmUninstall(app: AppModel) {
        // 系统应用不可卸载，直接提示，避免 ACTION_DELETE 无反应造成"卸载无效"
        val isSystem = runCatching {
            packageManager.getApplicationInfo(app.packageName, 0).flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
        if (isSystem) {
            NuiToast.show(this, getString(R.string.cannot_uninstall_system_app), Toast.LENGTH_SHORT)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.uninstall_confirm_title, app.label))
            .setMessage(R.string.uninstall_confirm_msg)
            .setPositiveButton(R.string.uninstall) { _, _ ->
                val uri = android.net.Uri.parse("package:${app.packageName}")
                val i = Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(i) }
                // 从系统卸载页返回后 onResume 重载网格，卸载掉的应用从列表消失
                pendingReloadOnResume = true
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun confirmHide(app: AppModel) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.hide_confirm_title, app.label))
            .setMessage(R.string.hide_confirm_msg)
            .setPositiveButton(R.string.hide_app) { _, _ ->
                HiddenApps.hide(this, app.packageName)
                NuiToast.show(this, getString(R.string.hide_done, app.label), Toast.LENGTH_SHORT)
                // 重新加载应用网格（隐藏项消失），保留"桌面设置"入口
                appGridLoaded = false
                val grid = appGridView
                if (grid != null) {
                    grid.adapter = null
                    appGridPageAdapters.clear()
                    loadAppGrid(grid)
                }
            }
            .setNegativeButton(R.string.back, null)
            .show()
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
        Log.d("NUI.SyncRight", "sw=$sw density=$density mapLeft=${mlp.leftMargin} mapW=${mp.width} mapRight=$mapRight availableRight=$availableRight gap=$gap panelW=$panelW minPanel=$minPanel geometryLoaded=${if (::mapHost.isInitialized) mapHost.geometryLoaded else false}")
        // 地图几何尚未加载完成时，不隐藏音乐栏、不扩展地图，避免首次启动把默认位置冲掉
        val geoReady = !::mapHost.isInitialized || mapHost.geometryLoaded
        if (panelW < minPanel && geoReady) {
            // 音乐栏太窄：隐藏，地图自动扩展到右缘（不留空）
            Log.d("NUI.SyncRight", "音乐栏宽度 $panelW < 最小 $minPanel，隐藏音乐栏，地图扩展到右缘")
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
            Log.d("NUI.SyncRight", "音乐栏可见，宽度=$panelW")
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

        // 悬浮地图底部限制：按当前配置主动计算并下发（不依赖 insets 回调时序），
        // 修复切换系统 Dock 显隐后高德浮窗底边不恢复/不避让的问题
        // 注意：用物理屏高度计算（displayMetrics 在非沉浸下已扣系统栏，再减导航栏会双重扣减）
        if (::mapHost.isInitialized) {
            if (showDock) {
                val sh = realScreenHeight()
                val navH = getNavBarHeight()
                mapHost.setBottomLimit(sh - navH - (8 * resources.displayMetrics.density).toInt())
            } else {
                mapHost.setBottomLimit(0)
            }
        }

        // 系统栏显示时显式让位：按状态栏/导航栏 inset 给根布局加 padding，
        // 使左侧 dock、页面、天气层等各窗口动态缩小上移，不遮挡系统 Dock。
        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (UiTheme.showStatusBar(this)) insets.getSystemWindowInsetTop() else 0
            val bottom = if (UiTheme.showSystemDock(this)) insets.getSystemWindowInsetBottom() else 0
            v.setPadding(0, top, 0, bottom)
            // 悬浮地图（高德浮窗）同步避让底部系统 Dock：上限=物理屏高-导航栏高-8dp；
            // Dock 隐藏时解除限制（可拖到屏幕最底部）
            if (::mapHost.isInitialized) {
                if (UiTheme.showSystemDock(this)) {
                    // 导航栏高度统一用 getNavBarHeight()（含 nui_debug_navbar_h 模拟值），
                    // 不用 inset：模拟器/无系统栏设备无真实 inset，用 inset 会把限制错误清掉
                    val sh = realScreenHeight()
                    mapHost.setBottomLimit(sh - getNavBarHeight() - (8 * resources.displayMetrics.density).toInt())
                } else {
                    mapHost.setBottomLimit(0)
                }
            }
            insets
        }
        binding.root.requestApplyInsets()
        // 系统 Dock 显隐变化后刷新高德浮窗几何（位置/边界可能已变）
        if (::mapHost.isInitialized) {
            mapHost.refreshFloat()
        }
    }

    /** 系统导航栏高度（px，含手势条场景尽量取系统上报值）；获取失败返回 0 */
    private fun getNavBarHeight(): Int {
        // [调试] 模拟系统导航栏高度（px）：模拟器/无系统栏设备上验证 Dock 边界逻辑用。
        // 用法: adb shell settings put global nui_debug_navbar_h 120 （0=关闭模拟）
        val sim = android.provider.Settings.Global.getInt(
            contentResolver, "nui_debug_navbar_h", 0)
        if (sim > 0) return sim
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /** 物理屏幕高度（px，含系统栏区域）。
     *  displayMetrics.heightPixels 在非沉浸窗口下会扣掉系统栏，用它算地图底部边界
     *  会造成"有/无系统 Docker 栏"时边界不一致（双重扣减），故统一用真实物理尺寸。 */
    private fun realScreenHeight(): Int {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(m)
        return m.heightPixels
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
