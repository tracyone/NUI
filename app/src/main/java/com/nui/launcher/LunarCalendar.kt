package com.nui.launcher

import java.util.Calendar
import java.util.TimeZone

/**
 * 农历 / 二十四节气 / 传统节日换算，供负一屏大时钟展示（无第三方依赖）。
 *
 * - 农历：1900-2100 年数据表（每年各月大小 + 闰月），已与权威历法全量比对通过。
 * - 节气：以 1900 年为基准的回归年线性公式 + 2000-2070 年 ±1 天修正表，
 *   修正后与权威历法一致；超出修正范围的年份最多差 1 天。
 * - 只用 Calendar（minSdk 21，不能依赖 java.time），全部按 UTC 计算，避免时区漂移。
 */
object LunarCalendar {

    /**
     * 返回当天信息，例："农历九月廿九 · 立冬"。
     * 有节日/节气时追加在农历日期后（都没有则只显示农历日期）。
     */
    fun describe(y: Int, m: Int, d: Int): String {
        val lunar = lunarOf(y, m, d) ?: return ""
        val text = buildString {
            append("农历")
            if (lunar.isLeap) append('闰')
            append(MONTH_NAMES[lunar.month - 1]).append('月')
            append(DAY_NAMES[lunar.day - 1])
        }
        val extras = mutableListOf<String>()
        if (!lunar.isLeap) {
            LUNAR_FESTIVALS["${lunar.month}-${lunar.day}"]?.let { extras += it }
            // 腊月最后一天为除夕（腊月可能是 29 或 30 天）
            if (lunar.month == 12 && lunar.day == lunar.monthDays) extras += "除夕"
        }
        SOLAR_FESTIVALS["$m-$d"]?.let { extras += it }
        termOf(y, m, d)?.let { extras += it }
        return if (extras.isEmpty()) text else "$text · ${extras.joinToString(" ")}"
    }

    // ===== 农历 =====

    private data class Lunar(
        val year: Int,
        val month: Int,
        val day: Int,
        val isLeap: Boolean,
        val monthDays: Int,
    )

    private fun lunarOf(y: Int, m: Int, d: Int): Lunar? {
        if (y < 1900 || y > 2100) return null
        var offset = daysFromSolarBase(y, m, d)
        var ty = 1900
        var temp = 0
        while (ty <= 2100 && offset > 0) {
            temp = yearDays(ty)
            offset -= temp
            ty++
        }
        if (offset < 0) {
            offset += temp
            ty--
        }
        if (ty < 1900 || ty > 2100) return null
        val leap = leapMonth(ty)
        var isLeap = false
        var month = 1
        while (month < 13 && offset > 0) {
            if (leap > 0 && month == leap + 1 && !isLeap) {
                month--
                isLeap = true
                temp = leapDays(ty)
            } else {
                temp = monthDays(ty, month)
            }
            if (isLeap && month == leap + 1) isLeap = false
            offset -= temp
            month++
        }
        // 恰好落在闰月/正月交界时需要回退一格（经典农历算法的边界修正）
        if (offset == 0 && leap > 0 && month == leap + 1) {
            if (isLeap) {
                isLeap = false
            } else {
                isLeap = true
                month--
            }
        }
        if (offset < 0) {
            offset += temp
            month--
        }
        if (month !in 1..12) return null
        return Lunar(ty, month, offset + 1, isLeap, monthDays(ty, month))
    }

    private fun leapMonth(y: Int): Int = LUNAR_INFO[y - 1900] and 0xf

    private fun leapDays(y: Int): Int = when {
        leapMonth(y) == 0 -> 0
        LUNAR_INFO[y - 1900] and 0x10000 != 0 -> 30
        else -> 29
    }

    private fun monthDays(y: Int, m: Int): Int =
        if (LUNAR_INFO[y - 1900] and (0x10000 shr m) != 0) 30 else 29

    private fun yearDays(y: Int): Int {
        var sum = 348
        var bit = 0x8000
        while (bit > 0x8) {
            if (LUNAR_INFO[y - 1900] and bit != 0) sum++
            bit = bit shr 1
        }
        return sum + leapDays(y)
    }

    // ===== 二十四节气 =====

    /** 返回当天节气名；不是节气日返回 null。偶数下标为上半月节气，奇数下标为下半月。 */
    private fun termOf(y: Int, m: Int, d: Int): String? {
        val first = (m - 1) * 2
        for (n in first..first + 1) {
            if (n in TERM_NAMES.indices && termDay(y, n) == d) return TERM_NAMES[n]
        }
        return null
    }

    /** 第 n 个节气（n=0 小寒 … 23 冬至）在当年的公历日（1-31），已叠加修正表。 */
    private fun termDay(y: Int, n: Int): Int {
        val cal = utcCal()
        cal.timeInMillis =
            TERM_BASE_MS + (31556925974.7 * (y - 1900) + TERM_INFO[n] * 60_000L).toLong()
        cal.add(Calendar.DAY_OF_MONTH, termFix(y, n))
        return cal.get(Calendar.DAY_OF_MONTH)
    }

    /** 公式与权威历法的日差修正：'0'=-1 天、'1'=0、'2'=+1 天。 */
    private fun termFix(y: Int, n: Int): Int {
        val row = TERM_FIX.getOrNull(y - TERM_FIX_YEAR) ?: return 0
        return when (row.getOrNull(n)) {
            '0' -> -1
            '2' -> 1
            else -> 0
        }
    }

    // ===== 日期基准 =====

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private fun utcCal(): Calendar = Calendar.getInstance(UTC).apply { clear() }

    /** 农历 1900 年正月初一 = 公历 1900-01-31 */
    private val LUNAR_BASE_MS: Long = utcCal().apply { set(1900, 0, 31) }.timeInMillis

    /** 节气公式基准：1900-01-06 02:05 UTC */
    private val TERM_BASE_MS: Long = utcCal().apply { set(1900, 0, 6, 2, 5) }.timeInMillis

    private fun daysFromSolarBase(y: Int, m: Int, d: Int): Int =
        ((utcCal().apply { set(y, m - 1, d) }.timeInMillis - LUNAR_BASE_MS) / 86_400_000L).toInt()

    // ===== 文案 =====

    private val MONTH_NAMES = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")

    private val DAY_NAMES = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    private val LUNAR_FESTIVALS = mapOf(
        "1-1" to "春节", "1-15" to "元宵节", "2-2" to "龙头节", "5-5" to "端午节",
        "7-7" to "七夕", "8-15" to "中秋节", "9-9" to "重阳节", "12-8" to "腊八节", "12-23" to "小年",
    )

    private val SOLAR_FESTIVALS = mapOf(
        "1-1" to "元旦", "2-14" to "情人节", "3-8" to "妇女节", "3-12" to "植树节",
        "4-1" to "愚人节", "5-1" to "劳动节", "5-4" to "青年节", "6-1" to "儿童节",
        "7-1" to "建党节", "8-1" to "建军节", "9-10" to "教师节", "10-1" to "国庆节", "12-25" to "圣诞节",
    )

    private val TERM_NAMES = arrayOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
        "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
        "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )

    /** 每个节气相对回归年起点的分钟偏移（0 = 小寒） */
    private val TERM_INFO = intArrayOf(
        0, 21208, 42467, 63836, 85337, 107014, 128867, 150921,
        173149, 195551, 218072, 240693, 263343, 285989, 308563, 331033,
        353350, 375494, 397447, 419210, 440795, 462224, 483532, 504758,
    )

    /** 修正表起始年份与每年的 24 个修正字符（'0'=-1天 '1'=0天 '2'=+1天） */
    private const val TERM_FIX_YEAR = 2000

    private val TERM_FIX = arrayOf(
        "111111111111111111111111", "111111111111111111111111", "111111111111111111111111",
        "111111111111111111111111", "111111111111111111111111", "111111111111111111111111",
        "111111110111110111111111", "111111111111111111111211", "121111111111111110111111",
        "112111111111111111111111", "111121111111110111111111", "211111111111111111111211",
        "121111111011111111111121", "112111111111101111111112", "111121111111111111111111",
        "211111111111111111111111", "111111111111111111111121", "111111111111101111111112",
        "111212111111111111111111", "111111111110111111111111", "111111111111011011111121",
        "111111111111111111111111", "111211111111111101111111", "111111111110111111122111",
        "111111111111111011111111", "111111111111111111111111", "111111111101111111111111",
        "111111111111111111111111", "111111111111111111111111", "111111111111111111111111",
        "111111111101111111111111", "111111111111111111111111", "111111111111111111111111",
        "111111111111111111111111", "111111111111111111111111", "111111110111110111111111",
        "111111111111111111111111", "121111111111111111111111", "111111111111111111111111",
        "111111111111110111111111", "211111111111111111211211", "121111111011111111111111",
        "112111111111111111111111", "111121111111110111111111", "211111111111111111111211",
        "121111101011111111111121", "112111111111101111111112", "111121111111111111111111",
        "211111111110111111111111", "111111111111011011111121", "111111111111101111111112",
        "111211111111111101111111", "111111111110111111111111", "111111111111011011111121",
        "111111111111111111111112", "111211111101111101111111", "111111111110111111122111",
        "111111111111111111111111", "111111111111111111111111", "111211111101111111111111",
        "111111111111111111111111", "111111111111111111111111", "111111111111111111111111",
        "111111111101111111111111", "111111111111111111111111", "111111111111111111111111",
        "111111111111111111111111", "111111111111111111111111", "111111110111110111111111",
        "111111111111111111111111", "121111111011111110111111",
    )

    /** 农历年数据表 1900-2100：bit16=闰月大小月，bit15..4=十二个月大小月，bit3..0=闰月月份 */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06aa0, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520,
    )
}
