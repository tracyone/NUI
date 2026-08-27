package com.nui.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent

/**
 * “添加应用到 Dock”选择器：
 * 列出系统中所有可启动应用（排除已添加的），点击即添加。
 */
object DockPickerDialog {

    fun show(context: Context, exclude: List<String>, onPick: (AppModel) -> Unit) {
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

        if (apps.isEmpty()) {
            AlertDialog.Builder(context)
                .setMessage("没有可添加的应用")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("添加应用到 Dock")
            .setItems(labels) { _, which -> onPick(apps[which]) }
            .setNegativeButton("取消", null)
            .show()
    }
}
