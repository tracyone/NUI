package com.nui.launcher.map

import android.content.Context
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
 * - 按用户选择的数据源，在 [container] 中渲染内嵌 OSM 地图或外部地图应用入口卡。
 * - 持久化用户选择（SharedPreferences）。
 * - 转发 MapView 生命周期（onResume/onPause/onDetach）。
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

    fun onResume() {
        mapView?.onResume()
    }

    fun onPause() {
        mapView?.onPause()
    }

    fun onDestroy() {
        destroyMap()
    }

    private fun destroyMap() {
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
