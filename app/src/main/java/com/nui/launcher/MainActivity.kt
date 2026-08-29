package com.nui.launcher

import android.app.AlertDialog
import android.content.Intent
import android.util.Log
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
import com.nui.launcher.music.MusicHost
import com.nui.launcher.nav.NavHost

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
    private lateinit var navHost: NavHost
    private lateinit var musicHost: MusicHost
    private lateinit var wallpaper: WallpaperController
    private val mapSources by lazy { MapSources.build(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wallpaper = WallpaperController(this, binding.root)
        wallpaper.applyOnStart()
        setupDock()
        setupMap()
        setupNav()
        setupMusic()
        setupWallpaper()
        // 初始同步一次右侧面板位置
        binding.root.post { syncRightPanel() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        wallpaper.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        mapHost.onResume()
        musicHost.refresh()
    }

    override fun onPause() {
        super.onPause()
        mapHost.onPause()
    }

    override fun onDestroy() {
        mapHost.onDestroy()
        musicHost.onDestroy()
        super.onDestroy()
    }

    /**
     * 同步右侧面板位置：紧跟地图右边 + 间距，宽度默认 240dp。
     * 地图缩放挤占右侧空间时：
     * - 可用空间 >= 面板宽：正常显示 240dp
     * - 可用空间 >= 半宽：缩小面板宽度但仍显示
     * - 可用空间 < 半宽：面板隐藏（GONE），地图可铺满到屏幕右边
     * 地图调整大小时由 MapHost.onGeometryChanged 回调触发。
     */
    private fun syncRightPanel() {
        val map = binding.mapPanel
        val rp = binding.rightPanel
        val gap = (12 * resources.displayMetrics.density).toInt()
        val panelWidth = (240 * resources.displayMetrics.density).toInt()
        val rightMargin = (20 * resources.displayMetrics.density).toInt()
        val sw = resources.displayMetrics.widthPixels
        val mapLp = map.layoutParams as android.widget.FrameLayout.LayoutParams
        val mapRight = mapLp.leftMargin + map.width
        val lp = rp.layoutParams as android.widget.FrameLayout.LayoutParams
        val availForPanel = sw - rightMargin - mapRight - gap
        when {
            availForPanel >= panelWidth -> {
                rp.visibility = android.view.View.VISIBLE
                lp.leftMargin = mapRight + gap
                lp.width = panelWidth
            }
            availForPanel >= panelWidth / 2 -> {
                // 部分挤占：缩小面板宽度但仍显示
                rp.visibility = android.view.View.VISIBLE
                lp.leftMargin = mapRight + gap
                lp.width = availForPanel
            }
            else -> {
                // 面板消失，地图可铺满
                rp.visibility = android.view.View.GONE
            }
        }
        rp.layoutParams = lp
    }

    private fun setupDock() {
        binding.dockApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        binding.dockApps.setOnLongClickListener {
            Log.d("NUI", "dockApps long press")
            mapHost.toggleAdjust()
            true
        }
        renderDock()
    }

    /** 渲染 dock 槽位：空槽显示加号，已填显示应用图标 */
    private fun renderDock() {
        binding.dockItems.removeAllViews()
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val gap = (8 * dp).toInt()
        val bg = ContextCompat.getDrawable(this, R.drawable.bg_dock_item)
        val addIcon = ContextCompat.getDrawable(this, R.drawable.ic_dock_add)
        val apps = DockConfig.loadApps(this)
        for (i in 0 until DockConfig.SLOT_COUNT) {
            val app = apps.getOrNull(i)
            val btn = ImageButton(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = bg
                if (app != null) {
                    setImageDrawable(app.icon)
                    setOnClickListener { runCatching { startActivity(app.launchIntent) } }
                    setOnLongClickListener { confirmRemove(i, app); true }
                } else {
                    setImageDrawable(addIcon)
                    setOnClickListener { openPicker(i) }
                }
            }
            binding.dockItems.addView(
                btn,
                LinearLayout.LayoutParams(size, size).apply { topMargin = gap },
            )
        }
    }

    /** 点空槽加号：弹应用选择器填入指定槽位 */
    private fun openPicker(slot: Int) {
        mapHost.closeFloat()
        DockPickerDialog.show(
            context = this,
            exclude = DockConfig.filledPackages(this),
            onPick = { app ->
                DockConfig.setSlot(this, slot, app.packageName)
                renderDock()
                Toast.makeText(this, "已添加 ${app.label}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { mapHost.showFloat() },
        )
    }

    /** 长按已填槽：确认移除（变回加号即可重新添加） */
    private fun confirmRemove(slot: Int, app: AppModel) {
        mapHost.closeFloat()
        val d = AlertDialog.Builder(this)
            .setTitle("从 Dock 移除")
            .setMessage("移除 ${app.label}?")
            .setPositiveButton("移除") { _, _ ->
                DockConfig.setSlot(this, slot, null)
                renderDock()
                Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        d.setOnDismissListener { mapHost.showFloat() }
        d.show()
    }

    private fun setupMap() {
        mapHost = MapHost(this, binding.mapContainer, binding.mapPanel)
        // 地图几何变化时同步右侧面板位置
        mapHost.onGeometryChanged = { binding.root.post { syncRightPanel() } }
        mapHost.start(mapSources, autoLaunch = true)
        binding.dockBar.isClickable = true
        binding.dockBar.setOnLongClickListener { Log.d("NUI", "dock long press"); mapHost.toggleAdjust(); true }

        binding.btnSwitchMap.setOnClickListener {
            mapHost.closeFloat()
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source, autoLaunch = true)
                    Toast.makeText(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
    }

    private fun setupNav() {
        navHost = NavHost(this, binding.btnNavHome, binding.btnNavCompany)
        // 弹导航编辑/切换对话框前隐藏悬浮地图，关闭后恢复（同 MusicHost 模式）
        navHost.onHideFloat = { mapHost.closeFloat() }
        navHost.onShowFloat = { mapHost.showFloat() }
        navHost.start()
    }

    private fun setupMusic() {
        musicHost = MusicHost(this, binding.musicContainer)
        // 弹音乐选择对话框前隐藏悬浮地图，关闭后恢复
        musicHost.onHideFloat = { mapHost.closeFloat() }
        musicHost.onShowFloat = { mapHost.showFloat() }
        musicHost.start()
    }

    /** 长按时钟区 → 设置壁纸菜单 */
    private fun setupWallpaper() {
        // 弹壁纸菜单/选图前隐藏悬浮地图，关闭后恢复（同 NavHost/MusicHost 模式）
        wallpaper.onHideFloat = { mapHost.closeFloat() }
        wallpaper.onShowFloat = { mapHost.showFloat() }
        // 时钟/日期可长按触发壁纸设置
        binding.clockTime.setOnLongClickListener { wallpaper.showMenu(); true }
        binding.clockDate.setOnLongClickListener { wallpaper.showMenu(); true }
        // 右侧面板空白区也可长按
        binding.rightPanel.setOnLongClickListener { wallpaper.showMenu(); true }
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        val flags = (WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(flags)
    }
}
