package com.nui.launcher.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 悬浮窗圆角遮罩层（单窗口方案）。
 *
 * 背景：地图 / 音乐这类"外部 App 的 overlay 浮窗"是方角矩形，NUI 改不了它们的窗口形状，
 * 只能在它们上面再叠一层，用壁纸像素把四个角"补回去"并把内侧挖成圆弧。
 *
 * 早期实现是"每个角一个 r×r 的小 overlay 窗口"（4 角 × 多层 = 8~16 个窗口）：
 * - 每个窗口都要独立 addView/removeView，几何一变就是十几次跨进程 IPC，主线程掉帧；
 * - 层级要靠 remove+add 反复抢，浮窗 App 重建时会把它们压回下面，表现为"选了圆角还是直角"；
 * - 只能服务地图，音乐浮窗要再做一份。
 *
 * 本类改为**一个全屏透明 overlay**：窗口只 add 一次，之后只更新绘制内容。
 * 同一层里可以同时给任意多个浮窗矩形（地图、音乐）补圆角，层级竞争只剩一个窗口。
 */
class CornerMaskLayer(
    private val context: Context,
    /** 圆角半径（px），与 UI 卡片一致 */
    private val radiusPx: Int,
) {
    companion object {
        private const val TAG = "NUI.CornerMask"
    }

    /** 需要圆角的浮窗矩形（屏幕坐标，已含外扩） */
    data class Target(val rect: Rect)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MaskView? = null
    private var attached = false

    /** 桌面壁纸 drawable（根布局背景），由宿主注入 */
    var wallpaperProvider: (() -> Drawable?)? = null

    private var wasVisible = false
    private var raster: Bitmap? = null
    private var rasterSrc: Drawable? = null
    private var screenW = 0
    private var screenH = 0

    /** 角块绘制指令：屏幕坐标矩形 + 角序号（0 左上 / 1 右上 / 2 左下 / 3 右下） */
    private data class Corner(val rect: Rect, val which: Int)

    /** 上次渲染用的矩形集合：几何没变就不重绘（每 0.5s 兜底轮询时时避免无谓开销） */
    private val lastRects = ArrayList<Rect>()

    /** 更新需要显示的圆角矩形集合；show=false 或空集合时隐藏（窗口保留，仅置空内容） */
    fun update(targets: List<Target>, show: Boolean) {
        if (!show || targets.isEmpty() || radiusPx <= 0) {
            setVisible(false)
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            setVisible(false)
            return
        }
        // 壁纸换了（换图/换昼夜）→ 缓存失效，必须重绘
        if (wallpaperProvider?.invoke() !== rasterSrc) lastRects.clear()
        val rects = targets.map { it.rect }
        val unchanged = rects.size == lastRects.size && rects.indices.all { rects[it] == lastRects[it] }
        val v = ensureView()
        if (!unchanged || v.isEmpty()) {
            val src = resolveWallpaperBitmap() ?: return
            v.setContent(src, buildCorners(targets))
            lastRects.clear()
            lastRects.addAll(rects)
        }
        val justShown = !wasVisible
        setVisible(true)
        wasVisible = true
        // 浮窗是另一个进程的 overlay：它每次重建都会把自己加到窗口栈顶，只切内容不重加，
        // 遮罩就会被压在下面（这正是"选了圆角还是直角"的成因）。首次显示时重加一次钉子。
        if (justShown) bringToFront()
    }

    /** 隐藏遮罩内容（切页/失焦/非悬浮形态时），窗口本身常驻，避免频繁 add/remove */
    fun setVisible(visible: Boolean) {
        val v = view ?: return
        if (visible) {
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        } else {
            wasVisible = false
            if (v.visibility != View.GONE) v.visibility = View.GONE
        }
    }

    /** 外部浮窗 App 重建后可能把自己加到栈顶，把本层重新提到最上（必要时才 remove+add） */
    fun bringToFront() {
        val v = view ?: return
        if (!attached) return
        runCatching {
            wm.removeViewImmediate(v)
            wm.addView(v, lp())
        }
    }

    /** 彻底移除窗口（退前台 / 失焦时用）。比 GONE 更硬：窗口本身不存在，
     *  不可能盖在其他 App、对话框或系统界面上；下次 update 会按需重新 add。 */
    fun detach() {
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        attached = false
        view = null
        lastRects.clear()
        wasVisible = false
    }

    fun release() {
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        attached = false
        view = null
        raster?.recycle()
        raster = null
        rasterSrc = null
    }

    private fun ensureView(): MaskView {
        view?.let { return it }
        val v = MaskView(context)
        runCatching { wm.addView(v, lp()) }
            .onFailure { Log.e(TAG, "addView failed（多半是无 SYSTEM_ALERT_WINDOW 权限）", it) }
        attached = true
        view = v
        return v
    }

    private fun lp() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    /** 把每个目标矩形拆成四个角块，越界部分裁掉 */
    private fun buildCorners(targets: List<Target>): List<Corner> {
        val w = screenWidth()
        val h = screenHeight()
        val r = radiusPx
        val out = ArrayList<Corner>(targets.size * 4)
        for (t in targets) {
            val rc = Rect(t.rect)
            if (rc.width() <= r * 2 || rc.height() <= r * 2) continue
            if (!rc.intersect(0, 0, w, h)) continue
            val l = rc.left
            val tp = rc.top
            val rt = rc.right
            val bt = rc.bottom
            out += Corner(Rect(l, tp, minOf(l + r, rt), minOf(tp + r, bt)), 0)
            out += Corner(Rect(maxOf(rt - r, l), tp, rt, minOf(tp + r, bt)), 1)
            out += Corner(Rect(l, maxOf(bt - r, tp), minOf(l + r, rt), bt), 2)
            out += Corner(Rect(maxOf(rt - r, l), maxOf(bt - r, tp), rt, bt), 3)
        }
        return out
    }

    private fun screenWidth(): Int {
        if (screenW <= 0) screenW = context.resources.displayMetrics.widthPixels
        return screenW
    }

    private fun screenHeight(): Int {
        if (screenH <= 0) screenH = context.resources.displayMetrics.heightPixels
        return screenH
    }

    /** 取桌面壁纸的屏幕像素副本（根布局背景），按屏幕尺寸栅格化一次并缓存 */
    private fun resolveWallpaperBitmap(): Bitmap? {
        val bg = wallpaperProvider?.invoke() ?: return null
        val cached = raster
        if (cached != null && !cached.isRecycled && rasterSrc === bg) return cached
        return try {
            val w = screenWidth()
            val h = screenHeight()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // 用副本绘制，避免改动共享 drawable 的 bounds
            val d = bg.constantState?.newDrawable(context.resources) ?: bg
            d.setBounds(0, 0, w, h)
            d.draw(c)
            if (cached != null && !cached.isRecycled) cached.recycle()
            raster = bmp
            rasterSrc = bg
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "resolveWallpaperBitmap failed", e)
            null
        }
    }

    /** 全屏透明遮罩视图：只在角块区域落笔，其余保持透明 */
    private class MaskView(ctx: Context) : View(ctx) {
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val clearPaint = Paint().apply {
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private var src: Bitmap? = null
        private var corners: List<Corner> = emptyList()

        fun isEmpty(): Boolean = src == null || src?.isRecycled == true

        fun setContent(bitmap: Bitmap, items: List<Corner>) {
            src = bitmap
            corners = items
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val b = src ?: return
            if (b.isRecycled) return
            for (c in corners) {
                val dst = c.rect
                if (dst.width() <= 0 || dst.height() <= 0) continue
                val rr = dst.width().toFloat()
                canvas.save()
                canvas.translate(dst.left.toFloat(), dst.top.toFloat())
                val layer = canvas.saveLayer(0f, 0f, rr, rr, null)
                // 用壁纸同位置的像素填住这一小块
                canvas.drawBitmap(
                    b,
                    Rect(dst.left, dst.top, dst.right, dst.bottom),
                    Rect(0, 0, dst.width(), dst.height()),
                    bitmapPaint,
                )
                // 挖掉内侧的四分之一圆，留下外侧的壁纸，形成圆弧
                val hole = Path()
                when (c.which) {
                    0 -> { // 左上
                        hole.arcTo(RectF(0f, 0f, 2 * rr, 2 * rr), 180f, 90f, true)
                        hole.lineTo(rr, rr)
                    }
                    1 -> { // 右上
                        hole.arcTo(RectF(-rr, 0f, rr, 2 * rr), 270f, 90f, true)
                        hole.lineTo(0f, rr)
                    }
                    2 -> { // 左下
                        hole.arcTo(RectF(0f, -rr, 2 * rr, rr), 90f, 90f, true)
                        hole.lineTo(rr, 0f)
                    }
                    else -> { // 右下
                        hole.arcTo(RectF(-rr, -rr, rr, rr), 0f, 90f, true)
                        hole.lineTo(0f, 0f)
                    }
                }
                hole.close()
                canvas.drawPath(hole, clearPaint)
                canvas.restoreToCount(layer)
                canvas.restore()
            }
        }
    }
}
