package com.nui.launcher

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nui.launcher.databinding.ItemAppGridBinding

/**
 * 应用列表网格适配器。
 * 点击启动应用；长按预留（后期用于添加到 Dock）。
 */
class AppListAdapter(
    private val context: Context,
    private val apps: List<AppModel>,
    private val onClick: (AppModel) -> Unit,
    private val onLongClick: (AppModel) -> Unit = {},
) : RecyclerView.Adapter<AppListAdapter.AppVH>() {

    class AppVH(val binding: ItemAppGridBinding) : RecyclerView.ViewHolder(binding.root)

    private val inflater = LayoutInflater.from(context)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
        return AppVH(ItemAppGridBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: AppVH, position: Int) {
        val app = apps[position]
        // 图标大小随 UiTheme.appIconScale 动态调整（0.6~1.4，默认 72dp）
        val scale = UiTheme.appIconScale(context)
        val sizePx = (UiTheme.DEFAULT_APP_ICON_DP * context.resources.displayMetrics.density * scale).toInt()
        holder.binding.appIcon.layoutParams = holder.binding.appIcon.layoutParams.apply {
            width = sizePx; height = sizePx
        }
        holder.binding.appIcon.setImageBitmap(IconUtils.toBitmap(app.icon, sizePx))
        holder.binding.appLabel.text = app.label
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.binding.root.setOnLongClickListener { onLongClick(app); true }
    }

    override fun getItemCount(): Int = apps.size
}
