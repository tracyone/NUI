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
import android.util.Log
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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.min
import com.nui.launcher.NuiToast
import com.nui.launcher.R

/**
 * 右侧音乐区：通过 MediaSession 读取当前正在播放的歌曲（标题/艺术家/封面/歌词），
 * 并提供播放/暂停、上一首、下一首控制。
 *
 * 实现"协议方式"获取歌曲信息：MediaSession 是 Android 标准 API，
 * 所有注册了媒体会话的音乐 App（QQ音乐/网易云/酷狗/Spotify 等）都能读取。
 * 歌词来源（统一走网易云）：
 *   1. 网易云歌词（LyricFetcher 按"歌名+歌手"从 music.163.com 抓 LRC，所有绑定音乐统一使用）；
 *   2. 网易云结果到达前，暂显 MediaSession 元数据 LYRIC / 通知 bigText 歌词作兜底。
 * LRC 解析后按播放进度滚动高亮。
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
    /** 音乐外观：经典（小矩形卡片，歌名+歌手+播放/上下首按钮）/ 黑胶唱片（唱碟+唱臂+旋转） */
    enum class MusicStyle { CLASSIC, VINYL }

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
    /** 唱碟旋转动画（播放时旋转，暂停时停止） */
    private var discRotationAnim: ObjectAnimator? = null
    init {
        lyricFetcher.onLyricReady = { lrc, _ ->
            // 对收到的 LRC 做基本一致性校验：不重复采用相同内容
            if (lastLyricRaw != lrc) {
                lastLyricRaw = lrc
                val parsed = parseLrc(lrc)
                if (parsed.isNotEmpty()) {
                    lyrics = parsed
                    lyricHighlight = -1
                    // 不重置 lyricFetchKey：同一首歌保持"已处理"，避免缓存命中→重绘→再请求的循环；
                    // 切歌后 key 变化自然触发新抓取
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
            // 硬绑定：设置了绑定 App 时，其它音乐 App 的通知一律不接收，避免抢占面板/歌词
            val boundApp = prefs.getString(KEY_APP, null)
            if (boundApp != null && pkg != boundApp) {
                Log.d(TAG, "notify[硬绑定] 忽略非绑定 App 通知: pkg=$pkg, 绑定=$boundApp")
                return
            }
            // 未绑定时只兜底当前播放的音乐（按包名匹配，避免覆盖正在播放的其他 App）
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
            // 封面只在通知标题与当前歌曲匹配时才设置（避免切歌后收到上一首的通知导致封面张冠李戴）
            val titleMatch = title.isNullOrBlank() || lastTitle.isBlank() || title == lastTitle
            if (cover != null && lastCover == null && titleMatch) lastCover = cover
            if (!lyric.isNullOrBlank() && lastLyricRaw == null) lastLyricRaw = lyric
            // 如果歌词从无变有，重新 parseLrc 触发悬浮窗显示
            if (!lyric.isNullOrBlank() && lyrics.isEmpty()) {
                val parsed = parseLrc(lastLyricRaw)
                if (parsed.isNotEmpty()) { lyrics = parsed; lyricHighlight = -1 }
            }
            // 封面或歌词更新后，立即重绘一次（不等待 refresh() 轮询）
            if (currentController != null && (cover != null || !lyric.isNullOrBlank())) {
                renderPlaying(currentController!!)
            } else if (currentController == null && (!title.isNullOrBlank() || !artist.isNullOrBlank())) {
                // 还没有媒体会话但收到了音乐通知：主动 refresh 拉取会话（解决播放时悬浮歌词不出现的问题）
                refresh()
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
    /**
     * 封面缓冲期：切歌瞬间 MediaSession metadata 常先带 title、后带封面（甚至滞后几百 ms），
     * 若立即按"无封面"切紧凑布局，封面到达后又切回大布局，上下首之间会来回跳。
     * 切歌后 [COVER_GRACE_MS] 内即使没有封面也维持大布局（黑胶无封面图），等封面或缓冲结束。
     */
    private var lastCoverSetAt = 0L
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
    /** 用户主动暂停标记：NUI 按钮 pause 置 true（自动恢复不干预）；播放/切歌后重置 */
    private var userPaused = false
    /** 最近一次自动恢复播放时间：防抖，10s 内只自动恢复一次（避免状态抖动循环） */
    private var lastAutoResumeAt = 0L
    private val metadataCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            // 位置/进度每几百毫秒就变化一次：**不重建面板**，避免反复清高亮、推延 lyricTick。
            // 仅当播放/暂停状态真正切换时才重建（保证播放/暂停按钮图标同步）。
            val st = s?.state ?: PlaybackState.STATE_NONE
            if (st == PlaybackState.STATE_PLAYING) userPaused = false
            if (st != lastRenderState) {
                lastRenderState = st
                refresh()
            }
            // 酷我车机版等退后台会自动暂停播放：非用户主动暂停且绑定 App 不在前台 → 延迟自动恢复
            if (st == PlaybackState.STATE_PAUSED) maybeAutoResume()
        }
    }

    /** 弹出选择对话框前隐藏悬浮地图，关闭后恢复（由外部注入） */
    var onHideFloat: (() -> Unit)? = null
    var onShowFloat: (() -> Unit)? = null

    fun start() {
        renderEmpty()
        val f = IntentFilter(MusicListenerService.ACTION_NOTIFY)
        lbm.registerReceiver(notifyReceiver, f)
        // 监听媒体会话变化（从无到有/切换 App），及时触发 refresh 渲染播放面板
        try {
            val sessionListener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, notificationListener)
        } catch (e: Exception) {
            android.util.Log.w("NUI.MusicHost", "注册会话变化监听器失败: ${e.message}")
        }
        refresh()
    }

    /**
     * 刷新选择要展示的媒体会话。
     *
     * 硬绑定策略：一旦用户在"选择音乐 App"里绑定了某个 App（[KEY_APP]），桌面音乐卡就**只认它**——
     * 无论它正在播放还是暂停都展示它的会话；它没有活跃会话（未运行）时显示启动卡，
     * **不会**因为其它音乐 App（如酷我）正在播放就抢占面板。要控制别的 App 需长按重新绑定。
     *
     * 仅当用户**从未绑定**时，才回退为"跟随当前播放源"：优先正在播放的会话，其次最后一个会话。
     */
    fun refresh() {
        try {
            val controllers = sessionManager.getActiveSessions(notificationListener)
            hasPermission = true
            val preferred = prefs.getString(KEY_APP, null)
            if (preferred != null) {
                // 已绑定：只展示绑定 App 的会话（播放/暂停都算）；它没运行就显示启动卡，不跟随其它 App
                val bound = controllers.firstOrNull { it.packageName == preferred }
                Log.d(TAG, "refresh[硬绑定] preferred=$preferred, 会话=${controllers.map { it.packageName }}, 选中=${bound?.packageName ?: "无会话→启动卡"}")
                if (bound != null) renderPlaying(bound) else renderEmpty()
                return
            }
            // 未绑定：跟随当前播放源——优先正在播放的会话，其次最后一个会话
            if (controllers.isEmpty()) { renderEmpty(); return }
            val playing = controllers.filter { isPlaying(it.playbackState) }
            val chosen = playing.firstOrNull() ?: controllers.last()
            Log.d(TAG, "refresh[未绑定] 会话=${controllers.map { it.packageName }}, 选中=${chosen.packageName}")
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

        // 布局重建淡入淡出：大布局/紧凑布局切换（封面有无变化）不生硬跳动
        android.transition.TransitionManager.beginDelayedTransition(
            container, android.transition.AutoTransition().setDuration(220)
        )
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
            lastCoverSetAt = android.os.SystemClock.elapsedRealtime()   // 记录切歌时刻，进入封面缓冲期
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

        val p = UiTheme.palette(context)
        // 有封面时作为音乐区背景铺满（直接设到 container 上，避免被卡片背景遮挡），无封面时清空
        if (art != null) {
            container.background = android.graphics.drawable.BitmapDrawable(context.resources, art).apply {
                gravity = android.view.Gravity.FILL
            }
        } else {
            container.background = null
        }
        // 外观设置（不再按封面有无自动切换，避免不稳定）：
        //   - 经典：固定小矩形卡片（歌名 + 歌手 + 播放/上一首/下一首按钮）
        //   - 黑胶唱片：固定唱碟大布局（黑胶 + 封面 + 唱臂），无封面时显示纯黑胶盘面
        if (musicStyle(context) == MusicStyle.CLASSIC) {
            renderCompactNoCover(title, artist, controller, playing = isPlaying(controller.playbackState), p)
            return
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            // 有封面时加半透明遮罩保证文字清晰，无封面时用默认背景色
            setBackgroundColor(0x99000000.toInt())
        }
        // 有封面背景时文字用白色，无封面时用主题色
        val titleColor = if (art != null) 0xFFFFFFFF.toInt() else p.textPrimary
        val subColor = if (art != null) 0xCCCCCCFF.toInt() else p.textSecondary
        // 标题
        col.addView(TextView(context).apply {
            text = title; setTextColor(titleColor); textSize = 15f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        })
        // 艺术家
        col.addView(TextView(context).apply {
            text = artist; setTextColor(subColor); textSize = 12f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        // 唱碟（黑胶唱片+封面）+ 唱臂；播放时唱片旋转、唱臂落在唱片上；停止时唱片停、唱臂离开
        // 去掉播放按钮和内嵌歌词，整个唱碟区域可点击播放/暂停；view 里只显示歌曲名和歌手
        val playing = isPlaying(controller.playbackState)

        // 计算唱碟大小：尽量大，前提是唱臂能塞进去；container 已测量则直接算，否则给默认值避免布局抖动
        val discSize = if (container.width > 0 && container.height > 0) {
            val dw = container.width - dp(24)
            val dh = container.height - dp(56)  // 标题+歌手+上下 padding
            min(dw, dh).coerceAtLeast(dp(96))
        } else {
            dp(170)
        }
        val coverSize = (discSize * 0.62f).toInt()
        val armLen = (discSize * 0.52f).toInt()

        // 唱碟容器（唱碟+唱臂，可点击）
        val discWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(discSize, discSize).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        }
        // 黑胶唱片
        val disc = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF141414.toInt())
                setStroke(dp(2), 0xFF3A3A3A.toInt())
            }
        }
        // 封面（圆形裁剪，比唱碟小一圈）
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (art != null) setImageBitmap(toCircleBitmap(art))
        }
        disc.addView(cover, FrameLayout.LayoutParams(coverSize, coverSize, Gravity.CENTER))
        discWrap.addView(disc, FrameLayout.LayoutParams(discSize, discSize, Gravity.CENTER))

        // 唱臂：转轴底座 + 唱杆 + 唱头，绕转轴中心旋转
        val tonearm = FrameLayout(context).apply {
            // 转轴底座（大圆，银色渐变）
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(0xFFE8E8E8.toInt(), 0xFFB0B0B0.toInt(), 0xFF888888.toInt())
                    setStroke(dp(1), 0xFF666666.toInt())
                }
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START))
            // 转轴中心小点
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF555555.toInt())
                }
            }, FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(8); topMargin = dp(8)
            })
            // 唱杆（细矩形，从转轴中心向右延伸）
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    colors = intArrayOf(0xFFD8D8D8.toInt(), 0xFFA0A0A0.toInt())
                    setStroke(dp(1), 0xFF777777.toInt())
                }
            }, FrameLayout.LayoutParams(armLen, dp(5), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(11); topMargin = dp(9)
            })
            // 唱头（小圆，在唱杆末端）
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF333333.toInt())
                    setStroke(dp(1), 0xFF666666.toInt())
                }
            }, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(11) + armLen - dp(3); topMargin = dp(6)
            })
            // 旋转中心点：转轴中心
            pivotX = dp(11).toFloat(); pivotY = dp(11).toFloat()
            rotation = if (playing) 22f else -18f
        }
        discWrap.addView(tonearm, FrameLayout.LayoutParams(dp(22) + armLen + dp(8), dp(22), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(2); topMargin = dp(2)
        })

        // 整个唱碟区域可点击播放/暂停
        discWrap.setOnClickListener {
            safe {
                if (isPlaying(controller.playbackState)) {
                    userPaused = true
                    controller.transportControls.pause()
                } else {
                    // 首次播放该应用：先预热（启动进程确保 metadata/歌词可用），返回后自动播放
                    if (primedPackage != controller.packageName) {
                        primeAndPlay(controller)
                    } else {
                        userPaused = false
                        controller.transportControls.play()
                    }
                }
            }
        }
        // container 大小变化时同步调整唱碟大小（避免第一次测量不准）
        container.post {
            val dw = container.width - dp(24)
            val dh = container.height - dp(56)
            val newSize = min(dw, dh).coerceAtLeast(dp(96))
            if (newSize != discSize) {
                val newCover = (newSize * 0.62f).toInt()
                val newArm = (newSize * 0.52f).toInt()
                discWrap.layoutParams = (discWrap.layoutParams as LinearLayout.LayoutParams).apply {
                    width = newSize; height = newSize
                }
                disc.layoutParams = (disc.layoutParams as FrameLayout.LayoutParams).apply {
                    width = newSize; height = newSize
                }
                cover.layoutParams = (cover.layoutParams as FrameLayout.LayoutParams).apply {
                    width = newCover; height = newCover
                }
            }
        }
        // 播放时唱片旋转（20秒一圈），暂停时停止
        discRotationAnim?.cancel()
        if (playing) {
            discRotationAnim = ObjectAnimator.ofFloat(disc, "rotation", 0f, 360f).apply {
                duration = 20000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        col.addView(discWrap)

        // 歌词行引用（悬浮歌词刷新时用，view 里不显示内嵌歌词）
        val lyricLines = arrayOfNulls<TextView>(2)
        val lyricView = TextView(context)  // 占位，保持 tag 兼容

        container.addView(col)
        // 单击：启动当前绑定的音乐 App（先 hideFloat 关外部地图浮窗，回 NUI 后 showFloat 恢复）
        col.setOnClickListener { launchPreferredApp() }
        // 长按：弹"选择音乐 App"对话框，把 QQ 音乐换成酷我等；选完直接启动新 App
        col.setOnLongClickListener {
            pickPreferredApp(onPickedLaunch = true)
            true
        }

        container.setTag(R.id.tag_lyric_view, lyricView)
        container.setTag(R.id.tag_lyric_lines, lyricLines)
        // 持久歌词循环：只在未启动时启动一次，不再被高频渲染反复重置
        if (!lyricTickRunning) {
            lyricTickRunning = true
            handler.post(lyricTick)
        }
        // 重建后立即同步一次当前歌词行（不等下一次 tick，避免先闪 ♪/暂无歌词）
        refreshLyric()
    }

    /** 无封面时的紧凑播放界面：只显示歌名 + 歌手 + 播放/上一首/下一首按钮（不显示唱碟/歌词） */
    private fun renderCompactNoCover(
        title: String, artist: String, controller: MediaController, playing: Boolean,
        p: UiTheme.Palette,
    ) {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        // 歌名
        col.addView(TextView(context).apply {
            text = title; setTextColor(p.textPrimary); textSize = 15f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        })
        // 歌手
        col.addView(TextView(context).apply {
            text = artist; setTextColor(p.textSecondary); textSize = 12f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })

        // 控制行：上一首 / 播放暂停 / 下一首
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(ctrlBtn(android.R.drawable.ic_media_previous) {
            safe { controller.transportControls.skipToPrevious() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(4) })
        row.addView(playBtn(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        ) {
            safe {
                if (isPlaying(controller.playbackState)) {
                    userPaused = true
                    controller.transportControls.pause()
                } else if (primedPackage != controller.packageName) {
                    primeAndPlay(controller)   // 首次播放该应用：先预热（与唱碟点击一致）
                } else {
                    userPaused = false
                    controller.transportControls.play()
                }
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) })
        row.addView(ctrlBtn(android.R.drawable.ic_media_next) {
            safe { controller.transportControls.skipToNext() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(4) })
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        container.addView(col)
        // 单击：启动当前绑定的音乐 App；长按：弹"选择音乐 App"对话框（与大布局一致）
        col.setOnClickListener { launchPreferredApp() }
        col.setOnLongClickListener {
            pickPreferredApp(onPickedLaunch = true)
            true
        }

        // 公共尾部：歌词悬浮 tag + 持久歌词循环（悬浮歌词独立于本 view 工作）
        val lyricLines = arrayOfNulls<TextView>(2)
        val lyricView = TextView(context)
        container.setTag(R.id.tag_lyric_view, lyricView)
        container.setTag(R.id.tag_lyric_lines, lyricLines)
        if (!lyricTickRunning) {
            lyricTickRunning = true
            handler.post(lyricTick)
        }
        refreshLyric()
    }

    private fun ctrlBtn(icon: Int, onClick: () -> Unit): View =
        ImageButton(context).apply {
            setImageResource(icon)
            // 参考主流车机/音乐 App 控件：半透明深色圆形底 + 细白描边 + 白色图标，
            // 在封面/壁纸/深浅主题任意背景下都清晰
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000.toInt())
                setStroke(dp(1), 0x4DFFFFFF.toInt())
            }
            setColorFilter(0xFFFFFFFF.toInt())
            setOnClickListener { onClick() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

    /** 播放/暂停键：绿色实心圆（Apple Music 风格），比上下首更醒目 */
    private fun playBtn(icon: Int, onClick: () -> Unit): View =
        ctrlBtn(icon, onClick).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF34C759.toInt())
            }
        }

    /** 把 bitmap 裁剪成圆形 */
    private fun toCircleBitmap(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        val rect = Rect(0, 0, size, size)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    private fun safe(block: () -> Unit) {
        runCatching { block() }
            .onFailure { NuiToast.show(context, "该 App 不支持此操作", Toast.LENGTH_SHORT) }
    }

    /** 酷我车机 6.0 等车机版音乐 App 退后台会自动暂停播放：
     *  检测到"非用户主动暂停 + 绑定 App 不在前台"时，延迟约 800ms 自动恢复播放。
     *  10s 内只自动恢复一次（防状态抖动循环）；用户主动暂停（NUI 按钮）不干预。 */
    private fun maybeAutoResume() {
        if (userPaused) return
        val now = System.currentTimeMillis()
        if (now - lastAutoResumeAt < 10_000L) return
        val preferred = prefs.getString(KEY_APP, null) ?: return
        val controller = currentController ?: return
        if (controller.packageName != preferred) return
        if (isAppForeground(preferred)) return
        lastAutoResumeAt = now
        android.util.Log.i("NUI.MusicHost", "检测到$preferred 退后台被暂停，自动恢复播放")
        handler.postDelayed({
            if (!userPaused) safe { controller.transportControls.play() }
        }, 800L)
    }

    /** 判断 App 是否在前台（Android 9 车机 getRunningTasks 可用；高版本受限时保守返回 false 即"不在前台"） */
    private fun isAppForeground(pkg: String): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName == pkg
    } catch (e: Exception) {
        false
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

        // 清掉上一首铺在容器上的封面背景，避免启动卡还残留旧专辑图
        container.background = null
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

    /** 所有绑定音乐统一走网易云歌词：切歌后总是触发（播放器自带歌词仅作网易云结果到达前的临时显示），
     *  LyricFetcher 内部按 key 去重+缓存，不会重复请求。网易云无词时回调 null，保留自带歌词兜底。 */
    private fun maybeFetchLyric() {
        val t = lastTitle
        if (t.isBlank()) return
        val key = "$t||$lastArtist"
        if (key == lyricFetchKey) return
        lyricFetchKey = key
        android.util.Log.d("NUI.MusicHost", "maybeFetchLyric: $t - $lastArtist")
        lyricFetcher.requestLyric(t, lastArtist, 0L, allowQqFallback = false)
    }

    /** 启动首选音乐 App，延迟返回 NUI（与地图逻辑一致）。
     *  返回后 refresh() 由 MainActivity.onResume() 触发，自动更新播放状态。
     *
     *  浮窗顺序管理（防止酷我的 mini player 跟地图悬浮区视觉叠在一起）：
     *    启动音乐前先 onHideFloat（关掉外部高德浮窗）
     *    返回 NUI 后，再稍等约 900ms 让酷我自身的 overlay 收掉，然后 onShowFloat 恢复地图浮窗 */
    /** 首次播放前预热：启动音乐应用确保进程在运行（metadata/歌词可用），延迟返回后自动播放。
     *  解决 QQ 音乐等应用：MediaSession 存在但进程未运行时，transportControls.play() 能播但拿不到歌词。 */
    private fun primeAndPlay(controller: MediaController) {
        val pkg = controller.packageName
        Log.d(TAG, "primeAndPlay: pkg=$pkg primedPackage=$primedPackage")
        // 车机版音乐 App（如酷我）可能无 LAUNCHER activity，ACTION_MAIN 兜底
        val i = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
            }
        if (i.resolveActivity(context.packageManager) == null) {
            // 拿不到启动 Intent，直接播放
            Log.d(TAG, "primeAndPlay: no launch intent, play directly")
            controller.transportControls.play()
            primedPackage = pkg
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onHideFloat?.invoke()
        runCatching { context.startActivity(i) }
        Log.d(TAG, "primeAndPlay: launched $pkg, will return in 3s")
        // 延迟返回 NUI，返回后自动播放 + 恢复地图浮窗
        handler.postDelayed({
            val back = Intent().apply {
                setClassName(context, "com.nui.launcher.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching { context.startActivity(back) }
            primedPackage = pkg
            Log.d(TAG, "primeAndPlay: returned to NUI, will play in 900ms")
            handler.postDelayed({
                controller.transportControls.play()
                onShowFloat?.invoke()
                Log.d(TAG, "primeAndPlay: auto play triggered")
            }, 900L)
        }, 3000L)
    }

    private fun launchPreferredApp() {
        val pkg = prefs.getString(KEY_APP, null)
        if (pkg != null) {
            // 车机版音乐 App（如酷我车机版）可能没有 LAUNCHER activity，getLaunchIntentForPackage 返回 null，
            // 用 ACTION_MAIN + CATEGORY_LAUNCHER + setPackage 兜底显式启动
            val i = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
            if (i.resolveActivity(context.packageManager) != null) {
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
                    primedPackage = pkg
                    // 回 NUI 后再恢复地图浮窗（错开酷我自有关闭窗口的动画窗口）
                    handler.postDelayed({ onShowFloat?.invoke() }, 900L)
                }, 3000L)
                return
            }
            NuiToast.show(context, "无法启动已绑定的音乐 App（$pkg）", Toast.LENGTH_SHORT)
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
        val wasShowing = lyricFloatView != null
        lyricFloatView?.let { runCatching { wm.removeView(it) } }
        lyricFloatView = null
        karaokeView = null
        lyricFloatNext = null
        // 悬浮歌词消失时重绘音乐栏，恢复内嵌歌词
        if (wasShowing) currentController?.let { renderPlaying(it) }
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
            // 悬浮歌词出现时重绘音乐栏，隐藏内嵌歌词
            currentController?.let { renderPlaying(it) }
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
        val seen = mutableSetOf<String>()
        // 1) 白名单精确检测（有桌面启动入口或已安装都算——车机版音乐 App 常无 LAUNCHER activity）
        for (pkg in MUSIC_PACKAGES) {
            val installed = pm.getLaunchIntentForPackage(pkg) != null ||
                runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            if (!installed) continue
            seen.add(pkg)
            apps.add(Triple(pkg, appLabel(pm, pkg), appIcon(pm, pkg)))
        }
        // 2) 关键字动态扫描：包名未知的版本（如车厂定制 QQ 音乐）也能检测到，放宽匹配
        runCatching {
            for (ai in pm.getInstalledApplications(0)) {
                val pkg = ai.packageName
                if (pkg in seen || pkg == context.packageName) continue
                if (!isMusicLike(pm, ai)) continue
                seen.add(pkg)
                apps.add(Triple(pkg, appLabel(pm, pkg), appIcon(pm, pkg)))
            }
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

    private fun appLabel(pm: android.content.pm.PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    private fun appIcon(pm: android.content.pm.PackageManager, pkg: String): android.graphics.drawable.Drawable =
        runCatching { pm.getApplicationIcon(pkg) }.getOrDefault(pm.defaultActivityIcon)

    /** 音乐应用关键字识别（放宽匹配）：包名或应用名命中任一关键词即认为音乐应用。
     *  覆盖 QQ 音乐全系列（手机版/车机版/TV版/平板版/爱趣听及车厂定制改名版）、酷我、酷狗、网易云等。 */
    private fun isMusicLike(pm: android.content.pm.PackageManager, ai: android.content.pm.ApplicationInfo): Boolean {
        val pkg = ai.packageName.lowercase()
        // 包名关键词（腾讯系：qqmusic 全家桶 + aiqiting 爱趣听；酷我/酷狗/网易云/Spotify；通用 music）
        val pkgKeywords = listOf(
            "qqmusic", "qqmusictv", "qqmusicpad", "aiqiting",
            "kuwo", "kwmusic", "kugou",
            "netease", "cloudmusic", "spotify",
            "music",
        )
        if (pkgKeywords.any { pkg.contains(it) }) return true
        // 应用名关键词
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
        val labelKeywords = listOf(
            "音乐", "QQ音乐", "Q音", "酷我", "酷狗", "网易云", "爱趣听", "Spotify", "虾米", "咪咕", "汽水音乐",
        )
        if (labelKeywords.any { label.contains(it) }) return true
        return false
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

    /** 根据当前播放进度更新歌词高亮行（仅悬浮歌词，内嵌歌词已移除）。 */
    private fun refreshLyric() {
        val controller = currentController
        playingNow = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (lyrics.isEmpty()) { hideLyricFloat(); return }
        val pos = controller?.playbackState?.position ?: -1L
        if (pos < 0L) { updateLyricFloat(); return }

        var idx = -1
        for (i in lyrics.indices) {
            if (lyrics[i].first <= pos) idx = i else break
        }
        // 播放位置早于首句歌词（前奏/无词段）：默认显示第一句
        if (idx < 0 && lyrics.isNotEmpty()) idx = 0
        if (idx >= 0 && idx != lyricHighlight) lyricHighlight = idx
        updateLyricFloat()
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MusicHost"
        private const val PREFS = "nui_music"
        private const val KEY_APP = "music_app"
        private const val KEY_LYRIC_BG_ALPHA = "lyric_bg_alpha"
        private const val KEY_STYLE = "music_style"
        /** 切歌后封面缓冲期：metadata 封面滞后到达时，缓冲期内维持大布局避免上下首之间来回跳 */
        private const val COVER_GRACE_MS = 2500L
        /** 已预热过的音乐应用包名（首次播放前启动一次，确保进程在运行、metadata/歌词可用） */
        private var primedPackage: String? = null
        const val DEFAULT_LYRIC_BG_ALPHA = 20

        /** 读取音乐外观，默认经典 */
        fun musicStyle(context: Context): MusicStyle =
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STYLE, "classic") == "vinyl"
            ) MusicStyle.VINYL else MusicStyle.CLASSIC

        /** 保存音乐外观 */
        fun setMusicStyle(context: Context, style: MusicStyle) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_STYLE, if (style == MusicStyle.VINYL) "vinyl" else "classic").apply()
        }

        /** 读取悬浮歌词背景不透明度（0-100，默认 80） */
        fun lyricBgAlpha(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LYRIC_BG_ALPHA, DEFAULT_LYRIC_BG_ALPHA).coerceIn(0, 100)

        /** 保存悬浮歌词背景不透明度（0-100） */
        fun setLyricBgAlpha(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LYRIC_BG_ALPHA, percent.coerceIn(0, 100)).apply()
        }

        // 常见音乐 App 包名（用于启动卡选择列表，含车机版。QQ 音乐系列覆盖手机版/车机版/TV版/平板版/爱趣听）
        val MUSIC_PACKAGES = setOf(
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.tencent.qqmusiccar",
            "com.tencent.qqmusictv",
            "com.tencent.qqmusicpad",
            "com.tencent.aiqiting",
            "com.kugou.android",
            "com.kugou.android.lite",
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
