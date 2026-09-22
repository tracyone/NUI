package com.nui.launcher

import android.content.Context
import android.graphics.Matrix
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * 负一屏动态视频壁纸：TextureView + MediaPlayer，centerCrop 铺满全屏无黑边。
 *
 * 注意：TextureView 默认把视频帧 fitXY 拉伸到整个 view（纹理坐标 [0,1] 映射到 view 四边）。
 * 因此 centerCrop 是在「已铺满 view」的基础上，对偏窄的那一维再放大并居中裁剪：
 *  - 视频比 view 更宽：高度填满，横向按比例放大后左右裁掉；
 *  - 视频比 view 更高：宽度填满，纵向按比例放大后上下裁掉。
 * 无论视频分辨率比屏幕大还是小，最终都铺满全屏、无黑边、不变形。静音循环播放。
 *
 * 生命周期：ViewPager2 会预加载/重建 ViewHolder，可能同时存在多个本 View 实例。
 * 必须在 surface 销毁、view detach 时彻底释放 MediaPlayer，用 playToken 丢弃陈旧播放器的
 * 异步回调，避免多个播放器抢占解码器/Surface 导致画面卡死。
 *
 * 起播采用「确定性恢复」：宿主在滑入负一屏、从外部 App（系统文件选择器等）返回、重新选择
 * 视频后统一调用 [ensurePlaying]，不依赖某一个生命周期回调恰好触发。另对 prepareAsync
 * 在少数设备上既不回调 onPrepared 也不回调 onError（解码器资源竞争/静默失败）加了看门狗，
 * 超时自动重启一次，避免首帧永远不来、TextureView 透出底层桌面壁纸（表现为"设置无效"）。
 */
class VideoWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : TextureView(context, attrs, defStyle), TextureView.SurfaceTextureListener {

    private var mediaPlayer: MediaPlayer? = null
    private var videoPath: String? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantPlaying = false
    private var playToken = 0
    private var prepareRetries = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 播放失败回调（what/extra 为 MediaPlayer.onError 参数），用于通知宿主设置失败。 */
    var onError: ((what: Int, extra: Int) -> Unit)? = null

    /** 首帧渲染成功回调（用于确认视频壁纸真正播起来了）。 */
    var onFirstFrame: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    /** 设置视频路径；surface 已就绪则确定性起播，否则等 surface 可用时自动播放。 */
    fun setVideo(path: String) {
        val changed = videoPath != path
        videoPath = path
        prepareRetries = 0
        if (changed) {
            // 新视频：释放旧播放器，由 ensurePlaying 重新创建
            releasePlayer(retainPath = true)
        }
        ensurePlaying()
    }

    /**
     * 确定性起播：宿主在滑入本页、Activity onResume、重选视频后调用。
     *  - 没有路径 / surface 未就绪：只标记 wantPlaying，等 onSurfaceTextureAvailable；
     *  - 播放器已 prepared：确保 start；
     *  - 播放器正在 prepareAsync：不重复创建（看门狗会兜底卡死的 prepare）；
     *  - 没有播放器：创建并起播。
     */
    fun ensurePlaying() {
        wantPlaying = true
        val path = videoPath ?: return
        val s = surface ?: return
        val mp = mediaPlayer
        when {
            mp != null && prepared -> runCatching { if (!mp.isPlaying) mp.start() }
            mp != null -> { /* prepareAsync 在途，等待回调；看门狗兜底 */ }
            else -> startPlay()
        }
    }

    /** 滑到本页：确保开始播放（语义同 ensurePlaying，保留语义化入口）。 */
    fun resume() = ensurePlaying()

    /**
     * 强制重启：释放播放器并重新绑定当前 surface 后起播。
     * 用于 Activity 从系统文件选择器/其它 App 遮挡返回负一屏时，播放器可能仍绑定
     * 遮挡期间失效的旧 surface（帧在解码但画面不合成、透出桌面壁纸）的情况。
     */
    fun forceRestart() {
        wantPlaying = true
        if (videoPath == null || surface == null) return
        releasePlayer(retainPath = true)
        prepareRetries = 0
        startPlay()
    }

    /** 滑离本页：彻底释放解码器（保留路径，回本页由 ensurePlaying 重新起播），避免离屏仍占用硬解/与新播放器抢占。 */
    fun pause() {
        wantPlaying = false
        cancelPrepareWatchdog()
        releasePlayer(retainPath = true)
    }

    /** 释放当前播放器；[retainPath]=true 时保留路径，surface 回来可自动重播。 */
    private fun releasePlayer(retainPath: Boolean) {
        val mp = mediaPlayer
        mediaPlayer = null
        prepared = false
        playToken++ // 使该播放器所有在途异步回调失效
        cancelPrepareWatchdog()
        if (mp != null) {
            runCatching { mp.setOnPreparedListener(null) }
            runCatching { mp.setOnVideoSizeChangedListener(null) }
            runCatching { mp.setOnErrorListener(null) }
            runCatching { mp.setOnInfoListener(null) }
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        if (!retainPath) videoPath = null
    }

    /** 外部彻底释放（Activity 销毁等）。 */
    fun release() {
        releasePlayer(retainPath = false)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyCropMatrix()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // ViewHolder 回收/重建：立即释放播放器，避免离屏仍占解码器
        releasePlayer(retainPath = true)
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && wantPlaying && surface != null) ensurePlaying()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        // 无论之前因何种时序漏播，surface 就绪时确定性起播一次
        if (videoPath != null && wantPlaying) ensurePlaying()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        applyCropMatrix()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        releasePlayer(retainPath = true)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {
        // 首帧到达即说明解码管线正常，取消 prepare 看门狗
        if (prepareWatchToken == playToken) cancelPrepareWatchdog()
    }

    private var prepareWatchToken = -1
    private val prepareWatchdog = Runnable {
        if (prepareWatchToken != playToken) return@Runnable
        // prepareAsync 超时仍未 onPrepared：解码器资源竞争/静默失败，重启一次（仅一次）
        if (!prepared && wantPlaying && videoPath != null && surface != null && prepareRetries < MAX_PREPARE_RETRIES) {
            prepareRetries++
            Log.w(TAG, "prepare timeout, restart playback (retry=$prepareRetries)")
            startPlay()
        }
    }

    private fun cancelPrepareWatchdog() {
        prepareWatchToken = -1
        mainHandler.removeCallbacks(prepareWatchdog)
    }

    private fun startPlay() {
        val path = videoPath ?: return
        val s = surface ?: return
        releasePlayer(retainPath = true)
        val token = playToken
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f) // 静音
            mp.setOnVideoSizeChangedListener { _, _, _ ->
                if (token == playToken) applyCropMatrix()
            }
            mp.setOnPreparedListener {
                if (token != playToken) { // 已被新一轮播放取代，释放这个陈旧播放器
                    runCatching { it.release() }
                    return@setOnPreparedListener
                }
                prepared = true
                applyCropMatrix()
                cancelPrepareWatchdog()
                if (wantPlaying) runCatching { it.start() }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error token=$token what=$what extra=$extra")
                if (token == playToken) {
                    cancelPrepareWatchdog()
                    releasePlayer(retainPath = true) // 释放 Error 状态播放器，保留路径供下次重试
                    onError?.invoke(what, extra)
                }
                true // 已处理，阻止进入 Error 状态后再回调 onCompletion
            }
            mp.setOnInfoListener { _, what, _ ->
                if (token == playToken && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    cancelPrepareWatchdog()
                    onFirstFrame?.invoke()
                }
                false
            }
            mp.prepareAsync()
            mediaPlayer = mp
            // 看门狗：prepareAsync 在 PREPARE_TIMEOUT_MS 内未回调 onPrepared 则重启一次
            prepareWatchToken = token
            mainHandler.removeCallbacks(prepareWatchdog)
            mainHandler.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
        }
    }

    /**
     * centerCrop：TextureView 内容默认已 fitXY 铺满 view，这里在其基础上
     * 对偏窄一维放大并居中，保证铺满无黑边、不变形。
     */
    private fun applyCropMatrix() {
        val mp = mediaPlayer ?: return
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return
        val mw = mp.videoWidth
        val mh = mp.videoHeight
        if (mw <= 0 || mh <= 0) return

        val videoRatio = mw.toFloat() / mh   // 视频宽高比
        val viewRatio = vw.toFloat() / vh   // view 宽高比
        val sx: Float
        val sy: Float
        if (videoRatio > viewRatio) {
            // 视频更宽：高度填满，横向放大裁两边
            sx = videoRatio / viewRatio
            sy = 1f
        } else {
            // 视频更高（或等比）：宽度填满，纵向放大裁上下
            sx = 1f
            sy = viewRatio / videoRatio
        }
        val scaledW = vw * sx
        val scaledH = vh * sy
        val dx = (vw - scaledW) / 2f
        val dy = (vh - scaledH) / 2f

        val matrix = Matrix()
        matrix.setScale(sx, sy)
        matrix.postTranslate(dx, dy)
        setTransform(matrix)
    }

    companion object {
        private const val TAG = "VideoWallpaper"
        private const val PREPARE_TIMEOUT_MS = 8000L
        private const val MAX_PREPARE_RETRIES = 1
    }
}
