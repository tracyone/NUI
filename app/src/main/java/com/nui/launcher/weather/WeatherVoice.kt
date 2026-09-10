package com.nui.launcher.weather

import android.content.Context
import android.os.Build
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

    init {
        // 后台预热语音引擎：首次天气播报/手动播报时避免等待模型加载
        tts.warmUp()
    }

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
        if (tasks.isNotEmpty()) android.util.Log.d("WeatherVoice", "onWeather 播报 ${tasks.size} 条: ${tasks.first().take(30)}...")
    }

    /** 手动重播天气摘要 */
    fun speakSummary(info: WeatherFetcher.WeatherInfo) = tts.speak(summary(info))

    /** 天气查询不到时的首播：只播问候+日期，不播天气部分。
     *  与 [onWeather] 共用 firstSpoken 去重：先播了问候版，后续天气到达时不再重复首播，
     *  仅在有新重大预警时播报预警。 */
    fun speakGreetingOnly() {
        var needGreet = false
        synchronized(lock) {
            if (!firstSpoken) {
                firstSpoken = true
                announcedKeys = emptySet()
                needGreet = true
            }
        }
        if (needGreet) {
            val text = greetingAndDate()
            tts.speak(text)
            android.util.Log.d("WeatherVoice", "天气查询不到，首播仅问候: ${text.take(30)}...")
        }
    }

    /** 停止当前播报（离开界面时调用，避免与其它界面抢声） */
    fun stop() = tts.stop()

    /** 释放（共享引擎保留在内存中，此处仅停止播放） */
    fun shutdown() = tts.stop()

    /** 问候 + 日期（不含天气数据，天气查询不到时也能播）。
     *  星期用全称"星期X"+ 逗号停顿便于听清；当天是节日时顺带播报（离线计算，无需联网）。 */
    private fun greetingAndDate(): String {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val greeting = greetingFor(hour)
        return "$greeting，${dateText(c)}"
    }

    /** 日期段文案：现在是X月X日，星期X。+（今天是XX节。） */
    private fun dateText(c: Calendar): String {
        val week = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[c.get(Calendar.DAY_OF_WEEK) - 1]
        val base = "现在是${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日，$week。"
        val festival = festivalOf(c) ?: return base
        return "${base}今天是$festival。"
    }

    /**
     * 当天节日（离线判断，无需联网）：
     * - 公历固定节日（元旦/国庆等）
     * - 可计算的周日/周四节日（母亲节/父亲节/感恩节）
     * - 农历节日（春节/元宵/端午/中秋/重阳/腊八，Android 9 及以上系统内置农历）
     * 无节日返回 null。清明等日期随年份漂移的公历节日不包含，避免报错。
     */
    private fun festivalOf(c: Calendar): String? {
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        val solar = when {
            m == 1 && d == 1 -> "元旦"
            m == 2 && d == 14 -> "情人节"
            m == 3 && d == 8 -> "妇女节"
            m == 3 && d == 12 -> "植树节"
            m == 4 && d == 1 -> "愚人节"
            m == 5 && d == 1 -> "劳动节"
            m == 5 && d == 4 -> "青年节"
            m == 6 && d == 1 -> "儿童节"
            m == 7 && d == 1 -> "建党节"
            m == 8 && d == 1 -> "建军节"
            m == 9 && d == 10 -> "教师节"
            m == 10 && d == 1 -> "国庆节"
            m == 12 && d == 24 -> "平安夜"
            m == 12 && d == 25 -> "圣诞节"
            else -> null
        }
        if (solar != null) return solar

        // 周日/周四节日：母亲节=5月第2个周日、父亲节=6月第3个周日、感恩节=11月第4个周四
        val special = when {
            m == 5 && d == nthWeekdayOfMonth(c, 5, Calendar.SUNDAY, 2) -> "母亲节"
            m == 6 && d == nthWeekdayOfMonth(c, 6, Calendar.SUNDAY, 3) -> "父亲节"
            m == 11 && d == nthWeekdayOfMonth(c, 11, Calendar.THURSDAY, 4) -> "感恩节"
            else -> null
        }
        if (special != null) return special

        // 农历节日（Android 8.0+ 内置农历；车机 Android 9 可用，低版本跳过农历部分）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cc = android.icu.util.ChineseCalendar()
            cc.timeInMillis = c.timeInMillis
            val lm = cc.get(android.icu.util.ChineseCalendar.MONTH) + 1
            val ld = cc.get(android.icu.util.ChineseCalendar.DAY_OF_MONTH)
            return when {
                lm == 1 && ld == 1 -> "春节"
                lm == 1 && ld == 15 -> "元宵节"
                lm == 5 && ld == 5 -> "端午节"
                lm == 8 && ld == 15 -> "中秋节"
                lm == 9 && ld == 9 -> "重阳节"
                lm == 12 && ld == 8 -> "腊八节"
                else -> null
            }
        }
        return null
    }

    /** 计算某年第 nth 个 dayOfWeek 的日期（1-7），非当月返回 -1 */
    private fun nthWeekdayOfMonth(c: Calendar, month: Int, dayOfWeek: Int, nth: Int): Int {
        val first = Calendar.getInstance().apply {
            set(c.get(Calendar.YEAR), month - 1, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val firstDow = first.get(Calendar.DAY_OF_WEEK)
        return 1 + (dayOfWeek - firstDow + 7) % 7 + (nth - 1) * 7
    }

    /** 生成语音摘要文案 */
    private fun summary(info: WeatherFetcher.WeatherInfo): String {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val greeting = greetingFor(hour)
        val sb = StringBuilder()
        sb.append("$greeting，${dateText(c)}")
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

    private fun greetingFor(hour: Int): String = when (hour) {
        in 5..8 -> "主人早上好"
        in 9..11 -> "主人上午好"
        12 -> "主人中午好"
        in 13..17 -> "主人下午好"
        in 18..22 -> "主人晚上好"
        else -> "主人夜深了"
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
