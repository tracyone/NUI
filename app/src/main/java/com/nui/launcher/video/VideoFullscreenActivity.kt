package com.nui.launcher.video

import android.app.Activity
import android.content.Intent
import android.graphics.Matrix
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import com.nui.launcher.NuiToast
import com.nui.launcher.databinding.ActivityVideoFullscreenBinding
import java.io.File

/**
 * 全屏视频播放器：视频铺满全屏，底部控制层点画面自动显隐。
 *
 * 与悬浮态（[VideoHost]）是同一份视频列表逻辑：进入时按传入路径扫描同目录视频，
 * 支持上一个/下一个切换。退出时通过 setResult 把「最终路径 + 播放位置 + 播放状态」
 * 带回，供 [VideoHost] 无缝续播（在悬浮窗口继续）。
 */
class VideoFullscreenActivity : Activity(), android.view.TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityVideoFullscreenBinding
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false

    /** 进度条拖动中：暂停 tick 回写进度，避免和手指抢进度 */
    private var draggingProgress = false
    private var playToken = 0

    private var videoFiles: List<File> = emptyList()
    private var currentIndex = -1
    private var currentPath: String? = null

    /** 启动时传入的初始位置（进入全屏前悬浮态已暂停，位置从那里接续） */
    private var startPosition = 0
    private var startPlaying = false

    private val handler = Handler(Looper.getMainLooper())
    private var tickRunning = false
    private var controlsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()

        startPosition = intent.getIntExtra(EXTRA_POSITION, 0)
        startPlaying = intent.getBooleanExtra(EXTRA_PLAYING, true)
        val path = intent.getStringExtra(EXTRA_PATH)

        binding.videoFullscreenSurface.surfaceTextureListener = this
        setupControls()
        bindControls()

        if (path == null) {
            NuiToast.show(this, "未指定视频", android.widget.Toast.LENGTH_SHORT)
            finish()
            return
        }
        open(path, startPosition)
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun setupControls() {
        // 点画面切换控制层显隐
        binding.videoFullscreenRoot.setOnClickListener { toggleControls() }
        binding.videoFullscreenControls.setOnClickListener { /* 控制层自身点击不隐藏 */ }
    }

    private fun bindControls() {
        binding.btnFullPlay.setOnClickListener { togglePlay() }
        binding.btnFullPrev.setOnClickListener { prev() }
        binding.btnFullNext.setOnClickListener { next() }
        binding.btnFullRewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.btnFullForward.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.btnFullExit.setOnClickListener { exitFullscreen() }
        binding.videoFullscreenProgress.max = PROGRESS_MAX
        binding.videoFullscreenProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        binding.videoFullscreenControls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        if (controlsVisible) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, CONTROLS_HIDE_MS)
    }

    private val autoHideRunnable = Runnable {
        if (controlsVisible && mediaPlayer?.isPlaying == true) {
            controlsVisible = false
            binding.videoFullscreenControls.visibility = View.GONE
        }
    }

    // ==================== 播放 ====================

    private fun open(path: String, position: Int) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            NuiToast.show(this, "视频文件不存在", android.widget.Toast.LENGTH_SHORT)
            return
        }
        videoFiles = VideoHost.listVideos(file.parentFile)
        currentIndex = videoFiles.indexOfFirst { it.absolutePath == file.absolutePath }
        if (currentIndex < 0) { videoFiles = listOf(file); currentIndex = 0 }
        playFile(videoFiles[currentIndex], position)
    }

    private fun playFile(file: File, position: Int) {
        currentPath = file.absolutePath
        val idx = videoFiles.indexOfFirst { it.absolutePath == file.absolutePath }
        if (idx >= 0) currentIndex = idx
        startPlay(position)
    }

    private fun startPlay(position: Int) {
        val path = currentPath ?: return
        val s = surface
        if (s == null) {
            startPosition = position
            return
        }
        releasePlayer()
        val token = playToken
        try {
            val mp = MediaPlayer()
            // 明确媒体/电影音频属性：默认 USAGE_UNKNOWN 在车机 ROM 不路由（无声），模拟器才正常。
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            mp.setDataSource(path)
            mp.setSurface(s)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, _, _ ->
                if (token == playToken) applyCropMatrix()
            }
            mp.setOnPreparedListener {
                if (token != playToken) { runCatching { it.release() }; return@setOnPreparedListener }
                prepared = true
                applyCropMatrix()
                updateDuration()
                if (position > 0) runCatching { it.seekTo(position) }
                if (startPlaying) runCatching { it.start() }
                updatePlayIcon()
                startTick()
                scheduleAutoHide()
            }
            mp.setOnCompletionListener {
                if (token == playToken) next()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                if (token == playToken) {
                    NuiToast.show(this, "视频播放失败", android.widget.Toast.LENGTH_SHORT)
                }
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
        }
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        if (mp.isPlaying) {
            runCatching { mp.pause() }
        } else {
            runCatching { mp.start() }
            scheduleAutoHide()
        }
        updatePlayIcon()
    }

    private fun seekBy(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        val dur = mp.duration
        if (dur <= 0) return
        runCatching { mp.seekTo((mp.currentPosition + deltaMs.toInt()).coerceIn(0, dur)) }
        updateProgress()
    }

    private fun next() = step(1)

    private fun prev() = step(-1)

    private fun step(delta: Int) {
        if (videoFiles.isEmpty()) return
        val n = (currentIndex + delta + videoFiles.size) % videoFiles.size
        currentIndex = n
        playFile(videoFiles[n], 0)
    }

    // ==================== 退出全屏 ====================

    private fun exitFullscreen() {
        val mp = mediaPlayer
        val path = currentPath
        val pos = if (mp != null && prepared) mp.currentPosition else startPosition
        val playing = mp?.isPlaying == true
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_POSITION, pos)
                .putExtra(EXTRA_PLAYING, playing)
        )
        finish()
    }

    override fun onBackPressed() {
        exitFullscreen()
    }

    // ==================== 进度刷新 ====================

    private fun startTick() {
        if (tickRunning) return
        tickRunning = true
        handler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!tickRunning) return
            updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun updateProgress() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        val dur = mp.duration
        val pos = mp.currentPosition
        binding.videoFullscreenDurTime.text = formatTime(dur.toLong())
        binding.videoFullscreenPosTime.text = formatTime(pos.toLong())
        if (dur > 0 && !draggingProgress) {
            val max = binding.videoFullscreenProgress.max
            binding.videoFullscreenProgress.progress = (pos.toLong() * max / dur).toInt().coerceIn(0, max)
        }
        updatePlayIcon()
    }

    private fun updateDuration() {
        val mp = mediaPlayer ?: return
        binding.videoFullscreenDurTime.text = formatTime(mp.duration.toLong())
    }

    private fun updatePlayIcon() {
        val playing = prepared && mediaPlayer?.isPlaying == true
        binding.btnFullPlay.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    // ==================== surface 生命周期 ====================

    override fun onSurfaceTextureAvailable(
        surfaceTexture: android.graphics.SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surface = Surface(surfaceTexture)
        if (currentPath != null && mediaPlayer == null) startPlay(startPosition)
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: android.graphics.SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        applyCropMatrix()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        runCatching { mediaPlayer?.setSurface(null) }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}

    private fun applyCropMatrix() {
        val tv = binding.videoFullscreenSurface
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
        if (videoRatio > viewRatio) { sx = videoRatio / viewRatio; sy = 1f }
        else { sx = 1f; sy = viewRatio / videoRatio }
        val scaledW = vw * sx
        val scaledH = vh * sy
        val dx = (vw - scaledW) / 2f
        val dy = (vh - scaledH) / 2f
        val matrix = Matrix()
        matrix.setScale(sx, sy)
        matrix.postTranslate(dx, dy)
        tv.setTransform(matrix)
    }

    private fun releasePlayer() {
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
    }

    override fun onPause() {
        super.onPause()
        // 切后台时暂停；退出全屏走 exitFullscreen 已 setResult
        val mp = mediaPlayer
        if (mp != null && prepared && mp.isPlaying) {
            runCatching { mp.pause() }
            updatePlayIcon()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickRunning = false
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        surface?.release()
        surface = null
    }

    companion object {
        private const val TAG = "VideoFullscreen"
        const val EXTRA_PATH = "extra_video_path"
        const val EXTRA_POSITION = "extra_video_position"
        const val EXTRA_PLAYING = "extra_video_playing"
        private const val SEEK_STEP_MS = 10000L
        private const val TICK_MS = 500L

        /** 进度条精度（SeekBar 默认 max 只有 100，不足以表达长视频） */
        private const val PROGRESS_MAX = 1000
        private const val CONTROLS_HIDE_MS = 4000L
    }
}
