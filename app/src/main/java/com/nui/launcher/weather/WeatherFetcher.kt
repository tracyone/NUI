package com.nui.launcher.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 天气数据获取：基于 Open-Meteo 免费 API（无需 key），按经纬度查询当前天气 + 逐小时 + 逐日预报。
 * 每 30 分钟自动刷新一次，结果通过回调通知 UI。
 */
class WeatherFetcher(private val context: Context) {

    /** 逐小时预报点 */
    data class HourlyPoint(
        val time: String,          // "HH:00"
        val temperature: Float,
        val weatherCode: Int,
        val precipProb: Int,       // 降水概率 %
    )

    /** 逐日预报点 */
    data class DailyPoint(
        val date: String,          // "yyyy-MM-dd"
        val maxTemp: Float,
        val minTemp: Float,
        val weatherCode: Int,
        val precipSum: Float,      // 降水量 mm
        val precipProb: Int,       // 降水概率 %
    )

    /** 重大天气事件（用于语音预警） */
    data class SevereAlert(
        val key: String,           // 去重键
        val level: String,         // 严重 / 高温 / 低温 / 大风 / 暴雨
        val title: String,
        val message: String,       // 播报文案
    )

    data class WeatherInfo(
        val temperature: Float,        // 温度（摄氏度）
        val apparentTemperature: Float, // 体感温度
        val humidity: Int,             // 相对湿度 %
        val windSpeed: Float,          // 风速 m/s
        val windDirection: Int,        // 风向（度）
        val weatherCode: Int,          // WMO 天气代码
        val description: String,       // 中文描述
        val icon: String,              // emoji 图标
        val city: String,              // 城市名
        val isDay: Int,                // 1=白天 0=夜晚
        val maxTemp: Float,            // 今日最高温
        val minTemp: Float,            // 今日最低温
        val hourly: List<HourlyPoint>, // 未来 12 小时
        val daily: List<DailyPoint>,   // 未来 5 天
        val alerts: List<SevereAlert>, // 重大天气预警
    ) {
        val severe: Boolean get() = alerts.isNotEmpty()
    }

    /** 天气回调 */
    var onWeatherReady: ((WeatherInfo) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var fetching = false
    private var lastFetchTime = 0L
    private var cached: WeatherInfo? = null

    /** 当前位置经纬度和城市名，默认珠海（可通过 setLocation 更新） */
    private var lat = 22.2707
    private var lon = 113.5767
    private var cityName = "珠海"

    /** 设置位置（经纬度+城市名），设置后清缓存重新获取 */
    fun setLocation(latitude: Double, longitude: Double, name: String) {
        if (latitude != lat || longitude != lon) {
            lat = latitude
            lon = longitude
            cityName = name
            cached = null
            lastFetchTime = 0
        }
    }

    /** 刷新间隔：30 分钟 */
    private val refreshInterval = 30 * 60 * 1000L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetch(force = true)
            handler.postDelayed(this, refreshInterval)
        }
    }

    /** 启动定时刷新 */
    fun start() {
        fetch(force = false)
        handler.postDelayed(refreshRunnable, refreshInterval)
    }

    /** 停止定时刷新 */
    fun stop() {
        handler.removeCallbacks(refreshRunnable)
    }

    /**
     * 获取天气。
     * @param force true=强制刷新，false=有缓存且未过期则直接用缓存
     */
    fun fetch(force: Boolean = false) {
        if (!force && cached != null && System.currentTimeMillis() - lastFetchTime < refreshInterval) {
            cached?.let { onWeatherReady?.invoke(it) }
            return
        }
        if (fetching) return
        fetching = true

        thread {
            try {
                val url = "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat" +
                        "&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max" +
                        "&timezone=auto" +
                        "&forecast_days=7"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(response)
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m").toFloat()
                val code = current.getInt("weather_code")
                val apparent = current.optDouble("apparent_temperature", temp.toDouble()).toFloat()
                val humidity = current.optInt("relative_humidity_2m", 0)
                val wind = current.optDouble("wind_speed_10m", 0.0).toFloat()
                val windDir = current.optInt("wind_direction_10m", 0)
                val isDay = current.optInt("is_day", 1)

                val hourly = parseHourly(json.optJSONObject("hourly"))
                val daily = parseDaily(json.optJSONObject("daily"))

                val maxT = daily.firstOrNull()?.maxTemp ?: temp
                val minT = daily.firstOrNull()?.minTemp ?: temp
                val precipSum = daily.firstOrNull()?.precipSum ?: 0f

                val info = WeatherInfo(
                    temperature = temp,
                    apparentTemperature = apparent,
                    humidity = humidity,
                    windSpeed = wind,
                    windDirection = windDir,
                    weatherCode = code,
                    description = codeToDescription(code),
                    icon = codeToIcon(code),
                    city = cityName,
                    isDay = isDay,
                    maxTemp = maxT,
                    minTemp = minT,
                    hourly = hourly,
                    daily = daily,
                    alerts = detectAlerts(code, maxT, minT, wind, precipSum),
                )
                cached = info
                lastFetchTime = System.currentTimeMillis()
                handler.post { onWeatherReady?.invoke(info) }
            } catch (e: Exception) {
                Log.w("NUI.Weather", "获取天气失败: ${e.message}")
                // 有缓存时失败也不清空，界面继续显示旧数据
                cached?.let { handler.post { onWeatherReady?.invoke(it) } }
            } finally {
                fetching = false
            }
        }
    }

    private fun parseHourly(h: JSONObject?): List<HourlyPoint> {
        if (h == null) return emptyList()
        return runCatching {
            val time = h.getJSONArray("time")
            val t = h.getJSONArray("temperature_2m")
            val c = h.getJSONArray("weather_code")
            val p = h.optJSONArray("precipitation_probability")
            // 定位到当前小时
            val key = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(Date())
            var start = 0
            for (i in 0 until time.length()) {
                val s = time.getString(i)
                if (s >= key) { start = i; break }
            }
            val end = minOf(start + 12, time.length())
            (start until end).map { i ->
                HourlyPoint(
                    time = time.getString(i).substring(11, 16),
                    temperature = t.getDouble(i).toFloat(),
                    weatherCode = c.getInt(i),
                    precipProb = if (p != null && !p.isNull(i)) p.getInt(i) else 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseDaily(d: JSONObject?): List<DailyPoint> {
        if (d == null) return emptyList()
        return runCatching {
            val time = d.getJSONArray("time")
            val max = d.getJSONArray("temperature_2m_max")
            val min = d.getJSONArray("temperature_2m_min")
            val c = d.getJSONArray("weather_code")
            val ps = d.optJSONArray("precipitation_sum")
            val pp = d.optJSONArray("precipitation_probability_max")
            val end = minOf(5, time.length())
            (0 until end).map { i ->
                DailyPoint(
                    date = time.getString(i),
                    maxTemp = max.getDouble(i).toFloat(),
                    minTemp = min.getDouble(i).toFloat(),
                    weatherCode = c.getInt(i),
                    precipSum = if (ps != null && !ps.isNull(i)) ps.getDouble(i).toFloat() else 0f,
                    precipProb = if (pp != null && !pp.isNull(i)) pp.getInt(i) else 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 重大天气检测：根据当前天气码 + 今日最高/最低温 + 风速 + 降水量 判定预警。
     * 阈值：雷暴/大雨/大雪/高温≥35℃/低温≤0℃/大风≥8级(17.2m/s)/暴雨≥50mm
     */
    private fun detectAlerts(code: Int, maxT: Float, minT: Float, wind: Float, precipSum: Float): List<SevereAlert> {
        val alerts = mutableListOf<SevereAlert>()
        if (code >= 95) alerts += SevereAlert("storm", "严重", "雷暴", "雷暴天气，请远离空旷地带和树木，注意防雷安全。")
        if (code == 82 || code == 65 || code == 67) alerts += SevereAlert("heavyrain", "严重", "强降雨", "强降雨天气，低洼路段易积水，驾车出行请注意安全。")
        if (code == 75 || code == 86) alerts += SevereAlert("heavysnow", "严重", "强降雪", "强降雪天气，道路湿滑，请注意出行安全。")
        if (maxT >= 35f) alerts += SevereAlert("heat", "高温", "高温", "今日最高气温 ${maxT.toInt()}℃，请注意防暑降温。")
        if (minT <= 0f) alerts += SevereAlert("cold", "低温", "低温", "今日最低气温 ${minT.toInt()}℃，请注意防寒保暖。")
        if (wind >= 17.2f) alerts += SevereAlert("wind", "大风", "大风", "当前风力已达 ${beaufort(wind)} 级（${wind.toInt()} 米/秒），请注意防风。")
        if (precipSum >= 50f) alerts += SevereAlert("rainstorm", "暴雨", "暴雨", "今日预计降雨 ${precipSum.toInt()} 毫米，可能出现暴雨，请减少外出。")
        return alerts
    }

    /** 风速 m/s → 蒲福风级 */
    private fun beaufort(wind: Float): Int = when {
        wind < 0.3f -> 0
        wind < 1.6f -> 1
        wind < 3.4f -> 2
        wind < 5.5f -> 3
        wind < 8.0f -> 4
        wind < 10.8f -> 5
        wind < 13.9f -> 6
        wind < 17.2f -> 7
        wind < 20.8f -> 8
        wind < 24.5f -> 9
        wind < 28.5f -> 10
        wind < 32.7f -> 11
        else -> 12
    }

    /** 风向角度 → 中文方位 */
    fun windDirectionText(deg: Int): String {
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        return dirs[((deg + 22.5) / 45).toInt() % 8]
    }

    /** WMO 天气代码 → 中文描述 */
    fun codeToDescription(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "未知"
    }

    /**
     * 国内主要城市经纬度映射表（用于无网络逆地理编码时根据经纬度匹配城市名）。
     * 经纬度为各市政府所在地大致坐标。
     */
    private val cityCoords = listOf(
        "北京" to (39.9042 to 116.4074), "上海" to (31.2304 to 121.4737),
        "广州" to (23.1291 to 113.2644), "深圳" to (22.5431 to 114.0579),
        "中山" to (22.5231 to 113.3791), "珠海" to (22.2707 to 113.5767),
        "佛山" to (23.0218 to 113.1219), "东莞" to (23.0208 to 113.7518),
        "杭州" to (30.2741 to 120.1551), "南京" to (32.0603 to 118.7969),
        "成都" to (30.5728 to 104.0668), "重庆" to (29.5630 to 106.5516),
        "武汉" to (30.5928 to 114.3055), "西安" to (34.3416 to 108.9398),
        "天津" to (39.3434 to 117.3616), "苏州" to (31.2989 to 120.5853),
        "长沙" to (28.2282 to 112.9388), "郑州" to (34.7466 to 113.6254),
        "青岛" to (36.0671 to 120.3826), "大连" to (38.9140 to 121.6147),
        "厦门" to (24.4798 to 118.0894), "福州" to (26.0745 to 119.2965),
        "济南" to (36.6512 to 117.1201), "沈阳" to (41.8057 to 123.4315),
        "长春" to (43.8171 to 125.3235), "哈尔滨" to (45.8038 to 126.5350),
        "石家庄" to (38.0428 to 114.5149), "太原" to (37.8706 to 112.5489),
        "合肥" to (31.8206 to 117.2272), "南昌" to (28.6820 to 115.8929),
        "南宁" to (22.8170 to 108.3669), "海口" to (20.0440 to 110.1999),
        "贵阳" to (26.6470 to 106.6302), "昆明" to (25.0389 to 102.7183),
        "兰州" to (36.0611 to 103.8343), "乌鲁木齐" to (43.8256 to 87.6168),
        "呼和浩特" to (40.8426 to 111.7492)
    )

    /** 根据经纬度查找最近的城市名（匹配不到返回"当前位置"） */
    fun nearestCity(lat: Double, lon: Double): String {
        var best = "当前位置"
        var bestDist = Double.MAX_VALUE
        for ((name, coord) in cityCoords) {
            val dLat = lat - coord.first
            val dLon = lon - coord.second
            val dist = dLat * dLat + dLon * dLon
            if (dist < bestDist) {
                bestDist = dist
                best = name
            }
        }
        // 距离过远（不在国内主要城市附近）则返回"当前位置"
        return if (bestDist < 1.0) best else "当前位置"
    }

    /** WMO 天气代码 → emoji 图标 */
    fun codeToIcon(code: Int): String = when (code) {
        0 -> "☀️"
        1 -> "🌤️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}
