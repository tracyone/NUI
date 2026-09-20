package com.nui.launcher

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
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

    /**
     * 本实例已应用的地图昼夜状态（实例级去重）。
     * 动态注册的广播接收器在每个 Activity 实例都会触发；权限请求等流程可能在 standard task
     * 留下不可见的僵尸 MainActivity 实例。若用全局 SP 状态去重，僵尸实例先收到广播写入 SP 后，
     * 可见的桌面实例会误判"无需刷新"而不更新屏幕（表现为切高德外观 NUI 不跟随）。
     * 因此每个实例只和自己上一次应用的状态比较，独立刷新自己的 View。
     */
    private var appliedMapDark: Boolean? = null

    // 高德地图昼夜模式广播接收器（FOLLOW_MAP 模式下同步桌面深浅外观）
    private val amapDayNightReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val keyType = intent?.getIntExtra("KEY_TYPE", -1) ?: return
            if (keyType != 10019) return
            val state = intent.getIntExtra("EXTRA_STATE", -1)
            val mapDark = when (state) {
                37 -> false
                38 -> true
                else -> return
            }
            if (UiTheme.mode(this@MainActivity) != UiTheme.Mode.FOLLOW_MAP) return
            // 写入全局状态（供新启动实例/其他组件读取）
            UiTheme.setMapDark(this@MainActivity, mapDark)
            // 本实例 UI 与目标状态不一致才刷新（实例级判断，不受其他实例影响）
            if (appliedMapDark != mapDark) {
                appliedMapDark = mapDark
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
    // 负一屏（最左页）view 引用
    private var minusVideo: VideoWallpaperView? = null
    private var ivMinusWallpaper: ImageView? = null
    private var minusSong: TextView? = null
    private var minusArtist: TextView? = null
    private var minusCover: ImageView? = null
    private var minusBar: LinearLayout? = null
    private var minusBtnPlay: ImageView? = null
    private var minusRoot: View? = null
    private var minusBigClock: View? = null
    private var minusNavOverlay: View? = null
    // 闲置自动进入负一屏（类似屏保）：用户无操作达设定分钟后切到负一屏
    private val autoMinusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoMinusRunnable: Runnable? = null
    private var page0Ready = false
    private var appGridLoaded = false
    /** 从系统卸载页返回后需重载应用网格 */
    private var pendingReloadOnResume = false
    /** 分页应用网格：外层 ViewPager2 每页一个 6 列 RecyclerView；key=页索引(0..N) */
    private val appPageViews = mutableMapOf<Int, RecyclerView>()
    /** 分页应用网格每页对应的适配器（多选批量隐藏时统一刷新角标）；key=页索引 */
    private val appPageAdapters = mutableMapOf<Int, AppListAdapter>()
    /** 应用网格多选（批量隐藏）共享状态，跨页共用同一实例 */
    private val multiState = MultiSelectState()
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
        /** 应用分页数据（由 loadAppGrid 异步填充后增量 notify）；页面 0=桌面，1..N=应用各页 */
        var appPages: List<List<AppModel>> = emptyList()
        var appRowHeightDp: Float = 0f

        init {
            // stable ids = position：桌面页(0)的 ViewHolder 永不重建，
            // mapHost/musicHost 等持有的 View 引用不失效；应用页变化只做增量插入/删除
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = position.toLong()

        // 分页：0=负一屏（最左，动态壁纸+底部快捷横条），1=桌面（地图），2..N=应用各页
        override fun getItemCount() = 2 + appPages.size
        override fun getItemViewType(position: Int) = when (position) {
            0 -> 0    // 负一屏
            1 -> 1    // 桌面
            else -> 2 // 应用页
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val v = when (viewType) {
                0 -> inflater.inflate(R.layout.page_minus_one, parent, false)
                1 -> inflater.inflate(R.layout.page_desktop, parent, false)
                else -> {
                    // 应用页容器：必须 MATCH_PARENT（ViewPager2 要求页面占满），
                    // 带与桌面一致的 padding（dock 让位），内容为单页 6 列网格
                    FrameLayout(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val dp = resources.displayMetrics.density
                        // 左侧让位由 applyDockStyle 按 dock 形态统一设置在 RecyclerView 上
                        // （edge=108dp / 悬浮=116dp），容器只保留上/右/下 padding，避免双重叠加
                        setPadding(
                            0,
                            (32 * dp).toInt(),
                            (32 * dp).toInt(),
                            (48 * dp).toInt(),
                        )
                    }
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                0 -> bindMinusOne(holder.itemView)
                1 -> bindDesktop(holder.itemView)
                else -> bindAppPage(holder.itemView as ViewGroup, position - 2)
            }
        }
    }

    /** 应用网格左侧让位宽度（dp）：dock 宽 96dp + 12dp 间距；悬浮形态再 +8dp 左边距 */
    private fun appGridLeftPadDp(): Float {
        val edge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        val dockW = 96f
        return if (edge) dockW + 12 else 8 + dockW + 12
    }

    /** 应用页：往容器里放一个静态 6 列网格（不参与滚动，翻页由外层 ViewPager2 驱动） */
    private fun bindAppPage(container: ViewGroup, pageIndex: Int) {
        val pa = binding.viewPager.adapter as? PagerAdapter ?: return
        val pages = pa.appPages
        if (pageIndex < 0 || pageIndex >= pages.size) return
        val gridAdapter = AppListAdapter(
            context = this@MainActivity,
            apps = pages[pageIndex],
            onClick = { app ->
                if (app.onClick != null) app.onClick.invoke()
                else startActivity(app.launchIntent)
            },
            onLongClick = { app -> if (app.onClick == null) showAppMenu(app) },
            rowHeightDp = pa.appRowHeightDp,
            multi = multiState,
        )
        val rv = RecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            layoutManager = GridLayoutManager(this@MainActivity, 6)
            setHasFixedSize(true)
            itemAnimator = null
            isNestedScrollingEnabled = false
            adapter = gridAdapter
        }
        appPageAdapters[pageIndex] = gridAdapter
        // 立即按当前 dock 形态设置左侧让位（与 applyDockStyle 同一口径），避免首次显示贴住 dock
        val dp = resources.displayMetrics.density
        rv.setPadding((appGridLeftPadDp() * dp).toInt(), 0, 0, 0)
        appPageViews[pageIndex] = rv
        // 同一 ViewHolder 可能被多次 re-bind（图标比例/数据变化时 notifyItemRangeChanged），先清空再挂
        container.removeAllViews()
        container.addView(rv)
    }

    /** 负一屏（最左页）：全屏动态/静态壁纸 + 底部半透横条（快捷入口/音乐/歌词/时间天气）。
     *  音乐区/歌词/天气数据由 setupMusic/天气回调接入；壁纸由 applyMinusWallpaper 应用。 */
    private fun bindMinusOne(v: View) {
        minusRoot = v
        minusVideo = v.findViewById(R.id.mvWallpaper)
        minusSong = v.findViewById(R.id.minusSong)
        minusArtist = v.findViewById(R.id.minusArtist)
        minusCover = v.findViewById(R.id.minusCover)
        ivMinusWallpaper = v.findViewById(R.id.ivMinusWallpaper)
        minusBar = v.findViewById(R.id.minusBar)
        minusBtnPlay = v.findViewById(R.id.btnMinusPlay)
        minusBigClock = v.findViewById(R.id.minusBigClock)
        minusNavOverlay = v.findViewById(R.id.navInfoOverlayMinus)
        // 导航/巡航卡接入 NavInfoHost（host 可能尚未创建，缓存后由 setupNav 补附加）
        navInfoHost?.attachMinus(minusNavOverlay)
        // 音乐控制按钮
        v.findViewById<View>(R.id.btnMinusPrev).setOnClickListener {
            if (::musicHost.isInitialized) musicHost.prev()
        }
        v.findViewById<View>(R.id.btnMinusNext).setOnClickListener {
            if (::musicHost.isInitialized) musicHost.next()
        }
        minusBtnPlay?.setOnClickListener {
            if (::musicHost.isInitialized) musicHost.togglePlay()
        }
        // 快捷入口：回家/公司/收藏，复用桌面导航按钮逻辑
        v.findViewById<View>(R.id.shortcutNavHome).setOnClickListener {
            desktopBtnNavHome?.performClick()
        }
        v.findViewById<View>(R.id.shortcutNavCompany).setOnClickListener {
            desktopBtnNavCompany?.performClick()
        }
        v.findViewById<View>(R.id.shortcutNavFavorite).setOnClickListener {
            desktopBtnNavFavorite?.performClick()
        }
        // 应用负一屏壁纸（静态图或视频）
        applyMinusWallpaper()
        applyMinusTheme()
        // 若 musicHost 已就绪，立即同步一次当前音乐到负一屏横条
        if (::musicHost.isInitialized) musicHost.refresh()
        // 大号时钟等负一屏偏好
        applyMinusPrefs()
    }

    /** 负一屏壁纸：follow=跟随桌面（root 壁纸透出，根背景透明）/ image=独立静态图 / video=独立视频。
     *  未设置时默认跟随桌面（root 壁纸透过负一屏显示）。 */
    private fun applyMinusWallpaper() {
        val root = minusRoot ?: run { android.util.Log.w("NUI.Main", "applyMinusWallpaper: minusRoot=null"); return }
        val video = minusVideo
        // onBindViewHolder 可能在 wallpaper 初始化前就绑定负一屏（ViewPager2 预加载），做保护
        if (!::wallpaper.isInitialized) { root.background = null; video?.visibility = View.GONE; android.util.Log.w("NUI.Main", "applyMinusWallpaper: wallpaper not initialized"); return }
        val mode = wallpaper.minusWallpaperMode()
        android.util.Log.i("NUI.Main", "applyMinusWallpaper: mode=$mode video=$video path=${wallpaper.minusWallpaperPath()}")
        when (mode) {
            com.nui.launcher.WallpaperController.MinusMode.VIDEO -> {
                val path = wallpaper.minusWallpaperPath()
                if (!path.isNullOrBlank() && video != null) {
                    root.background = null
                    ivMinusWallpaper?.visibility = View.GONE
                    video.visibility = View.VISIBLE
                    // 播放失败时明确提示（不再静默回退默认）；首帧渲染确认成功
                    video.onError = { what, extra ->
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "视频壁纸播放失败（code=$what/$extra），已回退桌面壁纸",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        video.visibility = View.GONE
                        root.background = null
                    }
                    video.onFirstFrame = {
                        android.util.Log.d("NUI.Main", "minus video first frame rendered")
                    }
                    video.setVideo(path)   // centerCrop 铺满、静音循环
                } else {
                    // 视频路径无效：回退跟随桌面
                    video?.visibility = View.GONE
                    root.background = null
                }
            }
            com.nui.launcher.WallpaperController.MinusMode.IMAGE -> {
                video?.release()
                video?.visibility = View.GONE
                ivMinusWallpaper?.visibility = View.VISIBLE
                root.background = null
                val bmp = wallpaper.minusWallpaperBitmap()
                ivMinusWallpaper?.setImageBitmap(bmp)   // centerCrop 铺满
                if (bmp == null) ivMinusWallpaper?.visibility = View.GONE
            }
            else -> { // FOLLOW
                video?.release()
                video?.visibility = View.GONE
                ivMinusWallpaper?.setImageDrawable(null)
                ivMinusWallpaper?.visibility = View.GONE
                root.background = null       // 透明，透出 root 壁纸
            }
        }
    }

    /** 负一屏底部横条：背景与字体颜色随外观（与 dock 一致）。扁横条内导航只显彩色圆。 */
    private fun applyMinusTheme() {
        val pal = UiTheme.palette(this)
        minusBar?.setBackgroundColor(pal.dockBg)
        minusSong?.setTextColor(pal.textPrimary)
        minusArtist?.setTextColor(pal.textSecondary)
        minusRoot?.findViewById<TextView>(R.id.minusTime)?.setTextColor(pal.textPrimary)
        minusRoot?.findViewById<TextView>(R.id.minusDate)?.setTextColor(pal.textSecondary)
        // 三颗导航彩色圆内的图标：与 page0 运行时一致染 dockIconTint
        val tint = ColorStateList.valueOf(pal.dockIconTint)
        for (id in intArrayOf(R.id.shortcutNavHome, R.id.shortcutNavCompany, R.id.shortcutNavFavorite)) {
            minusRoot?.findViewById<ImageView>(id)?.imageTintList = tint
        }
        // 音乐控制键：播放键为绿色实心圆、上下曲为深色半透圆，图标固定白色（与桌面音乐卡一致），不随 dock 染色
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        minusRoot?.findViewById<ImageView>(R.id.btnMinusPrev)?.imageTintList = whiteTint
        minusRoot?.findViewById<ImageView>(R.id.btnMinusPlay)?.imageTintList = whiteTint
        minusRoot?.findViewById<ImageView>(R.id.btnMinusNext)?.imageTintList = whiteTint
    }

    /** 应用负一屏偏好：大号时钟显隐 + 重置闲置自动进入计时 */
    private fun applyMinusPrefs() {
        minusBigClock?.visibility = if (UiTheme.minusBigClock(this)) View.VISIBLE else View.GONE
        applyBigClockGlass()
        setupAutoMinusTimer()
    }

    /** 大号时钟 iOS 26 式玻璃效果：文字半透明 + 不透明描边（边缘清晰）+ 强阴影。 */
    private fun applyBigClockGlass() {
        val timeTv = minusBigClock?.findViewById<TextView>(R.id.minusBigTime)
        val dateTv = minusBigClock?.findViewById<TextView>(R.id.minusBigDate)
        for (tv in listOfNotNull(timeTv, dateTv)) {
            val p: android.graphics.Paint = tv.paint
            p.strokeWidth = if (tv === timeTv) 3.5f else 2f
            p.style = android.graphics.Paint.Style.FILL_AND_STROKE
            p.color = if (tv === timeTv) 0x99FFFFFF.toInt() else 0xCCFFFFFF.toInt()  // fill 半透明
            // 描边颜色单独设（stroke 用 setStrokeColor）
            try {
                val m = android.graphics.Paint::class.java.getMethod("setStrokeColor", Int::class.javaPrimitiveType)
                m.invoke(p, if (tv === timeTv) 0xF0FFFFFF else 0xE6FFFFFF)
            } catch (_: Exception) {}
            tv.invalidate()
        }
    }

    /** （重新）安排闲置自动进入负一屏；开关关或已在负一屏时不安排 */
    private fun setupAutoMinusTimer() {
        autoMinusHandler.removeCallbacksAndMessages(null)
        autoMinusRunnable = null
        if (!UiTheme.autoMinus(this)) return
        if (binding.viewPager.currentItem == 0) return
        val delayMs = UiTheme.autoMinusMinutes(this) * 60_000L
        val r = Runnable {
            if (!isFinishing && !isDestroyed && UiTheme.autoMinus(this) &&
                binding.viewPager.currentItem != 0
            ) {
                binding.viewPager.setCurrentItem(0, true)
            }
        }
        autoMinusRunnable = r
        autoMinusHandler.postDelayed(r, delayMs)
    }

    /** 用户产生操作（触摸/翻页）：重置闲置计时 */
    private fun onUserActive() {
        if (UiTheme.autoMinus(this)) setupAutoMinusTimer()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) onUserActive()
        return super.dispatchTouchEvent(ev)
    }

    /** 启动高德车机（负一屏导航快捷入口） */
    private fun launchAmap() {
        runCatching {
            val i = packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }
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
        bindNavFavoriteClick()
        if (!page0Ready) {
            page0Ready = true
            v.post {
                setupMap(); setupNav(); setupMusic(); setupWallpaper(); syncRightPanel()
                applyDockStyle()
                applyTheme()
                // mapHost 就绪后按当前开关重算悬浮地图底部边界（onCreate/onResume 时
                // mapHost 尚未初始化，applySystemDock 会跳过 setBottomLimit）
                applySystemDock()
                // 加载应用分页并挂到外层 PagerAdapter（页面 1..N 为应用各页）
                loadAppGrid()
            }
        }
    }

    /** 桌面页应用列表按钮（dock 底部）：切到应用第 1 页 */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemDock()

        // 初始化本实例已应用的地图昼夜状态（FOLLOW_MAP 下即当前外观），作为广播去重基准
        appliedMapDark = UiTheme.isDark(this)
        // 监听高德昼夜模式广播
        registerReceiver(amapDayNightReceiver, android.content.IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"))

        // 红绿灯监控已并入 NavInfoHost（巡航 ICON=0 数据驱动，比 10019 STATE=24 更可靠），
        // 旧 TrafficLightMonitor 停用以避免重复语音提醒
        // trafficLightMonitor = com.nui.launcher.nav.TrafficLightMonitor(this)
        // trafficLightMonitor.start()

        binding.viewPager.adapter = PagerAdapter()
        binding.viewPager.isUserInputEnabled = true
        // 等首帧 layout 后再跳到桌面(position 1)：初始同步 setCurrentItem 会让负一屏(0)
        // 的不透明近黑背景错位盖住全屏、dock 被误判为负一屏而隐藏
        binding.viewPager.postDelayed({ binding.viewPager.setCurrentItem(1, false) }, 300)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 指示器：点索引 = 外层页索引（0=负一屏，1=桌面，2..N=应用各页）
                updatePageIndicator(position)
                // 翻页属于用户活动，重置闲置自动进入计时
                onUserActive()
                // 负一屏无 dock 栏；其它页（桌面/应用列表）恢复 dock
                binding.dockBar.visibility = if (position == 0) View.GONE else View.VISIBLE
                // 离开桌面时关闭高德浮窗；回到桌面时浮窗几何由 IDLE 回调刷新
                // （滑动动画中 getLocationOnScreen 会取到过渡坐标，导致浮窗与 dock 重叠）
                if (::mapHost.isInitialized && position != 1) mapHost.closeFloat()
                // 离开桌面页时隐藏导航/巡航信息卡（负一屏/应用列表不显示悬浮信息）
                navInfoHost?.onPageChanged(position)
                syncWeatherLayer(position)
                // 悬浮歌词（全局窗）：负一屏(0)与桌面(1)都显示，应用列表(2+)隐藏
                if (::musicHost.isInitialized) {
                    musicHost.setFloatAreaVisible(position == 0 || position == 1)
                }
                // 负一屏动态壁纸：滑到本页才确保起播（离屏预加载时 TextureView 可能拿不到 surface），
                // 离开本页暂停解码省电。post 一帧等页面 layout 到位、view 有尺寸。
                if (position == 0) {
                    binding.viewPager.post {
                        if (!isDestroyed && !isFinishing && binding.viewPager.currentItem == 0) {
                            if (::wallpaper.isInitialized) applyMinusWallpaper()
                            minusVideo?.resume()
                        }
                    }
                } else {
                    minusVideo?.pause()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                // 页面完全静止后再刷新浮窗几何：HOME/应用列表按钮/滑动回桌面统一走这里，
                // 保证取数时机一致（非滑动切换与滑动结束都触发 SCROLL_STATE_IDLE）
                if (state == ViewPager2.SCROLL_STATE_IDLE &&
                    binding.viewPager.currentItem == 1 && ::mapHost.isInitialized
                ) {
                    // 回桌面先恢复浮窗（closeFloat 已置隐藏态），再按新几何刷新
                    mapHost.resumeFloat()
                    mapHost.refreshFloat()
                }
            }
        })

        setupDock()
        applyDockStyle()
        setupPageIndicator(2)
        updatePageIndicator(1)
        setupMultiSelectBar()

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
        // 高德已启动并返回：此时主动查询昼夜/导航状态必然有响应。
        // 修复 FOLLOW_MAP 无法跟随外观：启动时（第2s）的查询早于高德启动（第5s）失败后
        // 没有重试，被动广播又只在高德昼夜切换时才发 → 卡在初始外观；直到下次 onResume（如打开设置）才恢复。
        navInfoHost?.queryDayNight()
        navInfoHost?.queryNavState()
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
        if (position != 1) {
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
        if (binding.viewPager.currentItem != 1) return
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
                    onSystemDockToggle = { refreshForThemeChange() },
                )) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        wallpaper.onActivityResult(requestCode, resultCode, data)
        wallpaper.onMinusActivityResult(requestCode, resultCode, data)
        // 从图库选壁纸返回后，刷新设置面板中壁纸的状态文字
        if (requestCode == com.nui.launcher.WallpaperController.REQ_PICK ||
            requestCode == com.nui.launcher.WallpaperController.REQ_PICK_MINUS_IMAGE ||
            requestCode == com.nui.launcher.WallpaperController.REQ_PICK_MINUS_VIDEO) {
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

    override fun onBackPressed() {
        // 多选模式下返回先退出多选，不离开当前页；否则走默认行为
        if (multiState.mode) exitMultiSelect() else super.onBackPressed()
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
            // 在桌面内按 home：桌面(1) <-> 应用第一页(2)；负一屏(0)按 home 回桌面(1)
            if (binding.viewPager.currentItem == 1) binding.viewPager.currentItem = 2
            else binding.viewPager.currentItem = 1
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
        // 回前台重启闲置自动进入负一屏计时
        setupAutoMinusTimer()
        // 设置页可能改了 Dock 形态/图标比例，返回时刷新
        applyDockStyle()
        renderDock()
        // 强制窗口重排+重绘（后台期间 View 属性可能变更，恢复前台时补一次）
        binding.root.requestLayout()
        binding.root.invalidate()
        if (::mapHost.isInitialized) {
            mapHost.onResume()
            // 根据当前 page 决定悬浮地图显示状态（仅桌面显示浮窗）
            if (binding.viewPager.currentItem == 1) mapHost.resumeFloat()
            else mapHost.closeFloat()
        }
        if (::musicHost.isInitialized) {
            musicHost.refresh()
            // 悬浮歌词：负一屏(0)/桌面(1)显示，应用列表隐藏
            musicHost.setFloatAreaVisible(
                binding.viewPager.currentItem == 0 || binding.viewPager.currentItem == 1
            )
        }
        // 设置页可能改了应用列表图标比例，返回时刷新：仅重绑应用页（桌面页 ViewHolder 复用）
        // 延后一帧执行：返回时 ViewPager2 正在 relayout，立即 notify 会在页面宽度未就绪时
        // 重建网格 → 应用图标瞬时靠右、左边留白（先等布局稳定再重绑）
        (binding.viewPager.adapter as? PagerAdapter)?.let { pa ->
            if (pa.appPages.isNotEmpty()) {
                binding.viewPager.post {
                    if (isDestroyed || isFinishing) return@post
                    (binding.viewPager.adapter as? PagerAdapter)?.let { p ->
                        if (p.appPages.isNotEmpty()) p.notifyItemRangeChanged(2, p.appPages.size)
                    }
                }
            }
        }
        // 从系统卸载页返回：重载应用网格（被卸载的应用消失）
        if (pendingReloadOnResume) {
            pendingReloadOnResume = false
            appGridLoaded = false
            // 应用页 ViewHolder 复用，无需 clear（loadAppGrid 增量更新时会重新 bind）
            loadAppGrid()
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
        // 退到后台暂停闲置计时（回前台 onResume 重启）
        autoMinusHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        unregisterReceiver(amapDayNightReceiver)
        // if (::trafficLightMonitor.isInitialized) trafficLightMonitor.stop()
        navInfoHost?.stop()
        if (::weatherVoice.isInitialized) weatherVoice.shutdown()
        if (::weatherLayer.isInitialized) weatherLayer.removeCallbacks(weatherLayerFadeRunnable)
        if (::mapHost.isInitialized) mapHost.onDestroy()
        if (::musicHost.isInitialized) musicHost.onDestroy()
        super.onDestroy()
    }

    private fun setupDock() {
        binding.dockApps.setOnClickListener {
            // 桌面(1) -> 应用第一页(2)；其它页（含负一屏）-> 回桌面(1)
            if (binding.viewPager.currentItem == 1) binding.viewPager.currentItem = 2
            else binding.viewPager.currentItem = 1
        }
        binding.dockApps.setOnLongClickListener {
            if (binding.viewPager.currentItem == 1 && ::mapHost.isInitialized) {
                mapHost.toggleAdjust()
            }
            true
        }
        binding.dockBar.isClickable = true
        binding.dockBar.setOnLongClickListener {
            if (binding.viewPager.currentItem == 1 && ::mapHost.isInitialized) {
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
        // 强制整个窗口重排+重绘：高德外观广播期间窗口未 relayout 时，
        // 仅改 View 属性（background/color）不会触发屏幕更新（此现象已复现）。
        binding.root.requestLayout()
        binding.root.invalidate()
    }

    private fun applyTheme(dark: Boolean) {
        val p = UiTheme.palette(dark)
        val density = resources.displayMetrics.density

        // Dock 栏：半透明背景 + 时钟/图标色（圆角随 Dock 形态：贴边矩形 / 悬浮圆角）
        applyDockVisual(dark)
        applyMinusTheme()
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
        // 应用网格：dock 右侧留出 12dp 统一间距（参考氢桌面比例），各应用页同步
        val leftPad = appGridLeftPadDp()
        for (rv in appPageViews.values) {
            rv.setPadding((leftPad * dp.toFloat()).toInt(), rv.paddingTop, rv.paddingEnd, rv.paddingBottom)
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
            onDismiss = { if (::mapHost.isInitialized && binding.viewPager.currentItem == 1) mapHost.showFloat() },
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
        d.setOnDismissListener { if (::mapHost.isInitialized && binding.viewPager.currentItem == 1) mapHost.showFloat() }
        d.show()
        return true
    }

    private fun setupPageIndicator(count: Int) {
        binding.pageIndicator.removeAllViews()
        val dots = arrayOfNulls<View>(count)
        for (i in 0 until count) {
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
        val dots = binding.pageIndicator.tag as? Array<View> ?: return
        if (dots.isEmpty()) return
        val safe = position.coerceIn(0, dots.size - 1)
        for (i in dots.indices) dots[i].alpha = if (i == safe) 1f else 0.35f
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
        navHost.onShowFloat = { if (binding.viewPager.currentItem == 1) mapHost.showFloat() }
        navHost.start()
        // 导航信息显示：导航中右上角按钮区切换为导航卡（转向/距离/时间/道路/速度）
        navInfoHost = com.nui.launcher.nav.NavInfoHost(
            this,
            desktopNavInfoOverlay!!,
        )
        // 负一屏视图可能已先绑定（ViewPager2 预加载）：交给 host 在 start 时一并附加
        navInfoHost?.pendingMinusRoot = minusNavOverlay
        // 导航/巡航激活时重置闲置计时（发导航、巡航开始不算闲置）
        navInfoHost?.onNavActive = { onUserActive() }
        navInfoHost?.start()
        bindNavFavoriteClick()
    }

    /** 收藏夹按钮：打开高德地图收藏夹。page0 由 ViewPager2 管理，ViewHolder 重建后
     *  旧引用失效，须在 bindDesktop 每次重建时重新绑定 */
    private fun bindNavFavoriteClick() {
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
        musicHost.onShowFloat = { if (binding.viewPager.currentItem == 1) mapHost.showFloat() }
        musicHost.floatBoundsProvider = {
            if (::mapHost.isInitialized) mapHost.floatBounds() else null
        }
        // 负一屏横条音乐区：同步歌名/歌手/封面/播放状态
        musicHost.onMinusMusic = { title, artist, cover, playing ->
            minusSong?.text = title.ifEmpty { "点击打开音乐" }
            minusArtist?.text = artist
            if (cover != null) {
                minusCover?.setImageBitmap(cover)
                minusCover?.visibility = View.VISIBLE
            } else {
                minusCover?.setImageDrawable(null)
                minusCover?.visibility = View.GONE
            }
            minusBtnPlay?.setImageResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
        // 负一屏横条歌词：当前行（下一行并入）
        musicHost.start()
    }

    private fun setupWallpaper() {
        wallpaper = WallpaperController(this, binding.root)
        wallpaper.applyOnStart()
        wallpaper.onHideFloat = { mapHost.closeFloat() }
        wallpaper.onShowFloat = { if (binding.viewPager.currentItem == 1) mapHost.showFloat() }
        // 负一屏壁纸选完图片/视频后重新应用，并刷新设置面板状态
        wallpaper.onMinusWallpaperChanged = {
            applyMinusWallpaper()
            settingsDialog?.refreshWallpaper()
        }
        // 负一屏 ViewHolder 在 wallpaper 初始化前就被 ViewPager2 预加载绑定，
        // 当时 applyMinusWallpaper 因 lateinit 未就绪直接 return，这里 wallpaper 就绪后补应用一次
        applyMinusWallpaper()
        applyMinusTheme()
    }

    private fun loadAppGrid() {
        if (appGridLoaded) return
        appGridLoaded = true

        // 每页行数：按当前 DPI 与图标尺寸精确计算，保证每行图标+名称完整显示。
        // 行高 = item 上下 padding(12dp*2) + 图标(72dp*scale) + 标签区(8dp marginTop + 14sp 文字≈17dp + 上下 padding 4dp)，
        // 可用高 = 屏高(dp) - 网格上下 padding(32+48dp)。宁可少放一行也不允许文字被裁掉。
        val density = resources.displayMetrics.density
        val screenHdp = resources.displayMetrics.heightPixels / density
        val iconScale = UiTheme.appIconScale(this)
        val gridPadTop = 32f    // page_app_grid.xml paddingTop
        val gridPadBottom = 48f // page_app_grid.xml paddingBottom
        val itemPad = 24f       // item_app_grid.xml item 上下 padding 12dp*2
        val iconSizeDp = UiTheme.DEFAULT_APP_ICON_DP * iconScale
        val labelH = 29f        // 8dp marginTop + 14sp 文字(≈17dp) + 文字上下 padding 4dp
        val itemH = itemPad + iconSizeDp + labelH
        val availH = screenHdp - gridPadTop - gridPadBottom
        val rows = maxOf(2, (availH / itemH).toInt())
        val perPage = 6 * rows
        // 固定行高：均分可用高度撑满整屏（最后一行贴底不悬空），item 内容垂直居中
        val rowHeightDp = availH / rows
        android.util.Log.d("NUI.AppGrid", "分页网格: rows=$rows perPage=$perPage availH=$availH itemH=$itemH rowH=$rowHeightDp")

        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(main, 0)
            val hidden = HiddenApps.hiddenSet(this)
            val apps = resolved.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                // 隐藏的应用 + NUI 自身（桌面启动器不显示自己）都跳过
                if (pkg in hidden || pkg == packageName) return@mapNotNull null
                val label = ri.loadLabel(pm).toString()
                val hasIcon = IconUtils.hasCustomIcon(pm, ri)
                AppModel(
                    label = label,
                    packageName = pkg,
                    // icon 不在此处加载：AppListAdapter 按需加载（仅显示页加载，LruCache 缓存）
                    hasIcon = hasIcon,
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
                    dlg.onMinusWallpaperPick = { wallpaper.showMinusMenu() }
                    dlg.minusWallpaperStatus = {
                        when (wallpaper.minusWallpaperMode()) {
                            com.nui.launcher.WallpaperController.MinusMode.IMAGE -> "静态图片"
                            com.nui.launcher.WallpaperController.MinusMode.VIDEO -> "动态视频"
                            else -> "跟随桌面"
                        }
                    }
                    // 负一屏偏好（大号时钟 / 闲置自动进入 / 等待时间）变化即时生效
                    dlg.onMinusPrefsChanged = { applyMinusPrefs() }
                    // 设置面板关闭时刷新桌面主题/dock/音乐栏/应用列表（Dialog 关闭不触发 onResume）
                    dlg.setOnDismissListener {
                        settingsDialog = null
                        applyTheme()
                        applyDockStyle()
                        renderDock()
                        // 隐藏/恢复应用可能已变化：重载应用网格（而非仅 notifyDataSetChanged，
                        // 否则恢复的应用不会重新出现）
                        appGridLoaded = false
                        // 应用页 ViewHolder 复用，无需 clear（loadAppGrid 增量更新时会重新 bind）
                        loadAppGrid()
                        if (::musicHost.isInitialized) musicHost.refresh()
                    }
                    settingsDialog = dlg
                    dlg.show()
                    // 回调在构造后才赋值，构造内的首次 refreshWallpaper 拿不到负一屏状态，show 后补刷一次
                    dlg.refreshWallpaper()
                },
            )
            val allApps = listOf(settingsEntry) + apps
            val pages = allApps.chunked(perPage)
            android.util.Log.d("NUI.AppGrid", "应用总数=${allApps.size} 页数=${pages.size}")
            runOnUiThread {
                // 增量更新应用页：stable ids 保证桌面页 ViewHolder 复用（mapHost 引用不失效），
                // 只插入/删除/重绑应用页，绝不重建整个 adapter
                val pa = binding.viewPager.adapter as? PagerAdapter ?: return@runOnUiThread
                val oldAppCount = pa.appPages.size
                pa.appPages = pages
                pa.appRowHeightDp = rowHeightDp
                when {
                    oldAppCount < pages.size ->
                        pa.notifyItemRangeInserted(2 + oldAppCount, pages.size - oldAppCount)
                    oldAppCount > pages.size ->
                        pa.notifyItemRangeRemoved(2 + pages.size, oldAppCount - pages.size)
                }
                if (minOf(oldAppCount, pages.size) > 0) {
                    pa.notifyItemRangeChanged(2, minOf(oldAppCount, pages.size))
                }
                setupPageIndicator(2 + pages.size)
            }
        }.start()
    }

    /** 长按应用网格中的应用：卸载 / 隐藏（单个，需确认）/ 多选（批量隐藏入口）；设置入口不响应长按 */
    private fun showAppMenu(app: AppModel) {
        val items = arrayOf(
            getString(R.string.uninstall),
            getString(R.string.hide_app),
            getString(R.string.multi_select_entry),
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmUninstall(app)
                    1 -> confirmHide(app)
                    2 -> enterMultiSelect(app)
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
                // 应用页 ViewHolder 复用，无需 clear（loadAppGrid 增量更新时会重新 bind）
                loadAppGrid()
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 多选操作条"隐藏(N)"按钮圆角底：可按=品牌橙，不可按=灰 */
    private fun multiHideBtnBg(enabled: Boolean) = GradientDrawable().apply {
        cornerRadius = dpPx(10).toFloat()
        setColor(if (enabled) 0xFFFF7043.toInt() else 0xFF7A7A7A.toInt())
    }

    /** 绑定应用网格多选顶部操作条：取消 / 已选 N 个 / 全选 / 隐藏(N) */
    private fun setupMultiSelectBar() {
        multiState.onChanged = { updateMultiSelectBar() }
        val ripple = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        binding.btnCancel.setBackgroundResource(ripple.resourceId)
        binding.btnSelectAll.setBackgroundResource(ripple.resourceId)
        binding.btnSelectPage.setBackgroundResource(ripple.resourceId)
        binding.btnCancel.setOnClickListener { exitMultiSelect() }
        binding.btnSelectAll.setOnClickListener {
            val selectable = selectableApps()
            if (multiState.isAllSelected(selectable.size)) multiState.clearSelection()
            else multiState.selectAll(selectable.map { it.packageName })
            appPageAdapters.values.forEach { it.refreshMultiSelect() }
        }
        binding.btnSelectPage.setOnClickListener {
            multiState.applyPageSelection(currentPageSelectable().map { it.packageName })
            appPageAdapters.values.forEach { it.refreshMultiSelect() }
        }
        binding.btnHideSelected.setOnClickListener { confirmHideSelected() }
        updateMultiSelectBar()
    }

    /** 当前应用网格中可被勾选/隐藏的应用（排除"桌面设置"等 onClick 非空的内置入口） */
    private fun selectableApps(): List<AppModel> =
        (binding.viewPager.adapter as? PagerAdapter)?.appPages
            ?.flatten()
            ?.filter { it.onClick == null }
            ?: emptyList()

    /** 当前所在应用页（负一屏=0/桌面=1，故应用页从 2 起，减 2）内可被勾选/隐藏的应用，用于"本页全选" */
    private fun currentPageSelectable(): List<AppModel> {
        val pa = binding.viewPager.adapter as? PagerAdapter ?: return emptyList()
        val idx = binding.viewPager.currentItem - 2
        if (idx < 0 || idx >= pa.appPages.size) return emptyList()
        return pa.appPages[idx].filter { it.onClick == null }
    }

    /** 长按应用进入多选：预先勾选被按的应用，顶部显示操作条，网格整体下移让出操作条 */
    private fun enterMultiSelect(initial: AppModel) {
        multiState.enter(initial.packageName)
        binding.multiBar.visibility = View.VISIBLE
        appPageAdapters.values.forEach { it.refreshMultiSelect() }
        updateMultiSelectBar()
        // 网格顶部让出操作条：给应用页"容器"加顶部 padding（容器是 FrameLayout，setPadding 会
        // 重新布局 MATCH_PARENT 的网格 rv，必定带动整页下移；直接改 rv.paddingTop 在固定行高
        // 撑满的 GridLayoutManager 上实测不重排、不生效）。容器原始上 padding=32dp。
        val extra = dpPx(72)
        appPageViews.values.forEach { rv ->
            (rv.parent as? View)?.let { c ->
                c.setPadding(c.paddingLeft, dpPx(32) + extra, c.paddingRight, dpPx(48))
            }
        }
    }

    private fun exitMultiSelect() {
        multiState.exit()
        binding.multiBar.visibility = View.GONE
        // 还原应用页容器上 padding（32dp）
        appPageViews.values.forEach { rv ->
            (rv.parent as? View)?.let { c ->
                c.setPadding(c.paddingLeft, dpPx(32), c.paddingRight, dpPx(48))
            }
        }
        appPageAdapters.values.forEach { it.refreshMultiSelect() }
    }

    /** 同步多选操作条文案 / 按钮状态（勾选变化时由 [MultiSelectState.onChanged] 触发） */
    private fun updateMultiSelectBar() {
        val n = multiState.count()
        binding.multiTitle.text = getString(R.string.multi_selected_count, n)
        binding.btnHideSelected.text = getString(R.string.multi_hide_n, n)
        binding.btnHideSelected.isEnabled = n > 0
        binding.btnHideSelected.background = multiHideBtnBg(n > 0)
        binding.btnSelectAll.text = getString(
            if (multiState.isAllSelected(selectableApps().size)) R.string.multi_deselect_all
            else R.string.multi_select_all
        )
        val pagePkgs = currentPageSelectable().map { it.packageName }
        val pageAll = pagePkgs.isNotEmpty() && pagePkgs.all { it in multiState.selected }
        binding.btnSelectPage.text = getString(
            if (pageAll) R.string.multi_deselect_page else R.string.multi_select_page
        )
    }

    /** 批量隐藏确认：确认后一次性写入并重建网格 */
    private fun confirmHideSelected() {
        val pkgs = multiState.selected.toList()
        if (pkgs.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.multi_hide_confirm_title, pkgs.size))
            .setMessage(R.string.multi_hide_confirm_msg)
            .setPositiveButton(R.string.hide_app) { _, _ ->
                HiddenApps.hideAll(this, pkgs)
                NuiToast.show(this, getString(R.string.multi_hide_done, pkgs.size), Toast.LENGTH_SHORT)
                exitMultiSelect()
                appGridLoaded = false
                // 应用页 ViewHolder 复用，无需 clear（loadAppGrid 增量更新时会重新 bind）
                loadAppGrid()
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

    /** 按配置应用系统栏显隐（状态栏/导航栏）。
     *  API30+ 用 WindowInsetsController（应用退出回桌面后重设可靠，旧 systemUiVisibility 在
     *  Android 11+ 已废弃、切回前台后可能不生效）；低版本用 systemUiVisibility。 */
    private fun applySystemUi() {
        val showStatus = UiTheme.showStatusBar(this)
        val showDock = UiTheme.showSystemDock(this)
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (showStatus) controller.show(android.view.WindowInsets.Type.statusBars())
            else controller.hide(android.view.WindowInsets.Type.statusBars())
            if (showDock) controller.show(android.view.WindowInsets.Type.navigationBars())
            else controller.hide(android.view.WindowInsets.Type.navigationBars())
            // 手势滑动可临时唤出（等同 IMMERSIVE_STICKY），避免边缘滑出后永久显示
            controller.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
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
        }
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

        applySystemUi()

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

    /** 重新获得焦点时按配置重设系统栏显隐：
     *  启动系统应用期间其前台会显示系统栏（导航栏），应用退出回桌面后系统栏不会自动消失，
     *  必须在此重设隐藏标志。注意：应用自行退出（非 HOME 键）时，窗口恢复早期 SystemUI
     *  会忽略立即重设（HOME 时系统会主动重置导航栏所以看不出），需延迟到过渡结束后再设一次。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemUi()
            binding.root.requestApplyInsets()
            window.decorView.postDelayed({
                applySystemUi()
            }, 150)
        }
    }
}
