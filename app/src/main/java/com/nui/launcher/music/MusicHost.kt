package com.nui.launcher.music

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.MediaMetadata

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.nui.launcher.NuiToast
import com.nui.launcher.R

/**
 * 右侧音乐区：通过 MediaSession 读取当前正在播放的歌曲（标题/艺术家/封面/歌词），
 * 并提供播放/暂停、上一首、下一首控制。
 *
 * 实现"协议方式"获取歌曲信息：MediaSession 是 Android 标准 API，
 * 所有注册了媒体会话的音乐 App（QQ音乐/网易云/酷狗/Spotify 等）都能读取。
 * 歌词从 METADATA_KEY_LYRIC（API 24+）读取，LRC 格式解析后按播放进度滚动高亮。
 * 无歌词时显示"暂无歌词"。
 *
 * 无会话或未播放时：显示"点击打开音乐"启动卡（点击拉起首选音乐 App）。
 * 长按音乐区：选择首选音乐 App（用于启动卡点击）。
 *
 * 注意：读取其它 App 的 MediaSession 需要"通知监听"权限（系统会弹授权页），
 * 未授权则降级为启动卡。
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MusicHost(
    private val context: Context,
    private val container: FrameLayout,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val notificationListener = ComponentName(context, MusicListenerService::class.java)
    private var hasPermission = false
    private val handler = Handler(Looper.getMainLooper())

    /** 当前正在渲染的控制器，用于注册/注销元数据回调 */
    private var currentController: MediaController? = null
    /** 当前歌词列表：(timeMs, lyricText) 按时间升序 */
    private var lyrics: List<Pair<Long, String>> = emptyList()
    /** 当前高亮行的 index */
    private var lyricHighlight: Int = -1
    /** 歌词定时刷新 runnable */
    private val lyricTick = object : Runnable {
        override fun run() { refreshLyric(); handler.postDelayed(this, 500L) }
    }

    /** 元数据/播放状态变化回调：切歌时刷新封面等信息 */
    private val metadataCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
        override fun onPlaybackStateChanged(s: PlaybackState?) { refresh() }
    }

    /** 弹出选择对话框前隐藏悬浮地图，关闭后恢复（由外部注入） */
    var onHideFloat: (() -> Unit)? = null
    var onShowFloat: (() -> Unit)? = null

    fun start() {
        renderEmpty()
        refresh()
    }

    /** 刷新：遍历所有会话，取第一个正在播放的；否则取最后一个会话；都没有则显示启动卡。 */
    fun refresh() {
        try {
            val controllers = sessionManager.getActiveSessions(notificationListener)
            hasPermission = true
            if (controllers.isEmpty()) { renderEmpty(); return }
            val playing = controllers.firstOrNull { isPlaying(it.playbackState) }
            val chosen = playing ?: controllers.last()
            renderPlaying(chosen)
        } catch (e: SecurityException) {
            hasPermission = false
            renderEmpty()
        }
    }

    /** 用户点击"去授权"时调用：跳到通知监听权限设置页 */
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }

    private fun isPlaying(state: PlaybackState?): Boolean =
        state != null && state.state == PlaybackState.STATE_PLAYING

    /** 渲染播放中：封面 + 标题 + 艺术家 + 控制按钮 + 歌词（竖排） */
    private fun renderPlaying(controller: MediaController) {
        // 注销旧控制器回调，注册新控制器回调（切歌时自动刷新封面）
        currentController?.unregisterCallback(metadataCallback)
        controller.registerCallback(metadataCallback)
        currentController = controller

        container.removeAllViews()
        val md = controller.metadata ?: run { renderEmpty(); return }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "未知歌曲"
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知艺术家"
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val rawLyric = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            md.getString("android.media.metadata.LYRIC") else null
        lyrics = parseLrc(rawLyric)
        lyricHighlight = -1

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        // 封面
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (art != null) setImageBitmap(art)
            else setImageResource(android.R.drawable.ic_media_play)
            setBackgroundColor(Color.parseColor("#1F2A38"))
        }
        col.addView(cover, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(10) })
        // 标题
        col.addView(TextView(context).apply {
            text = title; setTextColor(Color.parseColor("#ECEFF1")); textSize = 15f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        })
        // 艺术家
        col.addView(TextView(context).apply {
            text = artist; setTextColor(Color.parseColor("#9AA0A6")); textSize = 12f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        // 控制按钮行
        val ctrls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        ctrls.addView(ctrlBtn(android.R.drawable.ic_media_previous) {
            safe { controller.transportControls.skipToPrevious() }
        })
        ctrls.addView(ctrlBtn(
            if (isPlaying(controller.playbackState)) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
        ) {
            safe {
                if (isPlaying(controller.playbackState)) controller.transportControls.pause()
                else controller.transportControls.play()
            }
        })
        ctrls.addView(ctrlBtn(android.R.drawable.ic_media_next) {
            safe { controller.transportControls.skipToNext() }
        })
        col.addView(ctrls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        // 歌词区
        val lyricView = TextView(context).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 3
            text = if (lyrics.isNotEmpty()) "\u266A" else "暂无歌词"
        }
        col.addView(lyricView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        container.addView(col)
        col.setOnClickListener { launchPreferredApp() }

        container.setTag(R.id.tag_lyric_view, lyricView)
        handler.removeCallbacks(lyricTick)
        handler.postDelayed(lyricTick, 500L)
    }

    private fun ctrlBtn(icon: Int, onClick: () -> Unit): View =
        ImageButton(context).apply {
            setImageResource(icon)
            background = null
            setColorFilter(Color.parseColor("#ECEFF1"))
            setOnClickListener { onClick() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

    private fun safe(block: () -> Unit) {
        runCatching { block() }
            .onFailure { NuiToast.show(context, "该 App 不支持此操作", Toast.LENGTH_SHORT) }
    }

    /** 无会话/无权限：启动卡，点击拉起首选音乐 App */
    private fun renderEmpty() {
        // 注销元数据回调 + 停歌词刷新
        currentController?.unregisterCallback(metadataCallback)
        currentController = null
        lyrics = emptyList()
        lyricHighlight = -1
        handler.removeCallbacks(lyricTick)

        container.removeAllViews()
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }
        v.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.parseColor("#9AA0A6"))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(6) })
        v.addView(TextView(context).apply {
            text = if (hasPermission) "点击打开音乐" else "点击授权读取歌曲"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        v.setOnClickListener { if (hasPermission) launchPreferredApp() else requestPermission() }
        v.setOnLongClickListener { pickPreferredApp(); true }
        container.addView(v)
    }

    /** 启动首选音乐 App，延迟返回 NUI（与地图逻辑一致）。
     *  返回后 refresh() 由 MainActivity.onResume() 触发，自动更新播放状态。 */
    private fun launchPreferredApp() {
        val pkg = prefs.getString(KEY_APP, null)
        if (pkg != null) {
            val i = context.packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(i) }
                // 延迟返回 NUI
                handler.postDelayed({
                    val back = Intent().apply {
                        setClassName(context, "com.nui.launcher.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    runCatching { context.startActivity(back) }
                }, 3000L)
                return
            }
        }
        pickPreferredApp()
    }

    /** 清理回调，Activity 销毁时调用 */
    fun onDestroy() {
        handler.removeCallbacks(lyricTick)
        currentController?.unregisterCallback(metadataCallback)
        currentController = null
    }

    private fun pickPreferredApp() {
        val pm = context.packageManager
        val apps = mutableListOf<Pair<String, String>>()
        // 列出已安装的常用音乐 App
        for (pkg in MUSIC_PACKAGES) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            apps.add(pkg to label)
        }
        if (apps.isEmpty()) {
            NuiToast.show(context, "未检测到常用音乐 App，可从应用列表打开", Toast.LENGTH_LONG)
            return
        }
        val labels = apps.map { it.second }.toTypedArray()
        onHideFloat?.invoke()
        val d = AlertDialog.Builder(context)
            .setTitle("选择音乐 App")
            .setItems(labels) { _, which ->
                prefs.edit { putString(KEY_APP, apps[which].first) }
                NuiToast.show(context, "已选择 ${labels[which]}", Toast.LENGTH_SHORT)
            }.create()
        d.setOnDismissListener { onShowFloat?.invoke() }
        d.show()
    }

    /** 解析 LRC 格式歌词：[mm:ss.xx]歌词文本 → (timeMs, text) 列表。 */
    private fun parseLrc(raw: String?): List<Pair<Long, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = mutableListOf<Pair<Long, String>>()
        val tagRe = Regex("""\[(\d{1,2}):(\d{2})[.:](\d{1,3})\]""")
        for (line in raw.lines()) {
            val matches = tagRe.findAll(line).toList()
            if (matches.isEmpty()) continue
            val lastTag = matches.last()
            val text = line.substring(lastTag.range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val msDigits = m.groupValues[3]
                val ms = msDigits.toLong().let {
                    when (msDigits.length) { 1 -> it * 100; 2 -> it * 10; else -> it }
                }
                val timeMs = (min * 60 + sec) * 1000 + ms
                result.add(timeMs to text)
            }
        }
        return result.sortedBy { it.first }
    }

    /** 根据当前播放进度更新歌词高亮行。 */
    private fun refreshLyric() {
        if (lyrics.isEmpty()) return
        val controller = currentController ?: return
        val state = controller.playbackState ?: return
        if (state.state != PlaybackState.STATE_PLAYING) return
        val pos = state.position

        var idx = -1
        for (i in lyrics.indices) {
            if (lyrics[i].first <= pos) idx = i else break
        }
        if (idx == lyricHighlight || idx < 0) return
        lyricHighlight = idx

        val lyricView = container.getTag(R.id.tag_lyric_view) as? TextView ?: return
        val sb = StringBuilder()
        if (idx > 0) sb.appendLine(lyrics[idx - 1].second)
        sb.append(">> ${lyrics[idx].second}")
        if (idx + 1 < lyrics.size) sb.appendLine().append(lyrics[idx + 1].second)
        lyricView.text = sb.toString()
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "nui_music"
        private const val KEY_APP = "music_app"
        // 常见音乐 App 包名（用于启动卡选择列表，含车机版）
        val MUSIC_PACKAGES = setOf(
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.tencent.qqmusiccar",
            "com.kugou.android",
            "cn.kuwo.player",
            "com.spotify.music",
        )
    }
}
