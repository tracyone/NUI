package com.nui.launcher

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nui.launcher.databinding.ActivityMainBinding
import com.nui.launcher.map.MapHost
import com.nui.launcher.map.MapPickerDialog
import com.nui.launcher.map.MapSources

/**
 * 桌面主页（Launcher / HOME）。
 *
 * 布局：左侧 Dock 栏 + 中间悬浮地图区域 + 右侧时钟/信息栏。
 *
 * Dock 栏：
 * - 顶部：用户自定义应用快捷方式（点加号添加，长按移除）
 * - 加号按钮：弹出应用选择器添加快捷方式
 * - 最下：应用列表入口
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapHost: MapHost
    private val mapSources by lazy { MapSources.build(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDock()
        setupMap()
    }

    override fun onResume() {
        super.onResume()
        mapHost.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapHost.onPause()
    }

    override fun onDestroy() {
        mapHost.onDestroy()
        super.onDestroy()
    }

    private fun setupDock() {
        binding.dockApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        binding.dockAdd.setOnClickListener {
            mapHost.closeFloat()
            DockPickerDialog.show(
                context = this,
                exclude = DockConfig.packages(this),
                onPick = { app ->
                    DockConfig.add(this, app.packageName)
                    renderDock()
                    Toast.makeText(this, "已添加 ${app.label}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
        renderDock()
    }

    /** 渲染用户自定义 dock 快捷方式 */
    private fun renderDock() {
        binding.dockItems.removeAllViews()
        val dp = resources.displayMetrics.density
        val size = (80 * dp).toInt()
        val gap = (8 * dp).toInt()
        val bg = ContextCompat.getDrawable(this, R.drawable.bg_dock_item)
        for (app in DockConfig.loadApps(this)) {
            val btn = ImageButton(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = bg
                setOnClickListener {
                    runCatching { startActivity(app.launchIntent) }
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("从 Dock 移除")
                        .setMessage("移除 ${app.label}?")
                        .setPositiveButton("移除") { _, _ ->
                            DockConfig.remove(this@MainActivity, app.packageName)
                            renderDock()
                            Toast.makeText(this@MainActivity, "已移除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            binding.dockItems.addView(
                btn,
                LinearLayout.LayoutParams(size, size).apply { topMargin = gap },
            )
        }
    }

    private fun setupMap() {
        mapHost = MapHost(this, binding.mapContainer)
        mapHost.start(mapSources)
        binding.btnSwitchMap.setOnClickListener {
            mapHost.closeFloat()
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source)
                    Toast.makeText(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        val flags = (WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(flags)
    }
}
