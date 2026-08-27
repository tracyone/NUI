package com.nui.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 悬浮地图占位视图。
 *
 * 当前阶段不接入高德 SDK，先用自绘方式画一个"地图风格"的背景
 * （渐变 + 网格 + 一条路径 + 定位点 + 指南针），让悬浮地图区域在视觉上完整。
 *
 * 后续接入高德地图时，将此视图替换为 com.amap.api.maps.MapView，
 * 或在 layout 中的 mapContainer 里动态 addView 一个 MapView 即可。
 */
class MapPlaceholderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 255, 255)
        strokeWidth = 1.5f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 197, 211)
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 197, 211) }
    private val dotInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF00C5D3") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 236, 239, 241)
        textSize = 30f
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 154, 160, 166)
        textSize = 22f
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 236, 239, 241)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private var routePath: Path? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0B1426"), Color.parseColor("#13283F")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        // 一条示意导航路径
        routePath = Path().apply {
            moveTo(w * 0.08f, h * 0.78f)
            cubicTo(w * 0.30f, h * 0.30f, w * 0.45f, h * 0.62f, w * 0.55f, h * 0.52f)
            cubicTo(w * 0.66f, h * 0.42f, w * 0.72f, h * 0.70f, w * 0.92f, h * 0.30f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 网格
        val step = 64f
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }

        // 导航路径
        routePath?.let { canvas.drawPath(it, routePaint) }

        // 定位点（路径末端附近）
        val cx = w * 0.92f
        val cy = h * 0.30f
        canvas.drawCircle(cx, cy, 26f, dotOuter)
        canvas.drawCircle(cx, cy, 10f, dotInner)

        // 指南针（右上角）
        val px = w - 48f
        val py = 48f
        canvas.drawCircle(px, py, 26f, compassPaint)
        canvas.drawLine(px, py - 26f, px, py + 26f, compassPaint)
        canvas.drawText("N", px - 9f, py - 30f, labelPaint)

        // 文案
        canvas.drawText("地图", 28f, h - 28f, labelPaint)
        canvas.drawText("接入高德后将显示实时路况", 28f, h - 60f, hintPaint)
    }
}
