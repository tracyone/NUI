package com.nui.launcher

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    private var desktopLightBar: View? = null
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
    /** 大时钟手势：点按开日历 / 长按不松手拖动移动 / 长按松手进入调大小 */
    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var clockLongPress: Runnable? = null
    private var clockLongPressed = false
    /** 长按后手的位移是否已超过 touchSlop（区分"拖动了"还是"原地点按"） */
    private var clockMoved = false
    private var clockDownRawX = 0f
    private var clockDownRawY = 0f
    private var clockStartTx = 0f
    private var clockStartTy = 0f
    /** 长按松手后进入"调整大小"模式：拖动右下角圆点缩放，点按完成 */
    private var clockResizing = false
    /** 调整大小时叠加在大时钟外沿的提示外框 */
    private var clockEditFrame: View? = null
    /** 外框右下角的拖拽把手（明显的"可缩放"标记） */
    private var clockEditHandle: View? = null
    /** 调整模式的全屏遮罩：点按时钟以外任意位置即保存并退出 */
    private var clockEditScrim: View? = null
    private var clockHandleDownX = 0f
    private var clockHandleDownY = 0f
    private var clockHandleStartScale = 1f
    private var clockHandleStartW = 0
    private var clockHandleStartH = 0
    /** 大时钟当前缩放（1.0 = XML 默认字号） */
    private var clockScale = 1f
    /** XML 中的原始字号（sp），首次绑定记录，缩放时以此基准乘系数 */
    private var clockBaseTimeSp = 0f
    private var clockBaseDateSp = 0f
    private var clockBaseLunarSp = 0f
    /** 负一屏横条播放进度刷新（每秒一次，仅在负一屏可见时更新） */
    private val minusProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var minusProgressTick: Runnable? = null
    /** 负一屏进度条是否正在被用户拖动（拖动期间暂停自动刷新） */
    private var minusSeeking = false
    /** 已展示的农历日期 key（"y-m-d"），跨天才重算农历/节气文本 */
    private var lastLunarKey = ""
    /** 负一屏当前会话总时长（毫秒），拖动结束按比例 seek */
    private var minusDurationMs = 0L
    // 闲置自动进入负一屏（类似屏保）：用户无操作达设定分钟后切到负一屏
    private val autoMinusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoMinusRunnable: Runnable? = null
    private var page0Ready = false
    private var appGridLoaded = false
    /** 上次计算应用网格时的 ViewPager2 高度（px），高度不变时跳过重算避免切页卡顿 */
    private var lastAppGridVpH = -1
    /** 上次应用的系统 Dock 显隐状态（null=尚未记录）；切换时需重算应用网格高度 */
    private var lastSystemDock: Boolean? = null
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
                    // 内容为单页 6 列网格（上/右 padding 在此设置；让位由 rv/root insets 统一处理）
                    FrameLayout(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val dp = resources.displayMetrics.density
                        // 左侧让位由 applyDockStyle 按 dock 形态统一设置在 RecyclerView 上，
                        // 底部让位同理（系统 Dock 显示时 root insets 已让位、隐藏时仅给分页
                        // 指示器留空），容器本身不再设底部 padding，避免双重叠加
                        setPadding(
                            0,
                            (32 * dp).toInt(),
                            (32 * dp).toInt(),
                            0,
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

    /** 应用网格底部内边距（dp）：系统 Dock 显示时 root insets 已让位（网格贴 ViewPager2 底）；
     *  系统 Dock 隐藏（沉浸）时仅给底部分页指示器留出空间，避免网格贴底后指示器压住最后一行。 */
    private fun appGridBottomPadDp(): Float =
        if (UiTheme.showSystemDock(this)) 0f else 28f

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
        rv.setPadding((appGridLeftPadDp() * dp).toInt(), 0, 0, (appGridBottomPadDp() * dp).toInt())
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
        // 记录 XML 原始字号（缩放以此为基准，避免反复绑定时叠加）
        if (clockBaseTimeSp <= 0f) {
            val sd = resources.displayMetrics.scaledDensity
            clockBaseTimeSp = v.findViewById<TextView>(R.id.minusBigTime)?.textSize?.div(sd) ?: 0f
            clockBaseDateSp = v.findViewById<TextView>(R.id.minusBigDate)?.textSize?.div(sd) ?: 0f
            clockBaseLunarSp = v.findViewById<TextView>(R.id.minusLunar)?.textSize?.div(sd) ?: 0f
        }
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
        // 音乐卡（封面/歌名/卡片非按钮区）点击 → 打开绑定的音乐 App（停留在 App 内）
        v.findViewById<View>(R.id.minusMusicArea).setOnClickListener {
            if (::musicHost.isInitialized) musicHost.openApp()
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
        // 大时钟手势：点按开日历、长按拖动移动、长按松手调大小
        setupClockGestures()
        // 调整大小时的提示外框（叠在页面上层，跟随时钟位置/尺寸）
        setupClockEditFrame(v)
        // 横条播放进度：进入负一屏立即刷新一次，之后每秒轮询
        lastLunarKey = ""
        updateMinusLunar()
        startMinusProgressTick()
        updateMinusProgress()
        // 进度条支持拖动/点按跳转
        minusRoot?.findViewById<android.widget.SeekBar>(R.id.minusProgress)?.apply {
            // 按住进度条时禁止 ViewPager2 横向拦截，否则拖动会被翻页手势抢走
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        sb: android.widget.SeekBar?,
                        value: Int,
                        fromUser: Boolean,
                    ) {
                        if (!fromUser) return
                        minusSeeking = true
                        minusRoot?.findViewById<TextView>(R.id.minusPosTime)?.text =
                            formatPlayTime(minusDurationMs * value / 1000L)
                    }

                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                        minusSeeking = true
                    }

                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        val dur = minusDurationMs
                        if (dur > 0L && ::musicHost.isInitialized) {
                            musicHost.seekTo(dur * (sb?.progress ?: 0) / 1000L)
                        }
                        minusSeeking = false
                    }
                }
            )
        }
    }

    /** 启动负一屏进度轮询（幂等：Activity 重建时复用同一个 runnable） */
    private fun startMinusProgressTick() {
        if (minusProgressTick != null) return
        val r = object : Runnable {
            override fun run() {
                updateMinusProgress()
                minusProgressHandler.postDelayed(this, 1000L)
            }
        }
        minusProgressTick = r
        minusProgressHandler.post(r)
    }

    /** 刷新负一屏横条进度条与已播/总时长（负一屏不可见时不做事，避免无谓刷新） */
    private fun updateMinusProgress() {
        val root = minusRoot ?: return
        if (!root.isShown) return
        val bar = root.findViewById<android.widget.SeekBar>(R.id.minusProgress) ?: return
        // 农历/节日/节气按天刷新（每秒 tick 里做日期比较，跨天才真正重算）
        updateMinusLunar()
        val posTv = root.findViewById<TextView>(R.id.minusPosTime) ?: return
        val durTv = root.findViewById<TextView>(R.id.minusDurTime) ?: return
        val progress = if (::musicHost.isInitialized) musicHost.playbackProgress() else null
        if (progress == null) {
            minusDurationMs = 0L
            if (!minusSeeking) {
                bar.progress = 0
                posTv.text = "00:00"
            }
            durTv.text = "00:00"
        } else {
            val (pos, dur) = progress
            minusDurationMs = dur
            durTv.text = formatPlayTime(dur)
            // 用户正在拖动时不要覆盖其预览位置
            if (!minusSeeking) {
                bar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                posTv.text = formatPlayTime(pos)
            }
        }
    }

    /** 毫秒 → mm:ss（超过 1 小时用 h:mm:ss） */
    private fun formatPlayTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    /** 负一屏壁纸：follow=跟随桌面（root 壁纸透出，根背景透明）/ image=独立静态图 / video=独立视频。
     *  未设置时默认跟随桌面（root 壁纸透过负一屏显示）。 */
    private fun applyMinusWallpaper() {
        val root = minusRoot ?: run { android.util.Log.w("NUI.Main", "applyMinusWallpaper: minusRoot=null"); return }
        val video = minusVideo
        // onBindViewHolder 可能在 wallpaper 初始化前就绑定负一屏（ViewPager2 预加载），做保护
        if (!::wallpaper.isInitialized) { root.background = null; video?.visibility = View.GONE; return }
        // 按当前槽位文件类型自动判断：视频 → 播放；图片 → 显示；无文件 → 跟随桌面
        val path = wallpaper.minusWallpaperPath()
        if (!path.isNullOrBlank() && video != null) {
            root.background = null
            ivMinusWallpaper?.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.onError = { what, extra ->
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "视频壁纸播放失败（code=$what/$extra），已回退桌面壁纸",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                video.visibility = View.GONE
                root.background = null
            }
            video.setVideo(path)   // centerCrop 铺满、静音循环
        } else {
            val bmp = wallpaper.minusWallpaperBitmap()
            video?.release()
            video?.visibility = View.GONE
            if (bmp != null) {
                ivMinusWallpaper?.visibility = View.VISIBLE
                root.background = null
                ivMinusWallpaper?.setImageBitmap(bmp)   // centerCrop 铺满
            } else {
                // 跟随桌面
                ivMinusWallpaper?.setImageDrawable(null)
                ivMinusWallpaper?.visibility = View.GONE
                root.background = null       // 透明，透出 root 壁纸
            }
        }
    }

    /** 负一屏底部横条：背景与字体颜色随外观（与 dock 一致）。扁横条内导航只显彩色圆。
     *  玻璃质感：顶部稍亮（高光）向底部渐暗的半透明底 + 细描边，壁纸透出，接近 iOS 毛玻璃。 */
    private fun applyMinusTheme() {
        val pal = UiTheme.palette(this)
        val dark = UiTheme.isDark(this)
        val dp = resources.displayMetrics.density
        // 顶部高光 32% → 底部 25% 的半透明渐变；深色带冷灰白描边，浅色带淡灰描边
        minusBar?.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (dark) 0x521F242C.toInt() else 0x52E8E8ED.toInt(),
                if (dark) 0x401F242C.toInt() else 0x40E8E8ED.toInt(),
            )
        ).apply {
            setStroke((1 * dp).toInt(), if (dark) 0x33FFFFFF.toInt() else 0x1F000000.toInt())
        }
        // 低栏变透明后文字加淡阴影，保证亮壁纸上可读
        val shadow = if (dark) 0x66000000.toInt() else 0x40000000.toInt()
        val posTv = minusRoot?.findViewById<TextView>(R.id.minusPosTime)
        val durTv = minusRoot?.findViewById<TextView>(R.id.minusDurTime)
        for (tv in listOfNotNull(
            minusSong, minusArtist, posTv, durTv,
        )) {
            tv.setShadowLayer(4f, 0f, 2f, shadow)
        }
        minusSong?.setTextColor(pal.textPrimary)
        // 歌手同用主文字色：次级灰在半透横条+亮壁纸上对比不足（与日期同处理）
        minusArtist?.setTextColor(pal.textPrimary)
        posTv?.setTextColor(pal.textPrimary)
        durTv?.setTextColor(pal.textPrimary)
        // 播放进度：绿色进度 + 半透明轨道（轨道要够明显，否则透亮壁纸上几乎看不见）
        minusRoot?.findViewById<android.widget.ProgressBar>(R.id.minusProgress)?.apply {
            progressTintList = ColorStateList.valueOf(0xFF34C759.toInt())
            progressBackgroundTintList =
                ColorStateList.valueOf(if (dark) 0x77FFFFFF.toInt() else 0x55000000.toInt())
        }
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

    /** 应用负一屏偏好：大号时钟玻璃效果 + 恢复位置/字号 + 重置闲置自动进入计时 */
    private fun applyMinusPrefs() {
        minusBigClock?.visibility = View.VISIBLE
        applyBigClockGlass()
        // 恢复上次拖动的位置与字号
        val (txDp, tyDp, scale) = UiTheme.minusClockTransform(this)
        val d = resources.displayMetrics.density
        minusBigClock?.translationX = txDp * d
        minusBigClock?.translationY = tyDp * d
        applyClockScale(scale)
        minusBigClock?.let { c -> c.post { clampClockInsideParent(c) } }
        setupAutoMinusTimer()
    }

    /**
     * 大时钟手势：
     * - 点按（未长按）→ 打开日历 App；
     * - 长按不松手后拖动 → 移动位置，松手保存；
     * - 长按后原地松手 → 进入"调整大小"模式：时钟外出现提示框，拖动框的边缘缩放，点按完成。
     * 长按触发前移动（如想翻页）不拦截，交给 ViewPager2。
     */
    private fun setupClockGestures() {
        val clock = minusBigClock ?: return
        clock.isClickable = true
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        clock.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    clockDownRawX = e.rawX
                    clockDownRawY = e.rawY
                    clockStartTx = v.translationX
                    clockStartTy = v.translationY
                    clockMoved = false
                    clockLongPressed = false
                    if (!clockResizing) {
                        val r = Runnable {
                            clockLongPressed = true
                            // 长按触发即亮出编辑外框（明确告诉用户"现在可以拖了"）
                            showClockEditFrame()
                            // 长按后自己接管手势，避免 ViewPager2 把拖动当成翻页
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        clockLongPress = r
                        clockHandler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (clockResizing) {
                        // 调整模式：只有右下角圆点会缩放，点在时钟上不算拖动
                        true
                    } else if (clockLongPressed) {
                        val dx = e.rawX - clockDownRawX
                        val dy = e.rawY - clockDownRawY
                        if (!clockMoved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                            clockMoved = true
                        }
                        v.translationX = clockStartTx + dx
                        v.translationY = clockStartTy + dy
                        clampClockInsideParent(v)
                        syncClockEditFrame()
                        true
                    } else {
                        // 长按触发前就移动了（例如想翻页）：撤销长按并把滑动还给 ViewPager2
                        if (kotlin.math.abs(e.rawX - clockDownRawX) > slop ||
                            kotlin.math.abs(e.rawY - clockDownRawY) > slop
                        ) {
                            cancelClockLongPress()
                        }
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelClockLongPress()
                    if (clockResizing) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        saveClockTransform()
                        // 调整模式里点按（没拖动）= 完成
                        if (!clockMoved) exitClockResizeMode(true)
                    } else if (clockLongPressed) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        if (clockMoved) {
                            saveClockTransform()
                            hideClockEditFrame()
                            NuiToast.show(this@MainActivity, "时钟位置已保存")
                        } else {
                            // 长按后原地松手 → 出现外框，进入调整大小模式
                            enterClockResizeMode()
                        }
                    } else {
                        openCalendarApp()
                    }
                    clockLongPressed = false
                    clockMoved = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelClockLongPress()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (clockResizing) {
                        saveClockTransform()
                        exitClockResizeMode(false)
                    } else {
                        if (clockMoved) saveClockTransform()
                        // 长按后手势被系统打断：收起编辑外框，避免留在界面上
                        hideClockEditFrame()
                    }
                    clockLongPressed = false
                    clockMoved = false
                    true
                }
                else -> false
            }
        }
    }

    /** 右下角圆点的拖拽：以时钟左上角为锚点，往右下拉变大、往左上收变小 */
    private fun setupClockResizeHandle(handle: View) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 圆点拖动时禁止 ViewPager2 拦截，避免被当成翻页
                    handle.parent?.requestDisallowInterceptTouchEvent(true)
                    clockHandleDownX = e.rawX
                    clockHandleDownY = e.rawY
                    clockHandleStartScale = clockScale
                    clockHandleStartW = minusBigClock?.width ?: 0
                    clockHandleStartH = minusBigClock?.height ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (clockHandleStartW > 0 && clockHandleStartH > 0) {
                        val dx = e.rawX - clockHandleDownX
                        val dy = e.rawY - clockHandleDownY
                        // 横/纵位移按宽高比例取平均，再乘到起始倍率
                        val ratio = 1f + (dx / clockHandleStartW + dy / clockHandleStartH) / 2f
                        applyClockScale(clockHandleStartScale * ratio)
                        syncClockEditFrame()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(false)
                    saveClockTransform()
                    true
                }
                else -> false
            }
        }
    }

    private fun enterClockResizeMode() {
        clockResizing = true
        showClockEditFrame()
        NuiToast.show(this@MainActivity, "拖动右下角把手调整大小，点按完成")
    }

    private fun exitClockResizeMode(saved: Boolean) {
        clockResizing = false
        hideClockEditFrame()
        if (saved) NuiToast.show(this@MainActivity, "时钟大小已保存")
    }

    /** 创建全屏遮罩 + 调整外框 + 右下角拖拽把手（叠在页面最上层） */
    private fun setupClockEditFrame(root: View) {
        val page = root as? ViewGroup ?: return
        clockEditScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        clockEditFrame?.let { (it.parent as? ViewGroup)?.removeView(it) }
        clockEditHandle?.let { (it.parent as? ViewGroup)?.removeView(it) }
        // 遮罩：点按时钟以外的任何位置即保存并退出调整模式（符合直觉）
        val scrim = View(this).apply {
            background = android.graphics.drawable.ColorDrawable(0x22000000)
            visibility = View.GONE
            setOnTouchListener { sv, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    // 调整模式下遮罩独占手势，避免横向滑动被 ViewPager2 当成翻页
                    sv.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    sv.parent?.requestDisallowInterceptTouchEvent(false)
                    saveClockTransform()
                    exitClockResizeMode(true)
                }
                true
            }
        }
        page.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        clockEditScrim = scrim
        val f = View(this).apply {
            background = getDrawable(R.drawable.bg_clock_edit_frame)
            visibility = View.GONE
        }
        page.addView(
            f,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        )
        clockEditFrame = f
        // 把手尺寸（iOS 风格灰色 L 形，比外框线粗、颜色明显不同，一眼看出这里能拖）
        val handleSize = (56 * resources.displayMetrics.density).toInt()
        val h = View(this).apply {
            background = getDrawable(R.drawable.bg_clock_resize_handle)
            visibility = View.GONE
        }
        page.addView(
            h,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.TOP or Gravity.START)
        )
        clockEditHandle = h
        setupClockResizeHandle(h)
        // 字号缩放会改变时钟尺寸，布局变化后外框跟随
        minusBigClock?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (clockResizing) syncClockEditFrame()
        }
    }

    private fun showClockEditFrame() {
        val f = clockEditFrame ?: return
        syncClockEditFrame()
        clockEditScrim?.visibility = View.VISIBLE
        f.visibility = View.VISIBLE
        clockEditHandle?.visibility = View.VISIBLE
    }

    private fun hideClockEditFrame() {
        clockEditScrim?.visibility = View.GONE
        clockEditFrame?.visibility = View.GONE
        clockEditHandle?.visibility = View.GONE
    }

    /** 外框与右下角把手跟随时钟（外框向外扩 8dp，把手贴在外框右下角、向外偏 4dp） */
    private fun syncClockEditFrame() {
        val clock = minusBigClock ?: return
        val f = clockEditFrame ?: return
        val parent = clock.parent as? View ?: return
        if (parent.width <= 0) return
        val inset = (8 * resources.displayMetrics.density).toInt()
        val left = (clock.left + clock.translationX - inset).toInt()
        val top = (clock.top + clock.translationY - inset).toInt()
        val w = clock.width + inset * 2
        val h = clock.height + inset * 2
        val lp = f.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && (lp.leftMargin != left || lp.topMargin != top ||
                lp.width != w || lp.height != h)
        ) {
            lp.leftMargin = left
            lp.topMargin = top
            lp.width = w
            lp.height = h
            f.layoutParams = lp
        }
        val hp = clockEditHandle?.layoutParams as? FrameLayout.LayoutParams ?: return
        // L 形两臂中线（图内 47/56 处）压在白框线上，外拐角略突出于白框右下角（iOS 编辑界面样式）
        val armCenter = hp.width * 47f / 56f
        val hx = (left + w - armCenter).toInt()
        val hy = (top + h - armCenter).toInt()
        if (hp.leftMargin != hx || hp.topMargin != hy) {
            hp.leftMargin = hx
            hp.topMargin = hy
            clockEditHandle?.layoutParams = hp
        }
    }

    private fun cancelClockLongPress() {
        clockLongPress?.let { clockHandler.removeCallbacks(it) }
        clockLongPress = null
    }

    /** 按基准字号 × 系数设置大时钟三行文字（0.55~2.0 倍） */
    private fun applyClockScale(scale: Float) {
        val clock = minusBigClock ?: return
        if (clockBaseTimeSp <= 0f) return
        val s = scale.coerceIn(0.55f, 2f)
        clock.findViewById<TextView>(R.id.minusBigTime)
            ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockBaseTimeSp * s)
        clock.findViewById<TextView>(R.id.minusBigDate)
            ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockBaseDateSp * s)
        clock.findViewById<TextView>(R.id.minusLunar)
            ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockBaseLunarSp * s)
        clockScale = s
    }

    /** 拖动/缩放后限制在负一屏页面内，避免时钟被拖出屏幕 */
    private fun clampClockInsideParent(v: View?) {
        val clock = v ?: minusBigClock ?: return
        val parent = clock.parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val baseLeft = clock.left - clock.translationX
        val baseTop = clock.top - clock.translationY
        val minTx = -baseLeft
        val maxTx = (parent.width - clock.width - baseLeft).coerceAtLeast(minTx)
        val minTy = -baseTop
        val maxTy = (parent.height - clock.height - baseTop).coerceAtLeast(minTy)
        clock.translationX = clock.translationX.coerceIn(minTx, maxTx)
        clock.translationY = clock.translationY.coerceIn(minTy, maxTy)
    }

    /** 保存大时钟相对默认位置的偏移（dp）与字号缩放 */
    private fun saveClockTransform() {
        val clock = minusBigClock ?: return
        val d = resources.displayMetrics.density
        UiTheme.setMinusClockTransform(this, clock.translationX / d, clock.translationY / d, clockScale)
    }

    /** 点按大时钟 → 打开日历 App（找不到任何日历时右上角提示） */
    private fun openCalendarApp() {
        val candidates = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse("content://com.android.calendar/time"), "time/epoch"),
            Intent(Intent.ACTION_MAIN).setPackage("com.android.calendar"),
            Intent(Intent.ACTION_MAIN).setPackage("com.google.android.calendar"),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = try {
                packageManager.resolveActivity(i, 0)
            } catch (_: Exception) {
                null
            }
            if (resolved != null) {
                try {
                    startActivity(i)
                    return
                } catch (_: Exception) {
                }
            }
        }
        NuiToast.show(this, "未找到日历应用")
    }

    /** 大号时钟 iOS 26 式玻璃效果：fill 极淡（像透明玻璃）、只有描边不透明（边缘清晰）、弱阴影保证可读。 */
    private fun applyBigClockGlass() {
        val timeTv = minusBigClock?.findViewById<TextView>(R.id.minusBigTime)
        val dateTv = minusBigClock?.findViewById<TextView>(R.id.minusBigDate)
        val lunarTv = minusBigClock?.findViewById<TextView>(R.id.minusLunar)
        for (tv in listOfNotNull(timeTv, dateTv, lunarTv)) {
            val p: android.graphics.Paint = tv.paint
            p.strokeWidth = if (tv === timeTv) 3f else 2f
            p.style = android.graphics.Paint.Style.FILL_AND_STROKE
            p.color = if (tv === timeTv) 0x2EFFFFFF.toInt() else 0x40FFFFFF.toInt()  // fill 18%/25%，透明玻璃
            // 描边颜色单独设（stroke 用 setStrokeColor）：只有边缘不透明
            try {
                val m = android.graphics.Paint::class.java.getMethod("setStrokeColor", Int::class.javaPrimitiveType)
                m.invoke(p, if (tv === timeTv) 0xE6FFFFFF else 0xD9FFFFFF)
            } catch (_: Exception) {}
            // 弱阴影：iOS 26 玻璃字几乎无阴影，仅保留一点保证壁纸上可读
            tv.setShadowLayer(6f, 0f, 2f, 0x26000000.toInt())
            tv.invalidate()
        }
    }

    /** 刷新大时钟农历/节日/节气行（按天变化，跨天时才重算） */
    private fun updateMinusLunar() {
        val tv = minusRoot?.findViewById<TextView>(R.id.minusLunar) ?: return
        val c = java.util.Calendar.getInstance()
        val y = c.get(java.util.Calendar.YEAR)
        val m = c.get(java.util.Calendar.MONTH) + 1
        val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        val key = "$y-$m-$d"
        if (key == lastLunarKey) return
        lastLunarKey = key
        tv.text = LunarCalendar.describe(y, m, d)
    }

    /** （重新）安排闲置自动进入负一屏；开关关、已在负一屏、或导航中时不安排。
     *  导航中不自动进负一屏，避免打断驾驶；巡航不受影响（巡航卡可在负一屏显示）。
     *  退出导航后由 onNavInactive 重新调度。 */
    private fun setupAutoMinusTimer() {
        autoMinusHandler.removeCallbacksAndMessages(null)
        autoMinusRunnable = null
        if (!UiTheme.autoMinus(this)) return
        if (binding.viewPager.currentItem == 0) return
        if (navInfoHost?.isNavigating() == true) return   // 仅导航中不调度，巡航正常
        val delayMs = UiTheme.autoMinusSeconds(this) * 1_000L
        val r = Runnable {
            if (!isFinishing && !isDestroyed && UiTheme.autoMinus(this) &&
                binding.viewPager.currentItem != 0 &&
                navInfoHost?.isNavigating() != true        // 触发时再次确认未在导航
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

    // 右侧音乐面板的子 View 会消费触摸事件，ViewPager2 收不到向右的横向滑动。
    // 在 Activity 级别检测：桌面页右缘区域向右滑 → 平滑进入负一屏（page0 在左边）。
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var edgeTriggered = false
    /** 上次重置闲置计时的时间戳（ACTION_MOVE 限流用，避免每帧都调 setupAutoMinusTimer） */
    private var lastActiveResetMs = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val action = ev.actionMasked
        val sw = resources.displayMetrics.widthPixels
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val rightEdgePx = (280 * resources.displayMetrics.density).toInt()
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                onUserActive()
                edgeDownX = ev.rawX; edgeDownY = ev.rawY
                edgeTriggered = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // 用户持续滑动（浏览应用列表/拖动等）也属活动，限流重置闲置计时
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastActiveResetMs > 2_000L) {
                    lastActiveResetMs = now
                    onUserActive()
                }
                if (!edgeTriggered && binding.viewPager.currentItem == 1) {
                    val dx = ev.rawX - edgeDownX
                    val dy = ev.rawY - edgeDownY
                    // 负一屏在 page0（左边），右侧音乐区域向右滑 → 露出左边的负一屏
                    if (edgeDownX > sw - rightEdgePx && dx > touchSlop &&
                        Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        edgeTriggered = true
                        // 触发即向 DOWN 时的触摸目标（音乐卡等）补发 CANCEL：后续 MOVE/UP
                        // 被本层吞掉，子 View 收不到 CANCEL 会保留 500ms 长按计时，
                        // 切到负一屏后误弹"选择音乐 App"对话框。
                        val cancel = android.view.MotionEvent.obtain(ev)
                        cancel.action = android.view.MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        binding.viewPager.setCurrentItem(0, true)
                    }
                }
                if (edgeTriggered) return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (edgeTriggered) { edgeTriggered = false; return true }
            }
        }
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
        desktopMapPanel?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncRightPanel()
            // 布局变化时若在桌面页，确保分割条可见并定位（防止时序问题导致卡在 -10000）
            if (binding.viewPager.currentItem == 1 && ::mapHost.isInitialized) {
                mapHost.setDividerVisible(true)
            }
            mapHostRefreshDivider()
        }
        desktopMapContainer = v.findViewById(R.id.mapContainer)
        desktopRightPanel = v.findViewById(R.id.rightPanel)
        desktopBtnSwitchMap = v.findViewById(R.id.btnSwitchMap)
        desktopMusicContainer = v.findViewById(R.id.musicContainer)
        desktopBtnNavHome = v.findViewById(R.id.btnNavHome)
        desktopBtnNavCompany = v.findViewById(R.id.btnNavCompany)
        desktopBtnNavFavorite = v.findViewById(R.id.btnNavFavorite)
        desktopNavInfoOverlay = v.findViewById(R.id.navInfoOverlay)
        desktopLightBar = v.findViewById(R.id.lightBar)
        // page0 的 ViewHolder 可能被回收重建：每次 bindDesktop 都把导航卡/红绿灯条
        // 重新挂到当前 View 上，否则 NavInfoHost 继续往旧 View 渲染，页面上看不到灯条。
        if (navInfoHost != null) {
            navInfoHost?.rebind(desktopNavInfoOverlay!!, desktopLightBar)
        }
        weatherText = v.findViewById(R.id.weatherText)
        weatherText?.setOnClickListener { startActivity(Intent(this, WeatherActivity::class.java)) }
        bindNavFavoriteClick()
        if (!page0Ready) {
            page0Ready = true
            v.post {
                setupMap(); setupNav(); setupMusic(); setupWallpaper(); syncRightPanel()
                applyDockStyle()
                applyTheme()
                // mapHost 就绪后按当前开关重算悬浮地图上下边界（onCreate/onResume 时
                // mapHost 尚未初始化，applySystemDock 会跳过 setVerticalInsets）
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
        binding.viewPager.postDelayed({
            binding.viewPager.setCurrentItem(1, false)
            // setCurrentItem(false) 不触发 onPageSelected，兜底确保桌面页分割条显示
            if (::mapHost.isInitialized) mapHost.setDividerVisible(true)
        }, 300)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // 【性能】动画期间不刷竖条：refreshDivider/updateViewLayout 是跨进程 IPC，
                // 动画中每帧调用会阻塞主线程导致掉帧尖峰；动画结束由 SCROLL_STATE_IDLE
                // 的 refreshDivider 一次性落位（用户感知不到延迟）。
                // 【翻页耗时测量】从应用列表切回桌面时，桌面页 position=1 且 offset=0 的瞬间，
                // 右侧音乐面板右边缘刚好到达屏幕右边缘，以此作为动画终点。
                if (pageLatencyMeasuring && position == 1 && positionOffset == 0f) {
                    val cost = android.os.SystemClock.elapsedRealtime() - pageLatencyStartMs
                    pageLatencyMeasuring = false
                    Log.i("NUI.PageLatency", "page1->page0 cost=${cost}ms (offset=0 settled)")
                }
            }
            override fun onPageSelected(position: Int) {
                // 指示器：点索引 = 外层页索引（0=负一屏，1=桌面，2..N=应用各页）
                updatePageIndicator(position)
                // 翻页属于用户活动，重置闲置自动进入计时
                onUserActive()
                // 负一屏无 dock 栏；其它页（桌面/应用列表）恢复 dock
                binding.dockBar.visibility = if (position == 0) View.GONE else View.VISIBLE
                // 进入负一屏时关闭桌面设置弹窗，避免弹窗悬浮在负一屏之上。
                // 使用无动画关闭，防止浅色背景淡出动画透过负一屏透明根布局形成白雾。
                if (position == 0) settingsDialog?.dismissImmediate()
                // 负一屏强制隐藏系统 Dock（不管用户设置）；切回其它页按用户设置恢复
                applySystemDock()
                // 切到应用列表页（position>=2）时强制重算网格：
                // 从 page0 滑过来时 padding 没变（都是显示 Dock），不会触发 applySystemDock 里的重算，
                // 但应用列表 RecyclerView 可能刚创建、高度需按当前 ViewPager2 尺寸重新计算
                if (position >= 2) {
                    binding.viewPager.post {
                        if (!isDestroyed && !isFinishing) {
                            appGridLoaded = false
                            loadAppGrid()
                        }
                    }
                }
                // 离开桌面时关闭高德浮窗（发 closemap，高德必定响应）；
                // 回到桌面时浮窗几何由 SCROLL_STATE_IDLE 的 resumeFloat 刷新。
                if (::mapHost.isInitialized && position != 1) mapHost.closeFloat()
                // 悬浮分割条：仅桌面页(1)显示。
                // 【性能】显示(setDividerVisible true)延迟到 IDLE：setCornerMaskVisible 刷新角窗
                // 位图 + updateDividerPosition 的 updateViewLayout 是 IPC，动画期执行会掉帧；
                // 隐藏走 alpha 切换（无 IPC），离开桌面时立即执行不影响动画。
                if (position != 1 && ::mapHost.isInitialized) {
                    mapHost.setDividerVisible(false)
                }
                // 离开桌面页时隐藏导航/巡航信息卡（负一屏/应用列表不显示悬浮信息）
                navInfoHost?.onPageChanged(position)
                syncWeatherLayer(position)
                // 悬浮歌词（全局窗）：负一屏(0)与桌面(1)显示，应用列表(2+)隐藏。
                // 【性能】显示延迟到 IDLE（updateLyricFloat 可能 addView IPC）；隐藏立即执行。
                if (::musicHost.isInitialized && position >= 2) {
                    musicHost.setFloatAreaVisible(false)
                }
                // 音乐浮窗：仅桌面页(1)请求绑定的音乐 App 在该窗口悬浮显示，其它页关闭
                if (position == 1) {
                    binding.viewPager.postDelayed({
                        if (!isDestroyed && !isFinishing && binding.viewPager.currentItem == 1) {
                            syncMusicFloat()
                        }
                    }, 300)
                } else {
                    syncMusicFloat()
                }
                // 负一屏动态壁纸：滑到本页才确保起播（离屏预加载时 TextureView 可能拿不到 surface），
                // 离开本页暂停解码省电。post 一帧等页面 layout 到位、view 有尺寸。
                if (position == 0) {
                    binding.viewPager.post {
                        if (!isDestroyed && !isFinishing && binding.viewPager.currentItem == 0) {
                            // VIDEO 分支 setVideo→ensurePlaying 已确定性起播，无需再 resume（避免重复建播放器）
                            if (::wallpaper.isInitialized) applyMinusWallpaper()
                        }
                    }
                } else {
                    minusVideo?.pause()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                // 【翻页耗时测量】兜底：若 onPageScrolled 未捕获到 offset=0（极端帧丢失），
                // 在 IDLE+currentItem=1 时结算，保证每次测量都有结果。
                if (pageLatencyMeasuring && state == ViewPager2.SCROLL_STATE_IDLE &&
                    binding.viewPager.currentItem == 1
                ) {
                    val cost = android.os.SystemClock.elapsedRealtime() - pageLatencyStartMs
                    pageLatencyMeasuring = false
                    Log.i("NUI.PageLatency", "page1->page0 cost=${cost}ms (IDLE fallback)")
                }
                // 页面完全静止后再刷新浮窗几何：HOME/应用列表按钮/滑动回桌面统一走这里。
                // 【性能】动画结束瞬间若立即执行窗口落位（浮窗/分割条/手柄），这些跨进程 IPC
                // （relayoutWindow/pauseSurface/bringDivider/hideSwap）在车机上会集中阻塞主线程，
                // 造成动画末尾掉帧尖峰。延迟 ~100ms 到动画后的空闲帧执行，动画末尾不再被阻塞，
                // 窗口在动画结束后 100ms 内自然落位（用户感知不到）。
                if (state == ViewPager2.SCROLL_STATE_IDLE &&
                    binding.viewPager.currentItem == 1 && ::mapHost.isInitialized
                ) {
                    binding.viewPager.postDelayed({
                        if (!isDestroyed && !isFinishing && binding.viewPager.currentItem == 1) {
                            // 回桌面：先显示分割条（alpha=1 + 定位），再恢复浮窗并按新几何刷新
                            mapHost.setDividerVisible(true)
                            // 回桌面先恢复浮窗（closeFloat 已置隐藏态），再按新几何刷新
                            mapHost.resumeFloat()
                            mapHost.refreshFloat()
                            // 动画期间不刷竖条，静止后一次性落位
                            mapHost.refreshDivider()
                            // 页面已静止：手柄在过渡中可能未定位（target 未挂载），此处重新落位
                            showSwapHandles()
                        }
                    }, 100)
                }
                // 悬浮歌词：负一屏(0)与桌面(1)显示，延迟到动画结束避免 addView IPC 掉帧
                if (state == ViewPager2.SCROLL_STATE_IDLE &&
                    (binding.viewPager.currentItem == 0 || binding.viewPager.currentItem == 1) &&
                    ::musicHost.isInitialized
                ) {
                    val targetPage = binding.viewPager.currentItem
                    binding.viewPager.postDelayed({
                        if (!isDestroyed && !isFinishing &&
                            (binding.viewPager.currentItem == 0 || binding.viewPager.currentItem == 1) &&
                            binding.viewPager.currentItem == targetPage
                        ) {
                            musicHost.setFloatAreaVisible(true)
                        }
                    }, 100)
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE && binding.viewPager.currentItem == 1) {
                    // 页面静止后再请求音乐浮窗：布局已稳定，几何取数才准确；同样延迟到动画后执行
                    binding.viewPager.postDelayed({
                        if (!isDestroyed && !isFinishing && binding.viewPager.currentItem == 1) syncMusicFloat()
                    }, 100)
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
        val wc = com.nui.launcher.WallpaperController
        if (requestCode == wc.REQ_PICK ||
            requestCode == wc.REQ_PICK_MINUS_DAY ||
            requestCode == wc.REQ_PICK_MINUS_NIGHT) {
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
        // 按 Home 键回到桌面：先关闭桌面设置弹窗，再走常规返回逻辑。
        // 否则弹窗窗口会一直压在最上层（系统只管回到 Home Activity，不会关应用内 Dialog），
        // 悬浮地图重新拉起后用户既回不到桌面、音乐窗口也被弹窗遮住。
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            settingsDialog?.let {
                it.dismissImmediate()
                settingsDialog = null
                android.util.Log.d("NUI.Main", "onNewIntent: HOME -> dismiss settings")
            }
        }
        // 地图（高德）自动返回：只保持当前 page，不切 page0/page1，也不做外部返回恢复
        if (intent.getBooleanExtra(com.nui.launcher.map.MapHost.EXTRA_AUTO_BACK, false)) {
            launchedExternalApp = false
            android.util.Log.d("NUI.Main", "onNewIntent: EXTRA_AUTO_BACK, keep page ${binding.viewPager.currentItem}")
            return
        }
        // 导航发起后返回：强制切到桌面页——导航卡/悬浮地图都在桌面页，
        // 从负一屏发起导航时不能恢复回负一屏（否则看不到导航状态）
        if (intent.getBooleanExtra(com.nui.launcher.nav.NavHost.EXTRA_BACK_DESKTOP, false)) {
            launchedExternalApp = false
            if (binding.viewPager.currentItem != 1) binding.viewPager.setCurrentItem(1, false)
            android.util.Log.d("NUI.Main", "onNewIntent: EXTRA_BACK_DESKTOP -> page 1")
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
            if (binding.viewPager.currentItem == 1) {
                mapHost.resumeFloat()
                // 兜底：启动/回前台时确保桌面页分割条已显示
                mapHost.setDividerVisible(true)
            } else {
                mapHost.closeFloat()
            }
            // 延迟兜底：setCurrentItem(1,false) 和布局稳定后再确认一次分割条状态，
            // 防止 onPageSelected 时序问题导致分割条卡在 -10000（移出屏幕）
            binding.root.postDelayed({
                if (!isDestroyed && !isFinishing && ::mapHost.isInitialized &&
                    binding.viewPager.currentItem == 1) {
                    mapHost.setDividerVisible(true)
                }
            }, 1000)
        }
        if (::musicHost.isInitialized) {
            musicHost.refresh()
            // 悬浮歌词：负一屏(0)/桌面(1)显示，应用列表隐藏
            musicHost.setFloatAreaVisible(
                binding.viewPager.currentItem == 0 || binding.viewPager.currentItem == 1
            )
            // 音乐浮窗：回前台后按当前 page 恢复（设置里改了开关也能在此生效）
            binding.root.postDelayed({ syncMusicFloat() }, 300)
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
        // 从系统文件选择器/其它 App 遮挡返回负一屏：遮挡期间 TextureView 的 surface 可能失效，
        // 播放器仍在解码但画面不合成（透出桌面壁纸，表现为"动态壁纸设置无效"）。强制重绑 surface 重启。
        if (::wallpaper.isInitialized &&
            binding.viewPager.currentItem == 0 &&
            wallpaper.minusWallpaperPath() != null
        ) {
            binding.viewPager.post {
                if (!isDestroyed && !isFinishing && binding.viewPager.currentItem == 0) {
                    minusVideo?.forceRestart()
                }
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
        if (::musicHost.isInitialized) {
            musicHost.setFloatAreaVisible(false)
            // 退后台关闭音乐浮窗，避免悬浮在其他 App / 系统弹窗之上
            musicHost.setMusicFloatVisible(false)
        }
        // 退到后台暂停闲置计时（回前台 onResume 重启）
        autoMinusHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        unregisterReceiver(amapDayNightReceiver)
        // if (::trafficLightMonitor.isInitialized) trafficLightMonitor.stop()
        navInfoHost?.stop()
        if (::weatherVoice.isInitialized) weatherVoice.shutdown()
        if (::weatherLayer.isInitialized) weatherLayer.removeCallbacks(weatherLayerFadeRunnable)
        if (::mapHost.isInitialized) { mapHost.hideDivider(); mapHost.onDestroy() }
        if (::musicHost.isInitialized) musicHost.onDestroy()
        minusProgressTick?.let { minusProgressHandler.removeCallbacks(it) }
        minusProgressTick = null
        super.onDestroy()
    }

    // ===== 翻页耗时测量（page1 应用列表 -> page0 桌面）=====
    // 触发：应用列表按钮（dockApps）从应用列表页切回桌面。
    // 终点：onPageScrolled 中桌面页 position=1 且 offset=0（此时右侧音乐面板右边缘到达屏幕右边缘）。
    // 仅在主线程读写，无需同步。
    private var pageLatencyStartMs = 0L
    private var pageLatencyMeasuring = false

    private fun setupDock() {
        binding.dockApps.setOnClickListener {
            // 桌面(1) -> 应用第一页(2)；其它页（含负一屏）-> 回桌面(1)
            val cur = binding.viewPager.currentItem
            if (cur == 1) {
                binding.viewPager.currentItem = 2
            } else {
                // 从应用列表/负一屏回桌面：开始计时，onPageScrolled(offset=0) 时结算
                pageLatencyStartMs = android.os.SystemClock.elapsedRealtime()
                pageLatencyMeasuring = true
                binding.viewPager.currentItem = 1
            }
        }
        binding.dockBar.isClickable = true
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
        // 壁纸切换后圆角遮罩要重绘（四角楔形是用壁纸像素填充的）
        if (::mapHost.isInitialized) mapHost.refreshCornerMask()
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
        // 外观切换时负一屏壁纸也按深浅重新解析（白天/晚上图自动切换）
        applyMinusWallpaper()
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

        // 右侧面板：背景 + 导航图标/文字 + 时钟。
        // dock 贴边形态时面板也改为矩形（与贴边 dock 的贯穿式风格一致），悬浮形态保留 24dp 圆角。
        val panelEdge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        desktopRightPanel?.let { rp ->
            rp.background = GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = if (panelEdge) 0f else 24 * density
                setStroke(1, p.divider)
            }
            rp.findViewById<ImageView>(R.id.navIconHome)?.setColorFilter(p.dockIconTint)
            rp.findViewById<ImageView>(R.id.navIconCompany)?.setColorFilter(p.dockIconTint)
            rp.findViewById<ImageView>(R.id.navIconFavorite)?.setColorFilter(p.dockIconTint)
            // 右侧面板文字：浅外观黑色、深外观白色；统一加粗 + 加大
            val panelText = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            rp.findViewById<TextView>(R.id.navLabelHome)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            rp.findViewById<TextView>(R.id.navLabelCompany)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            rp.findViewById<TextView>(R.id.navLabelFavorite)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            rp.findViewById<TextView>(R.id.weatherText)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            rp.findViewById<TextView>(R.id.clockTime)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f) }
            rp.findViewById<TextView>(R.id.clockDate)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
            rp.findViewById<MaterialCardView>(R.id.musicPanel)?.let { mp ->
                mp.setCardBackgroundColor(p.mapBg)
                // 音乐窗口圆角随 dock 形态：贴边矩形 / 悬浮 24dp（与地图卡片、右侧面板统一）
                mp.radius = if (panelEdge) 0f else 24 * density
            }
        }
    }

    /** Dock 栏背景：颜色随深浅，圆角随形态（贴边矩形 / 悬浮圆角） */
    private fun applyDockVisual(dark: Boolean = UiTheme.isDark(this)) {
        val p = UiTheme.palette(dark)
        val dp = resources.displayMetrics.density
        val edge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        binding.dockBar.background = GradientDrawable().apply {
            setColor(p.dockBg)
            cornerRadius = if (edge) 0f else 28 * dp
            setStroke(1, p.divider)
        }
        // 右侧音乐面板圆角同步 dock 形态：贴边时矩形，悬浮时 24dp 圆角
        desktopRightPanel?.let { rp ->
            (rp.background as? android.graphics.drawable.GradientDrawable)?.let { bg ->
                bg.cornerRadius = if (edge) 0f else 24 * dp
                rp.background = bg
            }
            // 音乐窗口卡片本身也随形态变矩形/圆角（悬浮 24dp，与地图卡片、右侧面板统一）
            rp.findViewById<MaterialCardView>(R.id.musicPanel)?.radius = if (edge) 0f else 24 * dp
            // 导航标签文字样式同步（dock 形态切换时重新应用）
            val panelText = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            rp.findViewById<TextView>(R.id.navLabelHome)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            rp.findViewById<TextView>(R.id.navLabelCompany)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            rp.findViewById<TextView>(R.id.navLabelFavorite)?.let { it.setTextColor(panelText); it.setTypeface(null, Typeface.BOLD); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
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

        // 地图窗口圆角：外部地图浮窗无法直接裁剪，悬浮形态下用四角壁纸遮罩补齐圆角
        if (::mapHost.isInitialized) {
            val mapRadius = (24 * dp).toInt()
            desktopMapPanel?.radius = if (edge) 0f else mapRadius.toFloat()
            mapHost.setCornerMask(!edge, mapRadius)
        }

        // 地图卡片：dock 右侧留出 12dp 统一间距（参考氢桌面比例）
        // 互换模式下地图锚定在右侧，左边界由互换几何决定，不能被 dock 形态覆盖
        // （否则会把"地图在右、音乐栏在左"的几何改回"贴 dock"，导致音乐栏宽度算成负值被隐藏）。
        val swapMode = ::mapHost.isInitialized && mapHost.swapped
        desktopMapPanel?.let { mp ->
            if (swapMode) return@let
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
        val bottomPad = (appGridBottomPadDp() * dp.toFloat()).toInt()
        for (rv in appPageViews.values) {
            rv.setPadding((leftPad * dp.toFloat()).toInt(), rv.paddingTop, rv.paddingEnd, bottomPad)
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
        // 负一屏（position 0）不显示页面指示器白点
        binding.pageIndicator.visibility = if (position == 0) View.GONE else View.VISIBLE
        val safe = position.coerceIn(0, dots.size - 1)
        for (i in dots.indices) dots[i].alpha = if (i == safe) 1f else 0.35f
    }

    private fun setupMap() {
        val mapPanel = desktopMapPanel ?: return
        val mapContainer = desktopMapContainer ?: return
        val rightPanel = desktopRightPanel ?: return
        val btnSwitchMap = desktopBtnSwitchMap ?: return

        mapHost = MapHost(this, mapContainer, mapPanel)
        // 圆角遮罩需要壁纸像素：直接取根布局背景（WallpaperController 设置的即为铺满全屏的壁纸）
        mapHost.wallpaperProvider = { binding.root.background }
        mapHost.onGeometryChanged = { binding.root.post { syncRightPanel(); mapHostRefreshDivider(); positionSwapHandles(); syncMusicFloat(); if (::musicHost.isInitialized) musicHost.requestFloatRefresh(); mapHost.refreshCornerMask() } }
        mapHost.onFloatShown = { if (::musicHost.isInitialized) musicHost.bringLyricFloatToFront(); bringSwapHandlesToFront() }
        mapHost.onAutoReturnDone = { flushPendingWeatherVoice() }
        mapHost.onPickMap = {
            mapHost.closeFloat()
            if (::musicHost.isInitialized) musicHost.setMusicFloatVisible(false)
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source, autoLaunch = true)
                    NuiToast.show(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT)
                },
                onDismiss = { mapHost.showFloat(); syncMusicFloat() },
            )
        }
        mapHost.start(mapSources, autoLaunch = true)
        // 触摸分割条（独立悬浮窗，不走 Activity.dispatchTouchEvent）也要重置闲置计时
        mapHost.onDividerInteract = { onUserActive() }
        // 底部互换横条常驻，跟随分割条显隐（分割条在非桌面页/对话框/失焦时隐藏，横条同步隐藏）
        mapHost.onDividerVisibilityChanged = { visible -> if (visible) showSwapHandles() else hideSwapHandles() }
        // 创建分割条 overlay（只创建一次，之后非 page0 时移出屏幕、page0 时定位）
        mapHost.showDivider()

        btnSwitchMap.setOnClickListener { showMapPicker() }
    }

    /** 地图几何/布局变化后刷新悬浮分割条位置（mapHost 可能未初始化则跳过） */
    private fun mapHostRefreshDivider() {
        if (::mapHost.isInitialized) mapHost.refreshDivider()
    }

    /** 桌面音乐面板的屏幕边界（x1,y1,x2,y2）；面板隐藏/未布局时返回 null。
     *  直接作为音乐悬浮窗口边界（面板全宽）：悬浮音乐内容会自适应窗口尺寸
     *  （实测正常模式拖动面板变宽时悬浮区域跟着变大、无上限），窗口必须与
     *  面板等大——窗口窄于面板会露出 NUI 自己的音乐卡（此前误加"内容固定宽"
     *  的封顶导致互换模式露出卡片左半边，已撤）。 */
    private fun rightPanelBounds(): IntArray? {
        val rp = desktopRightPanel ?: return null
        if (rp.visibility != View.VISIBLE || rp.width <= 0 || rp.height <= 0) return null
        val loc = IntArray(2)
        rp.getLocationOnScreen(loc)
        if (loc[0] <= 0 || loc[1] <= 0) return null
        return intArrayOf(loc[0], loc[1], loc[0] + rp.width, loc[1] + rp.height)
    }

    /** 同步音乐浮窗显隐：仅桌面页(1)且音乐面板可见时请求绑定的音乐 App 在该窗口悬浮显示。
     *  设置「音乐悬浮显示」未开启、无绑定 App 或 App 无悬浮插件时为幂等空操作。 */
    private fun syncMusicFloat() {
        if (!::musicHost.isInitialized) return
        val onDesktop = binding.viewPager.currentItem == 1
        val panelVisible = desktopRightPanel?.visibility == View.VISIBLE
        musicHost.setMusicFloatVisible(onDesktop && panelVisible)
    }

    /** 弹出地图选择器更换悬浮地图。[onPicked] 在选择后回调（设置面板用它刷新绑定名称）。
     *  选择器显示期间隐藏悬浮地图与分割条：两者都是 TYPE_APPLICATION_OVERLAY，
     *  会压在对话框之上，用户既看不清选项也点不到（竖条尤其明显）。 */
    private fun showMapPicker(onPicked: () -> Unit = {}) {
        if (!::mapHost.isInitialized) return
        mapHost.closeFloat()
        mapHost.setDividerVisible(false)
        // 音乐浮窗同为外部 overlay，对话框期间必须关闭，否则压在选择器之上
        if (::musicHost.isInitialized) musicHost.setMusicFloatVisible(false)
        MapPickerDialog.show(
            context = this,
            sources = mapSources,
            currentId = mapHost.currentId,
            onPick = { source ->
                mapHost.select(source, autoLaunch = true)
                NuiToast.show(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT)
                onPicked()
            },
            onDismiss = {
                if (binding.viewPager.currentItem == 1) {
                    mapHost.showFloat()
                    mapHost.setDividerVisible(true)
                }
                syncMusicFloat()
            },
        )
    }

    /** 桌面设置 → 地图 → 绑定地图应用 */
    private fun showMapPickerForSettings(dlg: com.nui.launcher.settings.SettingsDialog) {
        showMapPicker { dlg.refreshMap() }
    }

    private fun setupNav() {        navHost = NavHost(this, desktopBtnNavHome!!, desktopBtnNavCompany!!)
        navHost.onHideFloat = { mapHost.closeFloat(); if (::musicHost.isInitialized) musicHost.setMusicFloatVisible(false) }
        navHost.onShowFloat = { if (binding.viewPager.currentItem == 1) { mapHost.showFloat(); syncMusicFloat() } }
        navHost.start()
        // 导航信息显示：导航中右上角按钮区切换为导航卡（转向/距离/时间/道路/速度）
        navInfoHost = com.nui.launcher.nav.NavInfoHost(
            this,
            desktopNavInfoOverlay!!,
        )
        // 导航/巡航进行中不自动进入负一屏：激活时取消计时，结束时重新调度
        navInfoHost?.onNavActive = { setupAutoMinusTimer() }
        navInfoHost?.onNavInactive = { setupAutoMinusTimer() }
        navInfoHost?.start()
        // 音乐区上方的红绿灯条：复用 60073 巡航红绿灯胶囊渲染
        desktopLightBar?.let { navInfoHost?.attachLightBar(it) }
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
        // Activity 可能被系统重建（stateNotNeeded）后再次 onCreate：先销毁旧实例，
        // 避免新旧两个 MusicHost 并存（旧实例的自动恢复不受新实例 userPaused 约束 → 暂停后自动播放）
        if (::musicHost.isInitialized) {
            musicHost.onDestroy()
        }
        musicHost = MusicHost(this, desktopMusicContainer!!)
        musicHost.onHideFloat = { mapHost.closeFloat(); musicHost.setMusicFloatVisible(false) }
        musicHost.onShowFloat = {
            if (binding.viewPager.currentItem == 1) {
                mapHost.showFloat()
                syncMusicFloat()
            }
        }
        musicHost.floatBoundsProvider = {
            if (::mapHost.isInitialized) mapHost.floatBounds() else null
        }
        // 音乐浮窗几何：桌面音乐面板的屏幕边界（供"音乐悬浮显示"广播使用）
        musicHost.musicPanelBoundsProvider = { rightPanelBounds() }
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
            // 切歌/暂停后立即刷新进度，不等下一次每秒轮询
            updateMinusProgress()
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
        // 可用高 = ViewPager2 实际高度 - 网格上下 padding(32dp + 动态底部)。宁可少放一行也不允许文字被裁掉。
        val density = resources.displayMetrics.density
        // ViewPager2 内容区高度 = 物理屏高 - 状态栏(显示时) - 导航栏(显示时)。
        // 不用 root.height 或 viewPager.height——窗口 flag 切换(沉浸/非沉浸)后这两个值异步更新，
        // 切页瞬间拿到旧值会导致应用网格行数/行高算错、最后一行被裁。
        // 物理屏高和系统栏高度都是确定性常量，立即可用。
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarH = if (UiTheme.showStatusBar(this) && statusBarId > 0)
            resources.getDimensionPixelSize(statusBarId) else 0
        val navBarH = if (effectiveShowDock()) getNavBarHeight() else 0
        val vpH = realScreenHeight() - statusBarH - navBarH
        // 高度未变则跳过重算（避免每次切到应用列表都刷新 RecyclerView 导致卡顿）
        if (vpH == lastAppGridVpH) return
        lastAppGridVpH = vpH
        val screenHdp = vpH / density
        val iconScale = UiTheme.appIconScale(this)
        val gridPadTop = 32f    // 应用页容器 paddingTop（onCreateViewHolder）
        val gridPadBottom = appGridBottomPadDp() // 应用网格底部内边距（系统 Dock 隐藏时给指示器留空）
        val itemPad = 24f       // item_app_grid.xml item 上下 padding 12dp*2
        val iconSizeDp = UiTheme.DEFAULT_APP_ICON_DP * iconScale
        val labelH = 29f        // 8dp marginTop + 14sp 文字(≈17dp) + 文字上下 padding 4dp
        val itemH = itemPad + iconSizeDp + labelH
        val availH = screenHdp - gridPadTop - gridPadBottom
        // 屏幕可容纳的最大行数：够几行算几行，最少 1 行。
        // 不强制最少 2 行——导航栏很高时可用高度不足 2 行 item 高度，
        // 强制 2 行会导致行高 < item 内容高度，文字被裁。
        val maxRows = (availH / itemH).toInt().coerceAtLeast(1)

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
                icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_settings_ios)!!,
                launchIntent = Intent(),
                onClick = {
                    val dlg = com.nui.launcher.settings.SettingsDialog(this)
                    dlg.wallpaperHasCustom = { slot -> wallpaper.hasCustom(slot) }
                    dlg.wallpaperDisplayName = { slot -> wallpaper.wallpaperDisplayName(slot) }
                    dlg.onWallpaperPick = { slot -> wallpaper.showMenu(slot) }
                    // 地图：绑定应用名称 + 点击更换（原 dock 长按进入地图调整的入口已移除）
                    dlg.mapLabelProvider = {
                        if (!::mapHost.isInitialized) null
                        else mapSources.firstOrNull { it.id == mapHost.currentId }?.label
                    }
                    dlg.onPickMap = { showMapPickerForSettings(dlg) }
                    // 设置弹窗内的触摸也重置闲置计时
                    dlg.onUserTouch = { onUserActive() }
                    dlg.onMinusWallpaperPick = { day -> wallpaper.showMinusMenu(day) }
                    dlg.onMusicFloatChanged = { syncMusicFloat() }
                    dlg.minusWallpaperStatus = { day ->
                        wallpaper.minusWallpaperDisplayName(day) ?: "跟随桌面"
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
                    dlg.refreshMap()
                },
            )
            val allApps = listOf(settingsEntry) + apps
            // 实际行数 = min(屏幕可容纳最大行数, 应用所需行数)：应用不足整屏时均分可用高度，
            // 不留空行（18 个应用按 3 行均分，而不是算 4 行留空一整行）
            val needRows = maxOf(1, (allApps.size + 5) / 6)
            val rows = minOf(maxRows, needRows)
            val perPage = 6 * rows
            // 固定行高：均分可用高度撑满整屏（最后一行贴底不悬空），item 内容垂直居中
            val rowHeightDp = availH / rows
            android.util.Log.d("NUI.AppGrid", "分页网格: rows=$rows perPage=$perPage availH=$availH itemH=$itemH rowH=$rowHeightDp")
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
        val rightMargin = (8 * density).toInt()
        val gap = (12 * density).toInt()
        val minPanel = (180 * density).toInt()          // 音乐栏最小宽度，低于则整体消失
        val availableRight = sw - rightMargin
        // 地图右缘上限：铺满时 = 屏幕宽 - 12dp（MapHost.mapRightLimit），
        // 大于 availableRight（音乐栏贴边）——铺满时音乐栏已隐藏，地图用足右侧空间
        //（旧逻辑钳在 availableRight，浮窗右缘与屏幕右缘之间留 12px 空隙没用上）
        val mapRightCap = if (::mapHost.isInitialized) mapHost.mapRightLimit() else availableRight
        // 地图右缘（相对 Page0，全屏坐标系）。
        // 用 mlp.width 而非 mp.width：拖动时 applyGeometry 刚改了 layoutParams，
        // 但 layout 尚未执行、mp.width 还是旧值，会导致音乐栏宽度滞后、要切页才刷新。
        val mlp = mp.layoutParams as FrameLayout.LayoutParams
        val mapLeft = mlp.leftMargin
        val mapRight = mapLeft + mlp.width
        val swappedMode = ::mapHost.isInitialized && mapHost.swapped
        val geoReady = !::mapHost.isInitialized || mapHost.geometryLoaded

        if (swappedMode) {
            // ===== 互换模式：音乐栏在左，地图在右 =====
            // 面板左缘贴 dock 右侧 + 12dp：与正常模式地图左缘（applyDockStyle 的 dockW+12）
            // 完全一致，保证首次启动与互换后的左侧间隙观感相同；不能压到 dock 栏。
            // 宽度 = mapLeft - gap - leftEdge
            val dockLeft = if (UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE) 0 else 8
            val leftEdge = ((dockLeft + 96 + 12) * density).toInt()
            val panelW = mapLeft - gap - leftEdge
            if (panelW < minPanel && geoReady) {
                // 音乐栏太窄：隐藏，地图自动扩展到左缘（不留空）
                rp.visibility = android.view.View.GONE
                val rightEdge = mapRight
                if (mapLeft > leftEdge + 2) {
                    mlp.leftMargin = leftEdge
                    mlp.width = rightEdge - leftEdge
                    mp.layoutParams = mlp
                }
                mp.post { mapHost?.refreshFloat() }
            } else {
                rp.visibility = android.view.View.VISIBLE
                val lp = rp.layoutParams as FrameLayout.LayoutParams
                // 面板贴左边：gravity 改为 top|start，leftMargin = leftEdge
                val wantGravity = Gravity.TOP or Gravity.START
                if (lp.gravity != wantGravity || lp.leftMargin != leftEdge) {
                    lp.gravity = wantGravity
                    lp.leftMargin = leftEdge
                    rp.layoutParams = lp
                }
                if (lp.width != panelW) {
                    lp.width = panelW
                    rp.layoutParams = lp
                } else if (rp.width != panelW) {
                    // 快速拖动松手后最后一帧 requestLayout 可能被吞（同正常模式的补救）
                    rp.requestLayout()
                }
            }
            syncMusicFloat()
            return
        }
        // ===== 正常模式：地图在左，音乐栏在右 =====
        val panelW = availableRight - gap - mapRight
        // 地图几何尚未加载完成时，不隐藏音乐栏、不扩展地图，避免首次启动把默认位置冲掉
        if (panelW < minPanel && geoReady) {
            // 音乐栏太窄：隐藏，地图自动扩展到右缘（不留空）
            rp.visibility = android.view.View.GONE
            val targetW = mapRightCap - mapLeft
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
            val lp = rp.layoutParams as FrameLayout.LayoutParams
            // 面板贴右边：gravity 恢复 top|end，leftMargin 归零（从互换模式回来时）
            val wantGravity = Gravity.TOP or Gravity.END
            if (lp.gravity != wantGravity || lp.leftMargin != 0) {
                lp.gravity = wantGravity
                lp.leftMargin = 0
                rp.layoutParams = lp
            }
            if (lp.width != panelW) {
                lp.width = panelW
                rp.layoutParams = lp
            } else if (rp.width != panelW) {
                // layoutParams 已是目标值但视图没落地：快速拖动松手后，最后一帧的
                // requestLayout 发生在布局回调里会被系统吞掉，而后续刷新因宽度相等
                // 走不到上面的写入分支、连 requestLayout 都不发 → 间隙永远停在中途帧。
                // 这里补发一次强制重新布局（切页/时间刷新恢复正常的等价物）。
                rp.requestLayout()
            }
        }
        // 音乐栏宽度/显隐变化后同步音乐浮窗几何（面板隐藏时会关闭浮窗）
        syncMusicFloat()
    }

    /** 有效的系统 Dock 显示状态：负一屏（position 0）强制隐藏系统 Dock（不管用户设置），
     *  其它页（桌面/应用列表）按用户设置。切页时由 onPageSelected 重新应用。 */
    private fun effectiveShowDock(): Boolean {
        // 调试：nui_debug_navbar_h>0 时模拟"显示系统 dock"开启，便于无系统栏设备复现
        val simNav = android.provider.Settings.Global.getInt(contentResolver, "nui_debug_navbar_h", 0)
        val showDock = if (simNav > 0) true else UiTheme.showSystemDock(this)
        return showDock && binding.viewPager.currentItem != 0
    }

    /** 按配置应用系统栏显隐（状态栏/导航栏）。
     *  API30+ 用 WindowInsetsController（应用退出回桌面后重设可靠，旧 systemUiVisibility 在
     *  Android 11+ 已废弃、切回前台后可能不生效）；低版本用 systemUiVisibility。 */
    private fun applySystemUi() {
        val showStatus = UiTheme.showStatusBar(this)
        val showDock = effectiveShowDock()
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
        val showDock = effectiveShowDock()
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

        // 悬浮地图上下 inset：状态栏/导航栏显示时把地图高度限定在内容区，
        // 避免溢进系统栏（表现为上下紧贴、横条被导航栏盖住）。
        if (::mapHost.isInitialized) {
            mapHost.setVerticalInsets(
                getStatusBarHeight(),
                if (showDock) getNavBarHeight() else 0
            )
        }

        // 系统栏显示时显式让位：按状态栏/导航栏 inset 给根布局加 padding，
        // 使左侧 dock、页面、天气层等各窗口动态缩小上移，不遮挡系统 Dock。
        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (UiTheme.showStatusBar(this)) insets.getSystemWindowInsetTop() else 0
            // nui_debug_navbar_h 模拟导航栏高度（无系统栏设备调试用），优先于真实 inset
            val simNav = android.provider.Settings.Global.getInt(contentResolver, "nui_debug_navbar_h", 0)
            val bottom = if (effectiveShowDock()) {
                if (simNav > 0) simNav else insets.getSystemWindowInsetBottom()
            } else 0
            v.setPadding(0, top, 0, bottom)
            // 系统 Dock 显隐切换（含负一屏强制隐藏）：ViewPager2 高度变化 → 应用网格可用高度变化，
            // 更新 rv 底部内边距并重算行数/行高。
            // setPadding 触发的 layout 是异步的，单次 post 时 ViewPager2 高度可能还没更新，
            // 双重 post 确保 layout 完成后再重算，否则拿到旧的全屏高度导致最后一行被裁。
            val curDock = effectiveShowDock()
            val prevDock = lastSystemDock
            lastSystemDock = curDock
            if (prevDock == null || prevDock != curDock) {
                applyDockStyle()
                // padding 已同步设置，高度可直接计算，无需等 layout
                appGridLoaded = false
                loadAppGrid()
            }
            // 悬浮地图（高德浮窗）同步避让状态栏/导航栏：按内容区高度重算地图尺寸
            if (::mapHost.isInitialized) {
                mapHost.setVerticalInsets(
                    if (UiTheme.showStatusBar(this)) top else 0,
                    if (effectiveShowDock()) bottom else 0
                )
            }
            insets
        }
        // 模拟导航栏高度时（模拟器无真实导航栏，insets 回调可能不触发），
        // 直接同步设置 root padding：显示 Dock 时留出导航栏高度，隐藏时清除。
        val simNavNow = android.provider.Settings.Global.getInt(contentResolver, "nui_debug_navbar_h", 0)
        if (simNavNow > 0) {
            val bottomPad = if (effectiveShowDock()) simNavNow else 0
            val prevPad = binding.root.paddingBottom
            binding.root.setPadding(0, binding.root.paddingTop, 0, bottomPad)
            if (prevPad != bottomPad) {
                // padding 已同步设置，高度可直接计算，无需等 layout
                appGridLoaded = false
                loadAppGrid()
            }
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

    /** 状态栏高度（px）；状态栏未显示或获取失败返回 0 */
    private fun getStatusBarHeight(): Int {
        if (!UiTheme.showStatusBar(this)) return 0
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
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
            // 焦点回到桌面窗口（对话框已关闭）且仍在桌面页时，恢复分割条
            if (::mapHost.isInitialized && binding.viewPager.currentItem == 1) {
                mapHost.setDividerVisible(true)
            }
        } else {
            // 焦点被其它窗口抢走（对话框 / 系统弹窗 / 其它应用）：分割条是
            // TYPE_APPLICATION_OVERLAY，会悬浮在这些窗口之上（竖条压在对话框中间，
            // 难看且挡住点击），必须先隐藏。对话框关闭时上面的 hasFocus 分支会恢复。
            //（底部互换横条由 onDividerVisibilityChanged 联动隐藏，无需单独处理）
            if (::mapHost.isInitialized) mapHost.setDividerVisible(false)
        }
    }

    // ===== 底部拖动手柄（灰色横条）：水平拖动超过阈值 → 地图与音乐栏左右互换位置 =====
    // 两个 overlay 小窗（各自 140dp×14dp），常驻显示、跟随分割条显隐（onDividerVisibilityChanged）。
    // 横条落在"窗口底边与屏幕底边之间的空隙"里，不遮挡窗口内容。

    private var swapHandleMap: View? = null
    private var swapHandlePanel: View? = null
    private var swapHandlesVisible = false
    /** 手柄拖动：本次拖动是否已触发过互换（防止一次拖动反复横跳） */
    private var swapDragTriggered = false

    private var swapHandleDownX = 0f
    private var swapHandleStartWinX = 0

    private val SWAP_HANDLE_TRIGGER_DP = 96
    /** 手柄窗口宽/高（dp）：高度压在窗口底部空隙里，故做得扁 */
    private val SWAP_HANDLE_W_DP = 140
    private val SWAP_HANDLE_H_DP = 14

    /** 物理屏幕高度（含系统栏区域），用于把横条定位到窗口底边与屏幕底边的空隙 */
    private val realScreenH: Int by lazy {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
        m.heightPixels
    }

    private fun dpSwap(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 创建手柄窗口内容：灰色胶囊横条（窗口内水平垂直居中） */
    private fun createSwapHandleContent(): View {
        val w = dpSwap(SWAP_HANDLE_W_DP)
        val h = dpSwap(SWAP_HANDLE_H_DP)
        return FrameLayout(this).apply {
            addView(View(this@MainActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dpSwap(2).toFloat()
                    setColor(0xCCAAAAAA.toInt())   // 灰色横条
                }
            }, FrameLayout.LayoutParams(dpSwap(96), dpSwap(4)).apply {
                gravity = Gravity.CENTER
            })
            layoutParams = android.view.ViewGroup.LayoutParams(w, h)
        }
    }

    /** 显示/刷新两个窗口底部的拖动手柄（常驻）。分割条显示时由 onDividerVisibilityChanged 触发。 */
    fun showSwapHandles() {
        if (binding.viewPager.currentItem != 1 || !::mapHost.isInitialized) return
        if (!Settings.canDrawOverlays(this)) return

        val mp = desktopMapPanel ?: return
        positionSwapHandle(isMap = true, target = mp)
        val rp = desktopRightPanel
        if (rp != null && rp.visibility == android.view.View.VISIBLE && rp.width > 0) {
            positionSwapHandle(isMap = false, target = rp)
        } else {
            removeSwapHandle(isMap = false)
        }
        swapHandlesVisible = true
    }

    /** 刷新两个手柄位置（几何变化后调用）；手柄未显示时不动。 */
    private fun positionSwapHandles() {
        if (!swapHandlesVisible) return
        desktopMapPanel?.let { positionSwapHandle(isMap = true, target = it) }
        val rp = desktopRightPanel
        if (rp != null && rp.visibility == android.view.View.VISIBLE && rp.width > 0) {
            positionSwapHandle(isMap = false, target = rp)
        } else {
            removeSwapHandle(isMap = false)
        }
    }

    private fun positionSwapHandle(isMap: Boolean, target: View) {
        val handle = ensureSwapHandle(isMap)
        // 视图未挂载/无尺寸时定位无意义：手柄保持创建时的屏外位置，等页面稳定后由
        // onPageScrollStateChanged(IDLE) 的 showSwapHandles() 重新定位。
        if (!target.isAttachedToWindow || target.width <= 0) return
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val lp = handle.layoutParams as android.view.WindowManager.LayoutParams
        lp.x = loc[0] + target.width / 2 - lp.width / 2
        // 横条放进"窗口底边 ~ 内容区底边"的空隙。
        // 内容区底边取页面根布局（ViewPager 页）的实测屏幕底边：系统 Dock 显示时窗口被 resize，
        // 页面高度已让出系统栏，与地图内容区口径一致（MapHost.contentHeightPx 同样取父布局实测高度）。
        // 不能用 realScreenH - root.paddingBottom：Dock 显示时 FLAG_LAYOUT_NO_LIMITS 被取消，
        // 系统按 resize 让位、不再下发 inset，root.paddingBottom=0，横条会被算进系统 Dock 区域，
        // 在真机上被系统 Dock 窗口盖住而消失（本 bug 根因）。
        val pageRoot = target.parent as? View
        val contentBottom = if (pageRoot != null && pageRoot.height > 0) {
            val ploc = IntArray(2)
            pageRoot.getLocationOnScreen(ploc)
            ploc[1] + pageRoot.height
        } else {
            realScreenH - binding.root.paddingBottom
        }
        val winBottom = loc[1] + target.height
        val gapH = (contentBottom - winBottom).coerceAtLeast(0)
        lp.y = winBottom + (gapH - lp.height) / 2
        runCatching { windowManager.updateViewLayout(handle, lp) }
    }

    private fun ensureSwapHandle(isMap: Boolean): View {
        val existing = if (isMap) swapHandleMap else swapHandlePanel
        if (existing != null) return existing
        val handle = createSwapHandleContent()
        val lp = android.view.WindowManager.LayoutParams(
            dpSwap(SWAP_HANDLE_W_DP), dpSwap(SWAP_HANDLE_H_DP),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 创建时先移到屏幕外：定位前视图可能未挂载（翻页过渡），若用 (0,0) 会
            // 在左上角闪一条横条；定位成功后 updateViewLayout 会把它移到窗口底部。
            x = -10000; y = 0
        }
        handle.setOnTouchListener(swapHandleTouch)
        runCatching { windowManager.addView(handle, lp) }
        if (isMap) swapHandleMap = handle else swapHandlePanel = handle
        return handle
    }

    private val swapHandleTouch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swapDragTriggered = false
                swapHandleDownX = e.rawX
                swapHandleStartWinX = (v.layoutParams as android.view.WindowManager.LayoutParams).x
                onUserActive()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                onUserActive()
                val lp = v.layoutParams as android.view.WindowManager.LayoutParams
                lp.x = (swapHandleStartWinX + (e.rawX - swapHandleDownX)).toInt()
                    .coerceIn(0, resources.displayMetrics.widthPixels - lp.width)
                runCatching { windowManager.updateViewLayout(v, lp) }
                if (!swapDragTriggered &&
                    kotlin.math.abs(e.rawX - swapHandleDownX) > dpSwap(SWAP_HANDLE_TRIGGER_DP)) {
                    swapDragTriggered = true
                    mapHost.swapped = !mapHost.swapped
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 松手后手柄回到对应窗口底部居中（互换后窗口位置已变，需重新定位）
                binding.root.post {
                    if (v === swapHandleMap) {
                        desktopMapPanel?.let { positionSwapHandle(isMap = true, target = it) }
                    } else {
                        desktopRightPanel?.let { positionSwapHandle(isMap = false, target = it) }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun removeSwapHandle(isMap: Boolean) {
        val handle = if (isMap) swapHandleMap else swapHandlePanel
        handle?.let { runCatching { windowManager.removeView(it) } }
        if (isMap) swapHandleMap = null else swapHandlePanel = null
    }

    /** 隐藏两个拖动手柄（分割条隐藏时由 onDividerVisibilityChanged 联动调用） */
    fun hideSwapHandles() {
        if (!swapHandlesVisible) return
        swapHandlesVisible = false
        removeSwapHandle(isMap = true)
        removeSwapHandle(isMap = false)
    }

    /** 手柄置顶（浮窗出现后调用，避免被盖住） */
    fun bringSwapHandlesToFront() {
        if (!swapHandlesVisible) return
        listOfNotNull(swapHandleMap, swapHandlePanel).forEach { h ->
            val old = h.layoutParams as android.view.WindowManager.LayoutParams
            runCatching { windowManager.removeView(h) }
            val lp = android.view.WindowManager.LayoutParams(
                old.width, old.height, old.type, old.flags, old.format
            ).apply {
                gravity = old.gravity; x = old.x; y = old.y
            }
            runCatching { windowManager.addView(h, lp) }
        }
    }
}
