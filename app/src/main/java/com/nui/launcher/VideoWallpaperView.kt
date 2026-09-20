package com.nui.launcher

import android.content.Context
import android.graphics.Matrix
import android.media.MediaPlayer
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

    /** 播放失败回调（what/extra 为 MediaPlayer.onError 参数），用于通知宿主设置失败。 */
    var onError: ((what: Int, extra: Int) -> Unit)? = null

    /** 首帧渲染成功回调（用于确认视频壁纸真正播起来了）。 */
    var onFirstFrame: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    /** 设置视频路径；surface 已就绪则立即起播，否则等 surface 可用时自动播放。 */
    fun setVideo(path: String) {
        val same = videoPath == path
        videoPath = path
        wantPlaying = true
        if (surface != null) {
            // 同一路径且播放器已存在，不重建（只确保播放）；否则起播新视频
            if (!same || mediaPlayer == null) startPlay() else runCatching { mediaPlayer?.start() }
        }
        // surface 尚未创建时不做任何事，onSurfaceTextureAvailable 会自动起播
    }

    /** 滑到本页：确保开始播放。 */
    fun resume() {
        wantPlaying = true
        val mp = mediaPlayer
        if (mp != null && prepared) {
            runCatching { mp.start() }
        } else if (surface != null && videoPath != null) {
            startPlay()
        }
        applyCropMatrix()
    }

    /** 滑离本页：彻底释放解码器（保留路径，回本页由 resume 重新起播），避免离屏仍占用硬解/与新播放器抢占。 */
    fun pause() {
        wantPlaying = false
        releasePlayer(retainPath = true)
    }

    /** 释放当前播放器；[retainPath]=true 时保留路径，surface 回来可自动重播。 */
    private fun releasePlayer(retainPath: Boolean) {
        val mp = mediaPlayer
        mediaPlayer = null
        prepared = false
        playToken++ // 使该播放器所有在途异步回调失效
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
        if (visibility == VISIBLE && wantPlaying && surface != null) resume()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        if (videoPath != null && wantPlaying) startPlay()
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

    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit

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
                if (wantPlaying) runCatching { it.start() }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error token=$token what=$what extra=$extra")
                if (token == playToken) onError?.invoke(what, extra)
                true // 已处理，阻止进入 Error 状态后再回调 onCompletion
            }
            mp.setOnInfoListener { _, what, _ ->
                if (token == playToken && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    onFirstFrame?.invoke()
                }
                false
            }
            mp.prepareAsync()
            mediaPlayer = mp
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
    }
}
