package com.nui.launcher

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.nui.launcher.UiTheme

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
    private var appGridView: androidx.recyclerview.widget.RecyclerView? = null

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
            v.post {
                setupMap(); setupNav(); setupMusic(); setupWallpaper(); syncRightPanel()
                applyDockStyle()
                applyTheme()
            }
        }
    }

    private fun bindAppGrid(v: View) {
        appGridView = v.findViewById(R.id.appGridMain)
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
        applyDockStyle()
        setupPageIndicator()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (com.nui.launcher.settings.KeyMapExecutor.handle(
                    this, event.keyCode,
                    if (::musicHost.isInitialized) musicHost else null,
                    if (::navHost.isInitialized) navHost else null,
                )) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        wallpaper.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        // 设置页可能改了 Dock 形态/图标比例，返回时刷新
        applyDockStyle()
        renderDock()
        if (::mapHost.isInitialized) {
            if (binding.viewPager.currentItem == 0) mapHost.showFloat()
            mapHost.onResume()
        }
        if (::musicHost.isInitialized) {
            musicHost.refresh()
            musicHost.setFloatAreaVisible(true)
        }
    }

    /** 系统深浅模式切换（跟随系统模式）时同步重刷桌面配色 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (UiTheme.mode(this) == UiTheme.Mode.SYSTEM) {
            // resources 可能尚未同步，用 newConfig 判断深浅
            applyTheme(UiTheme.isSystemDark(newConfig))
            if (::musicHost.isInitialized) musicHost.refresh()
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

    /** 按深浅模式应用桌面配色（Dock / 右侧面板 / 地图卡片） */
    private fun applyTheme() = applyTheme(UiTheme.isDark(this))

    private fun applyTheme(dark: Boolean) {
        val p = UiTheme.palette(dark)
        val density = resources.displayMetrics.density

        // Dock 栏：半透明背景 + 时钟/图标色（圆角随 Dock 形态：贴边矩形 / 悬浮圆角）
        applyDockVisual()
        binding.dockClock.setTextColor(p.textPrimary)
        binding.dockApps.imageTintList = ColorStateList.valueOf(p.dockIconTint)
        val itemBg = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF.toInt() else 0x33000000.toInt()),
            null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * density },
        )
        for (i in 0 until binding.dockItems.childCount) {
            val c = binding.dockItems.getChildAt(i)
            c.background = itemBg
            // 空槽加号：跟随深浅模式换色
            if (c.tag == "add") {
                (c as ImageButton).imageTintList = ColorStateList.valueOf(p.dockIconTint)
            }
        }

        // 地图卡片
        desktopMapPanel?.setCardBackgroundColor(p.mapBg)
        desktopBtnSwitchMap?.imageTintList = ColorStateList.valueOf(p.textPrimary)

        // 右侧面板：背景 + 导航图标/文字 + 时钟
        desktopRightPanel?.let { rp ->
            rp.background = GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = 24 * density
                setStroke(1, p.divider)
            }
            rp.findViewById<ImageView>(R.id.navIconHome)?.setColorFilter(p.dockIconTint)
            rp.findViewById<ImageView>(R.id.navIconCompany)?.setColorFilter(p.dockIconTint)
            rp.findViewById<TextView>(R.id.navLabelHome)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.navLabelCompany)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.clockTime)?.setTextColor(p.textPrimary)
            rp.findViewById<TextView>(R.id.clockDate)?.setTextColor(p.textSecondary)
            rp.findViewById<MaterialCardView>(R.id.musicPanel)?.setCardBackgroundColor(p.mapBg)
        }
    }

    /** Dock 栏背景：颜色随深浅，圆角随形态（贴边矩形 / 悬浮圆角） */
    private fun applyDockVisual() {
        val p = UiTheme.palette(this)
        val dp = resources.displayMetrics.density
        val edge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        binding.dockBar.background = GradientDrawable().apply {
            setColor(p.dockBg)
            cornerRadius = if (edge) 12 * dp else 28 * dp
            setStroke(1, p.divider)
        }
    }

    /** 按 Dock 形态（贴边矩形 / 圆角悬浮）调整 dock 位置边距 + 宽度 + 地图/应用网格让位 */
    private fun applyDockStyle() {
        val dp = resources.displayMetrics.density
        val edge = UiTheme.dockStyle(this) == UiTheme.DockStyle.EDGE
        // Dock 宽度跟随图标比例：图标 + 两侧 12dp padding + 4dp 余量
        val dockW = UiTheme.DEFAULT_DOCK_ICON_DP * UiTheme.dockIconScale(this) + 28
        val barLp = binding.dockBar.layoutParams as FrameLayout.LayoutParams
        barLp.width = (dockW * dp).toInt()
        if (edge) {
            // 贴边：上下+左边都贴边，矩形贯穿全高（CarPlay 风格）
            barLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            barLp.leftMargin = 0
            barLp.topMargin = 0
            barLp.bottomMargin = 0
            barLp.gravity = android.view.Gravity.START
        } else {
            // 悬浮：紧凑竖条垂直居中，四边留 20dp（元素上下不再分散）
            barLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            barLp.leftMargin = (20 * dp).toInt()
            barLp.topMargin = (20 * dp).toInt()
            barLp.bottomMargin = (20 * dp).toInt()
            barLp.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        binding.dockBar.layoutParams = barLp
        applyDockVisual()

        // 地图卡片：dock 贴边时左移贴近 dock（dock宽+16dp），悬浮时再让出 20dp
        desktopMapPanel?.let { mp ->
            val lp = mp.layoutParams as FrameLayout.LayoutParams
            val left = if (edge) dockW + 16 else 20 + dockW + 16
            if (lp.leftMargin != (left * dp).toInt()) {
                lp.leftMargin = (left * dp).toInt()
                mp.layoutParams = lp
            }
        }
        // 应用网格：dock 贴边时压缩左侧 padding
        appGridView?.let { g ->
            val leftPad = if (edge) dockW + 8 else 20 + dockW + 8
            g.setPadding((leftPad * dp.toFloat()).toInt(), g.paddingTop, g.paddingEnd, g.paddingBottom)
        }
    }

    /** 渲染 dock 槽位：空槽显示加号，已填显示应用图标（图标大小随 [UiTheme.dockIconScale]） */
    private fun renderDock() {
        binding.dockItems.removeAllViews()
        val dp = resources.displayMetrics.density
        val scale = UiTheme.dockIconScale(this)
        val size = (UiTheme.DEFAULT_DOCK_ICON_DP * dp * scale).toInt()
        val gap = (4 * dp).toInt()   // 紧凑：图标间距 4dp
        val dark = UiTheme.isDark(this)
        val p = UiTheme.palette(this)
        val itemBg = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF.toInt() else 0x33000000.toInt()),
            null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * dp },
        )
        val addIcon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_dock_add)
        val apps = DockConfig.loadApps(this)
        for (i in 0 until DockConfig.SLOT_COUNT) {
            val app = apps.getOrNull(i)
            val btn = android.widget.ImageButton(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                background = itemBg
                if (app != null) {
                    setImageDrawable(app.icon)
                    setOnClickListener {
                        if (app.packageName == StockHome.PKG_STOCK_HOME) {
                            val ok = StockHome.launch(this@MainActivity)
                            if (!ok) NuiToast.show(this@MainActivity, "未找到其他桌面", Toast.LENGTH_SHORT)
                        } else {
                            runCatching { startActivity(app.launchIntent) }
                        }
                    }
                    setOnLongClickListener { confirmRemove(i, app); true }
                } else {
                    setImageDrawable(addIcon)
                    // 加号颜色跟随深浅模式：深色下浅色 +，浅色下深色 +
                    imageTintList = ColorStateList.valueOf(p.dockIconTint)
                    tag = "add"
                    setOnClickListener { openPicker(i) }
                }
            }
            binding.dockItems.addView(
                btn,
                LinearLayout.LayoutParams(size, size).apply { topMargin = gap },
            )
        }
        // dockApps 底部按钮大小跟随图标比例
        val asize = (UiTheme.DEFAULT_DOCK_ICON_DP * dp * scale).toInt()
        val appsLp = binding.dockApps.layoutParams
        if (appsLp.width != asize) {
            appsLp.width = asize
            appsLp.height = asize
            binding.dockApps.layoutParams = appsLp
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
            val settingsEntry = AppModel(
                label = getString(R.string.desktop_settings),
                packageName = packageName,
                icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_dock_settings)!!,
                launchIntent = Intent(this, com.nui.launcher.settings.SettingsActivity::class.java),
            )
            runOnUiThread {
                grid.adapter = AppListAdapter(
                    context = this,
                    apps = listOf(settingsEntry) + apps,
                    onClick = { app -> startActivity(app.launchIntent) },
                )
            }
        }.start()
    }

    /** 右侧面板（音乐栏）宽度随地图右缘动态变化：地图右移 → 音乐栏变窄，太窄则整个消失。
     *  音乐栏右缘固定贴右，左缘 = 地图右缘 + gap；地图可一直右移到音乐栏消失。 */
    private fun syncRightPanel() {
        val rp = desktopRightPanel ?: return
        val mp = desktopMapPanel ?: return
        val density = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels
        val rightMargin = (16 * density).toInt()
        val gap = (12 * density).toInt()
        val minPanel = (180 * density).toInt()          // 音乐栏最小宽度，低于则整体消失
        val availableRight = sw - rightMargin
        // 地图右缘（相对 Page0，全屏坐标系）
        val mlp = mp.layoutParams as FrameLayout.LayoutParams
        val mapRight = mlp.leftMargin + mp.width
        val panelW = availableRight - gap - mapRight
        if (panelW < minPanel) {
            // 音乐栏太窄：隐藏，地图可占满右侧
            rp.visibility = android.view.View.GONE
        } else {
            rp.visibility = android.view.View.VISIBLE
            val lp = rp.layoutParams
            if (lp.width != panelW) {
                lp.width = panelW
                rp.layoutParams = lp
            }
        }
        // 地图右缘上限：音乐栏消失后地图可铺到屏幕右缘-16dp
        if (::mapHost.isInitialized) mapHost.setRightLimit(availableRight)
    }

    private fun applyImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }
}
