package com.nui.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nui.launcher.databinding.ActivityMainBinding

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
 * 时钟使用 TextClock 自动刷新，无需手动维护 Handler。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDock()
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

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        val flags = (WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(flags)
    }
}
