package com.nui.launcher.map

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * 构建可选的悬浮地图数据源列表：
 * 1. 内置 OSM 地图（无 Key，开箱即用）
 * 2. 已安装的常见地图应用（高德/百度/腾讯/Google，含车机版）
 *
 * 仅返回设备上实际存在的数据源。
 */
object MapSources {
    const val EMBEDDED_OSM_ID = "builtin_osm"

    /**
     * 支持广播悬浮窗的地图应用：包名 -> (显示浮窗 action, 关闭浮窗 action)。
     * 坐标通过广播 extras 传入：x/y 为左上角，w/h 为右下角（非宽高）。
     */
    private val FLOAT_BROADCAST = mapOf(
        "com.autonavi.amapauto" to ("com.autonavi.plus.showmap" to "com.autonavi.plus.closemap"),
    )

    /** 常见地图应用包名 → 缺省显示名（设备实际安装时再读取真实 label） */
    private val KNOWN = listOf(
        "com.autonavi.amapauto" to "高德地图（车机版）",
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.tencent.map" to "腾讯地图",
        "com.google.android.apps.maps" to "Google 地图",
    )

    fun build(context: Context): List<MapSource> {
        val pm = context.packageManager
        val result = mutableListOf<MapSource>()

        // 内置 OSM
        result += MapSource(
            id = EMBEDDED_OSM_ID,
            label = "内置地图（OpenStreetMap）",
            type = MapSource.Type.EMBEDDED_OSM,
        )

        // 已安装地图应用
        for ((pkg, fallbackLabel) in KNOWN) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(fallbackLabel)
            val icon: Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            val floatActions = FLOAT_BROADCAST[pkg]
            result += MapSource(
                id = pkg,
                label = label,
                type = if (floatActions != null) MapSource.Type.EXTERNAL_FLOAT
                    else MapSource.Type.EXTERNAL,
                packageName = pkg,
                launchIntent = launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                icon = icon,
                floatShowAction = floatActions?.first,
                floatCloseAction = floatActions?.second,
            )
        }
        return result
    }
}
