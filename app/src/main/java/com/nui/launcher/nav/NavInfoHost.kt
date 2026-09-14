package com.nui.launcher.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nui.launcher.R
import com.nui.launcher.voice.NuiTts
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导航/巡航信息显示（右上角"回家/公司/收藏"按钮区动态变化）。
 *
 * 监听高德车机版广播：
 * - 10019：导航/巡航状态（权威判据）
 *   - EXTRA_STATE=8 → 导航中；9 → 导航结束（恢复按钮区）
 *   - EXTRA_STATE=24 → 巡航进入；25 → 巡航退出（恢复按钮区）
 * - 10001：导航/巡航信息
 *   - NEW_ICON/ICON：转向图标编号（2~20，映射 sou{N}_night_a530 资源）
 *   - endPOIName：终点名称；ROUTE_REMAIN_TIME_AUTO/DIS_AUTO：全程剩余
 *   - CAMERA_DIST / CAMERA_SPEED：电子眼距离 / 测速限速
 *   - EXIT_NAME_INFO / EXIT_DIRECTION_INFO：高速出口
 *   - CUR_SPEED / LIMITED_SPEED：当前速度 / 限速
 *   - ROAD_TYPE / SAPA_DIST_AUTO / SAPA_NAME / SAPA_NUM：服务区（仅高速）
 * - 60073：红绿灯数据（巡航模式 lightsData 多方向，取第一个=最近的显示）
 *
 * 显示策略：
 * - 导航中（ICON≠0 或 STATE=8）：显示导航卡（终点+全程 / 电子眼 / 出口 / 速度 / 服务区）
 * - 巡航中（ICON=0 且 STATE=24）：显示巡航卡（当前速度大字 / 最近测速 / 最近红绿灯）
 * - 超速检测：巡航下 CUR_SPEED > LIMITED_SPEED 时语音提醒 + 速度变红
 * - 变灯提醒：由 TrafficLightMonitor 负责（速度≤20km/h 时红灯倒计时≤3s 语音提醒）
 */
class NavInfoHost(
    private val context: Context,
    private val overlay: View,
) {
    companion object {
        private const val TAG = "NUI.NavInfo"
        private const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
        private const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"
        private const val KEY_TYPE_NAV_STATE = 10019
        private const val KEY_TYPE_NAVI_INFO = 10001
        private const val KEY_TYPE_TRAFFIC_LIGHT = 60073
        private const val KEY_TYPE_TMC = 13011
        private const val NAV_STATE_NAVIGATING = 8
        private const val NAV_STATE_NAV_EXIT = 9
        // 10019 状态表（参考 Navi-Link 实测）：25=巡航结束（退出巡航界面）；46/47 实车不可靠，已弃用
        private const val NAV_STATE_CRUISE_END = 25
    }

    // 模式：NONE=普通桌面 / NAVI=导航 / CRUISE=巡航
    private enum class Mode { NONE, NAVI, CRUISE }
    private var mode = Mode.NONE

    // 导航卡元素
    private var turnView: ImageView? = null
    private var destView: TextView? = null
    private var etaView: TextView? = null
    private var cameraView: ViewGroup? = null
    private var exitView: TextView? = null
    private var tmcView: TextView? = null
    private var speedView: TextView? = null
    private var navBlock: View? = null

    // 巡航卡元素
    private var cruiseBlock: View? = null
    private var cruiseSpeedView: TextView? = null
    private var cruiseCameraView: ViewGroup? = null
    private var cruiseLightView: LinearLayout? = null   // 红绿灯容器（每方向一个胶囊，动态生成）

    private var weatherView: View? = null

    // 三按钮（回家/公司/收藏）：从 overlay 父容器动态查找——page0 由 ViewPager2 管理，
    // ViewHolder 重建后旧引用会失效，必须每次从当前视图树获取
    private fun navButton(id: Int): View? = (overlay.parent as? ViewGroup)?.findViewById(id)
    private val navButtonIds = listOf(R.id.btnNavHome, R.id.btnNavCompany, R.id.btnNavFavorite)

    // 巡航状态（供超速/变灯判断）
    private var curSpeed = 0
    private var limitedSpeed = 0
    // 巡航限速：车机巡航广播 LIMITED_SPEED 恒为 50（高德巡航默认值，不可信），
    // 巡航超速判断/播报改用测速点 CAMERA_SPEED；LIMITED_SPEED 仅导航卡使用
    private var cruiseLimit = 0
    private var overspeedAlerted = false   // 超速去重
    private var tts: NuiTts? = null

    // 数据断流看门狗：导航/巡航态下超过该时长收不到任何 10001/10019 → 恢复按钮区
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val DATA_TIMEOUT_MS = 15_000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("KEY_TYPE", -1)) {
                KEY_TYPE_NAV_STATE -> handleNavState(intent)
                KEY_TYPE_NAVI_INFO -> handleNaviInfo(intent)
                KEY_TYPE_TRAFFIC_LIGHT -> handleTrafficLight(intent)
                KEY_TYPE_TMC -> handleTmc(intent)
            }
        }
    }

    fun start() {
        turnView = overlay.findViewById(R.id.navInfoTurn)
        destView = overlay.findViewById(R.id.navInfoDest)
        etaView = overlay.findViewById(R.id.navInfoEta)
        cameraView = overlay.findViewById(R.id.navInfoCamera)
        exitView = overlay.findViewById(R.id.navInfoExit)
        tmcView = overlay.findViewById(R.id.navInfoTmc)
        speedView = overlay.findViewById(R.id.navInfoSpeed)
        navBlock = overlay.findViewById(R.id.navInfoNavBlock)
        cruiseBlock = overlay.findViewById(R.id.navInfoCruiseBlock)
        cruiseSpeedView = overlay.findViewById(R.id.cruiseSpeed)
        cruiseCameraView = overlay.findViewById(R.id.cruiseCamera)
        cruiseLightView = overlay.findViewById(R.id.cruiseLight) as LinearLayout
        // 天气文字在右侧面板（导航卡的兄弟节点）：导航时占掉天气区域的位置
        weatherView = (overlay.parent as? ViewGroup)?.findViewById(R.id.weatherText)
        tts = NuiTts(context)
        context.registerReceiver(receiver, IntentFilter(ACTION_SEND))
        // 启动 2s 后主动查询：12404 导航状态 + 13030 昼夜模式，结果都通过 10019 返回
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            queryNavState()
            queryDayNight()
        }, 2000L)
        Log.i(TAG, "导航/巡航信息显示已启动")
    }

    /** 高德巡航播报临时静音：10047 EXTRA_CASUAL_MUTE（进巡航静音，退巡航/导航恢复）。
     *  对齐实测用法（CSDN/am 命令）：显式指定 AmapAutoBroadcastReceiver、只带 EXTRA_CASUAL_MUTE；
     *  部分高德版本 receiver 类名可能不同，隐式 action 版本兜底双发（静音幂等，重复无害）。 */
    private fun setAmapCruiseMute(mute: Boolean) {
        val v = if (mute) 1 else 0
        val intents = listOf(
            Intent().apply {
                setClassName("com.autonavi.amapauto", "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver")
                action = ACTION_RECV
                putExtra("KEY_TYPE", 10047)
                putExtra("EXTRA_CASUAL_MUTE", v)
                putExtra("SOURCE_APP", context.packageName)
            },
            Intent(ACTION_RECV).apply {
                putExtra("KEY_TYPE", 10047)
                putExtra("EXTRA_CASUAL_MUTE", v)
                putExtra("SOURCE_APP", context.packageName)
            },
        )
        intents.forEach { q -> runCatching { context.sendBroadcast(q) } }
        Log.i(TAG, if (mute) "高德巡航播报临时静音(10047 显式+隐式)" else "高德巡航播报恢复(10047 显式+隐式)")
    }

    /** 主动查询导航状态：12404 EXTRA_REQUEST_AUTO_STATE=1 → 高德回 10019 STATE=8/9 */
    fun queryNavState() {
        val q = Intent(ACTION_RECV).apply {
            putExtra("KEY_TYPE", 12404)
            putExtra("EXTRA_REQUEST_AUTO_STATE", 1)
            putExtra("SOURCE_APP", context.packageName)
        }
        runCatching { context.sendBroadcast(q) }
            .onSuccess { Log.i(TAG, "已主动查询导航状态(12404)") }
    }

    /** 主动查询昼夜模式：13030 → 高德回 10019 EXTRA_STATE=37/38 */
    fun queryDayNight() {
        val q = Intent(ACTION_RECV).apply {
            putExtra("KEY_TYPE", 13030)
            putExtra("SOURCE_APP", context.packageName)
        }
        runCatching { context.sendBroadcast(q) }
            .onSuccess { Log.i(TAG, "已向高德查询昼夜模式(13030)") }
    }

    /** 外观切换时调用：导航卡背景是深蓝黑实色卡片，内部文字固定白色/浅蓝，不随外观 */
    fun applyTheme() {
        turnView?.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        Log.i(TAG, "导航/巡航卡固定深色底白字（不随外观）")
    }

    fun stop() {
        stopDataWatchdog()
        runCatching { context.unregisterReceiver(receiver) }
        tts = null
        Log.i(TAG, "导航/巡航信息显示已停止")
    }

    // ---------- 10019 状态 ----------

    private fun handleNavState(intent: Intent) {
        val state = intent.getIntExtra("EXTRA_STATE", -1)
        when (state) {
            // 8：导航中（权威导航判据）
            NAV_STATE_NAVIGATING -> setMode(Mode.NAVI)
            // 9：导航结束（回桌面）。仅结束导航态：巡航中收到 STATE=9（高德巡航下
            // 12404 查询即返回 9，会被周期性查询误杀巡航），巡航退出靠 STATE=25 / 看门狗
            NAV_STATE_NAV_EXIT -> {
                if (mode == Mode.NAVI) setMode(Mode.NONE)
            }
            // 25：巡航结束（退出巡航界面）
            NAV_STATE_CRUISE_END -> {
                if (mode == Mode.CRUISE) setMode(Mode.NONE)
            }
        }
        // 路口大图状态（无图片内容，仅状态）1=显示 0=消失
        if (intent.hasExtra("EXTRA_CROSS_MAP")) {
            Log.i(TAG, "路口大图: ${if (intent.getIntExtra("EXTRA_CROSS_MAP", 0) == 1) "显示" else "消失"}")
        }
    }

    // 桌面页可见性：切到应用列表页（page1+）隐藏悬浮信息卡，回 page0 恢复。
    // 与广播驱动的 setMode 叠加：两者都满足才显示 overlay。
    private var pageVisible = true

    /** 页面切换回调：0=桌面页（显示导航/巡航信息卡），其它页（应用列表等）隐藏 */
    fun onPageChanged(page: Int) {
        pageVisible = page == 0
        applyVisibility()
        Log.i(TAG, "onPageChanged page=$page pageVisible=$pageVisible overlay=vis${overlay.visibility}")
    }

    private fun applyVisibility() {
        overlay.visibility = if (mode != Mode.NONE && pageVisible) View.VISIBLE else View.GONE
    }

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        val old = mode
        mode = newMode
        // 高德巡航播报临时静音：进巡航静音，退巡航/进导航恢复（临时静音单次有效，不误伤导航语音）
        if (newMode == Mode.CRUISE && old != Mode.CRUISE) setAmapCruiseMute(true)
        if (old == Mode.CRUISE && newMode != Mode.CRUISE) setAmapCruiseMute(false)
        Log.i(TAG, "模式: ${old.name} -> ${newMode.name}")
        val active = newMode != Mode.NONE
        applyVisibility()
        // 导航：按钮让位给导航卡（全屏信息）；巡航：保留三按钮（回家/公司/收藏，便于操作），巡航卡显示在其下方
        val curButtons = navButtonIds.mapNotNull { navButton(it) }
        curButtons.forEach { it.visibility = if (newMode == Mode.NAVI) View.GONE else View.VISIBLE }
        val row = navButton(R.id.btnNavHome)?.parent
        val rowInfo = if (row is View) "vis=${row.visibility}/h=${row.height}" else "parent=${row?.javaClass?.simpleName}"
        val b0 = curButtons.firstOrNull()
        val loc = if (b0 != null && b0.isAttachedToWindow) {
            val p = IntArray(2); b0.getLocationOnScreen(p); "xy=${p[0]},${p[1]}"
        } else { "attached=${b0?.isAttachedToWindow}" }
        Log.d(TAG, "setMode=$newMode 按钮=${curButtons.joinToString { "vis${it.visibility}/h${it.height}" }} $loc navRow=$rowInfo overlay=vis${overlay.visibility}/h${overlay.height}")
        // 导航/巡航内容块互斥
        navBlock?.visibility = if (newMode == Mode.NAVI) View.VISIBLE else View.GONE
        cruiseBlock?.visibility = if (newMode == Mode.CRUISE) View.VISIBLE else View.GONE
        if (!active) {
            overspeedAlerted = false
            cruiseLightView?.visibility = View.GONE
            stopDataWatchdog()
        }
        if (active) resetDataWatchdog() else stopDataWatchdog()
        // 占用天气区域：隐藏天气文字；退出恢复
        weatherView?.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** MainActivity 更新天气时调用：导航/巡航中返回 true 则不显示天气文字（区域被占用） */
    fun isNavActive(): Boolean = mode != Mode.NONE

    /** 看门狗：每次收到导航数据重置 15s 计时，超时自动恢复按钮区 */
    private val watchdog = Runnable {
        Log.i(TAG, "看门狗超时：15s 无导航/巡航数据，恢复按钮区")
        setMode(Mode.NONE)
    }
    private fun resetDataWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, DATA_TIMEOUT_MS)
    }

    private fun stopDataWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
    }

    // ---------- 10001 导航/巡航信息 ----------

    private fun handleNaviInfo(intent: Intent) {
        // 模式判据（对齐 Navi-Link 实测方式）：
        // - ICON≠0（NEW_ICON 优先，ICON 兜底）：有转向引导 → 导航模式
        // - ICON=0：巡航数据。非导航模式下进入/更新巡航；导航模式下忽略巡航数据（不打断导航，
        //   导航活跃只由 ICON≠0 定义，导航结束由 STATE=9 或导航看门狗处理）
        // - 巡航结束：10019 STATE=25
        // （46/47 实车不可靠，已弃用；协议文档 TYPE 字段实测高德不带，已弃用）
        var icon = intent.getIntExtra("NEW_ICON", 0)
        if (icon == 0) icon = intent.getIntExtra("ICON", 0)

        // 速度/限速：导航和巡航都更新（超速检测）
        val speed = intent.getIntExtra("CUR_SPEED", 0)
        val limited = intent.getIntExtra("LIMITED_SPEED", 0)
        if (speed > 0) curSpeed = speed
        if (limited > 0) limitedSpeed = limited

        if (icon == 0) {
            when (mode) {
                Mode.NAVI -> {
                    // 导航中忽略巡航广播（双高德共存时不打断导航）；仅更新速度/限速/电子眼供导航卡
                    updateCamera(intent, cameraView)
                    renderNavSpeed(speedView, curSpeed, limitedSpeed)
                    resetDataWatchdog()
                    checkOverspeed()
                }
                else -> {
                    // ICON=0 → 巡航数据：进入或更新巡航卡
                    if (mode != Mode.CRUISE) {
                        setMode(Mode.CRUISE)
                        Log.i(TAG, "巡航进入: ICON=0")
                    }
                    updateCruise(intent)
                    resetDataWatchdog()
                    checkOverspeed()
                }
            }
            return
        }

        // ICON≠0：导航数据，立即显示导航卡
        setMode(Mode.NAVI)
        resetDataWatchdog()

        // 导航卡不显示转向图标/信息，速度区尽量放大当前速度
        turnView?.visibility = View.GONE

        // 终点名 + 全程剩余
        val dest = intent.getStringExtra("endPOIName") ?: ""
        val remainTime = intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO") ?: ""
        val remainDis = intent.getStringExtra("ROUTE_REMAIN_DIS_AUTO")
            ?: intent.getStringExtra("ROUTE_REMAIN_DIS") ?: ""
        destView?.text = dest.ifBlank { "导航中" }
        // 到达时间 = 当前时间 + 剩余时长（ROUTE_REMAIN_TIME_AUTO 如"50分钟"/"1小时20分钟"）
        val arriveTime = formatArriveTime(parseRemainMinutes(remainTime))
        val etaParts = listOf(
            arriveTime,
            remainDis,
        ).filter { it.isNotBlank() }
        etaView?.text = etaParts.joinToString(" · ").ifBlank { "导航中" }

        // 电子眼
        updateCamera(intent, cameraView)

        // 出口
        val exitName = intent.getStringExtra("EXIT_NAME_INFO") ?: ""
        val exitDir = intent.getStringExtra("EXIT_DIRECTION_INFO") ?: ""
        if (exitName.isNotBlank()) {
            exitView?.text = "出口 $exitName" + if (exitDir.isNotBlank()) " · $exitDir" else ""
            exitView?.visibility = View.VISIBLE
        } else {
            exitView?.visibility = View.GONE
        }

        // 当前速度大字（含限速小字）
        renderNavSpeed(speedView, curSpeed, limitedSpeed)

        Log.i(TAG, "导航信息: 转向=$icon 终点=$dest 全程=${etaParts.joinToString("/")} " +
            "出口=$exitName$exitDir 速度=${curSpeed}km/h 限速=$limitedSpeed")
    }

    /** 巡航卡更新：当前速度大字 + 最近测速 */
    private fun updateCruise(intent: Intent) {
        val speedText = if (curSpeed > 0) "$curSpeed" else "--"
        cruiseSpeedView?.text = speedText
        // 巡航限速：只认测速点 CAMERA_SPEED（LIMITED_SPEED 巡航下恒 50 不可信）
        val camSpeed = intent.getIntExtra("CAMERA_SPEED", 0)
        if (camSpeed > 0) cruiseLimit = camSpeed
        // 超速时速度变红
        if (cruiseLimit > 0 && curSpeed > cruiseLimit) {
            cruiseSpeedView?.setTextColor(0xFFFF6B6B.toInt())
        } else {
            cruiseSpeedView?.setTextColor(0xFFFFFFFF.toInt())
        }
        updateCamera(intent, cruiseCameraView)
    }

    /** 电子眼：距离+限速/类型；有则显示黄色警示，无则隐藏 */
    private fun updateCamera(intent: Intent, view: ViewGroup?) {
        // 兼容两种类型：部分高德版本 CAMERA_DIST 是 int（实测），部分可能是字符串
        val camDistRaw = intent.extras?.get("CAMERA_DIST")
        val cameraDist = when (camDistRaw) {
            is Int -> if (camDistRaw > 0) "$camDistRaw" else ""
            is String -> camDistRaw
            else -> ""
        }
        val cameraSpeed = intent.getIntExtra("CAMERA_SPEED", 0)
        val cameraType = intent.getIntExtra("CAMERA_TYPE", 0)
        val icon = view?.findViewById<ImageView>(R.id.navInfoCameraIcon)
            ?: view?.findViewById<ImageView>(R.id.cruiseCameraIcon)
        val text = view?.findViewById<TextView>(R.id.navInfoCameraText)
            ?: view?.findViewById<TextView>(R.id.cruiseCameraText)
        if (cameraDist.isNotBlank()) {
            // 摄像头类型图标（Navi-Link 同款资源；协议 CAMERA_TYPE：0=测速 1=监控 4=公交专用道）
            val iconRes = when (cameraType) {
                4 -> R.drawable.camera_bus
                1 -> R.drawable.camera_light
                else -> R.drawable.camera_default
            }
            icon?.setImageResource(iconRes)
            val warn = buildString {
                append(cameraDist.withSmallUnit())
                when {
                    cameraSpeed > 0 -> append(" 限速$cameraSpeed")          // 测速摄像头（含限速）
                    cameraType == 1 -> append(" 违章抓拍")                   // 监控/压线摄像头
                    cameraType == 4 -> append(" 公交专用道")                  // 公交专用道摄像头
                }
            }
            text?.text = warn
            view?.visibility = View.VISIBLE
        } else {
            view?.visibility = View.GONE
        }
    }

    // ---------- 60073 红绿灯（巡航显示 + 变灯提醒） ----------

    /**
     * 巡航红绿灯：遍历 lightsData 每个方向，动态生成"胶囊"（深蓝圆角背景 +
     * 圆形灯(颜色随灯状态) + 圆内白色方向箭头 + 右侧白色倒计时数字），
     * 参考 Navi-Link TrafficLightView 样式，多个方向横排。
     */
    private fun handleTrafficLight(intent: Intent) {
        // 60073 红绿灯是巡航专属广播：高德发它说明正在巡航。若巡航检测（46/ICON=0）
        // 因版本差异失败，收到有效红绿灯数据仍进入巡航显示，避免"有数据不显示"
        val lights = intent.getStringExtra("lightsData")
            ?: intent.getStringExtra("LIGHTS_DATA")
        if (mode == Mode.NAVI || lights.isNullOrBlank()) {
            if (mode != Mode.NAVI) cruiseLightView?.visibility = View.GONE
            return
        }
        if (mode != Mode.CRUISE) setMode(Mode.CRUISE)
        resetDataWatchdog()
        val container = cruiseLightView ?: return
        try {
            val array = JSONArray(lights)
            if (array.length() == 0) {
                container.visibility = View.GONE
                return
            }
            container.removeAllViews()
            val dm = context.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * dm + 0.5f).toInt()
            // 多方向时紧凑模式（参考 Navi-Link setCompact）：圆/字缩小，保证 4 个以上胶囊放得下
            val compact = array.length() >= 3
            val dotSize = if (compact) dp(26) else dp(44)
            val timeSize = if (compact) 18f else 30f
            val padH = if (compact) dp(3) else dp(6)
            val itemMargin = if (compact) dp(3) else dp(8)
            // 最近（第一个）红灯倒计时，供变灯提醒用
            var firstRedCountdown = -1
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val dir = obj.optString("dir", obj.optString("direction", "路口"))
                val status = obj.optString(
                    "trafficLightStatus",
                    obj.optString("status", obj.optString("state", "unknown"))
                )
                val countdown = obj.optInt(
                    "redLightCountDownSeconds",
                    obj.optInt("countdown", obj.optInt("countDown",
                        obj.optInt("remaining_time", -1)))
                )
                val isRed = status.contains("red", ignoreCase = true) ||
                    status == "1" || status == "0"
                val color = when {
                    isRed -> 0xFFFF3333.toInt()
                    status.contains("green", true) -> 0xFF34C759.toInt()
                    else -> 0xFFCC9900.toInt()
                }
                if (i == 0 && isRed) firstRedCountdown = countdown
                // 方向 → 箭头图标（Navi-Link 同款矢量箭头，叠加在圆形灯上）
                val arrowRes = when {
                    dir.contains("左") -> R.drawable.light_left
                    dir.contains("右") -> R.drawable.light_right
                    dir.contains("掉头") || dir.contains("回转") || dir.contains("调头") -> R.drawable.light_u_turn
                    else -> R.drawable.light_straight
                }
                // 胶囊：深蓝圆角背景 + [圆形灯(带箭头) + 倒计时数字]
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(padH, dp(2), padH, dp(2))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        setColor(0xE61E2A3A.toInt())   // 深蓝半透明胶囊
                    }
                }
                // 圆形灯 FrameLayout：底层圆形(灯色) + 上层箭头
                val dotBox = FrameLayout(context)
                dotBox.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
                val dot = View(context)
                dot.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                }
                // 方向箭头（图片资源，白色箭头叠加在圆形灯上）
                val arrowIv = ImageView(context).apply {
                    setImageResource(arrowRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                }
                dotBox.addView(dot)
                dotBox.addView(arrowIv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // 倒计时数字
                val timeTv = TextView(context).apply {
                    text = if (countdown >= 0) "$countdown" else ""
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = timeSize
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                }
                timeTv.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(4); marginEnd = dp(2)
                }
                item.addView(dotBox)
                item.addView(timeTv)
                container.addView(item, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = itemMargin
                })
                Log.d(TAG, "巡航红绿灯[$i]: dir=$dir status=$status ${countdown}秒 速度=${curSpeed}km/h")
            }
            container.visibility = View.VISIBLE

            // 变灯提醒：巡航中最近红灯倒计时 ≤3s 语音提醒（不设速度条件，高德悬浮窗巡航即有红绿灯）
            if (firstRedCountdown in 1..3) {
                val text = if (firstRedCountdown <= 1) "绿灯即将亮起" else "${firstRedCountdown}秒后变绿"
                tts?.speak(text)
                Log.i(TAG, "变灯提醒(巡航): $text")
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析红绿灯失败: ${e.message}")
        }
    }

    // ---------- 13011 TMC 实时路况（前方拥堵） ----------

    /**
     * 导航卡"前方拥堵"：
     * 分段坐标系判定——协议文档称所有段距离之和=residual_distance（分段覆盖剩余路程、从当前位置起算），
     * 但实测（参考 Navi-Link 实现按 total_distance 画比例）高德真实广播的分段覆盖全程（段之和≈total_distance），
     * 段0 是路线起点而非当前位置，必须用 finish_distance（已行驶里程）定位当前位置所在的段，再从当前段往后找拥堵。
     * 兼容两种坐标系：segSum≈residual → 当前位置=段0 起点；否则按全程坐标系用 finish_distance 定位。
     * 显示距下一段拥堵的实时距离（红=拥堵 / 深红=严重拥堵）；前方无拥堵则隐藏。
     */
    private fun handleTmc(intent: Intent) {
        val view = tmcView ?: return
        if (mode != Mode.NAVI) return
        val json = intent.getStringExtra("EXTRA_TMC_SEGMENT") ?: return
        try {
            val root = JSONObject(json)
            // 打印完整原始数据，便于核对真实广播的坐标系与字段
            Log.i(TAG, "TMC原始: $json")
            if (!root.optBoolean("tmc_segment_enabled", true)) {
                view.visibility = View.GONE
                return
            }
            val info = root.optJSONArray("tmc_info")
            if (info == null || info.length() == 0) {
                view.visibility = View.GONE
                return
            }
            val totalDistance = root.optInt("total_distance", 0)
            val residualDistance = root.optInt("residual_distance", 0)
            val finishDistance = root.optInt("finish_distance", 0)

            // 每段起点（分段坐标系内）+ 段总距离
            val segStart = IntArray(info.length())
            var segSum = 0
            for (i in 0 until info.length()) {
                segStart[i] = segSum
                segSum += info.getJSONObject(i).optInt("tmc_segment_distance", 0)
            }

            // 坐标系判定：真实广播为全程坐标系（段之和≈total_distance，段0=路线起点），
            // 用 finish_distance(已行驶) 定位当前位置所在段，拥堵距离随行驶实时减小；
            // 兼容旧版"剩余路程坐标系"（段之和≈residual 且不≈total，段0=当前位置）。
            // 注意不能只用 segSum≈residual 判断：刚出发时剩余≈总路程会误判成剩余坐标系，
            // 导致不减去已行驶里程、拥堵距离不实时变化。
            val isTotalCoords = totalDistance > 0 &&
                Math.abs(segSum - totalDistance) <= Math.max(100, totalDistance / 10)
            val isResidualCoords = !isTotalCoords && residualDistance > 0 &&
                Math.abs(segSum - residualDistance) <= Math.max(100, residualDistance / 10)
            val curOffset = if (isResidualCoords) 0 else finishDistance

            // 起点段：residual 坐标系=段0；全程坐标系=finish_distance 落在的段
            var firstIdx = 0
            if (!isResidualCoords) {
                firstIdx = -1
                for (i in info.length() - 1 downTo 0) {
                    if (segStart[i] <= curOffset) {
                        firstIdx = i
                        break
                    }
                }
                if (firstIdx < 0) firstIdx = 0
            }

            var aheadMeters = -1
            var targetStatus = -1
            var congestionEndMeters = -1
            for (i in firstIdx until info.length()) {
                val status = info.getJSONObject(i).optInt("tmc_status", -1)
                // 缓行(2)/拥堵(3)/严重拥堵(4) 都纳入，颜色区分；10=已驶过(灰)等其它状态跳过
                if (status == 2 || status == 3 || status == 4) {
                    aheadMeters = segStart[i] - curOffset
                    targetStatus = status
                    congestionEndMeters = segStart[i] +
                        info.getJSONObject(i).optInt("tmc_segment_distance", 0) - curOffset
                    break
                }
            }
            if (targetStatus < 0) {
                // 剩余路段无拥堵
                view.visibility = View.GONE
                return
            }
            val level = when (targetStatus) {
                4 -> "严重拥堵"
                3 -> "拥堵"
                else -> "缓行"
            }
            val text = if (aheadMeters <= 0) {
                // 当前位置已在拥堵段内：显示剩余距离（结束拥堵还需多远）
                val remain = if (congestionEndMeters > 0) congestionEndMeters else 0
                "当前$level · 剩余${formatAhead(remain)}"
            } else {
                "前方${formatAhead(aheadMeters)} $level"
            }
            view.text = text
            view.setTextColor(
                when (targetStatus) {
                    4 -> 0xFFD50000.toInt() // 严重拥堵：深红
                    3 -> 0xFFFF5252.toInt() // 拥堵：红
                    else -> 0xFFFFC400.toInt() // 缓行：黄
                }
            )
            view.visibility = View.VISIBLE
            resetDataWatchdog()
            Log.i(TAG, "前方拥堵: $text (status=$targetStatus 距=${aheadMeters}m 结束=${congestionEndMeters}m 坐标系=${if (isResidualCoords) "剩余路程" else "全程"} 段和=$segSum total=$totalDistance residual=$residualDistance finish=$finishDistance)")
        } catch (e: Exception) {
            Log.w(TAG, "解析TMC路况失败: ${e.message}")
        }
    }

    /** 米 → 友好文本：≥1000 米转公里（1位小数），并缩小单位字 */
    private fun formatAhead(meters: Int): CharSequence {
        val text = if (meters >= 1000) {
            val km = meters / 1000.0
            (if (km >= 10) "${km.toInt()}" else String.format("%.1f", km)) + "公里"
        } else "${meters}米"
        return text.withSmallUnit()
    }

    /**
     * 导航卡当前速度大字渲染：数字 2.4x 加粗（无单位），
     * 限速以" 限速80"小字灰蓝附加（可选），速度未知则只显示限速。
     * 颜色随超速程度变亮：不超速白 / 超10%内黄 / 超20%内橙 / 超20%以上亮红。
     */
    private fun renderNavSpeed(view: TextView?, speed: Int, limit: Int) {
        if (view == null) return
        val ss = SpannableStringBuilder()
        if (speed > 0) {
            val num = "$speed"
            ss.append(num)
            ss.setSpan(RelativeSizeSpan(2.4f), 0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(speedColor(speed, limit)), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (limit > 0) {
            if (ss.isNotEmpty()) ss.append("  ")
            val lt = "限速$limit"
            ss.append(lt)
            ss.setSpan(RelativeSizeSpan(0.75f), ss.length - lt.length, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(0xFFB0C4DE.toInt()), ss.length - lt.length, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = ss
    }

    /** 速度颜色：不超速=白；超速≤10%=黄；≤20%=橙；>20%=亮红（越严重越亮） */
    private fun speedColor(speed: Int, limit: Int): Int {
        if (limit <= 0) return 0xFFFFFFFF.toInt()
        val over = speed - limit
        if (over <= 0) return 0xFFFFFFFF.toInt()
        val pct = over * 100 / limit
        return when {
            pct <= 10 -> 0xFFFFD600.toInt() // 黄
            pct <= 20 -> 0xFFFF9100.toInt() // 橙
            else -> 0xFFFF1744.toInt()      // 亮红
        }
    }

    /** 超速检测：巡航下速度>限速 时语音提醒一次（回落后再超速会再提醒） */
    private fun checkOverspeed() {
        if (curSpeed <= 0) return
        // 巡航限速用测速点（cruiseLimit），导航用 LIMITED_SPEED（导航下真实）
        val limit = if (mode == Mode.CRUISE) cruiseLimit else limitedSpeed
        if (limit <= 0) return
        if (curSpeed > limit) {
            if (!overspeedAlerted) {
                overspeedAlerted = true
                tts?.speak("您已超速，当前限速${limit}")
                Log.i(TAG, "超速提醒: ${curSpeed}km/h > 限速${limit}km/h")
            }
        } else {
            overspeedAlerted = false
        }
    }

    /** 距离单位缩小（数字大、单位小） */
    private fun CharSequence.withSmallUnit(): SpannableString {
        val s = SpannableString(this)
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米|km|m)\\b").find(this)
        if (m != null) {
            val unitStart = m.range.first + m.groupValues[1].length
            s.setSpan(RelativeSizeSpan(0.55f), unitStart, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** 解析高德剩余时长文本（"50分钟"/"1小时20分钟"）为分钟数；无法解析返回 -1 */
    private fun parseRemainMinutes(raw: String): Int {
        if (raw.isBlank()) return -1
        var minutes = 0
        Regex("(\\d+(?:\\.\\d+)?)\\s*小时").find(raw)?.let {
            minutes += (it.groupValues[1].toDouble() * 60).toInt()
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*分钟").find(raw)?.let {
            minutes += it.groupValues[1].toDouble().toInt()
        }
        if (minutes == 0) {
            // 纯数字兜底（万一格式是"120"）
            raw.trim().toIntOrNull()?.let { minutes = it }
        }
        return if (minutes > 0) minutes else -1
    }

    /** 到达时间 = 当前时间 + 剩余分钟，格式 HH:mm */
    private fun formatArriveTime(minutes: Int): String {
        if (minutes <= 0) return ""
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, minutes)
        return String.format(
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    /** 转向图标编号 → 资源（高德风格白色箭头） */
    private fun turnIconRes(icon: Int): Int {
        val name = "sou${icon}_night_a530"
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.sou20_night_a530
    }
}
