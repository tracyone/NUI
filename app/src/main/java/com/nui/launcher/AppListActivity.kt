package com.nui.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.nui.launcher.databinding.ActivityAppListBinding
import kotlin.math.abs

/**
 * 应用列表页：全屏透明，只显示壁纸+应用图标。
 * 返回方式：左边缘向右滑 / 从上往下滑 / 系统返回键（多选模式下返回优先退出多选）。
 * 长按应用：弹出菜单（卸载 / 隐藏 / 多选），卸载/隐藏前需确认；
 *   选"多选"进入批量模式，点图标勾选多个后可一次批量隐藏。
 * 隐藏的应用可在 桌面设置 → 应用 → 管理隐藏应用 中恢复。
 */
class AppListActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiTheme.overrideUiDpi(base))
    }

    private lateinit var binding: ActivityAppListBinding
    private var adapter: AppListAdapter? = null
    private var currentApps: List<AppModel> = emptyList()
    private val multiState = MultiSelectState()

    /** 多选操作条"隐藏(N)"按钮的两种圆角底：可按=品牌橙，不可按=灰 */
    private val hideBtnBgEnabled by lazy { roundedRectBg(0xFFFF7043.toInt()) }
    private val hideBtnBgDisabled by lazy { roundedRectBg(0xFF7A7A7A.toInt()) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedRectBg(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSwipeBack()

        binding.appGrid.layoutManager = GridLayoutManager(this, 5)
        binding.appGrid.setHasFixedSize(true)
        binding.appGrid.itemAnimator = null

        setupMultiBar()
        loadAppsAsync()
    }

    /** 返回：多选模式下先退出多选，否则关闭应用列表页（系统返回键与滑动返回共用） */
    private fun navigateBack() {
        if (multiState.mode) exitMultiSelect() else finish()
    }

    override fun onBackPressed() {
        navigateBack()
    }

    /** 滑动返回：左边缘右滑 OR 从上向下滑，满足任一即返回 */
    private fun setupSwipeBack() {
        var downX = 0f
        var downY = 0f
        var downEdge = false
        val screenWidth = resources.displayMetrics.widthPixels

        binding.root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    // 左边缘 40dp 内按下 → 边缘滑动模式
                    val edgePx = (40 * resources.displayMetrics.density).toInt()
                    downEdge = event.rawX <= edgePx
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val absX = abs(dx)
                    val absY = abs(dy)
                    val minSwipe = (80 * resources.displayMetrics.density).toInt()

                    // 左边缘向右滑
                    if (downEdge && dx > minSwipe && absX > absY) {
                        navigateBack()
                    }
                    // 从上往下滑（起点在屏幕上半部分，且垂直位移大）
                    else if (downY < screenWidth / 2 && dy > minSwipe && absY > absX) {
                        navigateBack()
                    }
                }
            }
            false
        }
    }

    /** 绑定多选顶部操作条：取消 / 已选 N 个 / 全选 / 隐藏(N) */
    private fun setupMultiBar() {
        multiState.onChanged = { updateMultiBar() }
        // 文字按钮加系统 ripple 触摸反馈
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        binding.btnCancel.setBackgroundResource(ripple.resourceId)
        binding.btnSelectAll.setBackgroundResource(ripple.resourceId)
        binding.btnSelectPage.setBackgroundResource(ripple.resourceId)

        binding.btnCancel.setOnClickListener { exitMultiSelect() }
        binding.btnSelectAll.setOnClickListener {
            if (multiState.isAllSelected(selectableCount())) {
                multiState.clearSelection()
            } else {
                multiState.selectAll(currentApps.filter { it.onClick == null }.map { it.packageName })
            }
            adapter?.refreshMultiSelect()
        }
        binding.btnSelectPage.setOnClickListener {
            multiState.applyPageSelection(currentApps.filter { it.onClick == null }.map { it.packageName })
            adapter?.refreshMultiSelect()
        }
        binding.btnHideSelected.setOnClickListener { confirmHideSelected() }
        updateMultiBar()
    }

    private fun selectableCount(): Int = currentApps.count { it.onClick == null }

    /** 长按某个应用进入多选：进入批量模式并预先勾选被按的那个，网格顶部让出操作条 */
    private fun enterMultiSelect(initial: AppModel) {
        multiState.enter(initial.packageName)
        adapter?.refreshMultiSelect()
        binding.multiBar.visibility = View.VISIBLE
        val pad = dp(48)
        binding.appGrid.setPadding(pad, dp(112), pad, pad)
        updateMultiBar()
    }

    private fun exitMultiSelect() {
        multiState.exit()
        adapter?.refreshMultiSelect()
        binding.multiBar.visibility = View.GONE
        val pad = dp(48)
        binding.appGrid.setPadding(pad, pad, pad, pad)
    }

    /** 同步多选操作条文案 / 按钮状态（勾选变化时由 [MultiSelectState.onChanged] 触发） */
    private fun updateMultiBar() {
        val n = multiState.count()
        binding.multiTitle.text = getString(R.string.multi_selected_count, n)
        binding.btnHideSelected.text = getString(R.string.multi_hide_n, n)
        binding.btnHideSelected.isEnabled = n > 0
        binding.btnHideSelected.background = if (n > 0) hideBtnBgEnabled else hideBtnBgDisabled
        binding.btnSelectAll.text = getString(
            if (multiState.isAllSelected(selectableCount())) R.string.multi_deselect_all else R.string.multi_select_all
        )
        val pagePkgs = currentApps.filter { it.onClick == null }.map { it.packageName }
        val pageAll = pagePkgs.isNotEmpty() && pagePkgs.all { it in multiState.selected }
        binding.btnSelectPage.text = getString(
            if (pageAll) R.string.multi_deselect_page else R.string.multi_select_page
        )
    }

    /** 批量隐藏确认：确认后一次性写入并刷新列表 */
    private fun confirmHideSelected() {
        val pkgs = multiState.selected.toList()
        if (pkgs.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.multi_hide_confirm_title, pkgs.size))
            .setMessage(R.string.multi_hide_confirm_msg)
            .setPositiveButton(R.string.hide_app) { _, _ ->
                HiddenApps.hideAll(this, pkgs)
                NuiToast.show(this, getString(R.string.multi_hide_done, pkgs.size), Toast.LENGTH_SHORT)
                // 列表重建天然退出多选，收起操作条并还原网格内边距
                binding.multiBar.visibility = View.GONE
                val pad = dp(48)
                binding.appGrid.setPadding(pad, pad, pad, pad)
                loadAppsAsync()
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun loadAppsAsync() {
        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(main, 0)
            val hidden = HiddenApps.hiddenSet(this)
            val apps = resolved.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                // 隐藏的应用 + NUI 自身（桌面启动器不显示自己）都跳过
                if (pkg in hidden || pkg == packageName) return@mapNotNull null
                AppModel(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    // icon 不在此处加载：AppListAdapter 按需加载（仅显示项加载，LruCache 缓存）
                    launchIntent = pm.getLaunchIntentForPackage(pkg)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?: Intent(Intent.ACTION_MAIN).setPackage(pkg),
                )
            }.sortedBy { it.label.lowercase() }

            runOnUiThread {
                currentApps = apps
                adapter = AppListAdapter(
                    context = this,
                    apps = apps,
                    onClick = { app ->
                        startActivity(app.launchIntent)
                        finish()
                    },
                    onLongClick = { app -> showAppMenu(app) },
                    multi = multiState,
                )
                binding.appGrid.adapter = adapter
                updateMultiBar()
            }
        }.start()
    }

    /** 长按应用：卸载 / 隐藏（单个，均需确认）/ 多选（批量隐藏入口） */
    private fun showAppMenu(app: AppModel) {
        val items = arrayOf(
            getString(R.string.uninstall),
            getString(R.string.hide_app),
            getString(R.string.multi_select_entry),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmUninstall(app)
                    1 -> confirmHide(app)
                    2 -> enterMultiSelect(app)
                }
            }
            .show()
    }

    private fun confirmUninstall(app: AppModel) {
        // 系统应用不可卸载，直接提示
        val isSystem = runCatching {
            packageManager.getApplicationInfo(app.packageName, 0).flags and
                ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
        if (isSystem) {
            NuiToast.show(this, getString(R.string.cannot_uninstall_system_app), Toast.LENGTH_SHORT)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.uninstall_confirm_title, app.label))
            .setMessage(R.string.uninstall_confirm_msg)
            .setPositiveButton(R.string.uninstall) { _, _ ->
                val uri = Uri.parse("package:${app.packageName}")
                val i = Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(i) }
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun confirmHide(app: AppModel) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hide_confirm_title, app.label))
            .setMessage(R.string.hide_confirm_msg)
            .setPositiveButton(R.string.hide_app) { _, _ ->
                HiddenApps.hide(this, app.packageName)
                NuiToast.show(this, getString(R.string.hide_done, app.label), Toast.LENGTH_SHORT)
                loadAppsAsync()   // 重新加载，隐藏项消失
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun applyImmersive() {
        val flags = (WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(flags)
    }
}
