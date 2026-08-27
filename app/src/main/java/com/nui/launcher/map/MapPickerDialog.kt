package com.nui.launcher.map

import android.app.AlertDialog
import android.content.Context

/**
 * “选择悬浮地图”单选对话框。
 * 列出内置 OSM 与已安装的地图应用，点击即切换并持久化。
 */
object MapPickerDialog {

    fun show(
        context: Context,
        sources: List<MapSource>,
        currentId: String?,
        onPick: (MapSource) -> Unit,
    ) {
        val labels = sources.map { it.label }.toTypedArray()
        val checked = sources.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("选择悬浮地图")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onPick(sources[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
