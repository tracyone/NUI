package com.nui.launcher.map

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

/**
 * 悬浮地图容器控制器：
 * - 按用户选择的数据源，在 [container] 中渲染内嵌 OSM 地图、外部地图应用入口卡，
 *   或通过广播让外部地图应用（如高德车机版）在指定坐标显示悬浮地图窗。
 * - 持久化用户选择（SharedPreferences）。
 * - 转发 MapView 生命周期（onResume/onPause/onDetach）。
 *
 * 广播浮窗模式：外部地图应用以 WindowManager 浮层方式渲染在 [container] 的屏幕区域内，
 * 坐标通过广播 extras 传入（x/y 左上角，w/h 右下角，非宽高）。
 *
 * OSMDroid 瓦片缓存写入应用内部 cache，避免外部存储权限。
 */
class MapHost(
    private val context: Context,
    private val container: FrameLayout,
) {
    private var mapView: MapView? = null
    private var current: MapSource? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        val base = File(context.cacheDir, "osm").apply { mkdirs() }
        Configuration.getInstance().osmdroidBasePath = base
        Configuration.getInstance().osmdroidTileCache = File(base, "tiles")
    }

    /** 当前选中数据源 id（用于选择器高亮） */
    val currentId: String? get() = current?.id

    /** 初始化：按持久化的选择渲染；无选择时默认内置 OSM */
    fun start(sources: List<MapSource>) {
        val savedId = prefs.getString(KEY_SOURCE, MapSources.EMBEDDED_OSM_ID)
        val src = sources.firstOrNull { it.id == savedId }
            ?: sources.firstOrNull { it.type == MapSource.Type.EMBEDDED_OSM }
            ?: sources.firstOrNull()
        src?.let { render(it) }
    }

    /** 切换数据源（持久化 + 重新渲染） */
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
            controller.setCenter(GeoPoint(39.9042, 116.4074)) // 北京
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
        v.addView(
            icon,
            LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(12) },
        )
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

    /**
     * 广播浮窗模式：让外部地图应用在 [container] 的屏幕区域内显示悬浮地图。
     * 用 post 等待 layout 完成，确保坐标准确。
     */
    private fun renderFloat(source: MapSource) {
        if (source.floatShowAction == null) return
        container.post { sendFloatBroadcast(source.floatShowAction) }
    }

    /** 发送浮窗坐标广播：x/y 左上角，w/h 右下角 */
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

    /** 关闭当前广播浮窗（如有） */
    private fun closeFloat() {
        val closeAction = current?.floatCloseAction ?: return
        runCatching { context.sendBroadcast(Intent(closeAction)) }
    }

    fun onResume() {
        mapView?.onResume()
        // 广播浮窗：恢复时重新显示（onPause 已关闭）
        current?.takeIf { it.type == MapSource.Type.EXTERNAL_FLOAT }
            ?.let { sendFloatBroadcast(it.floatShowAction) }
    }

    fun onPause() {
        mapView?.onPause()
        // 广播浮窗：暂停时关闭，避免浮窗残留到其它应用上层
        current?.takeIf { it.type == MapSource.Type.EXTERNAL_FLOAT }?.let { closeFloat() }
    }

    fun onDestroy() {
        destroyMap()
    }

    private fun destroyMap() {
        closeFloat()
        mapView?.let {
            it.onDetach()
            mapView = null
        }
    }

    private fun dp(value: Int): Int =
        (value * container.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "nui_map"
        private const val KEY_SOURCE = "float_map_source_id"
    }
}
