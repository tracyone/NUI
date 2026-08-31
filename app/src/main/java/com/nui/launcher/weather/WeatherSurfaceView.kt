package com.nui.launcher.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * 全屏天气特效 View：按天气类型绘制天空渐变 + 粒子动画背景。
 *
 * 特效类型（由 [effect] 指定）：
 * - clear   晴：蓝天（昼）/ 星空（夜）
 * - partly  多云间晴：云 + 阳光
 * - clouds  多云：大片云
 * - overcast 阴：低饱和灰云
 * - drizzle 毛毛雨：细密小雨
 * - rain    雨：斜向雨丝
 * - snow    雪：飘雪
 * - storm   雷暴：暴雨 + 闪电
 * - fog     雾：流动雾层
 */
class WeatherSurfaceView(context: Context) : View(context) {

    /** 当前特效类型 */
    var effect: String = "clear"
        set(value) {
            if (field != value) {
                field = value
                buildParticles()
            }
        }

    /** 是否白天（决定晴空/星空等配色） */
    var isDay: Boolean = true

    /** 透明模式：不绘制天空渐变背景，只画粒子动画（用于叠加在桌面/壁纸之上） */
    var transparent: Boolean = false

    private val rand = Random(System.currentTimeMillis())
    private val drops = mutableListOf<Particle>()
    private val flakes = mutableListOf<Particle>()
    private var clouds = mutableListOf<Cloud>()
    private var stars = mutableListOf<Star>()
    private var fogBands = mutableListOf<FogBand>()

    private var cloudDrift = 0f
    private var flashAlpha = 0f
    private var flashTimer = 0f
    private var nextFlashIn = 2f
    private var lastFrame = 0L

    private val skyPaint = Paint()
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flakePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint()
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class Particle(var x: Float, var y: Float, var speed: Float, var size: Float, var phase: Float)
    private data class Cloud(var x: Float, var y: Float, var scale: Float, var speed: Float)
    private data class Star(var x: Float, var y: Float, var size: Float, var phase: Float)
    private data class FogBand(var y: Float, var height: Float, var speed: Float, var phase: Float)

    init {
        // 仅动画过程 invalidate；无手势回调，View 默认不会自动刷新
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildParticles()
    }

    private fun densityScale(): Float {
        val d = resources.displayMetrics.density
        return (d.coerceAtLeast(1f)) * (width.coerceAtLeast(1) / 1080f).coerceIn(0.6f, 1.4f)
    }

    /** 根据特效类型重建粒子系统 */
    private fun buildParticles() {
        drops.clear()
        flakes.clear()
        clouds.clear()
        stars.clear()
        fogBands.clear()
        if (width <= 0 || height <= 0) return

        val s = densityScale()
        val area = (width * height).toFloat()

        when (effect) {
            "drizzle" -> {
                val n = (area / 60000f * s).toInt().coerceIn(40, 160)
                repeat(n) {
                    drops += Particle(
                        rand.nextFloat() * width,
                        rand.nextFloat() * height,
                        (700f + rand.nextFloat() * 300f) * s,
                        (8f + rand.nextFloat() * 6f) * s,
                        rand.nextFloat() * 6.28f,
                    )
                }
            }
            "rain", "storm" -> {
                val n = (area / 18000f * s).toInt().coerceIn(120, 420)
                repeat(n) {
                    drops += Particle(
                        rand.nextFloat() * width,
                        rand.nextFloat() * height,
                        (1200f + rand.nextFloat() * 500f) * s,
                        (18f + rand.nextFloat() * 10f) * s,
                        rand.nextFloat() * 6.28f,
                    )
                }
            }
            "snow" -> {
                val n = (area / 22000f * s).toInt().coerceIn(80, 260)
                repeat(n) {
                    flakes += Particle(
                        rand.nextFloat() * width,
                        rand.nextFloat() * height,
                        (40f + rand.nextFloat() * 60f) * s,
                        (2.5f + rand.nextFloat() * 3.5f) * s,
                        rand.nextFloat() * 6.28f,
                    )
                }
            }
        }

        // 云（多云 / 阴 / 雾 / 阵雨 / 雷暴）
        if (effect in setOf("partly", "clouds", "overcast", "rain", "storm", "fog")) {
            val n = if (effect == "clouds" || effect == "overcast") 5 else if (effect == "fog") 6 else 3
            repeat(n) {
                clouds += Cloud(
                    rand.nextFloat() * width,
                    (0.05f + rand.nextFloat() * 0.4f) * height,
                    0.7f + rand.nextFloat() * 1.1f,
                    (6f + rand.nextFloat() * 10f) * s,
                )
            }
        }

        // 星空（夜晚的晴 / 多云间晴）
        if (!isDay && effect in setOf("clear", "partly")) {
            val n = (area / 16000f).toInt().coerceIn(60, 200)
            repeat(n) {
                stars += Star(
                    rand.nextFloat() * width,
                    rand.nextFloat() * height * 0.6f,
                    1f + rand.nextFloat() * 2f,
                    rand.nextFloat() * 6.28f,
                )
            }
        }

        // 雾层
        if (effect == "fog") {
            repeat(4) {
                fogBands += FogBand(
                    0.1f + rand.nextFloat() * 0.5f,
                    (0.06f + rand.nextFloat() * 0.1f) * height,
                    (8f + rand.nextFloat() * 14f) * s,
                    rand.nextFloat() * 6.28f,
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 50L) / 1000f
        lastFrame = now

        val w = width.toFloat()
        val h = height.toFloat()

        drawSky(canvas, w, h)
        drawStars(canvas, dt)
        drawSun(canvas, w, h)
        drawClouds(canvas, dt)
        drawFog(canvas, dt)
        drawRain(canvas, dt)
        drawSnow(canvas, dt)
        drawLightning(canvas, dt)

        postInvalidateOnAnimation()
    }

    private fun skyGradient(): IntArray {
        val dayTop: Int
        val dayBottom: Int
        when (effect) {
            "clear" -> { dayTop = 0xFF2E6FB8.toInt(); dayBottom = 0xFFA8D4F0.toInt() }
            "partly" -> { dayTop = 0xFF4A8CC9.toInt(); dayBottom = 0xFFC3DCEF.toInt() }
            "clouds" -> { dayTop = 0xFF6E87A1.toInt(); dayBottom = 0xFFC4D2DE.toInt() }
            "overcast" -> { dayTop = 0xFF5E6B7A.toInt(); dayBottom = 0xFFAEB9C4.toInt() }
            "drizzle", "rain" -> { dayTop = 0xFF33435E.toInt(); dayBottom = 0xFF6A7D96.toInt() }
            "snow" -> { dayTop = 0xFF7B93AD.toInt(); dayBottom = 0xFFD7E2EA.toInt() }
            "storm" -> { dayTop = 0xFF1E2536.toInt(); dayBottom = 0xFF4E5872.toInt() }
            "fog" -> { dayTop = 0xFF8D9AA8.toInt(); dayBottom = 0xFFD5DBE0.toInt() }
            else -> { dayTop = 0xFF2E6FB8.toInt(); dayBottom = 0xFFA8D4F0.toInt() }
        }
        if (isDay) return intArrayOf(dayTop, dayBottom)
        // 夜晚整体压暗并偏蓝紫
        return when (effect) {
            "clear" -> intArrayOf(0xFF070D1F.toInt(), 0xFF1C3258.toInt())
            "partly", "clouds" -> intArrayOf(0xFF0A1426.toInt(), 0xFF22364F.toInt())
            else -> intArrayOf(dayTop, dayBottom)
        }
    }

    private fun drawSky(canvas: Canvas, w: Float, h: Float) {
        if (transparent) return // 透明模式：透出底层壁纸/背景
        val g = skyGradient()
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h, g[0], g[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, skyPaint)
    }

    private fun drawStars(canvas: Canvas, dt: Float) {
        if (stars.isEmpty()) return
        starPaint.style = Paint.Style.FILL
        for (s in stars) {
            val alpha = (0.5f + 0.5f * sin(s.phase)).coerceIn(0.15f, 1f)
            starPaint.color = (alpha * 255).toInt() shl 24 or 0x00FFFFFF
            canvas.drawCircle(s.x, s.y, s.size, starPaint)
            s.phase += dt * 1.6f
        }
    }

    private fun drawSun(canvas: Canvas, w: Float, h: Float) {
        if (!isDay || effect !in setOf("clear", "partly")) return
        val cx = w * 0.78f
        val cy = h * 0.18f
        val r = w * 0.10f
        // 外圈光晕
        sunPaint.style = Paint.Style.FILL
        sunPaint.color = 0x35FFE9A0.toInt()
        canvas.drawCircle(cx, cy, r * 2.0f, sunPaint)
        sunPaint.color = 0x66FFDF7A.toInt()
        canvas.drawCircle(cx, cy, r * 1.4f, sunPaint)
        sunPaint.color = 0xFFFFF2C4.toInt()
        canvas.drawCircle(cx, cy, r, sunPaint)
    }

    private fun drawClouds(canvas: Canvas, dt: Float) {
        if (clouds.isEmpty()) return
        cloudDrift += dt * 6f
        cloudPaint.style = Paint.Style.FILL
        val baseAlpha = when (effect) {
            "partly" -> 150
            "fog" -> 70
            "rain", "storm" -> 120
            else -> 180
        }
        for (c in clouds) {
            val x = (c.x + cloudDrift * c.speed / 10f) % (width + 400f) - 200f
            val s = c.scale
            cloudPaint.color = (baseAlpha shl 24) or 0x00FFFFFF
            // 云朵：三个椭圆叠成
            canvas.drawOval(x - 60 * s, c.y - 18 * s, x + 40 * s, c.y + 22 * s, cloudPaint)
            canvas.drawOval(x - 30 * s, c.y - 34 * s, x + 70 * s, c.y + 8 * s, cloudPaint)
            canvas.drawOval(x + 20 * s, c.y - 22 * s, x + 110 * s, c.y + 18 * s, cloudPaint)
        }
    }

    private fun drawFog(canvas: Canvas, dt: Float) {
        if (fogBands.isEmpty()) return
        fogPaint.style = Paint.Style.FILL
        val w = width.toFloat()
        val h = height.toFloat()
        for (b in fogBands) {
            b.phase += dt * b.speed / 40f
            val x = (b.phase * 60f) % (w + 300f) - 150f
            val alpha = 0x22FFFFFF.toInt()
            fogPaint.color = alpha
            canvas.drawOval(x - 200f, b.y * h, x + w * 0.9f, b.y * h + b.height, fogPaint)
        }
    }

    private fun drawRain(canvas: Canvas, dt: Float) {
        if (drops.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        dropPaint.style = Paint.Style.STROKE
        dropPaint.strokeWidth = 1.6f * densityScale()
        dropPaint.strokeCap = Paint.Cap.ROUND
        dropPaint.color = if (effect == "storm") 0x99CFE8FF.toInt() else 0x88D8EAFF.toInt()
        val slant = 0.18f
        for (d in drops) {
            d.y += d.speed * dt
            d.x -= d.speed * slant * dt
            val len = d.size
            canvas.drawLine(d.x, d.y, d.x + slant * len, d.y - len, dropPaint)
            if (d.y > h + len) {
                d.y = -len - rand.nextFloat() * h * 0.2f
                d.x = rand.nextFloat() * (w + 100f) - 50f
            }
            if (d.x < -20f) d.x = w + 20f
        }
    }

    private fun drawSnow(canvas: Canvas, dt: Float) {
        if (flakes.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        flakePaint.style = Paint.Style.FILL
        flakePaint.color = 0xEFFFFFFF.toInt()
        for (f in flakes) {
            f.y += f.speed * dt
            f.phase += dt * 2.2f
            val x = f.x + sin(f.phase) * 30f * densityScale()
            canvas.drawCircle(x, f.y, f.size, flakePaint)
            if (f.y > h + 10f) {
                f.y = -10f
                f.x = rand.nextFloat() * w
            }
        }
    }

    private fun drawLightning(canvas: Canvas, dt: Float) {
        if (effect != "storm") return
        // 随机触发闪电
        nextFlashIn -= dt
        if (nextFlashIn <= 0f && flashTimer <= 0f) {
            flashTimer = 0.12f + rand.nextFloat() * 0.15f
            nextFlashIn = 3f + rand.nextFloat() * 7f
        }
        if (flashTimer > 0f) {
            flashTimer -= dt
            flashAlpha = flashTimer / 0.1f
            flashPaint.color = (0x66FFFFFF.toInt() and 0x00FFFFFF.toInt()) or ((flashAlpha * 200).toInt().coerceIn(0, 200) shl 24)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
    }

    /** 暂停动画（不可见时节省 CPU） */
    fun pauseAnimation() {
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    companion object {
        /** WMO 天气代码 → 特效类型 */
        fun effectFor(code: Int): String = when (code) {
            0 -> "clear"
            1 -> "partly"
            2 -> "clouds"
            3 -> "overcast"
            45, 48 -> "fog"
            51, 53, 55, 56, 57 -> "drizzle"
            61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
            71, 73, 75, 77, 85, 86 -> "snow"
            95, 96, 99 -> "storm"
            else -> "clear"
        }
    }
}
