package com.nui.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nui.launcher.databinding.ActivityMainBinding
import com.nui.launcher.map.MapHost
import com.nui.launcher.map.MapPickerDialog
import com.nui.launcher.map.MapSources

/**
 * 桌面主页（Launcher / HOME）。
 *
 * 布局：左侧 Dock 栏 + 中间悬浮地图区域 + 右侧时钟/信息栏，
 * 背景为深色壁纸渐变（后续可替换为静态/轮播壁纸）。
 *
 * Dock 功能：
 * - 应用列表：进入 AppListActivity
 * - 地图：切换悬浮地图显示/隐藏（壁纸模式）
 * - 音乐：启动系统默认音乐应用
 * - 设置：打开系统设置
 *
 * 悬浮地图：左上“切换”按钮选择数据源（内置 OSM 或已安装地图应用），
 * 选择由 [MapHost] 持久化。时钟使用 TextClock 自动刷新。
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
        binding.dockMap.setOnClickListener {
            val show = binding.mapPanel.visibility != View.VISIBLE
            binding.mapPanel.visibility = if (show) View.VISIBLE else View.GONE
            Toast.makeText(
                this,
                if (show) "已显示悬浮地图" else "已切换到壁纸模式",
                Toast.LENGTH_SHORT,
            ).show()
        }
        binding.dockMusic.setOnClickListener {
            val intent = Intent(Intent.CATEGORY_APP_MUSIC).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, "未找到音乐应用", Toast.LENGTH_SHORT).show()
                }
        }
        binding.dockSettings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure {
                    Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun setupMap() {
        mapHost = MapHost(this, binding.mapContainer)
        mapHost.start(mapSources)
        binding.btnSwitchMap.setOnClickListener {
            MapPickerDialog.show(this, mapSources, mapHost.currentId) { source ->
                mapHost.select(source)
                Toast.makeText(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        val flags = (WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(flags)
    }
}
