package com.nui.launcher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.nui.launcher.databinding.ActivityMainBinding
import com.nui.launcher.map.MapHost
import com.nui.launcher.map.MapPickerDialog
import com.nui.launcher.map.MapSources
import com.nui.launcher.music.MusicHost
import com.nui.launcher.nav.NavHost

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapHost: MapHost
    private lateinit var navHost: NavHost
    private lateinit var musicHost: MusicHost
    private lateinit var wallpaper: WallpaperController
    private val mapSources by lazy { MapSources.build(this) }

    // Page0 (desktop) 里的 view 引用
    private var desktopMapPanel: MaterialCardView? = null
    private var desktopMapContainer: android.widget.FrameLayout? = null
    private var desktopRightPanel: LinearLayout? = null
    private var desktopBtnSwitchMap: ImageButton? = null
    private var desktopMusicContainer: android.widget.FrameLayout? = null
    private var desktopBtnNavHome: View? = null
    private var desktopBtnNavCompany: View? = null
    private var page0Ready = false
    private var appGridLoaded = false

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 2
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val v = when (viewType) {
                0 -> inflater.inflate(R.layout.page_desktop, parent, false)
                else -> inflater.inflate(R.layout.page_app_grid, parent, false)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                0 -> bindDesktop(holder.itemView)
                1 -> bindAppGrid(holder.itemView)
            }
        }
    }

    private fun bindDesktop(v: View) {
        desktopMapPanel = v.findViewById(R.id.mapPanel)
        desktopMapPanel?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncRightPanel() }
        desktopMapContainer = v.findViewById(R.id.mapContainer)
        desktopRightPanel = v.findViewById(R.id.rightPanel)
        desktopBtnSwitchMap = v.findViewById(R.id.btnSwitchMap)
        desktopMusicContainer = v.findViewById(R.id.musicContainer)
        desktopBtnNavHome = v.findViewById(R.id.btnNavHome)
        desktopBtnNavCompany = v.findViewById(R.id.btnNavCompany)
        if (!page0Ready) {
            page0Ready = true
            v.post { setupMap(); setupNav(); setupMusic(); setupWallpaper(); syncRightPanel() }
        }
    }

    private fun bindAppGrid(v: View) {
        loadAppGrid(v.findViewById(R.id.appGridMain))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        binding.viewPager.adapter = PagerAdapter()
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                if (::mapHost.isInitialized) {
                    if (position == 0) mapHost.showFloat() else mapHost.closeFloat()
                }
            }
        })

        setupDock()
        setupPageIndicator()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        wallpaper.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (::mapHost.isInitialized) {
            if (binding.viewPager.currentItem == 0) mapHost.showFloat()
            mapHost.onResume()
        }
        if (::musicHost.isInitialized) {
            musicHost.refresh()
            musicHost.setFloatAreaVisible(true)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapHost.isInitialized) mapHost.onPause()
        if (::musicHost.isInitialized) musicHost.setFloatAreaVisible(false)
    }

    override fun onDestroy() {
        if (::mapHost.isInitialized) mapHost.onDestroy()
        if (::musicHost.isInitialized) musicHost.onDestroy()
        super.onDestroy()
    }

    private fun setupDock() {
        binding.dockApps.setOnClickListener {
            if (binding.viewPager.currentItem == 0) binding.viewPager.currentItem = 1
            else binding.viewPager.currentItem = 0
        }
        binding.dockApps.setOnLongClickListener {
            if (binding.viewPager.currentItem == 0 && ::mapHost.isInitialized) {
                mapHost.toggleAdjust()
            }
            true
        }
        binding.dockBar.isClickable = true
        binding.dockBar.setOnLongClickListener {
            if (binding.viewPager.currentItem == 0 && ::mapHost.isInitialized) {
                mapHost.toggleAdjust()
            }
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
        val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_dock_item)
        val addIcon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_dock_add)
        val apps = DockConfig.loadApps(this)
        for (i in 0 until DockConfig.SLOT_COUNT) {
            val app = apps.getOrNull(i)
            val btn = android.widget.ImageButton(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
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
        if (::mapHost.isInitialized) mapHost.closeFloat()
        DockPickerDialog.show(
            context = this,
            exclude = DockConfig.filledPackages(this),
            onPick = { app ->
                DockConfig.setSlot(this, slot, app.packageName)
                renderDock()
                NuiToast.show(this, "已添加 ${app.label}", Toast.LENGTH_SHORT)
            },
            onDismiss = { if (::mapHost.isInitialized) mapHost.showFloat() },
        )
    }

    /** 长按已填槽：确认移除（变回加号即可重新添加） */
    private fun confirmRemove(slot: Int, app: AppModel): Boolean {
        if (::mapHost.isInitialized) mapHost.closeFloat()
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("从 Dock 移除")
            .setMessage("移除 ${app.label}?")
            .setPositiveButton("移除") { _, _ ->
                DockConfig.setSlot(this, slot, null)
                renderDock()
                NuiToast.show(this, "已移除", Toast.LENGTH_SHORT)
            }
            .setNegativeButton("取消", null)
            .create()
        d.setOnDismissListener { if (::mapHost.isInitialized) mapHost.showFloat() }
        d.show()
        return true
    }

    private fun setupPageIndicator() {
        val dots = arrayOfNulls<View>(2)
        for (i in 0..1) {
            val dot = View(this)
            val size = (8 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(size, size).apply {
                setMargins(if (i == 0) 0 else (12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }
            dot.layoutParams = lp
            dot.background = getDrawable(R.drawable.bg_dot_indicator)
            dot.alpha = if (i == 0) 1f else 0.35f
            dots[i] = dot
            binding.pageIndicator.addView(dot)
        }
        binding.pageIndicator.tag = dots
    }

    @Suppress("UNCHECKED_CAST")
    private fun updatePageIndicator(position: Int) {
        val dots = binding.pageIndicator.tag as Array<View>
        for (i in dots.indices) dots[i].alpha = if (i == position) 1f else 0.35f
    }

    private fun setupMap() {
        val mapPanel = desktopMapPanel ?: return
        val mapContainer = desktopMapContainer ?: return
        val rightPanel = desktopRightPanel ?: return
        val btnSwitchMap = desktopBtnSwitchMap ?: return

        mapHost = MapHost(this, mapContainer, mapPanel)
        mapHost.onGeometryChanged = { binding.root.post { syncRightPanel() } }
        mapHost.onFloatShown = { if (::musicHost.isInitialized) musicHost.bringLyricFloatToFront() }
        mapHost.onPickMap = {
            mapHost.closeFloat()
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source, autoLaunch = true)
                    NuiToast.show(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT)
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
        mapHost.start(mapSources, autoLaunch = true)

        btnSwitchMap.setOnClickListener {
            mapHost.closeFloat()
            MapPickerDialog.show(
                context = this,
                sources = mapSources,
                currentId = mapHost.currentId,
                onPick = { source ->
                    mapHost.select(source, autoLaunch = true)
                    NuiToast.show(this, "已切换为 ${source.label}", Toast.LENGTH_SHORT)
                },
                onDismiss = { mapHost.showFloat() },
            )
        }
    }

    private fun setupNav() {
        navHost = NavHost(this, desktopBtnNavHome!!, desktopBtnNavCompany!!)
        navHost.onHideFloat = { mapHost.closeFloat() }
        navHost.onShowFloat = { mapHost.showFloat() }
        navHost.start()
    }

    private fun setupMusic() {
        musicHost = MusicHost(this, desktopMusicContainer!!)
        musicHost.onHideFloat = { mapHost.closeFloat() }
        musicHost.onShowFloat = { mapHost.showFloat() }
        musicHost.floatBoundsProvider = {
            if (::mapHost.isInitialized) mapHost.floatBounds() else null
        }
        musicHost.start()
    }

    private fun setupWallpaper() {
        wallpaper = WallpaperController(this, binding.root)
        wallpaper.applyOnStart()
        wallpaper.onHideFloat = { mapHost.closeFloat() }
        wallpaper.onShowFloat = { mapHost.showFloat() }
        val rightPanel = desktopRightPanel ?: return
        val clockArea = rightPanel.getChildAt(1) as? LinearLayout
        clockArea?.setOnLongClickListener { wallpaper.showMenu(); true }
    }

    private fun loadAppGrid(grid: RecyclerView) {
        if (appGridLoaded) return
        appGridLoaded = true
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.setHasFixedSize(true)
        grid.itemAnimator = null

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
                grid.adapter = AppListAdapter(
                    context = this,
                    apps = apps,
                    onClick = { app -> startActivity(app.launchIntent) },
                )
            }
        }.start()
    }

    /** 同步右侧面板位置（Page 0 的 mapPanel 右边 + gap） */
    private fun syncRightPanel() {
        val rp = desktopRightPanel ?: return
        val map = desktopMapPanel ?: return
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
                rp.visibility = android.view.View.VISIBLE
                lp.leftMargin = mapRight + gap
                lp.width = availForPanel
            }
            else -> {
                rp.visibility = android.view.View.GONE
            }
        }
        rp.layoutParams = lp
    }

    private fun applyImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }
}
