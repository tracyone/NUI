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

    /** 请求一首歌的歌词。命中缓存直接回调；否则后台抓取。线程安全（回调在主线程）。 */
    fun requestLyric(title: String, artist: String) {
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
        Log.d(TAG, "fetch lyric: $title - $artist")
        executor.execute {
            val result = runCatching { fetchLrc(title, artist) }.getOrNull()
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

    private fun fetchLrc(title: String, artist: String): String? {
        val candidates = searchTop(title, artist) ?: return null
        // 逐个候选取词，跳过纯音乐/无词版本，取第一个有真实时间轴歌词的
        for (song in candidates) {
            val lrc = fetchLyric(song)
            if (lrc != null && hasRealLyrics(lrc)) {
                Log.d(TAG, "pick song id=${song.id} name=${song.name} artist=${song.artists.joinToString("/")}")
                return lrc
            }
            Log.d(TAG, "skip song id=${song.id} name=${song.name} (no real lyric)")
        }
        return null
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

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.163.com/")
            conn.setRequestProperty("Accept", "application/json")
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
