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
        desktopMapContainer = v.findViewById(R.id.mapContainer)
        desktopRightPanel = v.findViewById(R.id.rightPanel)
        desktopBtnSwitchMap = v.findViewById(R.id.btnSwitchMap)
        desktopMusicContainer = v.findViewById(R.id.musicContainer)
        desktopBtnNavHome = v.findViewById(R.id.btnNavHome)
        desktopBtnNavCompany = v.findViewById(R.id.btnNavCompany)
        if (!page0Ready) {
            page0Ready = true
            v.post { setupMap(); setupNav(); setupMusic(); setupWallpaper() }
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

    override fun onResume() {
        super.onResume()
        if (::mapHost.isInitialized) {
            if (binding.viewPager.currentItem == 0) mapHost.showFloat()
            mapHost.onResume()
        }
        if (::musicHost.isInitialized) musicHost.refresh()
    }

    override fun onPause() {
        super.onPause()
        if (::mapHost.isInitialized) mapHost.onPause()
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
        binding.dockBar.isClickable = true
        binding.dockBar.setOnLongClickListener {
            if (binding.viewPager.currentItem == 0 && ::mapHost.isInitialized) {
                mapHost.toggleAdjust()
            }
            true
        }
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
        mapHost.onGeometryChanged = { binding.root.post { /* sync handled by MapHost */ } }
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
        musicHost.start()
    }

    private fun setupWallpaper() {
        wallpaper = WallpaperController(this, binding.root)
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

    private fun applyImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }
}
