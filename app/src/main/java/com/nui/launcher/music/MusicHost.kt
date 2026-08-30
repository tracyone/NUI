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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.nui.launcher.UiTheme
import android.graphics.Bitmap
import com.nui.launcher.NuiToast
import com.nui.launcher.R

/**
 * 右侧音乐区：通过 MediaSession 读取当前正在播放的歌曲（标题/艺术家/封面/歌词），
 * 并提供播放/暂停、上一首、下一首控制。
 *
 * 实现"协议方式"获取歌曲信息：MediaSession 是 Android 标准 API，
 * 所有注册了媒体会话的音乐 App（QQ音乐/网易云/酷狗/Spotify 等）都能读取。
 * 歌词来源（按优先级）：
 *   1. MediaSession 元数据 METADATA_KEY_LYRIC（API 24+，部分 App 提供）；
 *   2. 通知 bigText 兜底（MusicListenerService 解析）；
 *   3. 网络抓词（LyricFetcher）：酷我车机版不暴露歌词接口，按"歌名+歌手"
 *      从网易云公开接口抓 LRC，通用兜底。LRC 解析后按播放进度滚动高亮。
 * 无歌词时显示"暂无歌词"。
 *
 * 无会话或未播放时：显示"点击打开音乐"启动卡（点击拉起首选音乐 App）。
 * 右上角"切换"按钮 / 长按音乐区：选择首选音乐 App（切换 QQ/酷我等）。
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
    private val lbm = LocalBroadcastManager.getInstance(context)
    private var hasPermission = false
    private val handler = Handler(Looper.getMainLooper())
    /** 网络歌词数据源：按歌名+歌手从网易云抓 LRC（酷我车机版不暴露歌词接口，原 AIDL 方案已废弃） */
    private val lyricFetcher = LyricFetcher(context)
    /** 当前已发起网络抓词的歌 key（"title||artist"），用于防止同一首重复请求 */
    private var lyricFetchKey: String? = null
    init {
        lyricFetcher.onLyricReady = { lrc, _ ->
            // 对收到的 LRC 做基本一致性校验：不重复采用相同内容
            if (lastLyricRaw != lrc) {
                lastLyricRaw = lrc
                val parsed = parseLrc(lrc)
                if (parsed.isNotEmpty()) {
                    lyrics = parsed
                    lyricHighlight = -1
                    lyricFetchKey = null
                    val ctrl = currentController
                    if (ctrl != null) renderPlaying(ctrl)
                } else {
                    // 空解析就隐藏，不显示错的
                    lyrics = emptyList(); lyricHighlight = -1; hideLyricFloat()
                }
            }
        }
    }
    /** 通知兜底：从 MusicListenerService 本地广播接收通知里的 title/artist/cover/lyric（酷我车机版常用） */
    private val notifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            val pkg = i.getStringExtra(MusicListenerService.EXTRA_PKG) ?: return
            // 只兜底当前播放的音乐（按包名匹配，避免覆盖正在播放的其他 App）
            if (currentController?.packageName != null && currentController!!.packageName != pkg) return
            val title = i.getStringExtra(MusicListenerService.EXTRA_TITLE)
            val artist = i.getStringExtra(MusicListenerService.EXTRA_ARTIST)
            val cover = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                i.getParcelableExtra(MusicListenerService.EXTRA_COVER, Bitmap::class.java)
            else
                @Suppress("DEPRECATION") i.getParcelableExtra(MusicListenerService.EXTRA_COVER) as? Bitmap
            val lyric = i.getStringExtra(MusicListenerService.EXTRA_LYRIC)
            // 仅在缺值时才填（不覆盖 MediaSession 已有）
            if (!title.isNullOrBlank() && lastTitle.isBlank()) lastTitle = title
            if (!artist.isNullOrBlank() && lastArtist.isBlank()) lastArtist = artist
            if (cover != null && lastCover == null) lastCover = cover
            if (!lyric.isNullOrBlank() && lastLyricRaw == null) lastLyricRaw = lyric
            // 如果歌词从无变有，重新 parseLrc 触发悬浮窗显示
            if (!lyric.isNullOrBlank() && lyrics.isEmpty()) {
                val parsed = parseLrc(lastLyricRaw)
                if (parsed.isNotEmpty()) { lyrics = parsed; lyricHighlight = -1 }
            }
            // 封面或歌词更新后，立即重绘一次（不等待 refresh() 轮询）
            if (currentController != null && (cover != null || !lyric.isNullOrBlank())) {
                renderPlaying(currentController!!)
            }
        }
    }

    /** 当前正在渲染的控制器，用于注册/注销元数据回调 */
    private var currentController: MediaController? = null
    /** 上次渲染时的元数据快照（很多音乐App播放时短暂清空METADATA_KEY_ALBUM_ART，需要缓存避免封面闪没） */
    private var lastTitle: String = ""
    private var lastArtist: String = ""
    private var lastCover: android.graphics.Bitmap? = null
    private var lastLyricRaw: String? = null
    /** 当前歌词列表：(timeMs, lyricText) 按时间升序 */
    private var lyrics: List<Pair<Long, String>> = emptyList()
    /** 当前高亮行的 index */
    private var lyricHighlight: Int = -1
    /** 歌词定时刷新 runnable：持久循环，避免被高频 renderPlaying 反复重置 */
    private var lyricTickRunning = false
    private val lyricTick = object : Runnable {
        override fun run() {
            refreshLyric()
            if (lyricTickRunning) handler.postDelayed(this, 100L)
        }
    }

    /** 元数据/播放状态变化回调：切歌时刷新封面等信息 */
    private var lastRenderState: Int = PlaybackState.STATE_NONE
    private val metadataCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            // 位置/进度每几百毫秒就变化一次：**不重建面板**，避免反复清高亮、推延 lyricTick。
            // 仅当播放/暂停状态真正切换时才重建（保证播放/暂停按钮图标同步）。
            val st = s?.state ?: PlaybackState.STATE_NONE
            if (st != lastRenderState) {
                lastRenderState = st
                refresh()
            }
        }
    }

    /** 弹出选择对话框前隐藏悬浮地图，关闭后恢复（由外部注入） */
    var onHideFloat: (() -> Unit)? = null
    var onShowFloat: (() -> Unit)? = null

    fun start() {
        renderEmpty()
        val f = IntentFilter(MusicListenerService.ACTION_NOTIFY)
        lbm.registerReceiver(notifyReceiver, f)
        refresh()
    }

    /**
     * 刷新：优先展示"首选音乐 App"的会话（若在播放），其次任意正在播放的，再次首选 App 的会话；
     * 都没有则显示启动卡。这样把首选 App 从 QQ 切到酷我后，面板会跟随展示酷我的播放状态。
     */
    fun refresh() {
        try {
            val controllers = sessionManager.getActiveSessions(notificationListener)
            hasPermission = true
            if (controllers.isEmpty()) { renderEmpty(); return }
            val preferred = prefs.getString(KEY_APP, null)
            val playing = controllers.filter { isPlaying(it.playbackState) }
            val chosen = when {
                playing.any { it.packageName == preferred } -> playing.first { it.packageName == preferred }
                playing.isNotEmpty() -> playing.first()
                controllers.any { it.packageName == preferred } -> controllers.first { it.packageName == preferred }
                else -> controllers.last()
            }
            renderPlaying(chosen)
        } catch (e: SecurityException) {
            hasPermission = false
            renderEmpty()
        }
    }

    /** 用户点击"去授权"时调用：跳到通知监听权限设置页 */
    /** 方向盘按键用：下一首/上一首 */
    fun next() { currentController?.let { safe { it.transportControls.skipToNext() } } }
    fun prev() { currentController?.let { safe { it.transportControls.skipToPrevious() } } }

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
        lastRenderState = controller.playbackState?.state ?: PlaybackState.STATE_NONE

        container.removeAllViews()
        val md = controller.metadata
        val newTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val newArtist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val newArt = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val newLyricRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            md?.getString("android.media.metadata.LYRIC") else null
        // 切歌：title 变了就更新缓存，且主动丢掉旧歌词（避免酷我AIDL滞后时张冠李戴）
        val songSwitched = newTitle != null && newTitle.isNotBlank() && newTitle != lastTitle
        if (songSwitched) {
            lastTitle = newTitle
            newArtist?.let { lastArtist = it }
            lastCover = newArt   // 切歌时重置封面：新歌没提供就设 null，让通知兜底获取
            lastLyricRaw = newLyricRaw   // null 也接受 —— 新歌词没拿到时先清空，杜绝跨歌复用
        } else {
            // 同首歌：只更新有值的字段（不覆盖已有封面）
            if (newArtist != null) lastArtist = newArtist
            if (newArt != null) lastCover = newArt
            if (newLyricRaw != null) lastLyricRaw = newLyricRaw
        }
        val title = if (lastTitle.isNotBlank()) lastTitle else "未知歌曲"
        val artist = if (lastArtist.isNotBlank()) lastArtist else "未知艺术家"
        val art = lastCover
        // 歌词内容变化（切歌/重新抓词）才重置高亮；同歌词的重复渲染保留当前高亮，避免闪烁回 ♪
        val recomputed = parseLrc(lastLyricRaw)
        if (recomputed != lyrics) lyricHighlight = -1
        lyrics = recomputed
        // 歌词为空（MediaSession 元数据不带歌词）：按歌名+歌手走网络抓词（通用兜底，QQ/酷我等均适用）
        maybeFetchLyric()

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        val p = UiTheme.palette(context)
        // 封面
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (art != null) setImageBitmap(art)
            else setImageResource(android.R.drawable.ic_media_play)
            setBackgroundColor(p.panelBg)
        }
        col.addView(cover, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(10) })
        // 标题
        col.addView(TextView(context).apply {
            text = title; setTextColor(p.textPrimary); textSize = 15f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        })
        // 艺术家
        col.addView(TextView(context).apply {
            text = artist; setTextColor(p.textSecondary); textSize = 12f
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
        // 歌词区：2行（当前+下一句），紧凑布局不遮挡时钟
        val lyricScroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val lyricContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val lyricLines = Array(2) {
            TextView(context).apply {
                setTextColor(p.textSecondary)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, dp(2))
            }
        }
        lyricLines.forEach { lyricContainer.addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        lyricScroll.addView(lyricContainer)
        col.addView(lyricScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(6) })
        val lyricView = lyricLines[0]  // 第0行 = 当前句（用于 tag 兼容）
        lyricView.text = if (lyrics.isNotEmpty()) "\u266A" else "暂无歌词"
        container.setTag(R.id.tag_lyric_scroll, lyricScroll)
        container.setTag(R.id.tag_lyric_lines, lyricLines)

        container.addView(col)
        // 单击：启动当前绑定的音乐 App（先 hideFloat 关外部地图浮窗，回 NUI 后 showFloat 恢复）
        col.setOnClickListener { launchPreferredApp() }
        // 长按：弹"选择音乐 App"对话框，把 QQ 音乐换成酷我等；选完直接启动新 App
        col.setOnLongClickListener {
            pickPreferredApp(onPickedLaunch = true)
            true
        }

        container.setTag(R.id.tag_lyric_view, lyricView)
        // 持久歌词循环：只在未启动时启动一次，不再被高频渲染反复重置
        if (!lyricTickRunning) {
            lyricTickRunning = true
            handler.post(lyricTick)
        }
        // 重建后立即同步一次当前歌词行（不等下一次 tick，避免先闪 ♪/暂无歌词）
        refreshLyric()
    }

    private fun ctrlBtn(icon: Int, onClick: () -> Unit): View =
        ImageButton(context).apply {
            setImageResource(icon)
            background = null
            setColorFilter(UiTheme.palette(context).dockIconTint)
            setOnClickListener { onClick() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

    private fun safe(block: () -> Unit) {
        runCatching { block() }
            .onFailure { NuiToast.show(context, "该 App 不支持此操作", Toast.LENGTH_SHORT) }
    }

    /** 无会话/无权限：启动卡，点击拉起首选音乐 App */
    private fun renderEmpty() {
        // 注销元数据回调 + 停歌词刷新 + 清缓存（真正没会话时复位）
        currentController?.unregisterCallback(metadataCallback)
        currentController = null
        lastTitle = ""; lastArtist = ""; lastCover = null; lastLyricRaw = null
        lyrics = emptyList()
        lyricHighlight = -1
        lyricTickRunning = false
        handler.removeCallbacks(lyricTick)
        hideLyricFloat()

        container.removeAllViews()
        val p = UiTheme.palette(context)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }
        v.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(p.textSecondary)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(6) })
        v.addView(TextView(context).apply {
            text = if (hasPermission) "点击打开音乐" else "点击授权读取歌曲"
            setTextColor(p.textSecondary)
            textSize = 13f
            gravity = Gravity.CENTER
        })
        v.setOnClickListener { if (hasPermission) launchPreferredApp() else requestPermission() }
        v.setOnLongClickListener { pickPreferredApp(); true }
        container.addView(v)
    }

    /** 歌词为空时按当前歌曲（歌名+歌手）触发网络抓词；LyricFetcher 内部按 key 去重+缓存，不会重复请求。 */
    private fun maybeFetchLyric() {
        if (lyrics.isNotEmpty()) return
        val t = lastTitle
        if (t.isBlank()) return
        val key = "$t||$lastArtist"
        if (key == lyricFetchKey) return
        lyricFetchKey = key
        android.util.Log.d("NUI.MusicHost", "maybeFetchLyric: $t - $lastArtist")
        // 传入歌曲时长，供 QQ 音乐纯文本歌词估算时间戳
        val duration = currentController?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        lyricFetcher.requestLyric(t, lastArtist, duration)
    }

    /** 启动首选音乐 App，延迟返回 NUI（与地图逻辑一致）。
     *  返回后 refresh() 由 MainActivity.onResume() 触发，自动更新播放状态。
     *
     *  浮窗顺序管理（防止酷我的 mini player 跟地图悬浮区视觉叠在一起）：
     *    启动音乐前先 onHideFloat（关掉外部高德浮窗）
     *    返回 NUI 后，再稍等约 900ms 让酷我自身的 overlay 收掉，然后 onShowFloat 恢复地图浮窗 */
    private fun launchPreferredApp() {
        val pkg = prefs.getString(KEY_APP, null)
        if (pkg != null) {
            val i = context.packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                onHideFloat?.invoke()
                runCatching { context.startActivity(i) }
                // 延迟返回 NUI
                handler.postDelayed({
                    val back = Intent().apply {
                        setClassName(context, "com.nui.launcher.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    runCatching { context.startActivity(back) }
                    // 回 NUI 后再恢复地图浮窗（错开酷我自有关闭窗口的动画窗口）
                    handler.postDelayed({ onShowFloat?.invoke() }, 900L)
                }, 3000L)
                return
            }
        }
        pickPreferredApp()
    }

    /** 清理回调，Activity 销毁时调用 */
    fun onDestroy() {
        lyricTickRunning = false
        handler.removeCallbacks(lyricTick)
        lyricFetcher.stop()
        currentController?.unregisterCallback(metadataCallback)
        currentController = null
        runCatching { lbm.unregisterReceiver(notifyReceiver) }
        hideLyricFloat()
    }

    // ===== 卡拉OK悬浮歌词：跨 page 常驻、可拖动/拉伸、扫光高亮 =====
    private val wm =
        context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    private var lyricFloatView: FrameLayout? = null
    private var karaokeView: KaraokeTextView? = null
    /** 创建悬浮歌词时记录的背景不透明度（0-100），设置变化时用于触发重建 */
    private var lastLyricBgAlpha = -1
    private var lyricFloatNext: TextView? = null
    private var desktopVisible = false
    private var playingNow = false
    private var floatX = -1
    private var floatY = -1
    private var floatW = -1
    private var floatH = -1
    /** 提供悬浮地图几何（边界格式 x1,y1,x2,y2），用于默认位置 */
    var floatBoundsProvider: (() -> IntArray?)? = null

    /** 重新 addView 把歌词窗提到最上层（盖住高德浮窗） */
    fun bringLyricFloatToFront() {
        val view = lyricFloatView ?: return
        val lp = view.layoutParams as? android.view.WindowManager.LayoutParams ?: return
        runCatching {
            wm.removeViewImmediate(view)
            wm.addView(view, lp)
        }
    }

    fun setFloatAreaVisible(v: Boolean) {
        if (desktopVisible == v) return
        desktopVisible = v
        updateLyricFloat()
    }

    private fun defaultFloatGeo() {
        val bounds = floatBoundsProvider?.invoke()
        if (bounds != null) {
            floatW = (bounds[2] - bounds[0]) * 4 / 5
            floatH = dp(110)
            floatX = bounds[0] + (bounds[2] - bounds[0]) / 10
            floatY = bounds[3] - dp(150)
        } else {
            floatX = dp(200); floatY = dp(700)
            floatW = dp(700); floatH = dp(110)
        }
    }

    private fun loadFloatGeo() {
        floatX = prefs.getInt("lyric_x", -1)
        floatY = prefs.getInt("lyric_y", -1)
        floatW = prefs.getInt("lyric_w", -1)
        floatH = prefs.getInt("lyric_h", -1)
        if (floatX < 0 || floatW <= 0 || floatH <= 0) defaultFloatGeo()
    }

    private fun saveFloatGeo() {
        prefs.edit {
            putInt("lyric_x", floatX); putInt("lyric_y", floatY)
            putInt("lyric_w", floatW); putInt("lyric_h", floatH)
        }
    }

    private fun createLyricFloat() {
        val percent = MusicHost.lyricBgAlpha(context)
        lastLyricBgAlpha = percent
        val bgAlpha = percent * 255 / 100   // 0-100% → 0-255，默认 80% ≈ 204
        val root = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((bgAlpha shl 24).toInt())   // 黑色 + 背景不透明度
                cornerRadius = dp(14).toFloat()
            }
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(30), dp(8))
            clipChildren = true
            clipToPadding = true
        }
        karaokeView = KaraokeTextView(context).apply {
            textSize = 26f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lyricFloatNext = TextView(context).apply {
            setTextColor(0x99FFFFFF.toInt()); textSize = 14f
            gravity = Gravity.CENTER; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(karaokeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(lyricFloatNext, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.clipChildren = true
        root.clipToPadding = true
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 右下角拉伸手柄
        root.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF00E5FF.toInt()); cornerRadius = dp(2).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(18), dp(4)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(8); bottomMargin = dp(6)
        })
        root.setOnTouchListener(floatTouch)
        lyricFloatView = root
    }

    private var touchMode = 0 // 0 无 1 移动 2 拉伸
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var geoStartX = 0
    private var geoStartY = 0
    private var geoStartW = 0
    private var geoStartH = 0
    private val floatTouch = View.OnTouchListener { _, e ->
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val v = lyricFloatView ?: return@OnTouchListener false
                val inHandle = e.x > v.width - dp(48) && e.y > v.height - dp(32)
                touchMode = if (inHandle) 2 else 1
                touchStartX = e.rawX; touchStartY = e.rawY
                geoStartX = floatX; geoStartY = floatY
                geoStartW = floatW; geoStartH = floatH
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - touchStartX).toInt()
                val dy = (e.rawY - touchStartY).toInt()
                if (touchMode == 1) { floatX = geoStartX + dx; floatY = geoStartY + dy }
                else if (touchMode == 2) {
                    floatW = (geoStartW + dx).coerceAtLeast(dp(240))
                    floatH = (geoStartH + dy).coerceIn(dp(70), dp(300))
                }
                applyFloatLayout()
                true
            }
            else -> { touchMode = 0; saveFloatGeo(); true }
        }
    }

    private fun applyFloatLayout() {
        val view = lyricFloatView ?: return
        val lp = view.layoutParams as? android.view.WindowManager.LayoutParams ?: return
        lp.x = floatX; lp.y = floatY; lp.width = floatW; lp.height = floatH
        // 字号随窗口高度缩放
        karaokeView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, floatH * 0.30f)
        lyricFloatNext?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, floatH * 0.15f)
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun hideLyricFloat() {
        lyricFloatView?.let { runCatching { wm.removeView(it) } }
        lyricFloatView = null
        karaokeView = null
        lyricFloatNext = null
    }

    private fun updateLyricFloat() {
        if (!desktopVisible || lyrics.isEmpty() || !playingNow) { hideLyricFloat(); return }
        // 设置页改了背景不透明度：移除旧悬浮窗，下一次显示时按新值重建
        if (lastLyricBgAlpha != MusicHost.lyricBgAlpha(context)) {
            hideLyricFloat()
        }
        if (lyricFloatView == null) {
            loadFloatGeo()
            createLyricFloat()
            val lp = android.view.WindowManager.LayoutParams().apply {
                type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE
                format = android.graphics.PixelFormat.TRANSLUCENT
                flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                gravity = Gravity.TOP or Gravity.START
            }
            runCatching { wm.addView(lyricFloatView!!, lp) }
                .onFailure { hideLyricFloat(); return }
        }
        applyFloatLayout()
        val idx = lyricHighlight.coerceAtLeast(0)
        karaokeView?.text = lyrics[idx].second
        lyricFloatNext?.text = if (idx + 1 < lyrics.size) lyrics[idx + 1].second else ""
        updateKaraokeProgress()
    }

    /** 卡拉OK扫光：按播放进度更新当前行已唱比例 */
    private fun updateKaraokeProgress() {
        val view = karaokeView ?: return
        val controller = currentController ?: return
        val pos = controller.playbackState?.position ?: return
        val idx = lyricHighlight.coerceAtLeast(0)
        val start = lyrics[idx].first
        val end = if (idx + 1 < lyrics.size) lyrics[idx + 1].first else start + 5000L
        view.progress = if (end > start) (pos - start).toFloat() / (end - start) else 1f
    }

    /**
     * 弹出"选择音乐 App"对话框。
     * @param onPickedLaunch 选中后是否立即启动新选的 App（例如：长按音乐面板从 QQ 切到酷我时用）。
     */
    private fun pickPreferredApp(onPickedLaunch: Boolean = false) {
        val pm = context.packageManager
        val apps = mutableListOf<Triple<String, String, android.graphics.drawable.Drawable>>()
        // 列出已安装的常用音乐 App
        for (pkg in MUSIC_PACKAGES) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrDefault(pm.defaultActivityIcon)
            apps.add(Triple(pkg, label, icon))
        }
        if (apps.isEmpty()) {
            NuiToast.show(context, "未检测到常用音乐 App，可从应用列表打开", Toast.LENGTH_LONG)
            return
        }
        onHideFloat?.invoke()
        val adapter = com.nui.launcher.IconTextAdapter(context, apps.map { it.third to it.second })
        val d = AlertDialog.Builder(context)
            .setTitle("选择音乐 App")
            .setAdapter(adapter) { _, which ->
                val chosen = apps[which]
                prefs.edit { putString(KEY_APP, chosen.first) }
                NuiToast.show(context, "已选择 ${chosen.second}", Toast.LENGTH_SHORT)
                if (onPickedLaunch) {
                    // 选完立即启动新 App，复用 launchPreferredApp 的浮窗 hide/show 逻辑
                    handler.postDelayed({ launchPreferredApp() }, 180L)
                }
            }.create()
        d.setOnDismissListener { onShowFloat?.invoke() }
        d.show()
    }

    /** LRC 元数据行关键字（作词/作曲/编曲等），解析时过滤掉，避免当前句高亮到这些行。 */
    private val LRC_META = Regex(
        """^(作词|作曲|编曲|制作人|制作|监制|混音|母带|录音|和声|合声|吉他|贝斯|键盘|弦乐|钢琴|鼓|发行|出品|版权|原唱|翻唱|纯音乐|OP|SP|演唱|统筹|企划|营销|宣传|推广|出版|授权|策划|填词|谱曲|原曲|原词|念白|口白|说唱|配唱|人声|封面|视觉|设计|插画|摄影|导演|编剧|剪辑|特效|调色|字幕|翻译|校对|审核|鸣谢|感谢|联合出品|联合发行|独家发行|独家出品|音乐统筹|音乐发行|音乐出品|音乐制作|音乐监制|音乐企划|音乐营销|音乐宣传|音乐推广|出品人|发行人|监制人|厂牌|唱片公司|经纪公司|经纪|代理|总代理|独家代理|发行代理|版权代理|词曲版权|录音版权|词曲|词曲作者|词曲创作|创作|创作者|创作人|原创|专辑|单曲|EP|流派|风格|语言|地区|国家|发行时间|发行日期|ISRC|UPC|EAN|条形码|唱片编号|版权所有|翻录必究|版权声明|法律声明)\s*[:：]?.*$"""
    )

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
            // 过滤 LRC 元数据行（作词/作曲/编曲/制作人等），避免前奏阶段高亮到这些行
            if (text.isEmpty() || LRC_META.matches(text)) continue
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
        if (lyrics.isEmpty()) { hideLyricFloat(); return }
        val controller = currentController
        val pos = controller?.playbackState?.position ?: -1L
        playingNow = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (pos < 0L) { updateLyricFloat(); return }

        var idx = -1
        for (i in lyrics.indices) {
            if (lyrics[i].first <= pos) idx = i else break
        }
        // 播放位置早于首句歌词（前奏/无词段）：默认显示第一句，避免一直停留在 ♪
        if (idx < 0 && lyrics.isNotEmpty()) idx = 0
        if (idx >= 0 && idx != lyricHighlight) lyricHighlight = idx
        if (lyricHighlight < 0) { updateLyricFloat(); return }
        val idx2 = lyricHighlight

        val lyricLines = container.getTag(R.id.tag_lyric_lines) as? Array<TextView>
        val lyricScroll = container.getTag(R.id.tag_lyric_scroll) as? android.widget.ScrollView
        if (lyricLines != null) {
            // 2行：第0行当前句（高亮白加粗12号），第1行下一句（次白普通10.5号）
            val hi = Color.parseColor("#FFFFFF")
            val sub = Color.parseColor("#90A4AE")
            // line 0 = current
            val tv0 = lyricLines[0]
            tv0.text = lyrics[idx2].second
            tv0.setTextColor(hi)
            tv0.setTypeface(null, android.graphics.Typeface.BOLD)
            tv0.textSize = 12f
            tv0.alpha = 1f
            // line 1 = next
            val tv1 = lyricLines[1]
            val nextIdx = idx2 + 1
            if (nextIdx in lyrics.indices) {
                tv1.text = lyrics[nextIdx].second
                tv1.setTextColor(sub)
                tv1.setTypeface(null, android.graphics.Typeface.NORMAL)
                tv1.textSize = 10.5f
                tv1.alpha = 0.85f
            } else {
                tv1.text = ""
            }
        } else {
            val lyricView = container.getTag(R.id.tag_lyric_view) as? TextView
            if (lyricView != null) {
                val sb = android.text.SpannableStringBuilder()
                val dim = Color.parseColor("#78909C")
                val hi = Color.parseColor("#FFFFFF")
                fun appendLine(text: String, color: Int, bold: Boolean) {
                    val start = sb.length
                    sb.append(text)
                    sb.setSpan(android.text.style.ForegroundColorSpan(color),
                        start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (bold) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (idx2 > 0) appendLine(lyrics[idx2 - 1].second + "\n", dim, false)
                appendLine(lyrics[idx2].second, hi, true)
                if (idx2 + 1 < lyrics.size) appendLine("\n" + lyrics[idx2 + 1].second, dim, false)
                lyricView.text = sb
            }
        }
        updateLyricFloat()
    }
    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "nui_music"
        private const val KEY_APP = "music_app"
        private const val KEY_LYRIC_BG_ALPHA = "lyric_bg_alpha"
        const val DEFAULT_LYRIC_BG_ALPHA = 20

        /** 读取悬浮歌词背景不透明度（0-100，默认 80） */
        fun lyricBgAlpha(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LYRIC_BG_ALPHA, DEFAULT_LYRIC_BG_ALPHA).coerceIn(0, 100)

        /** 保存悬浮歌词背景不透明度（0-100） */
        fun setLyricBgAlpha(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LYRIC_BG_ALPHA, percent.coerceIn(0, 100)).apply()
        }

        // 常见音乐 App 包名（用于启动卡选择列表，含车机版）
        val MUSIC_PACKAGES = setOf(
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.tencent.qqmusiccar",
            "com.kugou.android",
            "cn.kuwo.player",
            "cn.kuwo.kwmusiccar",
            "com.android.mediacenter",
            "com.spotify.music",
            "com.luna.music",
            "com.luna.music.car",
        )
    }
}

/** 卡拉OK歌词行：未唱灰色，已唱金色渐变扫光 */
class KaraokeTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val basePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val sungPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: android.graphics.Canvas) {
        var text = text?.toString().orEmpty()
        if (text.isEmpty()) return
        basePaint.textSize = textSize; basePaint.typeface = typeface
        sungPaint.textSize = textSize; sungPaint.typeface = typeface
        // 超宽自动缩字号，保证整行显示
        val maxW = width - paddingLeft - paddingRight
        var tw = basePaint.measureText(text)
        if (tw > maxW && tw > 0) {
            val shrunk = textSize * maxW / tw
            basePaint.textSize = shrunk; sungPaint.textSize = shrunk
            tw = basePaint.measureText(text)
        }
        val x = (width - tw) / 2f
        val fm = basePaint.fontMetrics
        val y = (height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        basePaint.color = 0xFF8FA3AD.toInt()
        canvas.drawText(text, x, y, basePaint)
        if (progress > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, x + tw * progress, height.toFloat())
            sungPaint.shader = android.graphics.LinearGradient(
                x, 0f, x + tw, 0f,
                intArrayOf(0xFFFFC400.toInt(), 0xFFFFF59D.toInt(), 0xFFFFAB00.toInt()),
                null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawText(text, x, y, sungPaint)
            canvas.restore()
        }
    }
}
