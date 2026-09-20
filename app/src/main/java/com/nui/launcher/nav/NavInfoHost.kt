package com.nui.launcher.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.os.SystemClock
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
 * 导航/巡航信息显示。同一时刻驱动两套卡片：
 *  - page0（桌面右侧面板，[Refs.page]=1）：导航/巡航中替换"回家/公司/收藏"按钮区；
 *  - 负一屏（[Refs.page]=0）：悬浮于底部横条上方居中，复用同一份 view_nav_info 布局。
 * 两套卡片内部 view id 相同，各自在容器内 findViewById，互不冲突；数据/模式/语音只有一份。
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
        /** 昼夜外观轮询间隔（毫秒）：60 秒 */
        private const val DAY_NIGHT_POLL_MS = 60_000L
    }

    /** 一套导航/巡航卡的视图引用（page0 或负一屏各一份）。[page] 为该卡片所在 ViewPager2 页索引。 */
    private class Refs(val root: View, val page: Int) {
        val turn: ImageView? = root.findViewById(R.id.navInfoTurn)
        val dest: TextView? = root.findViewById(R.id.navInfoDest)
        val eta: TextView? = root.findViewById(R.id.navInfoEta)
        val camera: ViewGroup? = root.findViewById(R.id.navInfoCamera)
        val exit: TextView? = root.findViewById(R.id.navInfoExit)
        val tmc: TextView? = root.findViewById(R.id.navInfoTmc)
        val speed: TextView? = root.findViewById(R.id.navSpeedCircle)
        val limit: TextView? = root.findViewById(R.id.navLimitCircle)
        val navBlock: View? = root.findViewById(R.id.navInfoNavBlock)
        val cruiseBlock: View? = root.findViewById(R.id.navInfoCruiseBlock)
        val cruiseSpeed: TextView? = root.findViewById(R.id.cruiseSpeed)
        val cruiseLimit: TextView? = root.findViewById(R.id.cruiseLimit)
        val cruiseCamOther: ViewGroup? = root.findViewById(R.id.cruiseCamOther)
        val cruiseLight: LinearLayout? = root.findViewById(R.id.cruiseLight)
        // 天气文字仅 page0 右侧面板存在（导航时占用其位置）；负一屏树内无此 id → null
        val weather: View? = (root.parent as? ViewGroup)?.findViewById(R.id.weatherText)
    }

    /** 当前已附加的全部卡片（page0 必在；负一屏由 [attachMinus] 在视图绑定时加入） */
    private val refs = ArrayList<Refs>()
    private var minusAttachedRoot: View? = null

    // 模式：NONE=普通桌面 / NAVI=导航 / CRUISE=巡航
    private enum class Mode { NONE, NAVI, CRUISE }
    private var mode = Mode.NONE
    private var currentPage = 1   // 启动后默认落在桌面页（page=1）

    // ==================== 疲劳驾驶提醒 ====================
    // 进入导航/巡航（驾驶态）开始计时：连续 90 分钟语音提醒注意休息，
    // 共 3 次（每次间隔 2 分钟），3 次后重置计时进入下一轮。NAVI/CRUISE 都算驾驶。
    private val FATIGUE_FIRST_MS = 90 * 60 * 1000L
    private val FATIGUE_INTERVAL_MS = 2 * 60 * 1000L
    private val FATIGUE_REMIND_TIMES = 3
    private var drivingStartTime = 0L
    private var fatigueRemindCount = 0
    private val fatigueHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fatigueRunnable = Runnable {
        speakFatigueRemind()
        fatigueRemindCount++
        if (fatigueRemindCount >= FATIGUE_REMIND_TIMES) {
            // 3 次提醒完成：重置计时，进入下一轮（重新 90 分钟）
            drivingStartTime = SystemClock.elapsedRealtime()
            fatigueRemindCount = 0
            Log.i(TAG, "疲劳提醒：3 次完成，重置新一轮 90 分钟计时")
        }
        scheduleFatigueRemind()
    }

    // 三按钮（回家/公司/收藏）：只作用于 page0。从 overlay 父容器动态查找——page0 由 ViewPager2
    // 管理，ViewHolder 重建后旧引用会失效，必须每次从当前视图树获取
    private fun navButton(id: Int): View? = (overlay.parent as? ViewGroup)?.findViewById(id)
    private val navButtonIds = listOf(R.id.btnNavHome, R.id.btnNavCompany, R.id.btnNavFavorite)

    // 巡航状态（供超速/变灯判断）
    private var curSpeed = 0
    private var limitedSpeed = 0
    // 巡航限速：车机巡航广播 LIMITED_SPEED 恒为 50（高德巡航默认值，不可信），
    // 巡航超速判断/播报改用测速点 CAMERA_SPEED；LIMITED_SPEED 仅导航卡使用
    private var cruiseLimit = 0
    private var overspeedAlerted = false   // 超速去重
    // 限速摄像头语音播报状态：首次发现播报一次 → 距离<200m 再播报一次 → 通过后"登"一声
    private var camTracked = false         // 当前是否在跟踪限速摄像头
    private var camAnnouncedFirst = false  // 首次发现已播报
    private var camAnnouncedNear = false   // <200m 已播报
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
        refs.clear()
        refs.add(Refs(overlay, 1))
        // 负一屏可能已先于本方法完成绑定：若 MainActivity 已缓存其根视图则立即附加
        pendingMinusRoot?.let { attachMinus(it) }
        tts = NuiTts(context)
        context.registerReceiver(receiver, IntentFilter(ACTION_SEND))
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        // 启动 2s 后主动查询：12404 导航状态 + 13030 昼夜模式，结果都通过 10019 返回
        h.postDelayed({
            queryNavState()
            queryDayNight()
        }, 2000L)
        // 昼夜外观周期轮询兜底：高德车机版会随位置/时间动态切换昼夜外观（进隧道、日出日落等），
        // 被动广播（10019 37/38）可能漏发或时机错过（如启动时高德未运行），周期性主动查询保证
        // FOLLOW_MAP 持续跟随。13030 查询轻量，60s 一次无压力。
        h.postDelayed(dayNightPoller, DAY_NIGHT_POLL_MS)
        Log.i(TAG, "导航/巡航信息显示已启动（卡片数=${refs.size}）")
    }

    /** start() 前负一屏已绑定时，由 MainActivity 暂存其根视图，start() 内据此补附加 */
    var pendingMinusRoot: View? = null
    /** 导航/巡航激活时回调（用于重置闲置计时等） */
    var onNavActive: (() -> Unit)? = null

    /** 附加负一屏卡片（视图在 ViewPager2 中绑定/重建时调用，幂等） */
    fun attachMinus(root: View?) {
        if (root == null) return
        if (minusAttachedRoot === root) {
            syncRefsState()
            return
        }
        // ViewHolder 重建：移除旧的负一屏 Refs（page=0），加入新的
        refs.removeAll { it.page == 0 }
        refs.add(Refs(root, 0))
        minusAttachedRoot = root
        pendingMinusRoot = root
        applyTheme()
        syncRefsState()
        Log.i(TAG, "负一屏导航/巡航卡已附加（卡片数=${refs.size}）")
    }

    /** 把当前模式/可见性/数据块状态同步到所有卡片（附加新卡片或重建后调用） */
    private fun syncRefsState() {
        refs.forEach { r ->
            r.turn?.visibility = View.GONE
            r.navBlock?.visibility = if (mode == Mode.NAVI) View.VISIBLE else View.GONE
            r.cruiseBlock?.visibility = if (mode == Mode.CRUISE) View.VISIBLE else View.GONE
            if (mode == Mode.NONE) r.cruiseLight?.visibility = View.GONE
            r.root.visibility =
                if (mode != Mode.NONE && currentPage == r.page) View.VISIBLE else View.GONE
        }
    }

    /** 周期查询高德昼夜外观（13030），保证 FOLLOW_MAP 持续跟随高德外观变化 */
    private val dayNightPoller = object : Runnable {
        override fun run() {
            queryDayNight()
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this, DAY_NIGHT_POLL_MS)
        }
    }

    /** 高德巡航播报临时静音：10047 EXTRA_CASUAL_MUTE（进巡航静音，退巡航/导航恢复）。
     *  对齐实测用法（CSDN/am 命令）：显式指定 AmapAutoBroadcastReceiver、只带 EXTRA_CASUAL_MUTE；
     * 部分高德版本 receiver 类名可能不同，隐式 action 版本兜底双发（静音幂等，重复无害）。 */
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
        refs.forEach { it.turn?.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
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

    /** 页面切换回调：0=负一屏，1=桌面页，其它=应用列表页。每套卡片只在自己所在页可见 */
    fun onPageChanged(page: Int) {
        currentPage = page
        refs.forEach { r ->
            r.root.visibility =
                if (mode != Mode.NONE && page == r.page) View.VISIBLE else View.GONE
        }
        Log.i(TAG, "onPageChanged page=$page 卡片=${refs.joinToString { "p${it.page}=vis${it.root.visibility}" }}")
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
        if (active) onNavActive?.invoke()  // 导航/巡航激活视为用户活动，重置闲置计时
        // 疲劳提醒：进入驾驶态开始计时，退出驾驶态停止/重置
        val wasDriving = old == Mode.NAVI || old == Mode.CRUISE
        val nowDriving = newMode == Mode.NAVI || newMode == Mode.CRUISE
        if (!wasDriving && nowDriving) {
            drivingStartTime = SystemClock.elapsedRealtime()
            fatigueRemindCount = 0
            scheduleFatigueRemind()
        } else if (wasDriving && !nowDriving) {
            stopFatigueRemind()
        }
        // page0：导航时三按钮让位给导航卡（全屏信息）；巡航保留三按钮，巡航卡显示在其下方。
        // 负一屏横条按钮不与卡片抢位置，保持不变。
        val curButtons = navButtonIds.mapNotNull { navButton(it) }
        curButtons.forEach { it.visibility = if (newMode == Mode.NAVI) View.GONE else View.VISIBLE }
        // 两套卡片：导航/巡航内容块互斥 + 可见性跟随当前页
        refs.forEach { r ->
            r.navBlock?.visibility = if (newMode == Mode.NAVI) View.VISIBLE else View.GONE
            r.cruiseBlock?.visibility = if (newMode == Mode.CRUISE) View.VISIBLE else View.GONE
            r.root.visibility =
                if (active && currentPage == r.page) View.VISIBLE else View.GONE
            // 占用天气区域（仅 page0 有 weather）：隐藏天气文字；退出恢复
            r.weather?.visibility = if (active) View.GONE else View.VISIBLE
            if (!active) r.cruiseLight?.visibility = View.GONE
        }
        if (!active) {
            overspeedAlerted = false
            stopDataWatchdog()
        }
        if (active) resetDataWatchdog() else stopDataWatchdog()
    }

    /** MainActivity 更新天气时调用：导航/巡航中返回 true 则不显示天气文字（区域被占用） */
    fun isNavActive(): Boolean = mode != Mode.NONE

    // ==================== 疲劳驾驶提醒 ====================
    private fun isDriving(): Boolean = mode == Mode.NAVI || mode == Mode.CRUISE

    /** 安排下一次疲劳提醒：第一次 90 分钟，之后每次 +2 分钟（共 3 次） */
    private fun scheduleFatigueRemind() {
        fatigueHandler.removeCallbacks(fatigueRunnable)
        if (!isDriving()) return
        val target = drivingStartTime + FATIGUE_FIRST_MS + fatigueRemindCount * FATIGUE_INTERVAL_MS
        val delay = (target - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        fatigueHandler.postDelayed(fatigueRunnable, delay)
        Log.i(TAG, "疲劳提醒：${delay / 1000}s 后第 ${fatigueRemindCount + 1} 次提醒")
    }

    private fun stopFatigueRemind() {
        fatigueHandler.removeCallbacks(fatigueRunnable)
        fatigueRemindCount = 0
        Log.i(TAG, "疲劳提醒：退出驾驶态，停止计时")
    }

    private fun speakFatigueRemind() {
        Log.i(TAG, "疲劳提醒：已连续驾驶 ${FATIGUE_FIRST_MS / 60000} 分钟，语音提醒注意休息")
        runCatching {
            NuiTts(context).speak("主人，您已连续驾驶一个半小时，请注意休息")
        }
    }

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
                    refs.forEach { r ->
                        updateCamera(intent, r.camera)
                        renderNavSpeed(r, curSpeed, limitedSpeed)
                    }
                    resetDataWatchdog()
                    checkOverspeed()
                }
                else -> {
                    // ICON=0 → 巡航数据：进入或更新巡航卡
                    if (mode != Mode.CRUISE) {
                        setMode(Mode.CRUISE)
                        Log.i(TAG, "巡航进入: ICON=0")
                    }
                    refs.forEach { updateCruise(it, intent) }
                    // 限速摄像头语音（全局只播一次，与两套卡片渲染解耦）：
                    // 有距离且 TYPE=0 测速才播报；无距离视为通过（复位 + "登"一声）
                    val cdRaw = intent.extras?.get("CAMERA_DIST")
                    val cd = when (cdRaw) {
                        is Int -> cdRaw
                        is String -> cdRaw.toIntOrNull() ?: 0
                        else -> 0
                    }
                    val ctype = intent.getIntExtra("CAMERA_TYPE", 0)
                    when {
                        cd <= 0 -> checkCameraVoice(0, 0)
                        ctype == 0 -> checkCameraVoice(cd, intent.getIntExtra("CAMERA_SPEED", 0))
                    }
                    resetDataWatchdog()
                    checkOverspeed()
                }
            }
            return
        }

        // ICON≠0：导航数据，立即显示导航卡
        setMode(Mode.NAVI)
        resetDataWatchdog()

        // 终点名 + 全程剩余
        val dest = intent.getStringExtra("endPOIName") ?: ""
        val remainTime = intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO") ?: ""
        val remainDis = intent.getStringExtra("ROUTE_REMAIN_DIS_AUTO")
            ?: intent.getStringExtra("ROUTE_REMAIN_DIS") ?: ""
        val destText = dest.ifBlank { "导航中" }
        // 到达时间 = 当前时间 + 剩余时长（ROUTE_REMAIN_TIME_AUTO 如"50分钟"/"1小时20分钟"）
        val arriveTime = formatArriveTime(parseRemainMinutes(remainTime))
        val etaText = listOf(arriveTime, remainDis)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "导航中" }

        // 出口
        val exitName = intent.getStringExtra("EXIT_NAME_INFO") ?: ""
        val exitDir = intent.getStringExtra("EXIT_DIRECTION_INFO") ?: ""
        val exitText = if (exitName.isNotBlank()) {
            "出口 $exitName" + if (exitDir.isNotBlank()) " · $exitDir" else ""
        } else ""

        refs.forEach { r ->
            // 导航卡不显示转向图标/信息，速度区尽量放大当前速度
            r.turn?.visibility = View.GONE
            r.dest?.text = destText
            r.eta?.text = etaText
            // 电子眼
            updateCamera(intent, r.camera)
            // 出口
            if (exitText.isNotBlank()) {
                r.exit?.text = exitText
                r.exit?.visibility = View.VISIBLE
            } else {
                r.exit?.visibility = View.GONE
            }
            // 当前速度大字（含限速小字）
            renderNavSpeed(r, curSpeed, limitedSpeed)
        }

        Log.i(TAG, "导航信息: 转向=$icon 终点=$dest 全程=$etaText " +
            "出口=$exitText 速度=${curSpeed}km/h 限速=$limitedSpeed")
    }

    /** 巡航卡更新：当前速度大字 + 最近测速 */
    private fun updateCruise(r: Refs, intent: Intent) {
        val speedText = if (curSpeed > 0) "$curSpeed" else "--"
        adjustCircleText(r.cruiseSpeed, speedText)
        // 巡航限速：只认测速点 CAMERA_SPEED（LIMITED_SPEED 巡航下恒 50 不可信）
        val camSpeed = intent.getIntExtra("CAMERA_SPEED", 0)
        if (camSpeed > 0) {
            cruiseLimit = camSpeed
            r.cruiseLimit?.apply {
                adjustCircleText(this, "$cruiseLimit")
                visibility = View.VISIBLE
            }
        } else {
            r.cruiseLimit?.visibility = View.GONE
        }
        // 超速时速度变红 + 限速圈数字变红
        if (cruiseLimit > 0 && curSpeed > cruiseLimit) {
            r.cruiseSpeed?.setTextColor(0xFFFF6B6B.toInt())
            r.cruiseLimit?.setTextColor(0xFFE53935.toInt())
        } else {
            r.cruiseSpeed?.setTextColor(0xFFFFFFFF.toInt())
            r.cruiseLimit?.setTextColor(0xFF1C1C1E.toInt())
        }
        updateCruiseCamera(r, intent)
    }

    /** 巡航摄像头：所有类型统一在第二行显示 类型图标 + 距离（限速摄像头用摄像头本身图标）。
     *  第一行只保留 速度圈 + 限速值红圈。 */
    private fun updateCruiseCamera(r: Refs, intent: Intent) {
        val view = r.cruiseCamOther ?: return
        val camDistRaw = intent.extras?.get("CAMERA_DIST")
        val cameraDistInt = when (camDistRaw) {
            is Int -> camDistRaw
            is String -> camDistRaw.toIntOrNull() ?: 0
            else -> 0
        }
        val cameraDist = if (cameraDistInt > 0) "$cameraDistInt" else ""
        val cameraType = intent.getIntExtra("CAMERA_TYPE", 0)
        if (cameraDist.isNotBlank()) {
            val icon = view.findViewById<ImageView>(R.id.cruiseCamOtherIcon)
            val text = view.findViewById<TextView>(R.id.cruiseCamOtherText)
            icon?.setImageResource(cameraIconRes(cameraType))
            text?.text = cameraDist.withSmallUnit()
            view.visibility = View.VISIBLE
            // 限速摄像头语音播报（TYPE=0 测速/限速；TYPE=1 监控不做语音，避免轰炸）。
            // 语音只播一次（用第一套卡片驱动即可），故放在调用方统一处理。
        } else {
            view.visibility = View.GONE
        }
    }

    /** 限速摄像头语音提醒：发现摄像头即播"前方XX米有限速摄像头，限速XX"（提前警示，不限超速），
     *  距离 <200m 再提醒一次；通过后"登"一声。超速提醒由 checkOverspeed 负责（有摄像头超 10%、无摄像头超 20%）。
     *  camDist<=0 视为通过（重置状态）；距离回跳（如 100→400）视为进入下一个摄像头，重新播报。 */
    private fun checkCameraVoice(camDist: Int, camSpeed: Int) {
        if (camDist > 0) {
            // 距离回跳（通过后又遇到新的摄像头）重置跟踪
            if (camAnnouncedNear && camDist >= 300) {
                camTracked = false
                camAnnouncedFirst = false
                camAnnouncedNear = false
            }
            if (!camTracked) {
                camTracked = true
                camAnnouncedFirst = false
                camAnnouncedNear = false
            }
            if (!camAnnouncedFirst) {
                camAnnouncedFirst = true
                val text = "前方${camDist}米有限速摄像头，限速${camSpeed}"
                tts?.speak(text)
                Log.i(TAG, "限速摄像头首次提醒: $text")
            }
            if (!camAnnouncedNear && camDist < 200) {
                camAnnouncedNear = true
                val text = "前方${camDist}米有限速摄像头，限速${camSpeed}"
                tts?.speak(text)
                Log.i(TAG, "限速摄像头临近提醒: $text")
            }
        } else {
            if (camTracked) {
                camTracked = false
                camAnnouncedFirst = false
                camAnnouncedNear = false
                playDing()
                Log.i(TAG, "限速摄像头已通过")
            }
        }
    }

    /** "登"一声通过提示音（ToneGenerator 短促滴声，不占用 TTS 队列） */
    private fun playDing() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 500)
        }
    }

    /** 摄像头类型 → 图标（完整映射对齐 Navi-Link CameraWarningView.getIconRes）。
     *  重点：2/15=闯红灯拍照（红绿灯图标）；0=测速；1=监控；4=公交专用道。 */
    private fun cameraIconRes(cameraType: Int): Int = when (cameraType) {
        6, 20 -> R.drawable.camera_bicycle
        4, 16 -> R.drawable.camera_bus
        13, 1015 -> R.drawable.camera_byfoot
        11, 1099 -> R.drawable.camera_etc
        29, 1029 -> R.drawable.camera_hov
        22, 1001 -> R.drawable.camera_lamp
        2, 15 -> R.drawable.camera_light
        21, 1017 -> R.drawable.camera_park
        19, 1005 -> R.drawable.camera_phone
        12, 1030 -> R.drawable.camera_press
        26, 1024 -> R.drawable.camera_railway
        30, 1012 -> R.drawable.camera_recycle
        25, 1016 -> R.drawable.camera_reverse
        18, 1002 -> R.drawable.camera_safe
        24, 1021 -> R.drawable.camera_sonar
        28, 1028 -> R.drawable.camera_space
        5 -> R.drawable.camera_urgen
        27, 1011 -> R.drawable.camera_tail
        else -> R.drawable.camera_default
    }

    /** 电子眼（导航卡）：距离+限速/类型；有则显示黄色警示，无则隐藏 */
    private fun updateCamera(intent: Intent, view: ViewGroup?) {
        if (view == null) return
        // 兼容两种类型：部分高德版本 CAMERA_DIST 是 int（实测），部分可能是字符串
        val camDistRaw = intent.extras?.get("CAMERA_DIST")
        val cameraDist = when (camDistRaw) {
            is Int -> if (camDistRaw > 0) "$camDistRaw" else ""
            is String -> camDistRaw
            else -> ""
        }
        val cameraSpeed = intent.getIntExtra("CAMERA_SPEED", 0)
        val cameraType = intent.getIntExtra("CAMERA_TYPE", 0)
        val icon = view.findViewById<ImageView>(R.id.navInfoCameraIcon)
        val text = view.findViewById<TextView>(R.id.navInfoCameraText)
        if (cameraDist.isNotBlank()) {
            // 摄像头类型图标（完整映射对齐 Navi-Link，见 cameraIconRes）
            icon?.setImageResource(cameraIconRes(cameraType))
            val warn = buildString {
                append(cameraDist.withSmallUnit())
                when {
                    cameraSpeed > 0 -> append(" 限速$cameraSpeed")          // 测速摄像头（含限速）
                    cameraType == 1 -> append(" 违章抓拍")                   // 监控/压线摄像头
                    cameraType == 4 -> append(" 公交专用道")                  // 公交专用道摄像头
                }
            }
            text?.text = warn
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    // ---------- 60073 红绿灯（巡航显示 + 变灯提醒） ----------

    /**
     * 巡航红绿灯：两套卡片各自的 cruiseLight 容器内，按方向动态生成"胶囊"（深蓝圆角背景 +
     * 圆形灯(颜色随灯状态) + 圆内白色方向箭头 + 右侧白色倒计时数字），
     * 参考 Navi-Link TrafficLightView 样式，多个方向横排。
     */
    private fun handleTrafficLight(intent: Intent) {
        // 60073 红绿灯是巡航专属广播：高德发它说明正在巡航。若巡航检测（46/ICON=0）
        // 因版本差异失败，收到有效红绿灯数据仍进入巡航显示，避免"有数据不显示"
        val lights = intent.getStringExtra("lightsData")
            ?: intent.getStringExtra("LIGHTS_DATA")
        if (mode == Mode.NAVI || lights.isNullOrBlank()) {
            if (mode != Mode.NAVI) refs.forEach { it.cruiseLight?.visibility = View.GONE }
            return
        }
        if (mode != Mode.CRUISE) setMode(Mode.CRUISE)
        resetDataWatchdog()
        val array: JSONArray
        try {
            array = JSONArray(lights)
        } catch (e: Exception) {
            Log.w(TAG, "解析红绿灯失败: ${e.message}")
            return
        }
        if (array.length() == 0) {
            refs.forEach { it.cruiseLight?.visibility = View.GONE }
            return
        }
        // 最近（第一个）红灯倒计时，供变灯提醒用
        var firstRedCountdown = -1
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
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
            if (i == 0 && isRed) firstRedCountdown = countdown
        }
        // 两套卡片各自渲染一份相同的红绿灯胶囊
        refs.forEach { r -> r.cruiseLight?.let { buildCruiseLights(it, array) } }

        // 变灯提醒：巡航中最近红灯倒计时 ≤3s 语音提醒（不设速度条件，高德悬浮窗巡航即有红绿灯）
        if (firstRedCountdown in 1..3) {
            val text = if (firstRedCountdown <= 1) "绿灯即将亮起" else "${firstRedCountdown}秒后变绿"
            tts?.speak(text)
            Log.i(TAG, "变灯提醒(巡航): $text")
        }
    }

    /** 把红绿灯 JSON 数组渲染为一排胶囊到 [container]（每套卡片各调一次） */
    private fun buildCruiseLights(container: LinearLayout, array: JSONArray) {
        try {
            container.removeAllViews()
            val dm = context.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * dm + 0.5f).toInt()
            // 多方向时紧凑模式（参考 Navi-Link setCompact）：圆/字缩小，保证 4 个以上胶囊放得下
            val compact = array.length() >= 3
            val dotSize = if (compact) dp(26) else dp(44)
            val timeSize = if (compact) 18f else 30f
            val padH = if (compact) dp(3) else dp(6)
            val itemMargin = if (compact) dp(3) else dp(8)
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
        } catch (e: Exception) {
            Log.w(TAG, "构建红绿灯胶囊失败: ${e.message}")
        }
    }

    // ---------- 13011 TMC 实时路况（前方拥堵） ----------

    /**
     * 导航卡"前方拥堵"：
     * 分段坐标系判定——协议文档称所有段距离之和=residual_distance（分段覆盖剩余路程、从当前位置起算），
     * 但实测（参考 Navi-Link 实现按 total_distance 画比例）高德真实广播的分段覆盖全程（段之和≈total_distance），
     * 段0 是路线起点而非当前位置，必须用 finish_distance（已行驶里程）定位当前位置所在的段，再从当前段往后找拥堵。
     * 兼容两种坐标系。解析一次，两套卡片同步显示。
     */
    private fun handleTmc(intent: Intent) {
        if (mode != Mode.NAVI) return
        val json = intent.getStringExtra("EXTRA_TMC_SEGMENT") ?: return
        try {
            val root = JSONObject(json)
            Log.i(TAG, "TMC原始: $json")
            if (!root.optBoolean("tmc_segment_enabled", true)) {
                refs.forEach { it.tmc?.visibility = View.GONE }
                return
            }
            val info = root.optJSONArray("tmc_info")
            if (info == null || info.length() == 0) {
                refs.forEach { it.tmc?.visibility = View.GONE }
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
                refs.forEach { it.tmc?.visibility = View.GONE }
                return
            }
            val level = when (targetStatus) {
                4 -> "严重拥堵"
                3 -> "拥堵"
                else -> "缓行"
            }
            val text = if (aheadMeters <= 0) {
                val remain = if (congestionEndMeters > 0) congestionEndMeters else 0
                "当前$level · 剩余${formatAhead(remain)}"
            } else {
                "前方${formatAhead(aheadMeters)} $level"
            }
            val color = when (targetStatus) {
                4 -> 0xFFD50000.toInt() // 严重拥堵：深红
                3 -> 0xFFFF5252.toInt() // 拥堵：红
                else -> 0xFFFFC400.toInt() // 缓行：黄
            }
            refs.forEach { t ->
                t.tmc?.apply {
                    this.text = text
                    setTextColor(color)
                    visibility = View.VISIBLE
                }
            }
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
     * 速度/限速圆圈文字渲染：1-2 位保持 36sp 大字，3 位（≥100）自动缩到 26sp，
     * 避免"100/120"溢出 64dp 圆圈显示不全（巡航卡与导航卡统一）。
     */
    private fun adjustCircleText(view: TextView?, text: String) {
        if (view == null) return
        view.text = text
        view.textSize = if (text.length >= 3) 26f else 36f
    }

    /**
     * 导航卡当前速度圈渲染（与巡航卡样式对齐）：速度黑底蓝圈大字（无单位），
     * 限速红圈白底黑字（可选）。超速时速度圈红字、限速圈红字；不超速白/黑字。
     */
    private fun renderNavSpeed(r: Refs, speed: Int, limit: Int) {
        adjustCircleText(r.speed, if (speed > 0) "$speed" else "--")
        if (limit > 0) {
            r.limit?.apply {
                adjustCircleText(this, "$limit")
                visibility = View.VISIBLE
            }
        } else {
            r.limit?.visibility = View.GONE
        }
        if (limit > 0 && speed > limit) {
            r.speed?.setTextColor(0xFFFF6B6B.toInt())
            r.limit?.setTextColor(0xFFE53935.toInt())
        } else {
            r.speed?.setTextColor(0xFFFFFFFF.toInt())
            r.limit?.setTextColor(0xFF1C1C1E.toInt())
        }
    }

    /** 超速检测语音（仅巡航模式；导航模式高德软件自己播报，NUI 不重复）：
     *  - 巡航 + 跟踪限速摄像头：超限速 10% 播"您已超速，当前限速XX"；
     *  - 巡航 + 无限速摄像头：超限速 20% 才播。 */
    private fun checkOverspeed() {
        if (mode != Mode.CRUISE) return
        if (curSpeed <= 0) return
        // 巡航限速只认测速点 CAMERA_SPEED（cruiseLimit）
        val limit = cruiseLimit
        if (limit <= 0) return
        // 没有摄像头跟踪 → 超 20%；跟踪摄像头中 → 超 10%
        val threshold = if (!camTracked) limit * 12 / 10 else limit * 11 / 10
        if (curSpeed > threshold) {
            if (!overspeedAlerted) {
                overspeedAlerted = true
                tts?.speak("您已超速，当前限速${limit}")
                Log.i(TAG, "超速提醒: ${curSpeed}km/h > 限速${limit}km/h (超${if (!camTracked) 20 else 10}%)")
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
