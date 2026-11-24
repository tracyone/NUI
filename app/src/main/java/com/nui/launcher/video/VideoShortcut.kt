package com.nui.launcher.video

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nui.launcher.AppModel
import com.nui.launcher.R

/**
 * 视频播放器在桌面内的虚拟条目。
 *
 * [VideoBrowserActivity] 与 NUI 同包名，而应用网格 / Dock 选择器都会把桌面自身包名过滤掉
 * （`pkg == context.packageName`），仅给 Activity 加 LAUNCHER intent-filter 仍然不可见。
 * 因此与「原车桌面」「桌面设置」一样，用虚拟包名 [PKG_VIDEO] 构造 [AppModel] 注入列表：
 * - [com.nui.launcher.DockPickerDialog] 加入可选条目；
 * - [com.nui.launcher.MainActivity] 应用网格置顶注入；
 * - [com.nui.launcher.DockConfig.loadApps] 按虚拟包名还原（Dock 持久化只存包名）。
 */
object VideoShortcut {
    const val PKG_VIDEO = "__nui_video_player__"

    fun model(context: Context): AppModel {
        val launch = Intent(context, VideoBrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return AppModel(
            label = context.getString(R.string.video_app_name),
            packageName = PKG_VIDEO,
            icon = ContextCompat.getDrawable(context, R.drawable.ic_video_launcher),
            launchIntent = launch,
            // 带 onClick：应用网格直接显示完整图标（不套品牌橙圆底），
            // 同时不进入「隐藏应用」名单、不弹普通应用的长按菜单
            onClick = { context.startActivity(launch) },
        )
    }
}
