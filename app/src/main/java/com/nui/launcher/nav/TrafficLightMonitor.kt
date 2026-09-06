package com.nui.launcher.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.nui.launcher.voice.NuiTts
import org.json.JSONArray

/**
 * 红绿灯倒计时监控。
 *
 * 监听高德车机版广播：
 * - 10001：导航/巡航信息
 *   - ICON 字段判断模式：ICON=0 → 巡航模式，ICON>0 → 导航模式（转向图标）
 *   - CUR_SPEED：当前速度（km/h）
 * - 60073：红绿灯数据
 *   - 导航模式：redLightCountDownSeconds（单一路口）
 *   - 巡航模式：lightsData（JSON 数组，多方向）
 *
 * 提醒逻辑：
 * - 仅巡航模式下做红绿灯提醒（导航模式高德自己有播报，NUI 不重复提醒）
 * - 巡航模式：速度 < 15 km/h 时，监控最近的红绿灯，红灯倒计时 <=3 秒时提醒变灯
 *
 * 使用通用 NuiTts 离线语音，与天气/导航等其他场景解耦。
 */
class TrafficLightMonitor(private val context: Context) {
    companion object {
        private const val TAG = "NUI.TrafficLight"
        private const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
        private const val KEY_TYPE_NAVI_INFO = 10001
        private const val KEY_TYPE_TRAFFIC_LIGHT = 60073
        // 触发提醒的倒计时阈值（秒）
        private const val ALERT_THRESHOLD = 3
        // 巡航模式下启用提醒的速度阈值（km/h）
        private const val CRUISE_SPEED_THRESHOLD = 15.0
    }

    private var tts: NuiTts? = null

    // 当前模式：true=巡航模式，false=导航模式。从 10001 广播的 ICON 字段判断（ICON=0→巡航）
    @Volatile
    private var isCruiseMode = false

    // 当前速度（km/h），从 10001 广播获取
    @Volatile
    private var currentSpeed = 0.0

    // 巡航模式：记录每个方向的提醒状态，key=方向，value=是否已提醒
    private val cruiseAlerted = HashMap<String, Boolean>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val keyType = intent?.getIntExtra("KEY_TYPE", -1) ?: return
            when (keyType) {
                KEY_TYPE_NAVI_INFO -> handleNaviInfo(intent)
                KEY_TYPE_TRAFFIC_LIGHT -> handleTrafficLight(intent)
            }
        }
    }

    fun start() {
        tts = NuiTts(context)
        context.registerReceiver(receiver, IntentFilter(ACTION_SEND))
        Log.i(TAG, "红绿灯监控已启动（巡航速度阈值=${CRUISE_SPEED_THRESHOLD}km/h）")
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        tts = null
        Log.i(TAG, "红绿灯监控已停止")
    }

    // ---------- 10001 导航/巡航信息 ----------

    private fun handleNaviInfo(intent: Intent) {
        // ICON 字段判断模式：ICON=0 → 巡航模式，ICON>0 → 导航模式（转向图标）
        val icon = intent.getIntExtra("ICON", -1)
        if (icon >= 0) {
            val cruise = icon == 0
            if (cruise != isCruiseMode) {
                isCruiseMode = cruise
                Log.i(TAG, "模式切换: ${if (cruise) "巡航模式" else "导航模式"} (ICON=$icon)")
                // 模式切换时重置提醒状态
                cruiseAlerted.clear()
            }
        }

        val speed = intent.getDoubleExtra("CUR_SPEED", -1.0)
        if (speed >= 0) {
            currentSpeed = speed
            Log.d(TAG, "当前速度: ${speed}km/h (${if (isCruiseMode) "巡航" else "导航"}模式)")
        }
    }

    // ---------- 60073 红绿灯数据 ----------

    private fun handleTrafficLight(intent: Intent) {
        // 只有巡航模式下才做红绿灯提醒，导航模式高德自己有播报
        if (!isCruiseMode) {
            Log.d(TAG, "[导航模式] 忽略红绿灯广播（导航模式不提醒）")
            return
        }
        handleCruiseMode(intent)
    }

    /**
     * 巡航模式：多方向红绿灯 JSON 数组。
     * 字段：lightsData（JSONArray）
     * 速度 < 15 km/h 时才提醒，每个方向独立去重。
     * 取数组中第一个（最近的）红绿灯进行监控。
     */
    private fun handleCruiseMode(intent: Intent) {
        // 速度判断：只有低速时才提醒（等红灯或缓慢行驶）
        if (currentSpeed >= CRUISE_SPEED_THRESHOLD) {
            Log.d(TAG, "[巡航模式] 速度 ${currentSpeed}km/h >= ${CRUISE_SPEED_THRESHOLD}km/h，跳过提醒")
            return
        }

        val lightsData = intent.getStringExtra("lightsData")
        if (lightsData.isNullOrEmpty()) {
            Log.d(TAG, "[巡航模式] 无 lightsData 字段")
            return
        }

        try {
            val array = JSONArray(lightsData)
            if (array.length() == 0) {
                Log.d(TAG, "[巡航模式] lightsData 为空数组")
                return
            }

            Log.d(TAG, "[巡航模式] 收到 ${array.length()} 个方向红绿灯，速度=${currentSpeed}km/h")

            // 取第一个（最近的）红绿灯
            val first = array.getJSONObject(0)
            Log.d(TAG, "[巡航模式] 最近红绿灯原始数据: ${first.toString()}")

            // 尝试解析常见字段名（不同版本高德可能不同）
            val dir = first.optString("dir", first.optString("direction", "unknown"))
            val status = first.optString("trafficLightStatus", first.optString("state", "unknown"))
            val countdown = first.optInt(
                "redLightCountDownSeconds",
                first.optInt("remaining_time", first.optInt("countDown", -1))
            )

            Log.d(TAG, "[巡航模式] 最近红绿灯: 方向=$dir, 状态=$status, 倒计时=${countdown}秒")

            if (countdown < 0) {
                Log.d(TAG, "[巡航模式] 无法解析倒计时字段，跳过")
                return
            }

            // 只对红灯提醒（绿灯不需要提醒变灯）
            // status 可能是 "red" / "RED" / 数字等，做兼容判断
            val isRed = status.contains("red", ignoreCase = true) ||
                    status == "1" || status == "0"
            if (!isRed && status != "unknown") {
                Log.d(TAG, "[巡航模式] 当前不是红灯（status=$status），跳过")
                // 非红灯时重置该方向的提醒状态
                cruiseAlerted[dir] = false
                return
            }

            // 归零重置
            if (countdown == 0) {
                cruiseAlerted[dir] = false
                return
            }

            // 每个方向独立去重
            val alreadyAlerted = cruiseAlerted[dir] ?: false
            if (!alreadyAlerted && countdown <= ALERT_THRESHOLD) {
                cruiseAlerted[dir] = true
                alert(countdown, "巡航模式·方向$dir")
            }

        } catch (e: Exception) {
            Log.w(TAG, "[巡航模式] 解析 lightsData 失败: ${e.message}, 原始数据: $lightsData")
        }
    }

    private fun alert(countdown: Int, source: String) {
        val text = if (countdown <= 1) "绿灯即将亮起" else "${countdown}秒后变绿"
        Log.i(TAG, "触发提醒[$source]: $text")
        tts?.speak(text)
    }
}
