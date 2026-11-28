package com.nui.launcher.video

import android.app.Activity
import android.content.Intent
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import com.nui.launcher.NuiToast
import com.nui.launcher.R
import com.nui.launcher.databinding.VideoPlayerBinding
import java.io.File

/**
 * 视频播放器承载：复用桌面音乐窗口（musicContainer），视频与音乐二选一显示。
 *
 * - 播放用 TextureView + MediaPlayer（有声、非循环、fitCenter 等比缩放，完整画面可见、
 *   不足方向留黑边）；全屏 [VideoFullscreenActivity] 才用 centerCrop 铺满。
 * - 支持进度条（轨道透明）、播放/暂停、快进快退、上一个/下一个、全屏、关闭。
 * - 「上一个/下一个」在当前目录的视频文件列表中循环切换。
 * - 全屏走独立 [VideoFullscreenActivity]，返回时带回播放位置与播放状态继续。
 * - 切页（ViewPager2 回收 page0）时 surface 销毁仅分离不释放，回来重新绑定继续播放，
 *   位置不丢；彻底关闭/Activity 销毁才 release 解码器。
 */
class VideoHost(
    private val activity: Activity,
    private var container: FrameLayout,
) : TextureView.SurfaceTextureListener {

    private var binding: VideoPlayerBinding? = null
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantPlaying = false
    private var playToken = 0
    private var currentPath: String? = null

    /** 当前目录的视频文件列表（按名排序） */
    private var videoFiles: List<File> = emptyList()
    private var currentIndex = -1

    private val handler = Handler(Looper.getMainLooper())
    private var tickRunning = false

    /** 进度条拖动中：暂停 tick 回写进度，避免和手指抢进度 */
    private var draggingProgress = false

    /** 播放进度记忆：按文件绝对路径存上次停下的毫秒数（prefs: nui_video） */
    private val prefs by lazy { activity.getSharedPreferences("nui_video", 0) }
    /** 本次起播需要 seek 到的位置（prepared 后消费） */
    private var resumePosMs = 0
    /** 上次落盘进度的时间戳，播放中每 5 秒落一次 */
    private var lastSaveMs = 0L

    /** 用户关闭视频（点 X），通知宿主恢复音乐窗口 */
    var onClose: (() -> Unit)? = null

    /** 画中画小窗"回到桌面"按钮回调（切回桌面页恢复大窗），由宿主设置。 */
    var onPipHome: (() -> Unit)? = null

    /** 是否处于画中画小窗模式（决定底部控制层显隐） */
    private var pipMode = false

    // ==================== 打开视频 ====================

    /**
     * 桌面页 ViewHolder 被 ViewPager2 回收重建后，把已渲染的播放器 UI 迁移到新的容器
     * （保留播放器/进度，TextureView 重新挂载后由 onSurfaceTextureAvailable 重绑 surface）。
     */
    fun attach(newContainer: FrameLayout) {
        if (newContainer === container) return
        container = newContainer
        val root = binding?.root ?: return
        // 迁移窗口前先把播放器从旧 surface 上摘下来：旧 surface 随 TextureView detach 即失效，
        // 继续往它上面解码（或拿着它起播）会导致绿屏并把播放器拖进错误态（表现为绿屏+暂停）。
        runCatching { mediaPlayer?.setSurface(null) }
        surface?.release()
        surface = null
        (root.parent as? android.view.ViewGroup)?.removeView(root)
        newContainer.removeAllViews()
        newContainer.addView(root)
    }

    /** 打开一个视频文件：扫描同目录视频列表，渲染 UI 并开始播放。 */
    fun open(path: String) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            NuiToast.show(activity, "视频文件不存在", android.widget.Toast.LENGTH_SHORT)
            return
        }
        val dir = file.parentFile
        videoFiles = listVideos(dir)
        currentIndex = videoFiles.indexOfFirst { it.absolutePath == file.absolutePath }
        if (currentIndex < 0) {
            videoFiles = listOf(file)
            currentIndex = 0
        }
        ensureUi()
        playFile(videoFiles[currentIndex])
    }

    private fun ensureUi() {
        if (binding != null) return
        // 清除音乐卡（MusicHost 渲染的内容），视频与音乐二选一显示
        container.removeAllViews()
        val b = VideoPlayerBinding.inflate(LayoutInflater.from(activity), container, true)
        binding = b
        b.videoSurface.surfaceTextureListener = this
        b.btnVideoPlay.setOnClickListener { togglePlay() }
        b.btnVideoPrev.setOnClickListener { prev() }
        b.btnVideoNext.setOnClickListener { next() }
        b.btnVideoRewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        b.btnVideoForward.setOnClickListener { seekBy(SEEK_STEP_MS) }
        b.btnVideoFullscreen.setOnClickListener { fullscreen() }
        b.btnVideoClose.setOnClickListener { close() }
        b.btnVideoMute.setOnClickListener { toggleMute() }
        updateMuteIcon()
        // 画中画小窗控制按钮：暂停/回桌面/全屏；点空白处收起按钮层
        b.btnPipPlay.setOnClickListener { togglePlay() }
        b.btnPipHome.setOnClickListener { onPipHome?.invoke() }
        b.btnPipFullscreen.setOnClickListener { fullscreen() }
        b.videoPipControls.setOnClickListener { hidePipControls() }
        b.videoProgress.max = PROGRESS_MAX
        b.videoProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var dragTo = -1
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) dragTo = progress
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                draggingProgress = true
                dragTo = -1
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                draggingProgress = false
                if (dragTo >= 0) {
                    val mp = mediaPlayer
                    val dur = mp?.duration ?: 0
                    // Long 计算：dur(ms) × 1000 会超出 Int（>24 分钟的视频），溢出后 seek 到错误位置（表现为从头播）
                    if (dur > 0 && sb.max > 0) {
                        runCatching { mp?.seekTo((dur.toLong() * dragTo / sb.max).toInt()) }
                    }
                }
                dragTo = -1
                updateProgress()
            }
        })
    }

    // ==================== 播放控制 ====================

    private fun playFile(file: File) {
        // 切歌前先记住上一个视频的进度
        if (currentPath != null && currentPath != file.absolutePath) savePosition()
        currentPath = file.absolutePath
        val idx = videoFiles.indexOfFirst { it.absolutePath == file.absolutePath }
        if (idx >= 0) currentIndex = idx
        resumePosMs = savedPosition(file.absolutePath)
        wantPlaying = true
        startPlay()
    }

    private fun startPlay() {
        val path = currentPath ?: return
        val s = surface
        if (s == null) { wantPlaying = true; return } // surface 未就绪，等 onSurfaceTextureAvailable
        releasePlayer(retainSurface = true)
        val token = playToken
        try {
            val mp = MediaPlayer()
            // 明确声明媒体/电影音频属性：默认 MediaPlayer 是 USAGE_UNKNOWN+CONTENT_TYPE_UNKNOWN，
            // 车机 ROM 对 UNKNOWN 不路由到扬声器（表现为无声），标准 Android 才 fallback 正常出声。
            // 显式 USAGE_MEDIA 让车机正确出声。
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            mp.setDataSource(path)
            mp.setSurface(s)
            mp.isLooping = false
            applyMute(mp)
            mp.setOnVideoSizeChangedListener { _, _, _ ->
                if (token == playToken) applyFitMatrix()
            }
            mp.setOnPreparedListener {
                if (token != playToken) { runCatching { it.release() }; return@setOnPreparedListener }
                prepared = true
                applyFitMatrix()
                updateDuration()
                // 恢复上次停下的位置（接近结尾的视为看完，从头播）
                val rp = resumePosMs
                resumePosMs = 0
                if (rp > 0) {
                    val dur = runCatching { it.duration }.getOrDefault(0)
                    if (dur > 0 && rp < dur - END_GUARD_MS) runCatching { it.seekTo(rp) }
                }
                if (wantPlaying) runCatching { it.start() }
                updatePlayIcon()
                startTick()
            }
            mp.setOnCompletionListener {
                if (token == playToken) {
                    // 正常播完：清除该文件的续播点，下次从头开始
                    currentPath?.let { prefs.edit().remove(PREF_POS + it).apply() }
                    next()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error token=$token what=$what extra=$extra")
                if (token == playToken) {
                    NuiToast.show(activity, "视频播放失败", android.widget.Toast.LENGTH_SHORT)
                }
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
            NuiToast.show(activity, "无法播放该视频", android.widget.Toast.LENGTH_SHORT)
        }
    }

    fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        if (mp.isPlaying) pause() else play()
    }

    /** 切换静音：悬浮/画中画与全屏共用同一状态（companion muted），概率小到无需持久化。 */
    fun toggleMute() {
        muted = !muted
        applyMute(mediaPlayer)
        updateMuteIcon()
    }

    private fun applyMute(mp: MediaPlayer?) {
        val v = if (muted) 0f else 1f
        runCatching { mp?.setVolume(v, v) }
    }

    private fun updateMuteIcon() {
        binding?.btnVideoMute?.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
        )
    }

    fun play() {
        val mp = mediaPlayer ?: return
        if (prepared && !mp.isPlaying) runCatching { mp.start() }
        wantPlaying = true
        updatePlayIcon()
        startTick()
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (prepared && mp.isPlaying) runCatching { mp.pause() }
        wantPlaying = false
        updatePlayIcon()
        // 暂停即记下进度，下次打开从这里继续
        savePosition()
    }

    fun seekBy(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        val dur = mp.duration
        if (dur <= 0) return
        val target = (mp.currentPosition + deltaMs.toInt()).coerceIn(0, dur)
        runCatching { mp.seekTo(target) }
        updateProgress()
    }

    fun next() = step(1)

    fun prev() = step(-1)

    private fun step(delta: Int) {
        if (videoFiles.isEmpty()) return
        val n = (currentIndex + delta + videoFiles.size) % videoFiles.size
        currentIndex = n
        playFile(videoFiles[n])
    }

    // ==================== 全屏 ====================

    /** 记录当前位置进入全屏；返回后由 [onFullscreenResult] 恢复。 */
    fun fullscreen() {
        val mp = mediaPlayer ?: return
        val path = currentPath ?: return
        val pos = if (prepared) mp.currentPosition else 0
        val playing = mp.isPlaying
        // 进入全屏前暂停悬浮播放器，避免双实例同时出声
        pause()
        val i = Intent(activity, VideoFullscreenActivity::class.java)
            .putExtra(VideoFullscreenActivity.EXTRA_PATH, path)
            .putExtra(VideoFullscreenActivity.EXTRA_POSITION, pos)
            .putExtra(VideoFullscreenActivity.EXTRA_PLAYING, playing)
        activity.startActivityForResult(i, REQ_FULLSCREEN)
    }

    /** 全屏返回：更新当前视频与进度，恢复播放状态。 */
    fun onFullscreenResult(path: String?, position: Long, playing: Boolean) {
        if (path == null) return
        if (path == currentPath) {
            // 同一个视频：seek 到全屏结束位置，恢复播放状态
            val mp = mediaPlayer
            if (mp != null && prepared) {
                runCatching { mp.seekTo(position.toInt()) }
                updateProgress()
            }
            if (playing) play() else pause()
        } else {
            // 全屏里切了下一个视频：直接播放新路径
            open(path)
        }
    }

    // ==================== 生命周期 ====================

    /** 切离桌面页：仅暂停不释放（surface 销毁会走 onSurfaceTextureDestroyed 分离）。 */
    fun suspendPlayback() {
        pause()
    }

    /**
     * 画中画小窗模式切换：
     * - true：隐藏底部控制层（进度条/按钮），只保留右上角关闭按钮，点画面回桌面；
     * - false：恢复完整控制层。
     */
    fun setPipMode(on: Boolean) {
        pipMode = on
        binding?.videoControls?.visibility = if (on) View.GONE else View.VISIBLE
        // 切换小窗/大窗模式时收起小窗控制按钮层，避免残留
        binding?.videoPipControls?.visibility = View.GONE
    }

    /** 画中画小窗：切换控制按钮层（暂停/回桌面/全屏）显隐。 */
    fun togglePipControls() {
        val b = binding ?: return
        val show = b.videoPipControls.visibility != View.VISIBLE
        b.videoPipControls.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 隐藏画中画小窗控制按钮层。 */
    fun hidePipControls() {
        binding?.videoPipControls?.visibility = View.GONE
    }

    /** 回到桌面页：确保继续播放（surface 重建后重新绑定）。 */
    fun resumePlayback() {
        if (currentPath != null && wantPlaying) {
            val mp = mediaPlayer
            val s = surface
            if (mp != null && prepared && s != null) {
                runCatching { mp.setSurface(s) }
                runCatching { mp.seekTo(mp.currentPosition) }
                // 全屏里可能改过静音，回悬浮态同步一次
                applyMute(mp)
                updateMuteIcon()
                if (wantPlaying && !mp.isPlaying) runCatching { mp.start() }
                updatePlayIcon()
                startTick()
            } else if (s != null) {
                startPlay()
            }
            // surface 为空：等 onSurfaceTextureAvailable 重绑，绝不拿已释放的旧 surface 起播
        }
    }

    /** 用户点 X 关闭：释放播放器，清空窗口，通知宿主恢复音乐。 */
    fun close() {
        stopTick()
        savePosition()
        releasePlayer(retainSurface = false)
        currentPath = null
        videoFiles = emptyList()
        currentIndex = -1
        prepared = false
        binding?.root?.let { container.removeView(it) }
        binding = null
        onClose?.invoke()
    }

    /** Activity 销毁：彻底释放。 */
    fun release() {
        stopTick()
        savePosition()
        releasePlayer(retainSurface = false)
        surface?.release()
        surface = null
    }

    /** 是否正在播放视频（宿主据此协调与音乐窗口的互斥）。 */
    fun isActive(): Boolean = currentPath != null

    private fun releasePlayer(retainSurface: Boolean) {
        val mp = mediaPlayer
        mediaPlayer = null
        prepared = false
        playToken++
        if (mp != null) {
            runCatching { mp.setOnPreparedListener(null) }
            runCatching { mp.setOnCompletionListener(null) }
            runCatching { mp.setOnErrorListener(null) }
            runCatching { mp.setOnVideoSizeChangedListener(null) }
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        if (!retainSurface) {
            surface?.release()
            surface = null
        }
    }

    // ==================== 进度刷新 ====================

    private fun startTick() {
        if (tickRunning) return
        tickRunning = true
        handler.post(tickRunnable)
    }

    private fun stopTick() {
        tickRunning = false
        handler.removeCallbacks(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!tickRunning) return
            updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun updateProgress() {
        val b = binding ?: return
        val mp = mediaPlayer ?: return
        if (!prepared) return
        val dur = mp.duration
        val pos = mp.currentPosition
        b.videoDurTime.text = formatTime(dur.toLong())
        b.videoPosTime.text = formatTime(pos.toLong())
        if (dur > 0 && !draggingProgress) {
            val max = b.videoProgress.max
            b.videoProgress.progress = (pos.toLong() * max / dur).toInt().coerceIn(0, max)
        }
        updatePlayIcon()
        // 播放中每 5 秒落一次进度，避免进程被杀丢太多
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSaveMs > SAVE_INTERVAL_MS) {
            lastSaveMs = now
            if (mp.isPlaying) savePosition()
        }
    }

    // ==================== 进度记忆 ====================

    /** 读取某个文件上次停下的位置（0 表示没有/从头播）。 */
    private fun savedPosition(path: String): Int {
        val v = prefs.getInt(PREF_POS + path, 0)
        return if (v in 1..Int.MAX_VALUE) v else 0
    }

    /** 记录当前播放位置；接近结尾（< END_GUARD_MS）或开头（< MIN_RESUME_MS）则清除，下次从头播。 */
    private fun savePosition() {
        val path = currentPath ?: return
        val mp = mediaPlayer ?: return
        if (!prepared) return
        val pos = runCatching { mp.currentPosition }.getOrDefault(0)
        val dur = runCatching { mp.duration }.getOrDefault(0)
        if (dur <= 0) return
        val e = prefs.edit()
        if (pos < MIN_RESUME_MS || pos >= dur - END_GUARD_MS) {
            e.remove(PREF_POS + path)
        } else {
            e.putInt(PREF_POS + path, pos)
        }
        e.apply()
    }

    private fun updateDuration() {
        val b = binding ?: return
        val mp = mediaPlayer ?: return
        val dur = mp.duration
        b.videoDurTime.text = formatTime(dur.toLong())
    }

    private fun updatePlayIcon() {
        val b = binding ?: return
        val mp = mediaPlayer ?: return
        val playing = prepared && mp.isPlaying
        val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        b.btnVideoPlay.setImageResource(icon)
        b.btnPipPlay.setImageResource(icon)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    // ==================== TextureView surface 生命周期 ====================

    override fun onSurfaceTextureAvailable(
        surfaceTexture: android.graphics.SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surface = Surface(surfaceTexture)
        val mp = mediaPlayer
        if (mp != null && prepared) {
            // 切页/小窗迁移回来：重新绑定 surface 继续
            runCatching { mp.setSurface(surface) }
            // 换 surface 后主动 seek 到当前帧，强制解码器重新输出一帧，
            // 避免部分解码器残留绿屏/花屏；暂停态同样能刷出正确画面。
            runCatching { mp.seekTo(mp.currentPosition) }
            if (wantPlaying && !mp.isPlaying) runCatching { mp.start() }
            applyMute(mp)
            applyFitMatrix()
            updatePlayIcon()
        } else if (currentPath != null && wantPlaying) {
            startPlay()
        }
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: android.graphics.SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        applyFitMatrix()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        // 仅分离 surface 不释放播放器，保留位置与状态，回来可继续
        runCatching { mediaPlayer?.setSurface(null) }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}

    // ==================== fitCenter（等比缩放，完整画面可见） ====================

    /**
     * 等比缩放矩阵：把完整视频画面缩放到窗口内部（fitCenter），宽高比不变、不裁剪，
     * 不足的方向居中留黑边（窗口背景纯黑，黑边与背景融为一体）。
     * 注意：全屏 [VideoFullscreenActivity] 仍是 centerCrop 铺满，只有悬浮窗用 fitCenter。
     */
    private fun applyFitMatrix() {
        val tv = binding?.videoSurface ?: return
        val mp = mediaPlayer ?: return
        val vw = tv.width
        val vh = tv.height
        if (vw <= 0 || vh <= 0) return
        val mw = mp.videoWidth
        val mh = mp.videoHeight
        if (mw <= 0 || mh <= 0) return
        val videoRatio = mw.toFloat() / mh
        val viewRatio = vw.toFloat() / vh
        val sx: Float
        val sy: Float
        if (videoRatio > viewRatio) {
            // 视频比窗口更宽：宽贴满，高等比缩小（上下留黑边）
            sx = 1f; sy = viewRatio / videoRatio
        } else {
            // 视频比窗口更高：高贴满，宽等比缩小（左右留黑边）
            sx = videoRatio / viewRatio; sy = 1f
        }
        val scaledW = vw * sx
        val scaledH = vh * sy
        val dx = (vw - scaledW) / 2f
        val dy = (vh - scaledH) / 2f
        val matrix = Matrix()
        matrix.setScale(sx, sy)
        matrix.postTranslate(dx, dy)
        tv.setTransform(matrix)
    }

    // ==================== 工具 ====================

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "VideoHost"
        const val REQ_FULLSCREEN = 2001

        /** 续播进度存储键前缀（prefs 名为 nui_video，后接文件绝对路径） */
        private const val PREF_POS = "pos:"
        /** 小于该值不记忆（刚开播就关掉没意义） */
        private const val MIN_RESUME_MS = 3_000
        /** 距结尾小于该值视为看完，下次从头播 */
        private const val END_GUARD_MS = 5_000L
        /** 播放中进度落盘间隔 */
        private const val SAVE_INTERVAL_MS = 5_000L

        /**
         * 文件浏览器选完视频后写入的待播放路径；MainActivity onResume 时消费。
         * 用静态变量而非广播：文件浏览器 finish 后必回桌面（MainActivity 是 HOME），
         * onResume 必然触发，无竞态。
         */
        @Volatile var pendingOpenPath: String? = null

        /** 静音状态：悬浮/画中画/全屏共用（全屏是独立 MediaPlayer 实例） */
        @Volatile var muted = false

        /** 取走并清空待播放路径。 */
        fun consumePendingOpen(): String? {
            val p = pendingOpenPath
            pendingOpenPath = null
            return p
        }
        private const val SEEK_STEP_MS = 10000L
        private const val TICK_MS = 500L

        /** 进度条精度（SeekBar 默认 max 只有 100，不足以表达长视频） */
        private const val PROGRESS_MAX = 1000

        /** 支持的视频扩展名（小写，不带点） */
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m4v",
            "3gp", "webm", "mpg", "mpeg", "rmvb", "rm", "vob", "f4v",
        )

        /** 扫描目录下的视频文件，按文件名排序。 */
        fun listVideos(dir: File?): List<File> {
            if (dir == null || !dir.isDirectory) return emptyList()
            return runCatching {
                dir.listFiles()
                    ?.filter { it.isFile && VIDEO_EXTS.contains(it.extension.lowercase()) }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }
}
