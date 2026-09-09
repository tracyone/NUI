package com.nui.launcher.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nui.launcher.R
import org.json.JSONObject

/**
 * 导航信息显示（右上角"回家/公司/收藏"按钮区动态变化）。
 *
 * 监听高德车机版广播：
 * - 10019：导航状态（权威判据）
 *   - EXTRA_STATE=8 → 导航中；9 → 导航结束（恢复按钮区）
 * - 10001：导航信息
 *   - NEW_ICON/ICON：转向图标编号（2~20，映射 sou{N}_night_a530 资源）
 *   - SEG_REMAIN_DIS_AUTO：下一段剩余距离（"2.1公里"）
 *   - ROUTE_REMAIN_TIME_AUTO：全程剩余时间（"37分钟"）
 *   - NEXT_ROAD_NAME / CUR_ROAD_NAME：下一/当前道路名
 *   - ETA_TEXT：预计到达文本
 *   - CUR_SPEED / LIMITED_SPEED：当前速度 / 限速
 * - 13012：车道线（EXTRA_DRIVE_WAY JSON，参照 Navi-Link LaneLineView）
 *   - drive_way_enabled / drive_way_size / drive_way_info[]
 *   - drive_way_number：车道编号（0-based），drive_way_lane_Back_icon：图标编号
 *   - trafficLaneAdvised：推荐车道（高亮边框）
 *
 * 显示策略：导航中隐藏三按钮、显示导航卡（转向图标+距离+车道线+道路+速度）；
 * 导航结束恢复按钮。同时启动时向高德发 13030 主动查询昼夜状态。
 */
class NavInfoHost(
    private val context: Context,
    private val overlay: View,
    private val navButtons: List<View>,
) {
    companion object {
        private const val TAG = "NUI.NavInfo"
        private const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
        private const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"
        private const val KEY_TYPE_NAV_STATE = 10019
        private const val KEY_TYPE_NAVI_INFO = 10001
        private const val KEY_TYPE_LANE = 13012
        private const val NAV_STATE_NAVIGATING = 8
        private const val NAV_STATE_NAV_EXIT = 9
        private const val NAV_STATE_CRUISE_ENTER = 24
        private const val NAV_STATE_CRUISE_EXIT = 25
    }

    private var turnView: ImageView? = null
    private var distView: TextView? = null
    private var lanesView: LinearLayout? = null
    private var timeView: TextView? = null
    private var speedView: TextView? = null
    private var sapaView: TextView? = null
    private var sapaMoreView: TextView? = null
    private var weatherView: View? = null

    private var navActive = false

    // 靠近路口时主动请求车道线（10062），弥补被动推送频率不足
    private val laneHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lanePolling: Runnable? = null
    private val LANE_POLL_DISTANCE_M = 800   // 剩余距离小于该值开始主动轮询

    // 数据断流看门狗：导航态下超过该时长收不到任何 10001/13012/10019 → 恢复按钮区
    // （高德被杀/异常退出且未发 STATE=9 时的兜底，参照 Navi-Link）
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val DATA_TIMEOUT_MS = 15_000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("KEY_TYPE", -1)) {
                KEY_TYPE_NAV_STATE -> handleNavState(intent)
                KEY_TYPE_NAVI_INFO -> handleNaviInfo(intent)
                KEY_TYPE_LANE -> handleLane(intent)
            }
        }
    }

    fun start() {
        turnView = overlay.findViewById(R.id.navInfoTurn)
        distView = overlay.findViewById(R.id.navInfoDist)
        lanesView = overlay.findViewById(R.id.navInfoLanes)
        timeView = overlay.findViewById(R.id.navInfoTime)
        speedView = overlay.findViewById(R.id.navInfoSpeed)
        sapaView = overlay.findViewById(R.id.navInfoSapa)
        sapaMoreView = overlay.findViewById(R.id.navInfoSapaMore)
        // 天气文字在右侧面板（导航卡的兄弟节点）：导航时占掉天气区域的位置
        weatherView = (overlay.parent as? ViewGroup)?.findViewById(R.id.weatherText)
        context.registerReceiver(receiver, IntentFilter(ACTION_SEND))
        // 图标颜色跟随深浅外观（深色=白箭头，浅色=深灰箭头，否则浅色壁纸上看不见）
        applyTheme()
        // 启动 2s 后主动查询：12404 导航状态 + 13030 昼夜模式，结果都通过 10019 返回
        // 用主动查询校准被动广播，防止启动阶段错过 STATE 导致状态错乱
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            queryNavState()
            queryDayNight()
        }, 2000L)
        Log.i(TAG, "导航信息显示已启动")
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

    /** 外观切换时调用：转向图标重新染色（深色白、浅色深灰）；车道线 HUD 固定白色不随外观。
     *  距离/道路名/速度/服务区文字也必须跟随主题（XML 默认色是深色套的白色，浅色外观下会看不清）。 */
    fun applyTheme() {
        val p = com.nui.launcher.UiTheme.palette(context)
        turnView?.setColorFilter(p.textPrimary, android.graphics.PorterDuff.Mode.SRC_IN)
        distView?.setTextColor(p.textPrimary)
        timeView?.setTextColor(p.textPrimary)
        speedView?.setTextColor(p.textSecondary)
        sapaMoreView?.setTextColor(p.textSecondary)
        Log.i(TAG, "外观染色: ${if (com.nui.launcher.UiTheme.isDark(context)) "深色" else "浅色"}")
    }

    fun stop() {
        stopLanePolling()
        stopDataWatchdog()
        runCatching { context.unregisterReceiver(receiver) }
        Log.i(TAG, "导航信息显示已停止")
    }

    // ---------- 10019 导航状态 ----------

    private fun handleNavState(intent: Intent) {
        val state = intent.getIntExtra("EXTRA_STATE", -1)
        when (state) {
            NAV_STATE_NAVIGATING -> setNavActive(true)     // 8：导航中
            NAV_STATE_NAV_EXIT -> setNavActive(false)      // 9：导航结束
            // 24/25：进入/退出巡航播报——巡航不是导航，恢复按钮区
            NAV_STATE_CRUISE_ENTER, NAV_STATE_CRUISE_EXIT -> setNavActive(false)
        }
        // 路口大图状态（无图片内容，仅状态）1=显示 0=消失
        if (intent.hasExtra("EXTRA_CROSS_MAP")) {
            Log.i(TAG, "路口大图: ${if (intent.getIntExtra("EXTRA_CROSS_MAP", 0) == 1) "显示" else "消失"}")
        }
    }

    private fun setNavActive(active: Boolean) {
        if (navActive == active) return
        navActive = active
        Log.i(TAG, "导航${if (active) "开始，显示导航卡" else "结束，恢复按钮区"}")
        overlay.visibility = if (active) View.VISIBLE else View.GONE
        // 用 GONE 不占位：三个按钮隐藏后导航卡占满整行宽度
        navButtons.forEach { it.visibility = if (active) View.GONE else View.VISIBLE }
        if (!active) {
            lanesView?.removeAllViews()
            lanesView?.visibility = View.GONE
        }
        if (!active) stopLanePolling()
        if (active) resetDataWatchdog() else stopDataWatchdog()
        // 导航时占用天气区域：隐藏天气文字（位置让给导航卡）；结束时恢复
        weatherView?.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** MainActivity 更新天气时调用：导航中返回 true 则不显示天气文字（区域被导航卡占用） */
    fun isNavActive(): Boolean = navActive

    /** 导航数据看门狗：每次收到导航数据重置 15s 计时，超时自动恢复按钮区 */
    private val watchdog = Runnable {
        Log.i(TAG, "看门狗超时：15s 无导航数据，恢复按钮区")
        setNavActive(false)
    }
    private fun resetDataWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, DATA_TIMEOUT_MS)
    }

    private fun stopDataWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
    }

    // ---------- 靠近路口主动请求车道线（10062） ----------

    /** 解析"350米"/"2.1公里"→米；解析失败返回 null */
    private fun parseDistance(seg: String): Int? {
        val s = seg.trim()
        return when {
            s.contains("公里") ->
                (s.replace("公里", "").trim().toDoubleOrNull()?.times(1000))?.toInt()
            s.contains("米") -> s.replace("米", "").trim().toIntOrNull()
            s.endsWith("m", ignoreCase = true) ->
                s.dropLast(1).trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    /** 根据剩余距离决定是否启动/停止车道线主动轮询（10062，每秒一次） */
    private fun updateLanePolling(seg: String) {
        val meters = parseDistance(seg)
        if (meters != null && meters <= LANE_POLL_DISTANCE_M) {
            startLanePolling()
        } else {
            stopLanePolling()
        }
    }

    private fun startLanePolling() {
        if (lanePolling != null) return
        val runnable = object : Runnable {
            override fun run() {
                // 10062：主动请求最后一次车道线信息，高德回 13012
                val q = Intent(ACTION_RECV).apply {
                    putExtra("KEY_TYPE", 10062)
                    putExtra("SOURCE_APP", context.packageName)
                }
                runCatching { context.sendBroadcast(q) }
                laneHandler.postDelayed(this, 1000L)
            }
        }
        lanePolling = runnable
        laneHandler.post(runnable)
        Log.i(TAG, "剩余距离≤${LANE_POLL_DISTANCE_M}m，启动车道线主动请求(10062) 每秒一次")
    }

    private fun stopLanePolling() {
        val p = lanePolling ?: return
        laneHandler.removeCallbacks(p)
        lanePolling = null
    }

    // ---------- 10001 导航信息 ----------

    private fun handleNaviInfo(intent: Intent) {
        var icon = intent.getIntExtra("NEW_ICON", 0)
        if (icon == 0) icon = intent.getIntExtra("ICON", 0)
        if (icon == 0) return   // 巡航数据（ICON=0）：不更新导航卡，也不打断导航态

        // 10001 带转向图标 = 导航数据（Navi-Link 同款判定），立即显示导航卡；
        // 相比只等 10019 STATE=8，10001 是高频实时广播，更跟手、防漏
        setNavActive(true)
        resetDataWatchdog()

        val seg = intent.getStringExtra("SEG_REMAIN_DIS_AUTO") ?: ""
        // 接近路口（剩余距离小）时主动高频请求车道线，避免错过路口
        updateLanePolling(seg)
        val remainTime = intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO") ?: ""
        val nextRoad = intent.getStringExtra("NEXT_ROAD_NAME")
            ?: intent.getStringExtra("CUR_ROAD_NAME") ?: ""
        val eta = intent.getStringExtra("ETA_TEXT") ?: ""
        val speed = intent.getIntExtra("CUR_SPEED", 0)
        val limited = intent.getIntExtra("LIMITED_SPEED", 0)
        // 服务区：仅高速公路（ROAD_TYPE=0）显示下一个服务区距离，实时更新
        val roadType = intent.getIntExtra("ROAD_TYPE", -1)
        val sapaDist = intent.getStringExtra("SAPA_DIST_AUTO") ?: ""
        val sapaName = intent.getStringExtra("SAPA_NAME") ?: ""

        turnView?.setImageResource(turnIconRes(icon))
        applyTheme()   // 染色（新图标/外观变化后统一刷新）
        distView?.text = seg.withSmallUnit()
        // 道路名单独一行（主信息，参照 CarPlay 转向卡"进入宝石路"）
        timeView?.text = nextRoad.ifEmpty { "导航中" }
        // 小字行：剩余时间 · 速度 · 限速（服务区独立一行，避免信息堆叠）
        val remainText = remainTime.ifEmpty { eta.replace("预计", "").replace("到达", "") }
        val speedText = if (speed > 0) "$speed" + "km/h" else ""
        val limitText = if (limited > 0) "限速$limited" else ""
        speedView?.text = listOf(remainText, speedText, limitText)
            .filter { it.isNotBlank() }.joinToString(" · ")
        // 服务区大牌：仅高速公路（ROAD_TYPE=0）显示；深绿指示牌样式，文字"名称 · 距离"（参考高德）
        // 协议 10001 服务区字段仅：SAPA_DIST/SAPA_DIST_AUTO（距离）、SAPA_NAME（名称）、
        // SAPA_TYPE（类型）、SAPA_NUM（个数），无第二个服务区与加油/品牌等设施信息
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

        Log.i(TAG, "导航信息: 转向=$icon $seg ${nextRoad} 速度=${speed}km/h 限速=$limited 高速=$roadType 服务区=$sapaDist")
    }

    /**
     * 距离单位缩小（数字大、单位小，参考高德/导航卡风格）：
     * "2.1公里"→2.1 大 + "公里" 0.55 倍；"800米"同理。
     * 支持 公里/千米/米/km/m，兼容整米与小数。
     */
    private fun CharSequence.withSmallUnit(): SpannableString {
        val s = SpannableString(this)
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米|km|m)\\b").find(this)
        if (m != null) {
            val unitStart = m.range.first + m.groupValues[1].length
            s.setSpan(RelativeSizeSpan(0.55f), unitStart, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    // ---------- 13012 车道线 ----------

    private fun handleLane(intent: Intent) {        val driveWay = intent.getStringExtra("EXTRA_DRIVE_WAY") ?: return
        // 车道线只有导航状态才渲染；同时视为活动数据，重置看门狗
        if (!navActive) return
        resetDataWatchdog()
        renderLanes(driveWay)
    }

    /** 解析 EXTRA_DRIVE_WAY JSON 并渲染车道图标行（参照 Navi-Link LaneLineView） */
    private fun renderLanes(driveWayJson: String) {
        val lanes = lanesView ?: return
        try {
            val root = JSONObject(driveWayJson)
            if (!root.optBoolean("drive_way_enabled", false)) {
                lanes.removeAllViews(); lanes.visibility = View.GONE
                return
            }
            val size = root.optInt("drive_way_size", 0)
            val info = root.optJSONArray("drive_way_info") ?: run {
                lanes.removeAllViews(); lanes.visibility = View.GONE
                return
            }
            if (size <= 0 || info.length() == 0) {
                lanes.removeAllViews(); lanes.visibility = View.GONE
                return
            }

            val ordered = (0 until info.length())
                .map { info.getJSONObject(it) }
                .sortedBy { it.optInt("drive_way_number", 0) }

            // 先解析完所有车道，判断是否存在"推荐车道"标记（真实高德带 trafficLaneAdvised）
            // 有推荐标记 → CarPlay 语义：推荐道亮白+高亮边框，其余道暗灰（不能走）
            // 无推荐标记（旧版本/模拟）→ 全部正常亮度显示
            val lanesData = ordered
            val hasAdvised = lanesData.any {
                it.optBoolean("trafficLaneAdvised", false)
                    || "true".equals(it.optString("trafficLaneAdvised"), ignoreCase = true)
                    || it.optString("trafficLaneAdvised") == "1"
            }

            lanes.removeAllViews()
            val density = context.resources.displayMetrics.density
            val heightPx = (44 * density).toInt()
            val marginPx = (1 * density).toInt()

            lanesData.forEach { lane ->
                val iconNo = lane.optString("drive_way_lane_Back_icon", "")
                val iconId = iconNo.toIntOrNull() ?: -1
                // 可行驶判定（协议附录编号表）：15~48=可行驶/复杂车道，0~14=不可行驶
                // 高德新版额外带 trafficLaneAdvised 标记时优先视为可行驶
                val advised = lane.optBoolean("trafficLaneAdvised", false)
                    || "true".equals(lane.optString("trafficLaneAdvised"), ignoreCase = true)
                    || lane.optString("trafficLaneAdvised") == "1"
                val drivable = advised || iconId >= 15
                val hasAnyDrivable = hasAdvised || lanesData.any {
                    (it.optString("drive_way_lane_Back_icon", "").toIntOrNull() ?: -1) >= 15
                }
                val isAllBright = !hasAnyDrivable   // 没有任何可走车道（异常数据）：全部正常亮度
                // 可行驶车道用对应箭头图标；不可行驶车道（0~14 资源为空）用未知图标暗灰占位，
                // 让"这条路有车道但不能走"可见，而不是凭空少一条
                val res = context.resources.getIdentifier(
                    "lane_pdf_$iconNo", "drawable", context.packageName)
                val imgRes = if (drivable && res != 0) res else R.drawable.lane_special_unknown
                val iv = ImageView(context).apply {
                    setImageResource(imgRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    // 车道线 HUD：固定白色图标
                    setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    // 可走的道（编号≥15）完全不透明；不能走的明显变灰，一眼区分
                    imageAlpha = when {
                        isAllBright -> 255
                        drivable -> 255
                        else -> 110
                    }
                    // 不用代码画的边框/底色，纯亮度区分（更接近 CarPlay/高德原生）
                }
                // 可走的道放大 40%，配合完全不透明，主次一目了然
                val lp = LinearLayout.LayoutParams(
                    ((if (drivable && !isAllBright) 56 else 40) * density).toInt(), heightPx)
                lp.setMargins(marginPx, 0, marginPx, 0)
                iv.layoutParams = lp
                lanes.addView(iv)
            }
            lanes.visibility = View.VISIBLE
            // CarPlay 风格：车道行加深色半透明圆角底，突出亮/暗对比
            lanes.background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0x66000000.toInt())
            }
            Log.i(TAG, "车道线渲染: ${ordered.size} 条 (可走=${lanesData.count { it.optString("drive_way_lane_Back_icon", "").toIntOrNull() ?: -1 >= 15 || it.optBoolean("trafficLaneAdvised", false) } })")
        } catch (e: Exception) {
            Log.w(TAG, "解析车道线失败: ${e.message}")
            lanes.removeAllViews()
            lanes.visibility = View.GONE
        }
    }

    /** 转向图标编号 → 资源（高德风格白色箭头，Navi-Link 资源：sou{N}_night_a530） */
    private fun turnIconRes(icon: Int): Int {
        val name = "sou${icon}_night_a530"
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.sou20_night_a530
    }
}
