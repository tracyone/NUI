package com.nui.launcher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nui.launcher.databinding.ItemAppGridBinding

/**
 * 多选（批量隐藏）共享状态。
 * MainActivity 的应用网格分多页、每页一个 [AppListAdapter]，多个适配器共用同一个实例即可跨页勾选；
 * 单页的 AppListActivity 同样适用。宿主通过 [onChanged] 回调刷新顶部操作条计数。
 */
class MultiSelectState {
    /** 是否处于多选模式 */
    var mode = false
        private set
    /** 已勾选的包名（有序，稳定展示） */
    val selected = LinkedHashSet<String>()
    /** 模式 / 勾选变化回调：宿主用来刷新顶部操作条 */
    var onChanged: (() -> Unit)? = null

    /** 进入多选；[initialPkg] 非空时预先勾选（长按进入时把被按的那个先勾上） */
    fun enter(initialPkg: String? = null) {
        mode = true
        selected.clear()
        if (initialPkg != null) selected.add(initialPkg)
        onChanged?.invoke()
    }

    fun exit() {
        mode = false
        selected.clear()
        onChanged?.invoke()
    }

    fun toggle(pkg: String) {
        if (!selected.add(pkg)) selected.remove(pkg)
        onChanged?.invoke()
    }

    fun selectAll(pkgs: Collection<String>) {
        selected.clear()
        selected.addAll(pkgs)
        onChanged?.invoke()
    }

    /** 本页全选/取消：若 [pkgs] 已全部选中则全部移除，否则全部加入（跨页已选不动） */
    fun applyPageSelection(pkgs: Collection<String>) {
        if (pkgs.isNotEmpty() && pkgs.all { it in selected }) selected.removeAll(pkgs)
        else selected.addAll(pkgs)
        onChanged?.invoke()
    }

    fun clearSelection() {
        selected.clear()
        onChanged?.invoke()
    }

    fun count(): Int = selected.size
    fun isAllSelected(selectableTotal: Int): Boolean =
        selectableTotal > 0 && selected.size >= selectableTotal
}

/**
 * 应用列表网格适配器。
 * 普通模式：点击启动应用，长按弹菜单（卸载 / 隐藏 / 多选）。
 * 多选模式（[multi] 非空且其 mode=true）：点击/长按普通应用图标 = 勾选或取消勾选，
 *   选中项右上角显示实心橙勾 + 橙色描边卡片，未选中项显示空心圆；
 *   内置入口（[AppModel.onClick] 非空，如"桌面设置"）不可勾选，多选模式下点击忽略。
 * 应用名称标签随外观变化：深色=半透明黑底+白字，浅色=半透明白底+黑字。
 * [rowHeightDp]>0 时 item 固定行高（网格撑满可用高度，最后一行不悬空），内容垂直居中。
 */
class AppListAdapter(
    private val context: Context,
    private val apps: List<AppModel>,
    private val onClick: (AppModel) -> Unit,
    private val onLongClick: (AppModel) -> Unit = {},
    private val rowHeightDp: Float = 0f,
    private val multi: MultiSelectState? = null,
) : RecyclerView.Adapter<AppListAdapter.AppVH>() {

    class AppVH(val binding: ItemAppGridBinding) : RecyclerView.ViewHolder(binding.root)

    private val inflater = LayoutInflater.from(context)
    private val dp = context.resources.displayMetrics.density
    private val brandOrange = 0xFFFF7043.toInt()

    /** 选中角标：实心橙圆（静态 Drawable，多 item 共享安全） */
    private val checkBg by lazy {
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(brandOrange) }
    }
    /** 多选未选中角标：半透明黑底 + 白色空心圆 */
    private val emptyCheckBg by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33000000)
            setStroke((1.5f * dp).toInt().coerceAtLeast(1), 0x99FFFFFF.toInt())
        }
    }
    /** 选中项卡片底：半透明橙填充 + 橙色描边 */
    private val selectedCardBg by lazy {
        GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(0x26FF7043)
            setStroke((2 * dp).toInt().coerceAtLeast(1), brandOrange)
        }
    }

    /** 进入/退出多选、全选等整体变化后，由宿主调用以全量刷新角标 */
    fun refreshMultiSelect() = notifyDataSetChanged()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
        return AppVH(ItemAppGridBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: AppVH, position: Int) {
        val app = apps[position]
        val inMulti = multi?.mode == true
        // 内置入口（如桌面设置，onClick 非空）不参与勾选/隐藏
        val selectable = app.onClick == null
        val selected = inMulti && selectable && multi != null && app.packageName in multi.selected
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

        // 点击 / 长按：多选模式下用于勾选（内置入口忽略），普通模式分别为启动应用 / 弹菜单
        holder.binding.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val current = apps[pos]
            if (multi?.mode == true) {
                if (current.onClick == null) {
                    multi.toggle(current.packageName)
                    notifyItemChanged(pos)
                }
            } else {
                onClick(current)
            }
        }
        holder.binding.root.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val current = apps[pos]
                if (multi?.mode == true) {
                    if (current.onClick == null) {
                        multi.toggle(current.packageName)
                        notifyItemChanged(pos)
                    }
                } else {
                    onLongClick(current)
                }
            }
            true
        }

        // 多选选中态：角标 + 卡片描边；多选未选中（普通应用）：空心圆；内置入口与普通模式：无痕
        val mark = holder.binding.checkMark
        when {
            !inMulti -> {
                mark.visibility = View.GONE
                holder.binding.root.background = null
            }
            selected -> {
                mark.visibility = View.VISIBLE
                mark.text = "✓"
                mark.background = checkBg
                holder.binding.root.background = selectedCardBg
            }
            selectable -> {
                mark.visibility = View.VISIBLE
                mark.text = ""
                mark.background = emptyCheckBg
                holder.binding.root.background = null
            }
            else -> {
                mark.visibility = View.GONE
                holder.binding.root.background = null
            }
        }
    }

    override fun getItemCount(): Int = apps.size
}
