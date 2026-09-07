package com.nui.launcher

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.nui.launcher.databinding.ActivityAppListBinding
import kotlin.math.abs

/**
 * 应用列表页：全屏透明，只显示壁纸+应用图标。
 * 返回方式：左边缘向右滑 / 从上往下滑 / 系统返回键。
 * 长按应用：弹出菜单（卸载 / 隐藏），卸载/隐藏前需确认；隐藏的应用可在 桌面设置 → 应用 中恢复。
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding

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

        loadAppsAsync()
    }

    override fun onBackPressed() {
        finish()
    }

    /** 滑动返回：左边缘右滑 OR 从上向下滑，满足任一即 finish() */
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
                        finish(); true
                    }
                    // 从上往下滑（起点在屏幕上半部分，且垂直位移大）
                    else if (downY < screenWidth / 2 && dy > minSwipe && absY > absX) {
                        finish(); true
                    } else {
                        false
                    }
                }
            }
            false
        }
    }

    private fun loadAppsAsync() {
        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(main, 0)
            val fallbackIcon = pm.defaultActivityIcon
            val hidden = HiddenApps.hiddenSet(this)
            val apps = resolved.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg in hidden) return@mapNotNull null
                AppModel(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = IconUtils.getIconWithFallback(pm, pkg, fallbackIcon),
                    launchIntent = pm.getLaunchIntentForPackage(pkg)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?: Intent(Intent.ACTION_MAIN).setPackage(pkg),
                )
            }.sortedBy { it.label.lowercase() }

            runOnUiThread {
                binding.appGrid.adapter = AppListAdapter(
                    context = this,
                    apps = apps,
                    onClick = { app ->
                        startActivity(app.launchIntent)
                        finish()
                    },
                    onLongClick = { app -> showAppMenu(app) },
                )
            }
        }.start()
    }

    /** 长按应用：卸载 / 隐藏（均需确认）；卸载走系统卸载页，隐藏立即生效 */
    private fun showAppMenu(app: AppModel) {
        val pkg = app.packageName
        val items = arrayOf(
            getString(R.string.uninstall),
            getString(R.string.hide_app),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmUninstall(app)
                    1 -> confirmHide(app)
                }
            }
            .show()
    }

    private fun confirmUninstall(app: AppModel) {
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
