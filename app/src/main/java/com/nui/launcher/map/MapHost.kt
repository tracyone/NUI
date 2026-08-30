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
    var onPickMap: (() -> Unit)? = null
) {
    private var mapView: MapView? = null
    private var current: MapSource? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var adjustMode = false
    private var adjustOverlay: View? = null
    private val wm: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    /** 调整模式：起点（按下时的屏幕坐标+overlay尺寸）*/
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startLeft = 0   // 固定左边界（不允许移动）
    private var startTop = 0    // 固定顶边界（不允许移动）
    private var startWidth = 0
    private var startHeight = 0
    private var resizeMode = ResizeMode.NONE
    /** 地图右缘上限（px）：由外部 syncRightPanel 按右侧音乐栏位置设定，0=不限制 */
    private var rightLimit = 0

    /** 设置地图右缘上限；若当前地图超出则立即缩回 */
    fun setRightLimit(px: Int) {
        rightLimit = px
        if (px <= 0) return
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        val maxW = (px - lp.leftMargin).coerceAtLeast(MIN_SIZE)
        if (lp.width > maxW) {
            lp.width = maxW
            mapPanel.layoutParams = lp
            onGeometryChanged?.invoke()
        }
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
            if (autoLaunch) launchAndReturnHome()
        }
    }

    fun select(source: MapSource, autoLaunch: Boolean = false) {
        prefs.edit { putString(KEY_SOURCE, source.id) }
        render(source)
        if (autoLaunch) launchAndReturnHome()
    }

    /**
     * 启动外部地图应用，延迟 [delayMs] 毫秒后返回桌面（HOME）。
     * 回桌面后再恢复浮窗（高德浮窗需地图进程在运行才生效）。
     * 内置 OSM 无需启动外部应用，直接返回。
     */
    fun launchAndReturnHome(delayMs: Long = 3000L) {
        val src = current ?: return
        if (src.type == MapSource.Type.EMBEDDED_OSM) return
        val launch = src.launchIntent ?: return
        runCatching { context.startActivity(launch) }
            .onFailure { Log.e(TAG, "launch external map failed", it); return }
        container.postDelayed({
            // 显式返回 NUI（不依赖是否为默认 Launcher，HOME 可能回到其他桌面）
            val back = Intent().apply {
                setClassName(context, "com.nui.launcher.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching { context.startActivity(back) }
            // 回 NUI 后再恢复浮窗
            if (src.type == MapSource.Type.EXTERNAL_FLOAT) {
                container.postDelayed({ showFloat() }, 500L)
            }
        }, delayMs)
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
        // 高德浮窗边界比 mapPanel 外扩 4px：悬浮窗可能比卡片窄/有内边距，边缘会露出卡片深色背景
        val inset = 4
        val intent = Intent(action).apply {
            putExtra("x", x - inset)
            putExtra("y", y - inset)
            putExtra("w", w + inset)
            putExtra("h", h + inset)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    /** 悬浮地图几何（边界格式 x1,y1,x2,y2），未显示过返回 null */
    fun floatBounds(): IntArray? =
        if (cachedX < 0 || cachedW <= cachedX || cachedH <= cachedY) null
        else intArrayOf(cachedX, cachedY, cachedW, cachedH)

    fun closeFloat() {
        val closeAction = current?.floatCloseAction ?: return
        runCatching { context.sendBroadcast(Intent(closeAction)) }
    }

    fun showFloat() {
        current?.takeIf { it.type == MapSource.Type.EXTERNAL_FLOAT }
            ?.let { src ->
                container.post {
                    sendFloatBroadcast(src.floatShowAction)
                    container.postDelayed({ onFloatShown?.invoke() }, 600)
                }
            }
    }

    fun onResume() {
        mapView?.onResume()
        showFloat()
    }

    fun onPause() {
        mapView?.onPause()
        closeFloat()
    }

    fun onDestroy() {
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
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                width = context.resources.displayMetrics.widthPixels
                height = context.resources.displayMetrics.heightPixels
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
        val sw = context.resources.displayMetrics.widthPixels
        val sh = context.resources.displayMetrics.heightPixels
        // 右侧面板固定贴边后：地图右缘不超过 rightLimit（有值用值，无值用 屏幕宽-20dp）
        val maxRight = if (rightLimit > 0) rightLimit else sw - dp(20)
        val maxBottom = sh - dp(20)     // 不碰到底边
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
        onGeometryChanged?.invoke()
    }

    private fun loadGeometry() {
        // 一次性重置旧版地图几何（dock 变窄后旧位置离 dock 太远）
        if (!prefs.getBoolean(KEY_GEOM_V2, false)) {
            prefs.edit { remove(KEY_GEOMETRY); putBoolean(KEY_GEOM_V2, true) }
        }
        val g = prefs.getString(KEY_GEOMETRY, null)
        if (g != null) {
            val p = g.split(',').mapNotNull { it.toIntOrNull() }
            if (p.size == 4) {
                val c = clampSizeFixed(p[0], p[1], p[2], p[3])
                applyGeometry(c[0], c[1], c[2], c[3])
                return
            }
        }
        // 默认位置：左边贴 dock 栏右侧（dock 96dp+20margin+12gap≈128dp），上边 20dp
        val x = dp(128)
        val y = dp(20)
        val sw = context.resources.displayMetrics.widthPixels
        val sh = context.resources.displayMetrics.heightPixels
        val defaultW = (sw - x - dp(240)).coerceAtLeast(MIN_SIZE) // 留右侧信息栏
        val defaultH = (sh - y - dp(40)).coerceAtLeast(MIN_SIZE)
        val c = clampSizeFixed(x, y, defaultW, defaultH)
        applyGeometry(c[0], c[1], c[2], c[3])
    }

    private fun saveGeometry() {
        primeCache()  // 刷新缓存，否则 showFloat 会用旧坐标
        val lp = mapPanel.layoutParams as FrameLayout.LayoutParams
        prefs.edit { putString(KEY_GEOMETRY, "${lp.leftMargin},${lp.topMargin},${lp.width},${lp.height}") }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MapHost"
        private const val PREFS = "nui_map"
        private const val KEY_SOURCE = "float_map_source_id"
        private const val KEY_GEOMETRY = "map_geometry"
        private const val KEY_GEOM_V2 = "geometry_v2" // 新版几何标记（dock 变窄后重置旧位置）
        private const val EDGE_ZONE_DP = 28   // 右/下边缘把手宽度
        private val MIN_SIZE = 240 // px，缩放下限
    }
}
