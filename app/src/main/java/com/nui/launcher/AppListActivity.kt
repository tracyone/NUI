package com.nui.launcher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.nui.launcher.databinding.ActivityAppListBinding

/**
 * 应用列表页：
 * - 读取系统中所有可启动应用（按名称排序）
 * - 网格展示（横屏 4 列）
 * - 点击启动应用
 * - 顶部提供返回按钮回到桌面
 *
 * 加载放在后台线程，避免大量应用时阻塞 UI。
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 亮屏常亮 + 全屏沉浸（车机场景）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backBtn.setOnClickListener { finish() }

        binding.appGrid.layoutManager = GridLayoutManager(this, 4)
        binding.appGrid.setHasFixedSize(true)
        binding.appGrid.itemAnimator = null

        loadAppsAsync()
    }

    private fun loadAppsAsync() {
        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(main, 0)
            val fallbackIcon = pm.defaultActivityIcon
            val apps = resolved.map { ri ->
                val pkg = ri.activityInfo.packageName
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
                )
            }
        }.start()
    }

    private fun applyImmersive() {
        val flags = (WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(flags)
    }
}
