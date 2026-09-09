package com.nui.launcher.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.nui.launcher.R
import com.nui.launcher.voice.NuiTts
import org.json.JSONArray

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
        private const val NAV_STATE_NAVIGATING = 8
        private const val NAV_STATE_NAV_EXIT = 9
        // 协议 10019 状态表：46=主界面（含主图巡航界面），47=子界面（除主图巡航外的其他界面）
        private const val NAV_STATE_MAIN_CRUISE_UI = 46
        private const val NAV_STATE_SUB_UI = 47
    }

    // 模式：NONE=普通桌面 / NAVI=导航 / CRUISE=巡航
    private enum class Mode { NONE, NAVI, CRUISE }
    private var mode = Mode.NONE

    // 高德是否处于主图巡航界面（10019 STATE=46 置位 / 47 或 8、9 复位）。
    // 巡航显示的完整条件：isMainCruiseUi=true 且 10001 ICON=0（双重确认）
    private var isMainCruiseUi = false

    // 导航卡元素
    private var turnView: ImageView? = null
    private var destView: TextView? = null
    private var etaView: TextView? = null
    private var cameraView: TextView? = null
    private var exitView: TextView? = null
    private var speedView: TextView? = null
    private var sapaView: TextView? = null
    private var sapaMoreView: TextView? = null
    private var navBlock: View? = null

    // 巡航卡元素
    private var cruiseBlock: View? = null
    private var cruiseSpeedView: TextView? = null
    private var cruiseCameraView: TextView? = null
    private var cruiseLightView: TextView? = null

    private var weatherView: View? = null

    // 三按钮（回家/公司/收藏）：从 overlay 父容器动态查找——page0 由 ViewPager2 管理，
    // ViewHolder 重建后旧引用会失效，必须每次从当前视图树获取
    private fun navButton(id: Int): View? = (overlay.parent as? ViewGroup)?.findViewById(id)
    private val navButtonIds = listOf(R.id.btnNavHome, R.id.btnNavCompany, R.id.btnNavFavorite)

    // 巡航状态（供超速/变灯判断）
    private var curSpeed = 0
    private var limitedSpeed = 0
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
            }
        }
    }

    fun start() {
        turnView = overlay.findViewById(R.id.navInfoTurn)
        destView = overlay.findViewById(R.id.navInfoDest)
        etaView = overlay.findViewById(R.id.navInfoEta)
        cameraView = overlay.findViewById(R.id.navInfoCamera)
        exitView = overlay.findViewById(R.id.navInfoExit)
        speedView = overlay.findViewById(R.id.navInfoSpeed)
        sapaView = overlay.findViewById(R.id.navInfoSapa)
        sapaMoreView = overlay.findViewById(R.id.navInfoSapaMore)
        navBlock = overlay.findViewById(R.id.navInfoNavBlock)
        cruiseBlock = overlay.findViewById(R.id.navInfoCruiseBlock)
        cruiseSpeedView = overlay.findViewById(R.id.cruiseSpeed)
        cruiseCameraView = overlay.findViewById(R.id.cruiseCamera)
        cruiseLightView = overlay.findViewById(R.id.cruiseLight)
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
            NAV_STATE_NAVIGATING -> { isMainCruiseUi = false; setMode(Mode.NAVI) }
            // 9：导航结束
            NAV_STATE_NAV_EXIT -> { isMainCruiseUi = false; setMode(Mode.NONE) }
            // 46：主界面=主图巡航界面（进巡航的必要条件，还需 ICON=0 双重确认）
            NAV_STATE_MAIN_CRUISE_UI -> { isMainCruiseUi = true; Log.i(TAG, "主图巡航界面(46)") }
            // 47：子界面（权威退巡航）
            NAV_STATE_SUB_UI -> {
                isMainCruiseUi = false
                if (mode == Mode.CRUISE) setMode(Mode.NONE)
            }
        }
        // 路口大图状态（无图片内容，仅状态）1=显示 0=消失
        if (intent.hasExtra("EXTRA_CROSS_MAP")) {
            Log.i(TAG, "路口大图: ${if (intent.getIntExtra("EXTRA_CROSS_MAP", 0) == 1) "显示" else "消失"}")
        }
    }

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        val old = mode
        mode = newMode
        Log.i(TAG, "模式: ${old.name} -> ${newMode.name}")
        val active = newMode != Mode.NONE
        overlay.visibility = if (active) View.VISIBLE else View.GONE
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
        // 模式判据（用户确认的规则）：
        // - ICON≠0：有转向引导，一定是导航模式 → 切导航信息显示（无条件）
        // - ICON=0：无转向引导，需叠加"主图巡航界面(46)"才判定为巡航 → 巡航信息显示
        // - 退出巡航：10019 STATE=47（子界面）或 ICON≠0（任一触发）
        // - ICON=0 且非巡航：若已在导航中则保持导航卡（段间无转向），否则保持按钮区（普通主图）
        // （协议文档 TYPE 字段实测高德不带，已弃用）
        var icon = intent.getIntExtra("NEW_ICON", 0)
        if (icon == 0) icon = intent.getIntExtra("ICON", 0)
        val isCruise = icon == 0 && isMainCruiseUi

        // 速度/限速：导航和巡航都更新（超速检测）
        val speed = intent.getIntExtra("CUR_SPEED", 0)
        val limited = intent.getIntExtra("LIMITED_SPEED", 0)
        if (speed > 0) curSpeed = speed
        if (limited > 0) limitedSpeed = limited

        if (isCruise) {
            // 巡航数据：切到巡航模式并更新巡航卡（速度/测速）；从导航态来也强制切换
            if (mode != Mode.CRUISE) setMode(Mode.CRUISE)
            updateCruise(intent)
            resetDataWatchdog()
            checkOverspeed()
            return
        }

        // ICON=0 且非巡航：导航段间（保持导航卡，仅更新数据）或普通主图（保持按钮区）
        if (icon == 0) {
            if (mode == Mode.NAVI) {
                // 导航中段间无转向（长直线/高速）：保持导航卡，只更新速度限速/电子眼
                updateCamera(intent, cameraView)
                val speedText = if (curSpeed > 0) "$curSpeed" + "km/h" else ""
                val limitText = if (limitedSpeed > 0) "限速$limitedSpeed" else ""
                speedView?.text = listOf(speedText, limitText).filter { it.isNotBlank() }.joinToString(" · ")
                resetDataWatchdog()
                checkOverspeed()
            }
            return
        }

        // ICON≠0：导航数据，立即显示导航卡
        setMode(Mode.NAVI)
        resetDataWatchdog()

        turnView?.setImageResource(turnIconRes(icon))

        // 终点名 + 全程剩余
        val dest = intent.getStringExtra("endPOIName") ?: ""
        val remainTime = intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO") ?: ""
        val remainDis = intent.getStringExtra("ROUTE_REMAIN_DIS_AUTO")
            ?: intent.getStringExtra("ROUTE_REMAIN_DIS") ?: ""
        destView?.text = dest.ifBlank { "导航中" }
        val etaParts = listOf(
            remainTime.ifBlank { intent.getStringExtra("ETA_TEXT")?.replace("预计", "")?.replace("到达", "") ?: "" },
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

        // 速度 · 限速（导航卡小字）
        val speedText = if (curSpeed > 0) "$curSpeed" + "km/h" else ""
        val limitText = if (limitedSpeed > 0) "限速$limitedSpeed" else ""
        speedView?.text = listOf(speedText, limitText).filter { it.isNotBlank() }.joinToString(" · ")

        // 服务区
        val roadType = intent.getIntExtra("ROAD_TYPE", -1)
        val sapaDist = intent.getStringExtra("SAPA_DIST_AUTO") ?: ""
        val sapaName = intent.getStringExtra("SAPA_NAME") ?: ""
        if (roadType == 0 && sapaDist.isNotBlank()) {
            sapaView?.text = "${sapaName.ifBlank { "下一个服务区" }} · $sapaDist".withSmallUnit()
            sapaView?.visibility = View.VISIBLE
            val sapaNum = intent.getIntExtra("SAPA_NUM", 0)
            if (sapaNum > 1) {
                sapaMoreView?.text = "前方共 $sapaNum 个服务区"
                sapaMoreView?.visibility = View.VISIBLE
            } else {
                sapaMoreView?.visibility = View.GONE
            }
        } else {
            sapaView?.visibility = View.GONE
            sapaMoreView?.visibility = View.GONE
        }

        Log.i(TAG, "导航信息: 转向=$icon 终点=$dest 全程=${etaParts.joinToString("/")} " +
            "出口=$exitName$exitDir 速度=${curSpeed}km/h 限速=$limitedSpeed 服务区=$sapaDist")
    }

    /** 巡航卡更新：当前速度大字 + 最近测速 */
    private fun updateCruise(intent: Intent) {
        val speedText = if (curSpeed > 0) "$curSpeed" + "km/h" else "--"
        cruiseSpeedView?.text = speedText
        // 超速时速度变红
        if (limitedSpeed > 0 && curSpeed > limitedSpeed) {
            cruiseSpeedView?.setTextColor(0xFFFF6B6B.toInt())
        } else {
            cruiseSpeedView?.setTextColor(0xFFFFFFFF.toInt())
        }
        updateCamera(intent, cruiseCameraView)
    }

    /** 电子眼：距离+限速；有则显示黄色警示，无则隐藏 */
    private fun updateCamera(intent: Intent, view: TextView?) {
        val cameraDist = intent.getStringExtra("CAMERA_DIST") ?: ""
        val cameraSpeed = intent.getIntExtra("CAMERA_SPEED", 0)
        if (cameraDist.isNotBlank()) {
            val warn = buildString {
                append("⚠ 前方")
                append(cameraDist.withSmallUnit())
                if (cameraSpeed > 0) append(" 限速$cameraSpeed")
            }
            view?.text = warn
            view?.visibility = View.VISIBLE
        } else {
            view?.visibility = View.GONE
        }
    }

    // ---------- 60073 红绿灯（巡航显示 + 变灯提醒） ----------

    private fun handleTrafficLight(intent: Intent) {
        if (mode != Mode.CRUISE) return
        resetDataWatchdog()
        val lights = intent.getStringExtra("lightsData")
            ?: intent.getStringExtra("LIGHTS_DATA")
        if (lights.isNullOrBlank()) return
        try {
            val array = JSONArray(lights)
            if (array.length() == 0) return
            val first = array.getJSONObject(0)
            val dir = first.optString("dir", first.optString("direction", "路口"))
            val status = first.optString(
                "trafficLightStatus",
                first.optString("status", first.optString("state", "unknown"))
            )
            val countdown = first.optInt(
                "redLightCountDownSeconds",
                first.optInt("countdown", first.optInt("countDown",
                    first.optInt("remaining_time", -1)))
            )
            val isRed = status.contains("red", ignoreCase = true) ||
                status == "1" || status == "0"
            val statusText = if (isRed) "红灯" else if (status.contains("green", true)) "绿灯" else "黄灯"
            val color = when {
                isRed -> 0xFFFF6B6B.toInt()
                status.contains("green", true) -> 0xFF4CAF50.toInt()
                else -> 0xFFFFD54F.toInt()
            }
            cruiseLightView?.text = "🚦 $dir $statusText " + if (countdown >= 0) "${countdown}秒" else ""
            cruiseLightView?.setTextColor(color)
            cruiseLightView?.visibility = View.VISIBLE

            // 变灯提醒：≤25km/h 时红灯倒计时 ≤3s 语音提醒（去重由本方法内状态控制，阈值按用户要求 ≤25）
            if (curSpeed <= 25 && isRed && countdown in 1..3) {
                val text = if (countdown <= 1) "绿灯即将亮起" else "${countdown}秒后变绿"
                tts?.speak(text)
                Log.i(TAG, "变灯提醒(巡航): $text")
            }
            Log.d(TAG, "巡航红绿灯: $dir $statusText ${countdown}秒 速度=${curSpeed}km/h")
        } catch (e: Exception) {
            Log.w(TAG, "解析红绿灯失败: ${e.message}")
        }
    }

    /** 超速检测：巡航下速度>限速 时语音提醒一次（回落后再超速会再提醒） */
    private fun checkOverspeed() {
        if (limitedSpeed <= 0 || curSpeed <= 0) return
        if (curSpeed > limitedSpeed) {
            if (!overspeedAlerted) {
                overspeedAlerted = true
                tts?.speak("您已超速，当前限速${limitedSpeed}")
                Log.i(TAG, "超速提醒: ${curSpeed}km/h > 限速${limitedSpeed}km/h")
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

    /** 转向图标编号 → 资源（高德风格白色箭头） */
    private fun turnIconRes(icon: Int): Int {
        val name = "sou${icon}_night_a530"
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.sou20_night_a530
    }
}
