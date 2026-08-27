package com.nui.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent

/**
 * “添加应用到 Dock”选择器：
 * 列出系统中所有可启动应用（排除已添加的），点击即添加。
 *
 * [onDismiss] 在对话框消失时回调，用于恢复外部临时隐藏的浮窗。
 */
object DockPickerDialog {

    fun show(
        context: Context,
        exclude: List<String>,
        onPick: (AppModel) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            AppModel(
                label = ri.loadLabel(pm).toString(),
                packageName = pkg,
                icon = ri.loadIcon(pm),
                launchIntent = launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }.filter { it.packageName !in exclude }
            .sortedBy { it.label.lowercase() }

        val dialog = AlertDialog.Builder(context)
            .setTitle("添加应用到 Dock")
            .apply {
                if (apps.isEmpty()) setMessage("没有可添加的应用")
                    .setPositiveButton("确定", null)
                else setItems(apps.map { it.label }.toTypedArray()) { _, which -> onPick(apps[which]) }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }
}
