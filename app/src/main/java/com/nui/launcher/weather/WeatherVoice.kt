package com.nui.launcher.weather

import android.content.Context
import com.nui.launcher.voice.NuiTts
import java.util.Calendar

/**
 * 天气语音播报（天气场景对通用语音服务 NuiTts 的封装）。
 *
 * 仅负责"天气"这一场景的播报策略与文案，真正的合成/播放/音色由 [NuiTts] 承担，
 * 与天气模块完全解耦。规则：
 * - 首次成功获取到天气后：播报完整语音摘要（问候 + 日期 + 天气 + 温度/体感/风 + 建议）
 * - 后续刷新发现"新出现"的重大天气突发：单独播报预警（去重，预警消失后再出现可再次播报）
 *
 * MainActivity（桌面）与 WeatherActivity（全屏页）各自 new 一个实例，但底层共享同一个
 * NuiTts 引擎（模型只占一份内存）。音色切换在设置中通过 NuiTts 完成。
 */
class WeatherVoice(context: Context) {

    private val tts = NuiTts(context)

    /** 播报策略状态锁：天气回调线程与主线程（手动重播）可能并发触发 */
    private val lock = Any()
    private var firstSpoken = false
    private var announcedKeys = setOf<String>()

    /** 桌面/天气页收到天气数据后调用：自动处理首次播报 + 重大天气突发播报 */
    fun onWeather(info: WeatherFetcher.WeatherInfo) {
        val tasks = ArrayList<String>()
        synchronized(lock) {
            if (!firstSpoken) {
                firstSpoken = true
                announcedKeys = info.alerts.map { it.key }.toSet()
                tasks.add(summary(info))
            } else {
                val keys = info.alerts.map { it.key }.toSet()
                for (a in info.alerts) {
                    if (a.key !in announcedKeys) {
                        tasks.add("请注意，${info.city}气象提示：${a.message}")
                    }
                }
                announcedKeys = keys
            }
        }
        tasks.forEach { tts.speak(it) }
    }

    /** 手动重播天气摘要 */
    fun speakSummary(info: WeatherFetcher.WeatherInfo) = tts.speak(summary(info))

    /** 停止当前播报（离开界面时调用，避免与其它界面抢声） */
    fun stop() = tts.stop()

    /** 释放（共享引擎保留在内存中，此处仅停止播放） */
    fun shutdown() = tts.stop()

    /** 生成语音摘要文案 */
    private fun summary(info: WeatherFetcher.WeatherInfo): String {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..8 -> "早上好"
            in 9..11 -> "上午好"
            12 -> "中午好"
            in 13..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "夜深了"
        }
        val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(Calendar.DAY_OF_WEEK) - 1]
        val sb = StringBuilder()
        sb.append("$greeting，现在是${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日$week。")
        sb.append("${info.city}当前${info.description}，气温${info.temperature.toInt()}度，体感${info.apparentTemperature.toInt()}度，")
        sb.append("${windDirectionText(info.windDirection)}风${windLevel(info.windSpeed)}级，相对湿度百分之${info.humidity}。")
        sb.append("今天最高${info.maxTemp.toInt()}度，最低${info.minTemp.toInt()}度。")
        sb.append(advice(info))
        if (info.alerts.isNotEmpty()) {
            sb.append("请注意，")
            sb.append(info.alerts.joinToString("，") { it.message })
        }
        return sb.toString()
    }

    private fun advice(info: WeatherFetcher.WeatherInfo): String = when {
        info.weatherCode >= 95 -> "雷雨天气，出门注意安全。"
        info.weatherCode in intArrayOf(61, 63, 65, 80, 81, 82) -> "有雨，出门记得带伞。"
        info.weatherCode in intArrayOf(71, 73, 75, 77, 85, 86) -> "下雪了，注意保暖防滑。"
        info.weatherCode in intArrayOf(45, 48) -> "有雾，能见度较低，出行注意安全。"
        info.maxTemp >= 35f -> "天气炎热，注意防暑降温，多补充水分。"
        info.minTemp <= 0f -> "天气寒冷，注意添衣保暖。"
        info.weatherCode <= 1 -> "天气不错，适合出行。"
        else -> ""
    }

    private fun windDirectionText(deg: Int): String {
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        return dirs[((deg + 22.5) / 45).toInt() % 8]
    }

    private fun windLevel(speed: Float): Int {
        var level = 0
        val thresholds = floatArrayOf(0.3f, 1.6f, 3.4f, 5.5f, 8f, 10.8f, 13.9f, 17.2f, 20.8f, 24.5f, 28.5f, 32.7f)
        for (t in thresholds) if (speed >= t) level++ else break
        return level
    }
}
