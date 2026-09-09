package com.nui.launcher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nui.launcher.databinding.ItemAppGridBinding

/**
 * 应用列表网格适配器。
 * 点击启动应用；长按预留（后期用于添加到 Dock）。
 * 应用名称标签随外观变化：深色=半透明黑底+白字，浅色=半透明白底+黑字。
 * [rowHeightDp]>0 时 item 固定行高（网格撑满可用高度，最后一行不悬空），内容垂直居中。
 */
class AppListAdapter(
    private val context: Context,
    private val apps: List<AppModel>,
    private val onClick: (AppModel) -> Unit,
    private val onLongClick: (AppModel) -> Unit = {},
    private val rowHeightDp: Float = 0f,
) : RecyclerView.Adapter<AppListAdapter.AppVH>() {

    class AppVH(val binding: ItemAppGridBinding) : RecyclerView.ViewHolder(binding.root)

    private val inflater = LayoutInflater.from(context)
    private val dp = context.resources.displayMetrics.density

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
        return AppVH(ItemAppGridBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: AppVH, position: Int) {
        val app = apps[position]
        // 图标大小随 UiTheme.appIconScale 动态调整（0.6~1.4，默认 72dp）
        val scale = UiTheme.appIconScale(context)
        val sizePx = (UiTheme.DEFAULT_APP_ICON_DP * dp * scale).toInt()
        holder.binding.appIcon.layoutParams = holder.binding.appIcon.layoutParams.apply {
            width = sizePx; height = sizePx
        }
        // 固定行高：分页网格撑满可用高度，行内容垂直居中（最后一行不悬空在中间）
        if (rowHeightDp > 0f) {
            holder.binding.root.layoutParams = holder.binding.root.layoutParams.apply {
                height = (rowHeightDp * dp).toInt()
            }
        }
        val dark = UiTheme.isDark(context)
        // 图标：NUI 内置入口（如桌面设置）用品牌橙圆底 + 白色图形，深浅外观统一醒目；
        // 第三方应用按需从 PackageManager 懒加载（LruCache 命中秒显，分页滚动不卡顿）
        val icon = app.icon
        if (icon != null) {
            val badge = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF7043.toInt())
            }
            val insetPx = (sizePx * 0.22f).toInt()
            val inner = InsetDrawable(icon.mutate(), insetPx, insetPx, insetPx, insetPx)
            holder.binding.appIcon.setImageDrawable(LayerDrawable(arrayOf(badge, inner)))
        } else {
            holder.binding.appIcon.setImageBitmap(
                IconUtils.loadBitmap(
                    context.packageManager,
                    app.packageName,
                    context.packageManager.defaultActivityIcon,
                    sizePx,
                )
            )
        }
        holder.binding.appLabel.text = app.label
        // 应用名称标签随外观变化：深色=半透明黑底+白字，浅色=半透明白底+黑字
        val bgColor = if (dark) 0x80000000.toInt() else 0x80FFFFFF.toInt()
        val textColor = if (dark) 0xFFECEFF1.toInt() else 0xFF1A1A1A.toInt()
        holder.binding.appLabel.background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 6 * dp
        }
        holder.binding.appLabel.setTextColor(textColor)
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.binding.root.setOnLongClickListener { onLongClick(app); true }
    }

    override fun getItemCount(): Int = apps.size
}
