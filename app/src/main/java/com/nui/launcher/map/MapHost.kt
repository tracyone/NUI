package com.nui.launcher.map

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

    private enum class ResizeMode { NONE, RIGHT, BOTTOM, BOTH }

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        val base = File(context.cacheDir, "osm").apply { mkdirs() }
        Configuration.getInstance().osmdroidBasePath = base
        Configuration.getInstance().osmdroidTileCache = File(base, "tiles")
        loadGeometry()
    }

    val currentId: String? get() = current?.id

    fun start(sources: List<MapSource>) {
        val savedId = prefs.getString(KEY_SOURCE, MapSources.EMBEDDED_OSM_ID)
        val src = sources.firstOrNull { it.id == savedId }
            ?: sources.firstOrNull { it.type == MapSource.Type.EMBEDDED_OSM }
            ?: sources.firstOrNull()
        src?.let { render(it) }
    }

    fun select(source: MapSource) {
        prefs.edit { putString(KEY_SOURCE, source.id) }
        render(source)
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
                    Toast.makeText(context, "无法启动 ${source.label}", Toast.LENGTH_SHORT).show()
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
        container.post { sendFloatBroadcast(source.floatShowAction) }
    }

    private fun sendFloatBroadcast(action: String?) {
        if (action == null) return
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        val x = loc[0]
        val y = loc[1]
        val w = x + container.width
        val h = y + container.height
        if (container.width <= 0 || container.height <= 0) return
        val intent = Intent(action).apply {
            putExtra("x", x)
            putExtra("y", y)
            putExtra("w", w)
            putExtra("h", h)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun closeFloat() {
        val closeAction = current?.floatCloseAction ?: return
        runCatching { context.sendBroadcast(Intent(closeAction)) }
    }

    fun showFloat() {
        current?.takeIf { it.type == MapSource.Type.EXTERNAL_FLOAT }
            ?.let { container.post { sendFloatBroadcast(it.floatShowAction) } }
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
    fun toggleAdjust() {
        if (adjustMode) { exitAdjust(); return }
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "需要悬浮窗权限，授权后重试", Toast.LENGTH_LONG).show()
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return
        }
        enterAdjust()
    }

    private fun enterAdjust() {
        adjustMode = true
        closeFloat()
        val loc = IntArray(2)
        mapPanel.getLocationOnScreen(loc)
        val lp0 = mapPanel.layoutParams as FrameLayout.LayoutParams
        startLeft = lp0.leftMargin
        startTop = lp0.topMargin
        startWidth = mapPanel.width
        startHeight = mapPanel.height

        // overlay 根：FrameLayout 承载右/下边缘条
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0x332196F3.toInt()) // 极淡蓝底，仅提示调整区域
            // 右边缘条（可视化 + 热区）
            addView(View(context).apply {
                background = ColorDrawable(0xFF1976D2.toInt()) // 深青蓝实条
            }, FrameLayout.LayoutParams(edgeZone, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            })
            // 下边缘条
            addView(View(context).apply {
                background = ColorDrawable(0xFF1976D2.toInt())
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, edgeZone).apply {
                gravity = Gravity.BOTTOM
            })
            // 右下角把手
            addView(View(context).apply {
                background = ColorDrawable(0xFF0D47A1.toInt()) // 最深蓝
            }, FrameLayout.LayoutParams(edgeZone, edgeZone).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            })
            setOnTouchListener(adjustTouch)
        }
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            x = loc[0]
            y = loc[1]
            width = startWidth
            height = startHeight
        }
        runCatching { wm.addView(overlay, lp) }
            .onFailure { Log.e(TAG, "addView overlay failed", it); adjustMode = false; return }
        adjustOverlay = overlay
        Toast.makeText(context, "从右/下边缘或右下角拖，放手完成", Toast.LENGTH_LONG).show()
    }

    private val adjustTouch = View.OnTouchListener { _, e ->
        val ov = adjustOverlay ?: return@OnTouchListener false
        val lp = ov.layoutParams as WindowManager.LayoutParams
        val ez = edgeZone
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = e.rawX
                dragStartY = e.rawY
                startWidth = lp.width
                startHeight = lp.height
                // 根据按下位置决定是调右 / 调下 / 同时调右下
                val relX = e.rawX - lp.x   // overlay 内的相对 x
                val relY = e.rawY - lp.y
                val onRight = relX >= lp.width - ez
                val onBottom = relY >= lp.height - ez
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

    private fun exitAdjust() {
        adjustOverlay?.let { runCatching { wm.removeView(it) } }
        adjustOverlay = null
        adjustMode = false
        resizeMode = ResizeMode.NONE
        saveGeometry()
        showFloat()
        Toast.makeText(context, "已应用新大小", Toast.LENGTH_SHORT).show()
    }

    /** 按固定 x/y 限制宽高最大值（左/顶不动，只约束右侧/底部不越界）。 */
    private fun clampSizeFixed(x: Int, y: Int, w: Int, h: Int): IntArray {
        val sw = context.resources.displayMetrics.widthPixels
        val sh = context.resources.displayMetrics.heightPixels
        val maxRight = sw - dp(20)       // 不碰到右边信息栏
        val maxBottom = sh - dp(20)     // 不碰到底边
        // 最小尺寸
        var nw = w.coerceAtLeast(MIN_SIZE)
        var nh = h.coerceAtLeast(MIN_SIZE)
        // 最大尺寸：以固定 x/y 为基准，右边/下边不出界
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
    }

    private fun loadGeometry() {
        val g = prefs.getString(KEY_GEOMETRY, null)
        if (g != null) {
            val p = g.split(',').mapNotNull { it.toIntOrNull() }
            if (p.size == 4) {
                val c = clampSizeFixed(p[0], p[1], p[2], p[3])
                applyGeometry(c[0], c[1], c[2], c[3])
                return
            }
        }
        // 默认位置：左边贴 dock 栏右侧（152dp），上边 20dp
        val x = dp(152)
        val y = dp(20)
        val sw = context.resources.displayMetrics.widthPixels
        val sh = context.resources.displayMetrics.heightPixels
        val defaultW = (sw - x - dp(240)).coerceAtLeast(MIN_SIZE) // 留右侧信息栏
        val defaultH = (sh - y - dp(40)).coerceAtLeast(MIN_SIZE)
        val c = clampSizeFixed(x, y, defaultW, defaultH)
        applyGeometry(c[0], c[1], c[2], c[3])
    }

    private fun saveGeometry() {
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
        private const val EDGE_ZONE_DP = 28   // 右/下边缘把手宽度
        private val MIN_SIZE = 240 // px，缩放下限
    }
}
