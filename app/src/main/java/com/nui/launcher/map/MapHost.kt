package com.nui.launcher.map

import com.nui.launcher.NuiToast

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.nui.launcher.UiTheme
import com.google.android.material.card.MaterialCardView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

/**
 * 悬浮地图容器控制器：
 * - 按数据源渲染内嵌 OSM / 外部应用入口卡 / 广播浮窗（高德）
 * - 管理 [mapPanel] 几何（位置+大小），支持调整模式
 *
 * 调整模式：高德浮窗 closemap 后仍拦截触摸，故 NUI 用自己的 overlay
 * （TYPE_APPLICATION_OVERLAY，悬浮在 mapPanel 之上）接收拖动/缩放手势。
 * 长按非悬浮区（dock 栏）触发 → 关闭高德浮窗 + 显示 overlay → 拖动/缩放 →
 * 放手移除 overlay + 按新几何恢复高德浮窗。
 */
class MapHost(
    private val context: Context,
    private val container: FrameLayout,
    private val mapPanel: MaterialCardView,
    /** 几何（位置/大小）变更回调，用于外部同步联动右侧面板等。 */
    var onGeometryChanged: (() -> Unit)? = null,
    /** 高德浮窗广播发出、窗口出现后回调（用于歌词窗置顶） */
    var onFloatShown: (() -> Unit)? = null,
    /** 调整模式下点击"选择地图"按钮回调。 */
    var onPickMap: (() -> Unit)? = null,
    /** 自动启动外部地图（高德）并首次返回桌面完成后回调（用于延迟天气首次播报等） */
    var onAutoReturnDone: (() -> Unit)? = null
) {
    /** 是否处于"自动启动外部地图并等待首次返回"流程中（高德正在前台） */
    val isAutoReturnPending: Boolean get() = autoReturnPending
    private var autoReturnPending = false
    private var mapView: MapView? = null
    private var current: MapSource? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var adjustMode = false
    private var adjustOverlay: View? = null
    private val wm: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    /** 物理屏幕尺寸（含系统栏区域）。displayMetrics 在非沉浸窗口下会扣掉系统栏，用于
     *  地图底部边界会导致"有/无系统 Docker 栏"时边界不一致，故统一用真实物理尺寸。 */
    private val realScreenW: Int by lazy {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels
    }
    private val realScreenH: Int by lazy {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        m.heightPixels
    }
    /** 调整模式：起点（按下时的屏幕坐标+overlay尺寸）*/
    private var dragStartX = 0f
    private var dragStartY = 0f
    /** 上一次 MOVE 事件的 x 坐标，用于判断拖动方向 */
    private var lastMoveX = 0f
    /** 是否已吸附到最右端（音乐栏消失状态），用于滞回控制避免跳动 */
    private var snappedToRight = false
    /** 退出吸附的阈值（dp）：已吸附后需向左拖超过此值才退出，避免手指抖动 */
    private val snapExitThreshold: Int get() = dp(30)
    private var startLeft = 0   // 固定左边界（不允许移动）
    private var startTop = 0    // 固定顶边界（不允许移动）
    private var startWidth = 0
    private var startHeight = 0
    private var resizeMode = ResizeMode.NONE
    /** 地图底部上限（px）：系统底部 Dock（导航栏）显示时由外部传入（屏幕高-导航栏高-8dp），
     *  保证悬浮地图不进入系统 Dock 区域；0=不限制（可拖到屏幕最底部）。 */
    private var bottomLimit = 0
    /** 几何是否已加载完成（loadGeometry 执行完毕），用于防止 syncRightPanel 在默认位置生效前扩展地图 */
    var geometryLoaded = false

    // ===== 常驻悬浮分割条（类似 iOS 分屏竖条，悬浮于高德浮窗之上，拖动调整地图宽度）=====
    private var dividerOverlay: View? = null
    private var dividerShown = false
    private var dividerStartX = 0f
    private var dividerStartMapW = 0
    private var dividerStartLeft = 0
    private var dividerStartRight = 0
    /** 竖条白色细线引用（互换模式下白线水平位置随左右关系动态偏移） */
    private var dividerWhiteLine: View? = null
    /** 互换模式：true = 音乐栏在左、地图在右；false（默认）= 地图在左、音乐栏在右。
     *  与几何一样持久化，重启后保持。 */
    var swapped: Boolean = prefs.getBoolean(KEY_SWAPPED, false)
        set(value) {
            if (field == value) return
            field = value
            prefs.edit { putBoolean(KEY_SWAPPED, value) }
            transformGeometryForSwap(value)
            onGeometryChanged?.invoke()
        }

    /** 切换互换模式时真正调换两个窗口的位置（只动位置关系，不动高度）：
     *  正常 → 互换：音乐栏保持原宽度搬到 dock 右侧（不能压到 dock 栏），地图移到右边、右缘锚定铺满上限；
     *  互换 → 正常：音乐栏保持原宽度搬回右缘，地图左缘恢复贴 dock、宽度填满到音乐栏。
     *  音乐栏当前处于隐藏（地图铺满）状态时只移动地图，音乐栏保持隐藏。 */
    private fun transformGeometryForSwap(toSwapped: Boolean) {
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        val sw = realScreenW
        val gap = dp(MUSIC_GAP_DP)
        val rightMargin = dp(8)
        val leftEdge = dockEdgeX()   // 左侧可用边界：dock 右侧（互换模式下音乐栏/铺满地图都不能压 dock）
        val minPanel = dp(MUSIC_PANEL_MIN_WIDTH_DP)
        if (toSwapped) {
            // 换前音乐栏宽度（正常模式公式：右边距内 - 间距 - 地图右缘）；<minPanel 表示音乐栏已隐藏
            val panelW = (sw - rightMargin) - gap - (lp.leftMargin + lp.width)
            if (panelW >= minPanel) {
                val newLeft = leftEdge + panelW + gap
                lp.leftMargin = newLeft
                lp.width = (mapRightLimit() - newLeft).coerceAtLeast(MIN_SIZE)
            } else {
                // 音乐栏隐藏中：地图铺满整行（左缘=dock 右侧，右缘=铺满上限）
                lp.leftMargin = leftEdge
                lp.width = (mapRightLimit() - leftEdge).coerceAtLeast(MIN_SIZE)
            }
        } else {
            // 互换模式下的音乐栏宽度 = 地图左缘 - 间距 - 左侧可用边界
            val panelW = lp.leftMargin - gap - leftEdge
            val dockX = dockEdgeX()
            lp.leftMargin = dockX
            lp.width = if (panelW >= minPanel) {
                ((sw - rightMargin) - gap - panelW - dockX).coerceAtLeast(MIN_SIZE)
            } else {
                (mapRightLimit() - dockX).coerceAtLeast(MIN_SIZE)
            }
        }
        mapPanel.layoutParams = lp
        saveGeometry()
        refreshFloat()
    }

    /** 左侧可用边界（px）：dock 栏 96dp + 间距 12dp = 108dp，与正常模式地图左缘
     *  （MainActivity.applyDockStyle 的 dockW+12）保持一致。
     *  正常模式地图左缘、互换模式音乐栏左缘/地图铺满左缘都不能越过它（否则压到 dock 栏）。
     *  注意：这里必须是 12dp 而不是 24dp，否则互换后音乐栏离 dock 多出 12dp 空隙，
     *  与首次启动（正常模式）的观感不一致。悬浮 dock 样式下 dock 自身左移 8dp，需一并计入。 */
    private fun dockEdgeX(): Int {
        val dockLeft = if (UiTheme.dockStyle(context) == UiTheme.DockStyle.EDGE) 0 else dp(8)
        return dockLeft + dp(96) + dp(12)
    }
    /** 分割条已吸附到最右（音乐栏隐藏、地图铺满）；滞回阈值内保持吸附，防手指抖动来回跳 */
    private var dividerSnappedRight = false
    private var dividerLastMoveX = 0f
    /** 分割条调整模式：长按竖条后隐藏地图浮窗、只显示绑定地图应用图标，拖动期间生效 */
    private var dividerAdjustActive = false
    /** 调整模式期间显示的地图应用图标 overlay */
    private var iconOverlay: View? = null
    private var longPressPending = false
    private val longPressRunnable = Runnable { enterDividerAdjust() }
    /** 分割条正被触摸（DOWN~UP 之间），置顶 re-add 会断触摸流，需避开 */
    private var dividerTouching = false
    /** 翻页过渡中 loc 异常时，落位后重试定位（否则竖条会永远停在屏外） */
    private val dividerLocateRetry = Runnable { updateDividerPosition() }

    /** 分割条被触摸（按下/拖动）时回调，宿主用来重置"闲置自动进入负一屏"计时。
     *  分割条是独立悬浮窗，触摸不经过 Activity.dispatchTouchEvent，必须单独通知，
     *  否则用户开始拖动时闲置计时可能刚好到点，把页面切到负一屏、竖条中途消失。 */
    var onDividerInteract: (() -> Unit)? = null

    /** 分割条显隐变化回调（宿主用来联动底部互换横条：横条常驻、跟随分割条显隐） */
    var onDividerVisibilityChanged: ((Boolean) -> Unit)? = null

    /** 铺满时地图右缘距屏幕右缘的预留（px）：12dp。高德浮窗右缘比地图卡片外扩约 4px，
     *  剩下 8px 屏幕空隙恰好放 6dp 白线（上下各留 1px），浮窗尽量用足右侧空间；
     *  竖条热区（28dp）允许右侧探出屏外，不影响触摸（可见部分仍可点）。 */
    private val dividerRightReserve: Int get() = dp(12)

    /** 地图右缘允许的最大屏幕坐标（px）：屏幕宽 - 右侧预留。
     *  注意不能与 rightLimit（音乐栏贴边位置 = 屏幕宽-16dp）取 min：
     *  铺满时音乐栏已隐藏，地图应越过它用足右侧空间，否则浮窗右缘与屏幕之间
     *  会留一截没利用的空隙。分割条拖动、浮窗缩放吸附、隐藏音乐栏后的扩展都用它。 */
    fun mapRightLimit(): Int {
        return realScreenW - dividerRightReserve
    }

    /** 设置地图底部上限（px，屏幕坐标系）；系统 Dock 显示时调用，0=不限制 */
    fun setBottomLimit(px: Int) {
        bottomLimit = px
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        val sh = realScreenH
        if (px <= 0) {
            // 解除限制：恢复地图底边到屏幕底部（8dp 边距）。
            // 此前直接 return，地图保持被压缩的高度，导致高德浮窗底边永远停在系统栏上方无法超过。
            // 用布局参数（topMargin+height）计算，不依赖 getLocationOnScreen，
            // 保证在设置页（地图卡片离屏）切换开关时也能正确恢复。
            val maxBottomY = sh - dp(8)
            val bottomY = lp.topMargin + lp.height
            if (bottomY < maxBottomY) {
                val newH = (maxBottomY - lp.topMargin).coerceAtLeast(MIN_SIZE)
                if (lp.height != newH) {
                    lp.height = newH
                    mapPanel.layoutParams = lp
                    onGeometryChanged?.invoke()
                    refreshFloat()
                }
            }
            return
        }
        val bottomY = lp.topMargin + lp.height
        if (bottomY > px) {
            val newH = (px - lp.topMargin).coerceAtLeast(MIN_SIZE)
            if (lp.height != newH) {
                lp.height = newH
                mapPanel.layoutParams = lp
                onGeometryChanged?.invoke()
                refreshFloat()
            }
        }
    }

    /** 外部（如 dock 形态切换）导致地图卡片位置变化后，刷新高德浮窗几何并重发显示广播 */
    /** 是否允许显示悬浮窗（page0 允许；切到其它页 closeFloat 置 false，防止巡航等后台广播
     *  引起布局变化时 refreshFloat 又把浮窗拉回来） */
    private var floatEnabled = false

    fun refreshFloat() {
        // 等待布局完成后再取几何，否则 getLocationOnScreen / width 可能是旧值或过渡值
        // 用 OnLayoutChangeListener 确保在本次布局完成后取数；兜底 200ms 防止无布局变化时不触发
        mapPanel.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(v: android.view.View, left: Int, top: Int, right: Int, bottom: Int,
                                         oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                v.removeOnLayoutChangeListener(this)
                if (!floatEnabled) return
                primeCache()
                showFloat()
            }
        })
        mapPanel.postDelayed({
            if (!floatEnabled) return@postDelayed
            primeCache()
            showFloat()
        }, 200)
    }

    private enum class ResizeMode { NONE, RIGHT, BOTTOM, BOTH }

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        val base = File(context.cacheDir, "osm").apply { mkdirs() }
        Configuration.getInstance().osmdroidBasePath = base
        Configuration.getInstance().osmdroidTileCache = File(base, "tiles")
        loadGeometry()
    }

    val currentId: String? get() = current?.id

    /** 浮窗几何缓存：renderFloat 时取一次，之后永远用。
     *  ViewPager2 翻页后 Page 0 会 detach/attach，getLocationOnScreen 会返回错误值。 */
    private var cachedX = -1
    private var cachedY = -1
    private var cachedW = -1   // 边界格式（x + width）
    private var cachedH = -1   // 边界格式（y + height）

    fun start(sources: List<MapSource>, autoLaunch: Boolean = false) {
        // 优先高德浮窗；没有偏好时默认 EXTERNAL_FLOAT
        val defaultSrc = sources.firstOrNull { it.type == MapSource.Type.EXTERNAL_FLOAT }
            ?: sources.firstOrNull()
        val savedId = prefs.getString(KEY_SOURCE, null)
        val src = if (savedId != null) {
            sources.firstOrNull { it.id == savedId } ?: defaultSrc
        } else {
            defaultSrc
        }
        src?.let {
            prefs.edit { putString(KEY_SOURCE, it.id) }
            render(it)
            // 首次启动 NUI 时自动启动外部地图（高德），Activity 重建不重复执行
            if (autoLaunch && !autoLaunched) {
                scheduleLaunchAndReturnHome()
                autoLaunched = true
            }
        }
    }

    fun select(source: MapSource, autoLaunch: Boolean = false) {
        prefs.edit { putString(KEY_SOURCE, source.id) }
        render(source)
        // 用户主动切换/选择地图：每次都尝试启动外部地图（如高德），不受首次自动启动标记限制
        if (autoLaunch) {
            autoReturnPending = true
            scheduleLaunchAndReturnHome()
            // 同步置位，避免随后 Activity 重建时的 start() 再次自动启动造成重复
            autoLaunched = true
        }
    }

    /** 按配置的启动延迟启动外部地图（桌面启动后第 N 秒），随后按返回延迟回桌面 */
    private fun scheduleLaunchAndReturnHome() {
        autoReturnPending = true
        val launchDelayMs = UiTheme.mapLaunchDelaySec(context) * 1000L
        val task = Runnable { launchAndReturnHome() }
        if (launchDelayMs > 0) container.postDelayed(task, launchDelayMs) else task.run()
    }

    /**
     * 启动外部地图应用，延迟 [delayMs] 毫秒后返回桌面（HOME）。
     * 默认 delayMs = 返回时刻 - 启动时刻（如第10秒启动、第15秒回桌面，则等5秒）。
     * 回桌面后再恢复浮窗（高德浮窗需地图进程在运行才生效）。
     * 内置 OSM 无需启动外部应用，直接返回。
     * @return 是否成功发起启动
     */
    fun launchAndReturnHome(
        delayMs: Long = (UiTheme.mapReturnDelaySec(context) - UiTheme.mapLaunchDelaySec(context))
            .coerceAtLeast(1) * 1000L
    ): Boolean {
        val src = current ?: return false
        if (src.type == MapSource.Type.EMBEDDED_OSM) return false
        val launch = src.launchIntent ?: return false
        val started = runCatching { context.startActivity(launch) }.isSuccess
        if (!started) {
            Log.e(TAG, "launch external map failed: ${src.packageName}")
            // 启动失败：不再等待返回，结束"自动返回中"流程，避免天气首次播报被永久暂存
            if (autoReturnPending) {
                autoReturnPending = false
                onAutoReturnDone?.invoke()
            }
            return false
        }
        // 高德启动耗时不定：慢设备（如 32 位模拟器）上地图绘制完成可能晚于返回时刻，
        // 其绘制/初始化完成后会抢回前台。故首次返回后若被抢回，再补发一次夺回桌面。
        container.postDelayed({ goBackToNui(src, restoreFloat = true) }, delayMs)
        container.postDelayed({ goBackToNui(src, restoreFloat = false) }, delayMs + 2500L)
        return true
    }

    /** 回桌面：发 HOME intent 把已有的 home task（singleTask 的 MainActivity）带到前台，走 onNewIntent。
     *  不能用显式 Intent(context, MainActivity::class) + NEW_TASK——从高德等外部 task 启动时，
     *  系统在 standard task 重建新实例（singleTask 只在同 affinity task 内复用），反复自动返回会
     *  堆积僵尸实例；这些不可见实例仍注册广播、初始化天气/语音，还会抢先写状态导致可见桌面外观不跟随。
     *  Intent 带 [EXTRA_AUTO_BACK] 标记，让 MainActivity.onNewIntent 识别为"地图自动返回"，
     *  只保持当前 page，不触发桌面内 HOME 的 page0/page1 切换逻辑。 */
    private fun goBackToNui(src: MapSource, restoreFloat: Boolean) {
        Log.d(TAG, "goBackToNui restoreFloat=$restoreFloat")
        val back = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_AUTO_BACK, true)
        }
        runCatching { context.startActivity(back) }
        // 首次回 NUI：自动启动流程完成，通知外部（用于延迟天气首次播报等）
        if (restoreFloat) {
            if (autoReturnPending) {
                autoReturnPending = false
                onAutoReturnDone?.invoke()
            }
            // 回 NUI 后再恢复浮窗
            if (src.type == MapSource.Type.EXTERNAL_FLOAT) {
                container.postDelayed({ showFloat() }, 500L)
            }
        }
    }

    private fun render(source: MapSource) {
        current = source
        destroyMap()
        container.removeAllViews()
        when (source.type) {
            MapSource.Type.EMBEDDED_OSM -> renderOsm()
            MapSource.Type.EXTERNAL -> renderExternal(source)
            MapSource.Type.EXTERNAL_FLOAT -> renderFloat(source)
        }
    }

    private fun renderOsm() {
        val mv = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            setUseDataConnection(true)
            isHorizontalMapRepetitionEnabled = true
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(39.9042, 116.4074))
        }
        container.addView(
            mv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        mapView = mv
    }

    private fun renderExternal(source: MapSource) {
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(context).apply {
            setImageDrawable(source.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        v.addView(icon, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(12) })
        val name = TextView(context).apply {
            text = source.label
            setTextColor(Color.parseColor("#ECEFF1"))
            textSize = 20f
            gravity = Gravity.CENTER
        }
        v.addView(name)
        val hint = TextView(context).apply {
            text = "点击进入地图"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        v.addView(hint)
        v.setOnClickListener {
            source.launchIntent?.let {
                runCatching { context.startActivity(it) }.onFailure {
                    NuiToast.show(context, "无法启动 ${source.label}", Toast.LENGTH_SHORT)
                }
            }
        }
        container.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun renderFloat(source: MapSource) {
        if (source.floatShowAction == null) return
        container.post {
            primeCache()
            sendFloatBroadcast(source.floatShowAction)
        }
    }

    private fun primeCache() {
        val loc = IntArray(2)
        mapPanel.getLocationOnScreen(loc)
        val sw = context.resources.displayMetrics.widthPixels
        val sh = context.resources.displayMetrics.heightPixels
        // 合理性检查：视图必须在屏幕内，否则可能是布局过渡中取到的错误坐标，跳过更新
        if (loc[0] < 0 || loc[0] >= sw || loc[1] < 0 || loc[1] >= sh ||
            mapPanel.width <= 0 || mapPanel.height <= 0) {
            Log.w(TAG, "primeCache skip: loc=${loc[0]},${loc[1]} size=${mapPanel.width}x${mapPanel.height}")
            return
        }
        cachedX = loc[0]
        cachedY = loc[1]
        cachedW = cachedX + mapPanel.width
        cachedH = cachedY + mapPanel.height
    }

    private fun sendFloatBroadcast(action: String?) {
        if (action == null) return
        var x = cachedX; var y = cachedY; var w = cachedW; var h = cachedH
        if (w <= 0) {
            val loc = IntArray(2)
            container.getLocationOnScreen(loc)
            x = loc[0]; y = loc[1]
            w = x + container.width; h = y + container.height
            if (container.width <= 0 || container.height <= 0) return
            cachedX = x; cachedY = y; cachedW = w; cachedH = h
        }
        Log.d(TAG, "sendFloat: x=$x y=$y w(border)=$w h(border)=$h (cached: $cachedX,$cachedY,$cachedW,$cachedH)")
        // 浮窗边界比 mapPanel 四边外扩 4px：防浮窗比卡片窄时边缘露底。
        // 注意不要内缩：开关浮窗像素对比实测浮窗右缘外壁纸亮度与无浮窗时完全一致，
        // 高德浮窗并无深色边/投影；浅色模式下的竖向阴影来自分割条热区黑底（已改透明）。
        val inset = 4
        val intent = Intent(action).apply {
            putExtra("x", x - inset)
            putExtra("y", y - inset)
            putExtra("w", w + inset)
            putExtra("h", h + inset)
            // 关键：setPackage 只发给地图 App，否则酷我等其它 App 也会收到这个广播并按相同坐标创建自己的 FloatApp，覆盖在高德浮窗上
            current?.packageName?.let { setPackage(it) }
        }
        runCatching { context.sendBroadcast(intent) }
    }

    /** 悬浮地图几何（边界格式 x1,y1,x2,y2），未显示过返回 null */
    fun floatBounds(): IntArray? =
        if (cachedX < 0 || cachedW <= cachedX || cachedH <= cachedY) null
        else intArrayOf(cachedX, cachedY, cachedW, cachedH)

    fun closeFloat() {
        floatEnabled = false
        val closeAction = current?.floatCloseAction ?: return
        runCatching { context.sendBroadcast(Intent(closeAction)) }
    }

    fun showFloat() {
        floatEnabled = true
        current?.takeIf { it.type == MapSource.Type.EXTERNAL_FLOAT }
            ?.let { src ->
                container.post {
                    sendFloatBroadcast(src.floatShowAction)
                    container.postDelayed({ onFloatShown?.invoke() }, 600)
                    // 高德浮窗是后加的 APPLICATION_OVERLAY，会盖在竖条之上。
                    // 延迟把竖条置顶回来，保证可点（避开拖动中：dividerTouching/AdjustActive 守卫）。
                    container.postDelayed({
                        if (dividerShown && !dividerTouching && !dividerAdjustActive) bringDividerToFront()
                    }, 800)
                }
            }
    }

    fun onResume() {
        mapView?.onResume()
    }

    /** 离开桌面：无 pending 逻辑（保留空实现兼容调用方） */
    fun cancelPendingShow() {
        // no-op
    }

    /** 回到桌面：恢复高德悬浮窗（保留简单实现，兼容调用方） */
    fun resumeFloat() {
        showFloat()
    }

    fun onPause() {
        Log.d(TAG, "onPause: close float")
        mapView?.onPause()
        closeFloat()
        // 分割条是独立悬浮窗（TYPE_APPLICATION_OVERLAY），Activity 退到后台不会自动消失。
        // 从 dock/应用列表启动其它应用后不隐藏的话，竖条会浮在被启动的应用之上。
        setDividerVisible(false)
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy")
        destroyMap()
    }

    private fun destroyMap() {
        closeFloat()
        mapView?.let { it.onDetach(); mapView = null }
    }

    // ==================== 调整模式：边缘拖拽缩放（左/顶固定）====================
    //  - 左边、顶边固定不动，不允许拖动位移
    //  - 从右边缘条 拖动 → 变宽/变窄
    //  - 从下边缘条 拖动 → 变高/变矮
    //  - 从右下角    拖动 → 同时变宽变高

    private val edgeZone: Int get() = dp(EDGE_ZONE_DP)

    /** 长按非悬浮区触发。进入：关闭高德浮窗 + 显示带边缘条的 overlay；已在调整则退出。 */
    fun isAdjustMode() = adjustMode

    /** 当前绑定的外部地图应用包名（内置 OSM 时返回 null），用于 dock 固定槽位 */
    fun currentMapPackage(): String? = current?.packageName

    fun toggleAdjust() {
        if (adjustMode) { exitAdjust(); return }
        if (!Settings.canDrawOverlays(context)) {
            NuiToast.show(context, "需要悬浮窗权限，授权后重试", Toast.LENGTH_LONG)
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return
        }
        enterAdjust()
    }

    private fun enterAdjust() {
        if (adjustMode) return
        adjustMode = true
        closeFloat()
        val loc = IntArray(2)
        mapPanel.getLocationOnScreen(loc)
        val lp0 = mapPanel.layoutParams as FrameLayout.LayoutParams
        startLeft = lp0.leftMargin
        startTop = lp0.topMargin
        startWidth = mapPanel.width
        startHeight = mapPanel.height

        val overlay = FrameLayout(context).apply {
                setBackgroundColor(0x00000000) // 透明底，仅地图区域上色
                // 地图区域淡蓝底（提示调整区域）
                addView(View(context).apply {
                    setBackgroundColor(0x332196F3.toInt())
                }, FrameLayout.LayoutParams(startWidth, startHeight).apply {
                    leftMargin = startLeft
                    topMargin = startTop
                })
                // 左上角透明点击热区（与原 btnSwitchMap 位置一致：40dp 图标 + 10dp margin）
                addView(View(context).apply {
                    setBackgroundColor(0x00000000)
                    setOnClickListener {
                        adjustOverlay?.let { runCatching { wm.removeView(it) } }
                        adjustOverlay = null
                        adjustMode = false
                        resizeMode = ResizeMode.NONE
                        saveGeometry()
                        closeFloat()
                        onPickMap?.invoke()
                    }
                }, FrameLayout.LayoutParams(
                    (40 * context.resources.displayMetrics.density).toInt(),
                    (40 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = startLeft + (10 * context.resources.displayMetrics.density).toInt()
                    topMargin = startTop + (10 * context.resources.displayMetrics.density).toInt()
                })
                // 右边缘条（可视化 + 热区）
                addView(View(context).apply {
                    background = ColorDrawable(0xFF1976D2.toInt()) // 深青蓝实条
                }, FrameLayout.LayoutParams(edgeZone, startHeight).apply {
                    leftMargin = startLeft + startWidth - edgeZone
                    topMargin = startTop
                })
                // 下边缘条
                addView(View(context).apply {
                    background = ColorDrawable(0xFF1976D2.toInt())
                }, FrameLayout.LayoutParams(startWidth, edgeZone).apply {
                    leftMargin = startLeft
                    topMargin = startTop + startHeight - edgeZone
                })
                // 右下角把手
                addView(View(context).apply {
                    background = ColorDrawable(0xFF0D47A1.toInt()) // 最深蓝
                }, FrameLayout.LayoutParams(edgeZone, edgeZone).apply {
                    leftMargin = startLeft + startWidth - edgeZone
                    topMargin = startTop + startHeight - edgeZone
                })
                setOnTouchListener(adjustTouch)
            }
            val lp = WindowManager.LayoutParams().apply {
                type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                format = PixelFormat.TRANSLUCENT
                // NO_LIMITS：让 overlay 覆盖整个物理屏幕（含系统导航栏/Dock 区域），
                // 否则非沉浸时 overlay 被限制在可用区域，地图底部拖不进系统栏区域
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                width = realScreenW
                height = realScreenH
            }
        runCatching { wm.addView(overlay, lp) }
            .onFailure {
                Log.e(TAG, "addView overlay failed", it)
                adjustMode = false
                showFloat()  // 失败了恢复高德，不要让用户看不到地图
                return
            }
        adjustOverlay = overlay
        NuiToast.show(context, "从右/下边缘或右下角拖，放手完成", Toast.LENGTH_LONG)
    }

    private val adjustTouch = View.OnTouchListener { _, e ->
        val ov = adjustOverlay ?: return@OnTouchListener false
        val lp = ov.layoutParams as WindowManager.LayoutParams
        val ez = edgeZone
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = e.rawX
                dragStartY = e.rawY
                lastMoveX = e.rawX
                snappedToRight = false
                // startWidth/startHeight 已在 enterAdjust 记录为地图区域大小
                // 根据按下位置决定是调右 / 调下 / 同时调右下（相对地图区域）
                val relX = e.rawX - startLeft
                val relY = e.rawY - startTop
                val onRight = relX >= startWidth - ez
                val onBottom = relY >= startHeight - ez
                resizeMode = when {
                    onRight && onBottom -> ResizeMode.BOTH
                    onRight -> ResizeMode.RIGHT
                    onBottom -> ResizeMode.BOTTOM
                    else -> ResizeMode.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && resizeMode != ResizeMode.NONE) {
                    val dx = (e.rawX - dragStartX).toInt()
                    val dy = (e.rawY - dragStartY).toInt()
                    var nw = startWidth
                    var nh = startHeight
                    if (resizeMode == ResizeMode.RIGHT || resizeMode == ResizeMode.BOTH) nw += dx
                    if (resizeMode == ResizeMode.BOTTOM || resizeMode == ResizeMode.BOTH) nh += dy

                    val effectiveRight = mapRightLimit()
                    val snapWidth = effectiveRight - startLeft

                    if (snappedToRight) {
                        // 已吸附到最右端：保持宽度，直到向左拖超过阈值才退出（滞回，防手指抖动跳动）
                        if (dragStartX - e.rawX > snapExitThreshold) {
                            snappedToRight = false
                        } else {
                            nw = snapWidth
                        }
                    } else {
                        // 未吸附：仅向右拖动时检测吸附阈值
                        if (e.rawX > lastMoveX) {
                            val remainingRight = effectiveRight - (startLeft + nw)
                            if (remainingRight < dp(MUSIC_PANEL_MIN_WIDTH_DP + MUSIC_GAP_DP)) {
                                snappedToRight = true
                                nw = snapWidth
                                // 更新基准：使退出吸附后从当前吸附宽度继续计算，避免跳变
                                startWidth = snapWidth
                                dragStartX = e.rawX
                            }
                        }
                    }
                    lastMoveX = e.rawX

                    val c = clampSizeFixed(startLeft, startTop, nw, nh)
                    lp.width = c[2]
                    lp.height = c[3]
                    runCatching { wm.updateViewLayout(ov, lp) }
                    applyGeometry(null, null, c[2], c[3])
                }
            }
            MotionEvent.ACTION_UP -> exitAdjust()
        }
        true
    }

        fun exitAdjust() {
        adjustMode = false
        adjustOverlay?.let { runCatching { wm.removeView(it) } }
        adjustOverlay = null
        resizeMode = ResizeMode.NONE
        saveGeometry()
        // 同步外部联动（右侧音乐栏宽度等），确保最终几何落定后对齐
        onGeometryChanged?.invoke()
        showFloat()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ NuiToast.show(context, "已应用新大小", Toast.LENGTH_SHORT) }, 300L)
    }

    /** 按固定 x/y 限制宽高最大值（左/顶不动，只约束右侧/底部不越界）。
     *  右面板的显示/隐藏由外部 syncRightPanel 根据剩余空间自动处理，
     *  这里地图右边可铺到屏幕宽 - 20dp。 */
    private fun clampSizeFixed(x: Int, y: Int, w: Int, h: Int): IntArray {
        val sw = realScreenW
        val sh = realScreenH
        // 右侧：地图右缘上限 = 屏幕宽 - 分割条预留（铺满时音乐栏已隐藏，可越过音乐栏贴边位置）
        val maxRight = mapRightLimit()
        // 底部：系统 Dock 显示时不超过 bottomLimit（避开导航栏），否则不碰到底边 8dp
        val maxBottom = if (bottomLimit > 0) bottomLimit else sh - dp(8)
        var nw = w.coerceAtLeast(MIN_SIZE)
        var nh = h.coerceAtLeast(MIN_SIZE)
        if (x + nw > maxRight) nw = (maxRight - x).coerceAtLeast(MIN_SIZE)
        if (y + nh > maxBottom) nh = (maxBottom - y).coerceAtLeast(MIN_SIZE)
        return intArrayOf(x, y, nw, nh)
    }

    private fun applyGeometry(x: Int?, y: Int?, w: Int?, h: Int?) {
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        if (x != null) lp.leftMargin = x
        if (y != null) lp.topMargin = y
        if (w != null) lp.width = w
        if (h != null) lp.height = h
        mapPanel.layoutParams = lp
        mapPanel.post { updateDividerPosition() }
        onGeometryChanged?.invoke()
    }

    /** 外部几何联动完成后刷新分割条位置（公开，供 MainActivity 在 onGeometryChanged 调用） */
    fun refreshDivider() = mapPanel.post { updateDividerPosition() }

    private fun loadGeometry() {
        // 一次性重置旧版地图几何（dock 变窄后旧位置离 dock 太远）
        val geomV2 = prefs.getBoolean(KEY_GEOM_V2, false)
        Log.d(TAG, "loadGeometry: KEY_GEOM_V2=$geomV2, KEY_GEOMETRY=${prefs.getString(KEY_GEOMETRY, null)}")
        if (!geomV2) {
            prefs.edit { remove(KEY_GEOMETRY); putBoolean(KEY_GEOM_V2, true) }
            Log.d(TAG, "loadGeometry: 首次启动/版本升级，已清除旧几何数据")
        }
        val g = prefs.getString(KEY_GEOMETRY, null)
        if (g != null) {
            val p = g.split(',').mapNotNull { it.toIntOrNull() }
            if (p.size == 4) {
                val c = clampSizeFixed(p[0], p[1], p[2], p[3])
                Log.d(TAG, "loadGeometry: 从 SharedPreferences 读取几何 x=${c[0]} y=${c[1]} w=${c[2]} h=${c[3]}")
                applyGeometry(c[0], c[1], c[2], c[3])
                normalizeVerticalMargins()
                geometryLoaded = true
                return
            }
        }
        // 默认位置：根据屏幕分辨率精确计算，确保悬浮地图和音乐栏都能看到
        // 布局公式（与 MainActivity.syncRightPanel 保持一致）：
        //   屏幕宽度 = 左边距 + 地图宽度 + 间距 + 音乐栏宽度 + 右边距
        //   音乐栏默认 240dp（大于最小 180dp，保证舒适可见）
        val x = dockEdgeX()          // dock 右缘 + 12dp，不遮盖 dock 栏
        val y = dp(8)
        val sw = realScreenW
        val sh = realScreenH
        val density = context.resources.displayMetrics.density
        val musicPanelW = dp(240)   // 音乐栏默认宽度（>180dp 最小阈值，保证可见）
        val gap = dp(12)             // 地图与音乐栏之间的间距
        val rightMargin = dp(8)      // 屏幕右边距（与 rightPanel marginEnd 一致）
        // 地图宽度 = 屏幕宽度 - 左边距 - 间距 - 音乐栏宽度 - 右边距
        val defaultW = (sw - x - gap - musicPanelW - rightMargin).coerceAtLeast(MIN_SIZE)
        val defaultH = (sh - y - dp(16)).coerceAtLeast(MIN_SIZE)   // 上下各 8dp
        Log.d(TAG, "loadGeometry: 使用默认几何 sw=$sw sh=$sh density=$density x=$x y=$y defaultW=$defaultW defaultH=$defaultH (音乐栏应=$musicPanelW)")
        val c = clampSizeFixed(x, y, defaultW, defaultH)
        applyGeometry(c[0], c[1], c[2], c[3])
        geometryLoaded = true
        // 布局完成后再次确认宽度生效（防止 syncRightPanel 在布局测量前覆盖）
        mapPanel.post {
            val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
            if (lp.width != c[2]) {
                Log.d(TAG, "loadGeometry: post 修正宽度 ${lp.width} -> ${c[2]}")
                lp.width = c[2]
                mapPanel.layoutParams = lp
                onGeometryChanged?.invoke()
            }
        }
    }

    /** 强制地图上下边距为 8dp（保留左右位置和宽度），避免上下留空过大 */
    private fun normalizeVerticalMargins() {
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        val sh = realScreenH
        val targetTop = dp(8)
        val targetH = (sh - targetTop - dp(8)).coerceAtLeast(MIN_SIZE)
        if (lp.topMargin != targetTop || lp.height != targetH) {
            lp.topMargin = targetTop
            lp.height = targetH
            mapPanel.layoutParams = lp
            onGeometryChanged?.invoke()
        }
    }

    private fun saveGeometry() {
        primeCache()  // 刷新缓存，否则 showFloat 会用旧坐标
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        prefs.edit { putString(KEY_GEOMETRY, "${lp.leftMargin},${lp.topMargin},${lp.width},${lp.height}") }
    }

    /** 分割条拖动完成后保存当前几何（供外部 UI 调用，复用 saveGeometry 逻辑） */
    fun saveGeometryForDivider() = saveGeometry()

    // ==================== 常驻悬浮分割条（类似 iOS 分屏竖条）====================
    // 高德浮窗（TYPE_APPLICATION_OVERLAY）覆盖 mapPanel 右缘，布局内 view 会被其遮挡，
    // 故分割条用独立的悬浮 overlay，z 序高于高德浮窗，常驻显示、可拖动。

    fun showDivider() {
        if (dividerShown && dividerOverlay != null) {
            // 已存在：仅更新位置（竖条在高德浮窗外，无需置顶）
            updateDividerPosition()
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        dividerShown = true
        ensureDivider()
        updateDividerPosition()
        // 延迟重试：setupMap 调用时 mapPanel 可能尚未布局完成（loc 异常 → x=-10000），
        // 等布局稳定后再定位一次。
        mapPanel.postDelayed({ updateDividerPosition() }, 500)
        mapPanel.postDelayed({ updateDividerPosition() }, 1500)
    }

    /** 切换分割条可见性：非 page0 时直接移出屏幕（x=-10000），不 removeView 不 GONE，
     *  保持 overlay 稳定存在，避免反复创建/销毁导致丢失。page0 时定位到音乐栏左缘。 */
    fun setDividerVisible(visible: Boolean) {
        if (visible) {
            if (dividerOverlay == null) {
                showDivider()
            } else {
                dividerOverlay?.visibility = View.VISIBLE
                dividerShown = true
                updateDividerPosition()
                // 重新显示时置顶到高德浮窗之上（浮窗同为 APPLICATION_OVERLAY、后加在上）
                if (!dividerTouching && !dividerAdjustActive) bringDividerToFront()
            }
            onDividerVisibilityChanged?.invoke(true)
        } else {
            dividerShown = false
            dividerAdjustActive = false
            dividerOverlay?.let {
                val lp = it.layoutParams as WindowManager.LayoutParams
                lp.x = -10000
                runCatching { wm.updateViewLayout(it, lp) }
            }
            hideDividerIcon()
            onDividerVisibilityChanged?.invoke(false)
        }
    }

    fun hideDivider() {
        dividerShown = false
        dividerAdjustActive = false
        dividerOverlay?.let { runCatching { wm.removeView(it) } }
        dividerOverlay = null
        dividerWhiteLine = null
        hideDividerIcon()
        onDividerVisibilityChanged?.invoke(false)
    }

    fun isDividerShown() = dividerShown

    private fun ensureDivider() {
        if (dividerOverlay != null) return
        val barW = dp(DIVIDER_BAR_W_DP)
        val bar = FrameLayout(context).apply {
            // 触摸热区保持全透明：此前有 13% 黑底（0x22000000）"提示可拖动区域"，
            // 但浅色模式下它是地图与音乐栏交界处一条明显的竖向暗带（被误认为阴影）。
            // 可视提示由中央灰色细条承担，热区本身不需要可见。
            addView(View(context), FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(View(context).apply {
                // 用代码创建细条背景，避免 release 下 getIdentifier 找不到 drawable
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(3).toFloat()
                    setColor(0xE6AAAAAA.toInt())   // 灰色（原白色太扎眼）
                }
            }, FrameLayout.LayoutParams(
                dp(6),
                dp(120),
                Gravity.CENTER_VERTICAL,
            ).apply {
                // 竖条整体（触摸热区）中心贴音乐栏左缘，保证大部分落在高德浮窗右侧可点；
                // 白色细条左移半个间隙并补偿高德浮窗外扩的 4px，使其对齐
                // "地图浮窗右缘 ~ 音乐栏左缘" 之间可见间隙的中央。
                // swapped 模式（音乐栏在左）下白线位置由 updateDividerPosition 动态计算。
                val whiteW = dp(6)
                val overhangDp = (4 / context.resources.displayMetrics.density).toInt()
                leftMargin = barW / 2 - whiteW / 2 - dp(MUSIC_GAP_DP) / 2 + overhangDp / 2
            })
        }
        // 保留白线引用：swapped 切换时 updateDividerPosition 需动态改其 leftMargin
        dividerWhiteLine = (bar.getChildAt(1))
        val lp = WindowManager.LayoutParams(
            barW,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        lp.width = barW
        lp.height = realScreenH
        bar.setOnTouchListener(dividerTouch)
        try {
            wm.addView(bar, lp)
            dividerOverlay = bar
        } catch (e: Exception) {
            Log.e(TAG, "ensureDivider: addView FAILED", e)
            dividerOverlay = null
        }
    }

    /** 把分割条重新 addView 置顶（高德浮窗同为 TYPE_APPLICATION_OVERLAY、后加会在上）。
     *  切回桌面/拖动前调用，确保分割条悬浮于地图浮窗之上可见可拖。 */
    private fun bringDividerToFront() {
        val bar = dividerOverlay ?: return
        val oldLp = bar.layoutParams as WindowManager.LayoutParams
        runCatching { wm.removeView(bar) }
        val lp = WindowManager.LayoutParams(oldLp.width, oldLp.height, oldLp.type,
            oldLp.flags, oldLp.format).apply {
            gravity = oldLp.gravity; x = oldLp.x; y = oldLp.y
        }
        runCatching { wm.addView(bar, lp) }
    }

    /** 分割条贴音乐栏左缘（地图与音乐栏的实际边界），用屏幕坐标定位。
     *  注意：竖条中央对齐 musicLeft，白色细条（固定偏移）恰好落在高德浮窗右缘之外；
     *  铺满（音乐栏隐藏）时白线落在 [浮窗右缘, 屏幕右缘] 的空隙中央，热区部分探出屏外。 */
    private fun updateDividerPosition() {
        val bar = dividerOverlay ?: return
        // 已隐藏（非桌面页/退到后台）时不再定位，否则任何几何刷新都会把它拉回屏幕
        if (!dividerShown) return
        val lp = bar.layoutParams as WindowManager.LayoutParams
        val loc = IntArray(2)
        mapPanel.getLocationOnScreen(loc)
        val sw = realScreenW
        // mapPanel 只在桌面页存在且布局正常；非桌面页（负一屏/应用列表）mapPanel 不可见，
        // getLocationOnScreen 返回异常（0 或屏幕外），此时把分割条移出屏幕。
        if (loc[0] <= 0 || loc[0] >= sw || mapPanel.width <= 0) {
            if (lp.x != -10000) {
                lp.x = -10000
                runCatching { wm.updateViewLayout(bar, lp) }
            }
            // 翻页过渡中（页面还在滑动、未落位）loc 会短暂异常；此时若不做延迟重试，
            // 竖条会永远停在 -10000，回到桌面也不出现。落位后（600ms）再定位一次；
            // 仍异常（非桌面页已隐藏 → dividerShown=false）时重试会自动空转，不会死循环。
            bar.removeCallbacks(dividerLocateRetry)
            bar.postDelayed(dividerLocateRetry, 600)
            return
        }
        val gap = dp(MUSIC_GAP_DP)
        // 竖条中央对齐"音乐栏与地图的边界"：正常模式 = mapPanel 右缘 + gap；互换模式 = mapPanel 左缘 - gap。
        // 不能钳制到屏内：铺满时若钳制，白线会被拉进高德浮窗内部造成重叠；热区探出屏外无碍触摸。
        val boundary = if (swapped) loc[0] - gap else loc[0] + mapPanel.width + gap
        val targetX = (boundary - lp.width / 2).coerceAtLeast(0)
        if (lp.x != targetX) {
            lp.x = targetX
            runCatching { wm.updateViewLayout(bar, lp) }
        }
        // 白线水平位置：白线应落在地图与音乐栏之间的可见间隙正中（相对热区的 leftMargin）。
        // 正常模式：间隙 = 浮窗右缘(卡右+4) ~ 面板左缘(卡右+12)，中心 = 卡右+8 = +gap/2 + 2；
        // 互换模式：间隙 = 面板右缘(卡左-12) ~ 浮窗左缘(卡左-4)，中心 = 卡左-8 = -gap/2 - 2。
        dividerWhiteLine?.let { wl ->
            val wlLp = wl.layoutParams as FrameLayout.LayoutParams
            val whiteW = dp(6)
            val lineCenterAbs = if (swapped) {
                loc[0] - gap / 2 - 2
            } else {
                loc[0] + mapPanel.width + gap / 2 + 2
            }
            val wantMargin = (lineCenterAbs - targetX - whiteW / 2).coerceAtLeast(0)
            if (wlLp.leftMargin != wantMargin) {
                wlLp.leftMargin = wantMargin
                wl.layoutParams = wlLp
            }
        }
    }

    /** 拖动分割条 → 调整地图宽度（高度不变），音乐栏宽度由外部 syncRightPanel 联动。
     *  长按（或按下即拖动）进入调整模式：先隐藏地图浮窗、只显示绑定地图应用图标，
     *  拖动过程实时改宽度，松手保存几何并恢复地图浮窗。 */
    private val dividerTouch = View.OnTouchListener { bar, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 注意：不能在 DOWN 里 removeView/addView 分割条（置顶会中断触摸流，
                // 后续 MOVE 会被派发给下层 ViewPager 导致误切页）。置顶只发生在非触摸时机。
                dividerTouching = true
                onDividerInteract?.invoke()
                val lpDown = mapPanel.layoutParams as FrameLayout.LayoutParams
                dividerStartX = e.rawX
                dividerStartMapW = lpDown.width
                dividerStartLeft = lpDown.leftMargin
                dividerStartRight = lpDown.leftMargin + lpDown.width
                dividerLastMoveX = e.rawX
                // 起点已是铺满状态（音乐栏隐藏、地图占满）时保持吸附，避免一按下就抖动
                // 正常模式吸附右端（宽度=limitW）；互换模式吸附左端（leftMargin=左边距）
                dividerSnappedRight = if (swapped) {
                    dividerStartLeft <= dockEdgeX() + 1
                } else {
                    val limitW0 = mapRightLimit() - lpDown.leftMargin
                    dividerStartMapW >= limitW0 - 1
                }
                longPressPending = true
                bar.removeCallbacks(longPressRunnable)
                bar.postDelayed(longPressRunnable, LONG_PRESS_MS)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // 长按前就开始移动（快速拖动）→ 同样进入调整模式
                onDividerInteract?.invoke()
                if (!dividerAdjustActive && longPressPending) enterDividerAdjust()
                if (dividerAdjustActive) {
                    val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
                    if (swapped) {
                        // ===== 互换模式：音乐栏在左，地图在右。拖动手条改变地图左缘（左Margin），右缘固定。 =====
                        val leftEdge = dockEdgeX()   // 左侧可用边界：dock 右侧（铺满/音乐栏贴边都不能压 dock）
                        // 还能容下音乐栏时的最大 mapLeft：leftEdge + gap + minPanel
                        val keepPanelMaxLeft = leftEdge + dp(MUSIC_GAP_DP) + dp(MUSIC_PANEL_MIN_WIDTH_DP)
                        var newLeft = dividerStartLeft + (e.rawX - dividerStartX).toInt()
                        if (dividerSnappedRight) {
                            // 已吸附铺满：保持铺满，明确向右拖过阈值才退出（滞回，防手指抖动来回跳）
                            if (e.rawX - dividerStartX > snapExitThreshold) {
                                dividerSnappedRight = false
                                newLeft = keepPanelMaxLeft
                                dividerStartLeft = keepPanelMaxLeft
                                dividerStartX = e.rawX
                            } else {
                                newLeft = leftEdge
                            }
                        } else if (e.rawX < dividerLastMoveX && newLeft < keepPanelMaxLeft) {
                            // 向左拖且会挤掉音乐栏 → 吸附铺满
                            dividerSnappedRight = true
                            newLeft = leftEdge
                            dividerStartLeft = leftEdge
                            dividerStartX = e.rawX
                        }
                        dividerLastMoveX = e.rawX
                        newLeft = newLeft.coerceIn(leftEdge, dividerStartRight - MIN_SIZE)
                        val newW = dividerStartRight - newLeft
                        if (newLeft != lp.leftMargin) {
                            applyGeometry(newLeft, null, newW, null)
                            updateDividerPosition()
                            updateDividerIconPosition()
                        }
                    } else {
                        // ===== 正常模式：地图在左，音乐栏在右。拖动手条改变地图宽度，左缘固定。 =====
                        // 地图右缘上限（音乐栏隐藏后地图可铺满到这里；已扣除竖条所需右侧预留）
                        val limitW = (mapRightLimit() - lp.leftMargin).coerceAtLeast(MIN_SIZE)
                        // 还能容下音乐栏时的地图最大宽度，超过就会被 syncRightPanel 判定为"音乐栏太窄"而隐藏
                        val keepPanelMaxW = (limitW - dp(MUSIC_GAP_DP) - dp(MUSIC_PANEL_MIN_WIDTH_DP))
                            .coerceAtLeast(MIN_SIZE)
                        var newW = dividerStartMapW + (e.rawX - dividerStartX).toInt()
                        if (dividerSnappedRight) {
                            // 已吸附铺满：保持铺满，明确向左拖过阈值才退出（滞回，防手指抖动来回跳）。
                            // 退出时直接落到"音乐栏刚好还能显示"的宽度（而不是让地图缩到中间、音乐栏还没
                            // 回来的空白区），并重置基准，之后继续向左拖就是平滑收缩、音乐栏同步变宽。
                            if (dividerStartX - e.rawX > snapExitThreshold) {
                                dividerSnappedRight = false
                                newW = keepPanelMaxW
                                dividerStartMapW = keepPanelMaxW
                                dividerStartX = e.rawX
                            } else {
                                newW = limitW
                            }
                        } else if (e.rawX > dividerLastMoveX && newW > keepPanelMaxW) {
                            // 向右拖且会挤掉音乐栏 → 吸附铺满。必须在这里一步到位：
                            // syncRightPanel 会一边隐藏音乐栏一边把地图拉满，慢拖时和拖动逐帧互相打架，
                            // 结果就是"音乐栏没了但右边留空、还拖不动"。吸附后重置基准，退出吸附不跳变。
                            dividerSnappedRight = true
                            newW = limitW
                            dividerStartMapW = limitW
                            dividerStartX = e.rawX
                        }
                        dividerLastMoveX = e.rawX
                        newW = newW.coerceIn(MIN_SIZE, limitW)
                        if (newW != lp.width) {
                            lp.width = newW
                            mapPanel.layoutParams = lp
                            applyGeometry(null, null, newW, null)
                            updateDividerPosition()
                            updateDividerIconPosition()
                        }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                bar.removeCallbacks(longPressRunnable)
                longPressPending = false
                dividerTouching = false
                if (dividerAdjustActive) exitDividerAdjust()
                // 松手后再触发一次几何回调：此时布局已稳定，音乐浮窗按最终几何重发一次
                onGeometryChanged?.invoke()
                // 松手后高德浮窗会重新出现（后加在竖条之上），延迟把竖条置顶回来，
                // 保证下一次还能在浮窗之上抓到竖条。
                bar.postDelayed({ if (!dividerTouching && !dividerAdjustActive) bringDividerToFront() }, 350)
                true
            }
            else -> false
        }
    }

    /** 进入调整模式：隐藏地图浮窗（拖动过程不实时渲染地图、性能优先），显示绑定地图应用图标占位 */
    private fun enterDividerAdjust() {
        if (dividerAdjustActive) return
        dividerAdjustActive = true
        closeFloat()
        showDividerIcon()
    }

    /** 退出调整模式：隐藏图标、保存新几何、恢复地图浮窗 */
    private fun exitDividerAdjust() {
        if (!dividerAdjustActive) return
        dividerAdjustActive = false
        hideDividerIcon()
        saveGeometryForDivider()
        // 快速拖动松手后，最后一帧的联动（音乐栏宽度/浮窗几何）可能停在中途状态
        //（表现为地图与音乐栏之间露出一截间隙，切页/时间刷新后才恢复）。
        // 主动刷新两次兜底：post 等布局完成后刷新一次，300ms 后再刷一次，
        // 等价于用户手动"切一下 page / 等时间更新"的恢复效果。
        mapPanel.post {
            if (dividerAdjustActive) return@post
            primeCache()
            showFloat()
            onGeometryChanged?.invoke()
        }
        mapPanel.postDelayed({
            if (!dividerAdjustActive) onGeometryChanged?.invoke()
        }, 300)
    }

    /** 调整模式期间在地图区域中央显示绑定地图应用的图标（悬浮 overlay）。
     *  直接铺满图标本体，不套白色卡片——套卡片会让图标显得很小、外圈多一个矩形。 */
    private fun showDividerIcon() {
        if (iconOverlay != null) return
        val icon = current?.icon ?: return
        val view = ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val size = dp(DIVIDER_ICON_DP)
        val lp = WindowManager.LayoutParams(
            size, size,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        runCatching { wm.addView(view, lp) }
            .onFailure { Log.e(TAG, "addView divider icon failed", it); return }
        iconOverlay = view
        updateDividerIconPosition()
    }

    /** 图标保持在地图区域中央（拖动中地图变宽/变窄时跟随居中） */
    private fun updateDividerIconPosition() {
        val icon = iconOverlay ?: return
        val lp = icon.layoutParams as WindowManager.LayoutParams
        val loc = IntArray(2)
        mapPanel.getLocationOnScreen(loc)
        if (loc[0] <= 0 || loc[0] >= realScreenW || mapPanel.width <= 0) return
        lp.x = loc[0] + (mapPanel.width - lp.width) / 2
        lp.y = loc[1] + (mapPanel.height - lp.height) / 2
        runCatching { wm.updateViewLayout(icon, lp) }
    }

    private fun hideDividerIcon() {
        iconOverlay?.let { runCatching { wm.removeView(it) } }
        iconOverlay = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MapHost"
        private const val PREFS = "nui_map"
        private const val KEY_SOURCE = "float_map_source_id"
        private const val KEY_GEOMETRY = "map_geometry"
        private const val KEY_GEOM_V2 = "geometry_v2" // 新版几何标记（dock 变窄后重置旧位置）
        private const val KEY_SWAPPED = "map_swapped"   // 地图与音乐栏左右位置是否互换
        private const val EDGE_ZONE_DP = 28   // 右/下边缘把手宽度
        private val MIN_SIZE = 240 // px，缩放下限
        /** 长按分割条进入调整模式（隐藏地图、显示图标）的阈值 */
        private const val LONG_PRESS_MS = 500L
        /** 调整模式下地图区域中央显示的地图应用图标尺寸（dp） */
        private const val DIVIDER_ICON_DP = 96
        /** autoLaunch 是否已执行过——静态变量，防止 Activity 重建导致重复启动外部地图 */
        private var autoLaunched = false
        /** 右侧音乐栏最小宽度（dp）：低于此值音乐栏整体消失，地图吸附到最右端 */
        private const val MUSIC_PANEL_MIN_WIDTH_DP = 180
        /** 地图与音乐栏之间的间距（dp），与 MainActivity.syncRightPanel 保持一致 */
        private const val MUSIC_GAP_DP = 12
        /** 分割条触摸热区宽度（dp），与 ensureDivider 中 barW 保持一致 */
        private const val DIVIDER_BAR_W_DP = 28

        /** MainActivity.onNewIntent 识别地图自动返回的 Intent 标记 */
        const val EXTRA_AUTO_BACK = "nui_auto_back"

        /** 从 prefs 直接读取当前绑定的外部地图应用包名（不需要 MapHost 实例初始化，首次渲染即可用） */
        fun currentMapPackage(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_SOURCE, null) ?: return null
            if (id == "builtin_osm") return null
            // 外部地图应用的 id 就是包名，验证是否已安装
            return if (context.packageManager.getLaunchIntentForPackage(id) != null) id else null
        }
    }
}
