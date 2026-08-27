package com.nui.launcher.map

import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * 悬浮地图数据源。
 *
 * @param id           唯一标识：内置为 "builtin_osm"，外部应用为其包名
 * @param label        选择器中显示的名称
 * @param type         类型：内嵌 OSM / 外部入口卡 / 外部广播浮窗
 * @param packageName  外部地图应用包名（内嵌为 null）
 * @param launchIntent 外部地图应用启动 Intent（内嵌为 null）
 * @param icon         外部地图应用图标（内嵌为 null）
 * @param floatShowAction  广播浮窗模式：显示浮窗的广播 action（如 com.autonavi.plus.showmap）
 * @param floatCloseAction 广播浮窗模式：关闭浮窗的广播 action（如 com.autonavi.plus.closemap）
 */
data class MapSource(
    val id: String,
    val label: String,
    val type: Type,
    val packageName: String? = null,
    val launchIntent: Intent? = null,
    val icon: Drawable? = null,
    val floatShowAction: String? = null,
    val floatCloseAction: String? = null,
) {
    enum class Type { EMBEDDED_OSM, EXTERNAL, EXTERNAL_FLOAT }
}
