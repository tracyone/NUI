package com.nui.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * “添加应用到 Dock”选择器：
 * 列出系统中所有可启动应用（排除已添加的），点击即添加。
 * 列表项带应用图标 + 名称。
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
        val apps: MutableList<AppModel> = mutableListOf()
        // 置顶：虚拟条目 —— "原车桌面"（如果还没加入过）
        if (StockHome.PKG_STOCK_HOME !in exclude) {
            runCatching { StockHome.model(context) }.getOrNull()?.let { apps.add(it) }
        }
        apps += pm.queryIntentActivities(main, 0).mapNotNull { ri ->
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
                if (apps.isEmpty()) {
                    setMessage("没有可添加的应用")
                    setPositiveButton("确定", null)
                } else {
                    val adapter = IconTextAdapter(context, apps.map { it.icon to it.label })
                    setAdapter(adapter) { _, which -> onPick(apps[which]) }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }
}

/** 带图标 + 文字的列表项 Adapter，供选择对话框复用 */
class IconTextAdapter(
    context: Context,
    private val items: List<Pair<android.graphics.drawable.Drawable, String>>,
) : ArrayAdapter<String>(context, 0, items.map { it.second }) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.dialog_item_icon_text, parent, false)
        val (icon, label) = items[position]
        view.findViewById<ImageView>(R.id.itemIcon).setImageDrawable(icon)
        view.findViewById<TextView>(R.id.itemText).text = label
        return view
    }
}
