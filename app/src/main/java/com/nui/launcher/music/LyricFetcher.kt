package com.nui.launcher.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 网络歌词抓取器：按「歌名 + 歌手」从网易云音乐公开接口抓取整段 LRC 歌词。
 *
 * 背景：酷我车机版（cn.kuwo.kwmusiccar GKUI_yikatong）不对外暴露任何歌词接口——
 *   - 反编译确认其远程服务只暴露底层播放控制 AIDL（AIDLPlayContentInterface），
 *     全 APK 搜不到 getCurPlayLrc / getCurrentMusicName 等"歌曲信息+歌词"接口；
 *   - MediaSession 元数据只发布 TITLE/ARTIST/ALBUM/封面/时长，不含 LYRIC；
 *   - 前台通知被系统拦截且不带 bigText 歌词。
 * 因此改用「歌名+歌手 → 网易云搜索 → 歌词」的通用方案，QQ/网易云/酷狗/酷我都能用。
 *
 * 流程：
 *   1) search: https://music.163.com/api/search/get/web?s=<title artist>&type=1
 *   2) 打分选最匹配的一条（标题精确 + 歌手匹配加权）
 *   3) lyric:  https://music.163.com/api/song/lyric?id=<id>&lv=1&kv=1&tv=-1
 *
 * 网络请求在后台单线程执行，回调切回主线程；结果按「标题||歌手」缓存（含失败缓存），
 * 同一首歌只抓一次，避免切歌/轮询时反复打接口。
 */
class LyricFetcher(private val context: Context) {

    /** 新歌词回调：(lrcText, translation?) */
    var onLyricReady: ((String, String?) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    /** 缓存：normalize(title)||normalize(artist) -> lrc（空串表示该歌无词，避免反复抓） */
    private val cache = HashMap<String, String>()
    /** 正在抓取的 key（防止同一首歌重复请求） */
    private var fetchingKey: String? = null

    /** 请求一首歌的歌词。命中缓存直接回调；否则后台抓取。线程安全（回调在主线程）。
     *  @param durationMs 歌曲时长（毫秒），用于 QQ 音乐纯文本歌词估算时间戳；0 则用默认 200 秒 */
    fun requestLyric(title: String, artist: String, durationMs: Long = 0L) {
        if (title.isBlank()) return
        val key = norm(title) + "||" + norm(artist)
        if (key == fetchingKey) return                 // 正在抓这首歌，跳过
        val cached = cache[key]
        if (cached != null) {
            Log.d(TAG, "lyric cache hit: $title - $artist")
            handler.post { if (cached.isNotEmpty()) onLyricReady?.invoke(cached, null) }
            return
        }
        fetchingKey = key
        Log.d(TAG, "fetch lyric: $title - $artist (duration=${durationMs}ms)")
        executor.execute {
            val result = runCatching { fetchLrc(title, artist, durationMs) }.getOrNull()
            Log.d(TAG, "fetch lyric done: $title -> ${result?.length ?: 0} chars")
            handler.post {
                if (fetchingKey == key) fetchingKey = null
                cache[key] = result ?: ""              // 失败也缓存，短期不重试
                if (result != null) onLyricReady?.invoke(result, null)
            }
        }
    }

    fun stop() {
        runCatching { executor.shutdownNow() }
    }

    // ===== 网络 =====

    private fun fetchLrc(title: String, artist: String, durationMs: Long = 0L): String? {
        val candidates = searchTop(title, artist)?.toMutableList() ?: mutableListOf()
        // 先尝试原始歌名候选，取第一个有真实时间轴歌词的
        for (song in candidates) {
            val lrc = fetchLyric(song)
            if (lrc != null && hasRealLyrics(lrc)) {
                Log.d(TAG, "pick song id=${song.id} name=${song.name} artist=${song.artists.joinToString("/")}")
                return lrc
            }
            Log.d(TAG, "skip song id=${song.id} name=${song.name} (no real lyric)")
        }
        // 原始歌名没找到，用清洗后的歌名（去掉 DJ版/升调版/弹鼓版 等后缀）再搜一次
        // （汽水音乐等 app 的歌名常带版本后缀，直接搜命中率低）
        val cleaned = cleanTitle(title)
        if (cleaned.isNotEmpty() && cleaned != title) {
            Log.d(TAG, "retry with cleaned title: '$cleaned' (from '$title')")
            val more = searchTop(cleaned, artist) ?: emptyList()
            for (song in more) {
                if (candidates.any { it.id == song.id }) continue
                candidates.add(song)
                val lrc = fetchLyric(song)
                if (lrc != null && hasRealLyrics(lrc)) {
                    Log.d(TAG, "pick song id=${song.id} name=${song.name} artist=${song.artists.joinToString("/")}")
                    return lrc
                }
                Log.d(TAG, "skip song id=${song.id} name=${song.name} (no real lyric)")
            }
        }
        // 网易云无时间轴歌词时，fallback 到 QQ 音乐（汽水/抖音独家歌曲 QQ 库更全，但返回纯文本无时间戳）
        val qqLrc = fetchQqLyric(cleaned.ifEmpty { title }, artist, durationMs)
        if (qqLrc != null) {
            Log.d(TAG, "QQ music fallback lyric: ${qqLrc.length} chars")
            return qqLrc
        }
        return null
    }

    // ===== QQ 音乐 fallback =====

    /** QQ 音乐搜索 + 歌词：返回估算时间戳后的 LRC 文本（纯文本歌词按歌曲时长均分行时间） */
    private fun fetchQqLyric(title: String, artist: String, durationMs: Long): String? {
        val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" +
            enc(q) + "&format=json&n=5"
        val body = httpGet(searchUrl) ?: return null
        val songmid = runCatching {
            val obj = org.json.JSONObject(body)
            val list = obj.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                ?: org.json.JSONArray()
            if (list.length() == 0) return null
            // 取第一个匹配结果
            list.getJSONObject(0).optString("songmid")
        }.getOrDefault("")
        if (songmid.isBlank()) return null
        Log.d(TAG, "QQ music songmid=$songmid for '$title'")
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&format=json"
        val lyricBody = httpGet(lyricUrl, mapOf("Referer" to "https://y.qq.com/")) ?: return null
        val rawLrc = runCatching {
            val obj = org.json.JSONObject(lyricBody)
            val b64 = obj.optString("lyric", "")
            if (b64.isBlank()) return null
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
        if (rawLrc.isBlank()) return null
        // QQ 音乐返回纯文本（无时间戳），估算时间戳转 LRC
        return plainTextToLrc(rawLrc, durationMs)
    }

    /** 纯文本歌词转 LRC：过滤元数据行，按歌曲时长均分行时间戳 */
    private fun plainTextToLrc(raw: String, durationMs: Long): String {
        // QQ 音乐纯文本歌词里实际会出现的元数据关键词（行首 + 冒号）
        val metaKw = listOf(
            "演唱", "作词", "作曲", "编曲", "混音", "母带", "录音", "监制", "制作人", "制作",
            "统筹", "企划", "营销", "宣传", "推广", "出品", "发行", "出版", "版权", "授权",
            "OP", "SP", "和声", "和声编写", "配唱", "人声", "吉他", "贝斯", "键盘", "弦乐",
            "钢琴", "鼓", "笛子", "二胡", "古筝", "琵琶", "箫", "唢呐", "小提琴", "大提琴",
            "封面", "视觉", "设计", "插画", "摄影", "化妆", "造型", "服装", "导演", "编剧",
            "剪辑", "特效", "调色", "字幕", "翻译", "校对", "审核", "鸣谢", "特别鸣谢", "感谢",
            "友情出演", "联合出品", "联合发行", "独家发行", "独家出品", "音乐统筹", "音乐发行",
            "音乐出品", "音乐制作", "音乐监制", "音乐企划", "音乐营销", "音乐宣传", "音乐推广",
            "出品人", "发行人", "监制人", "原唱", "翻唱", "改编", "填词", "谱曲", "原曲",
            "原词", "念白", "口白", "说唱", "rap", "Rap", "RAP", "和声演唱", "和声歌手",
            "配唱制作人", "人声编辑", "混音工程师", "母带工程师", "录音工程师", "录音棚",
            "混音棚", "母带棚", "工作室", "录音室", "混音室", "母带室", "制作室", "厂牌",
            "唱片公司", "经纪公司", "经纪", "代理", "总代理", "独家代理", "发行代理", "版权代理",
            "词曲版权", "录音版权", "词曲", "词曲作者", "词曲创作", "创作", "创作者", "创作人",
            "原创", "原创作者", "专辑", "专辑名", "专辑名称", "单曲", "单曲名", "单曲名称",
            "EP", "EP名", "EP名称", "流派", "风格", "语言", "地区", "国家", "发行时间",
            "发行日期", "ISRC", "UPC", "EAN", "条形码", "唱片编号", "版权所有", "翻录必究",
            "版权声明", "法律声明", "本软件由", "本歌曲由", "本专辑由", "本单曲由", "本音乐由",
            "本作品由", "本内容由", "本资源由"
        )
        // 允许关键词后面跟 0-6 个字符再跟冒号，覆盖"企划营销："、"音乐统筹："等组合词
        val metaRe = Regex("^(?:" + metaKw.joinToString("|") { Regex.escape(it) } + ")(?:[^：:\\n]{0,6})?\\s*[:：]")
        // QQ 音乐纯音乐/无歌词占位文本
        val placeholderRe = Regex("(纯音乐|没有填词|请您欣赏|暂无歌词|无歌词|instrumental|纯音乐，请欣赏)")
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !metaRe.containsMatchIn(it) && !placeholderRe.containsMatchIn(it) }
        if (lines.isEmpty()) return ""
        val dur = if (durationMs > 0) durationMs else 200_000L  // 默认 200 秒
        val step = dur / lines.size
        val sb = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            val t = (i * step).coerceAtMost(dur - 1)
            val min = t / 60000
            val sec = (t % 60000) / 1000
            val ms = t % 1000
            sb.append(String.format("[%02d:%02d.%03d]%s\n", min, sec, ms, line))
        }
        return sb.toString()
    }

    /** 清洗歌名：去掉括号里的版本信息和常见版本后缀，提高搜索命中率。
     *  例如 "空想都红了眼眶 (DJ弹鼓版)0.8x升调版" → "空想都红了眼眶" */
    private fun cleanTitle(title: String): String {
        var t = title
        // 去掉括号里含版本关键词的内容（中英文括号）
        val versionKw = "DJ|版|remix|cover|live|伴奏|升调|降调|加速|减速|弹鼓|重鼓|咚鼓|0\\.\\d+x|\\d+x|纯音乐|instrumental"
        t = t.replace(Regex("\\([^)]*(?:$versionKw)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("（[^）]*(?:$versionKw)[^）]*）", RegexOption.IGNORE_CASE), " ")
        // 去掉末尾常见版本后缀
        t = t.replace(Regex("(?:DJ|升调|降调|加速|减速|弹鼓|重鼓|咚鼓|伴奏|纯音乐)\\s*版?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\b(?:remix|cover|live|instrumental|acoustic)\\b", RegexOption.IGNORE_CASE), "")
        // 去掉类似 "0.8x" / "1.2x" 的倍速标记
        t = t.replace(Regex("\\d+\\.?\\d*\\s*x\\b", RegexOption.IGNORE_CASE), "")
        // 合并多余空格
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    /** 是否有真实时间轴歌词：至少 3 行 [mm:ss] 时间戳 + 非空文本（排除纯音乐/仅元数据行）。 */
    private fun hasRealLyrics(lrc: String): Boolean {
        var count = 0
        for (line in lrc.lines()) {
            if (TIME_TAG.find(line) != null) {
                val text = line.substringAfterLast(']').trim()
                if (text.isNotEmpty() && ++count >= 3) return true
            }
        }
        return false
    }

    private fun searchTop(title: String, artist: String): List<Song>? {
        val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val url = "https://music.163.com/api/search/get/web?csrf_token=&s=" +
            enc(q) + "&type=1&offset=0&total=true&limit=10"
        val body = httpGet(url) ?: return null
        val songs = runCatching {
            val obj = JSONObject(body)
            val arr = obj.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val artistsArr = s.optJSONArray("artists")
                val artists = (0 until (artistsArr?.length() ?: 0)).map { j ->
                    artistsArr!!.getJSONObject(j).optString("name")
                }
                Song(
                    id = s.optLong("id"),
                    name = s.optString("name"),
                    artists = artists,
                )
            }
        }.getOrDefault(emptyList())
        if (songs.isEmpty()) return null

        // 打分：标题精确 +100 / 标题包含 +10 / 歌手精确 +50 / 歌手模糊 +20
        val t = norm(title)
        val a = norm(artist)
        return songs.sortedByDescending { s ->
            var sc = 0
            val sn = norm(s.name)
            if (sn.isNotEmpty() && sn == t) sc += 100
            if (t.isNotEmpty() && sn.contains(t)) sc += 10
            if (a.isNotEmpty()) {
                val names = s.artists.map(::norm)
                when {
                    names.contains(a) -> sc += 50
                    names.any { n -> n.isNotEmpty() && (n.contains(a) || a.contains(n)) } -> sc += 20
                }
            }
            sc
        }.take(4)
    }

    private fun fetchLyric(song: Song): String? {
        val url = "https://music.163.com/api/song/lyric?id=${song.id}&lv=1&kv=1&tv=-1"
        val body = httpGet(url) ?: return null
        return runCatching {
            val obj = JSONObject(body)
            if (obj.optInt("code", 200) != 200) return@runCatching null
            val lrc = obj.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (lrc.isBlank()) null else lrc
        }.getOrNull()
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", headers["Referer"] ?: "https://music.163.com/")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> if (k != "Referer") conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return null
            }
            val stream = conn.inputStream
            val bytes = stream.readBytes()
            stream.close()
            conn.disconnect()
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "httpGet fail: $urlStr - ${e.message}")
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun norm(s: String): String = s.trim().lowercase()
        .replace(Regex("[\\s\\p{Punct}·]+"), "")

    private data class Song(
        val id: Long,
        val name: String,
        val artists: List<String>,
    )

    companion object {
        private const val TAG = "NUI.LyricFetcher"
        private val TIME_TAG = Regex("""\[\d{1,2}:\d{2}""")
    }
}
