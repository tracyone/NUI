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
        holder.binding.appIcon.setImageDrawable(app.icon)
        holder.binding.appLabel.text = app.label
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.binding.root.setOnLongClickListener { onLongClick(app); true }
    }

    override fun getItemCount(): Int = apps.size
}
