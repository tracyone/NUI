package com.nui.launcher.map

import android.app.AlertDialog
import android.content.Context

/**
 * “选择悬浮地图”单选对话框。
 * 列出内置 OSM 与已安装的地图应用，点击即切换并持久化。
 *
 * [onDismiss] 在对话框消失（选了/取消/点外）时回调，用于恢复外部临时隐藏的浮窗。
 */
object MapPickerDialog {

    fun show(
        context: Context,
        sources: List<MapSource>,
        currentId: String?,
        onPick: (MapSource) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        val labels = sources.map { it.label }.toTypedArray()
        val checked = sources.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val dialog = AlertDialog.Builder(context)
            .setTitle("选择悬浮地图")
            .setSingleChoiceItems(labels, checked) { d, which ->
                onPick(sources[which])
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }
}
