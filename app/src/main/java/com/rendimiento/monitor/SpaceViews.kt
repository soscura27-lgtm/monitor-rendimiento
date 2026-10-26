package com.rendimiento.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fondo espacial: nebulosa (imagen) + estrellas que titilan, dibujadas en codigo.
 * La animacion corre a ~15 cuadros por segundo y se pausa cuando la vista no se ve.
 */
class SpaceBackgroundView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val nebula: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.bg_nebula)
    } catch (e: Throwable) {
        null
    }
    private val bmpMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val star = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flare = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** x, y, radio, fase, velocidad, tipo (0 blanca, 1 azul, 2 rosa, 3 grande con destello). */
    private var stars = FloatArray(0)
    private var starCount = 0
    private var visibleNow = false
    private val start = SystemClock.uptimeMillis()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bmp = nebula
        if (bmp != null && w > 0 && h > 0) {
            val scale = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, scale)
            bmpMatrix.postTranslate((w - bmp.width * scale) / 2f, (h - bmp.height * scale) / 2f)
        }
        scrim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x99050A24.toInt(), 0x22050A24, 0x33050A24, 0xAA050A24.toInt()),
            floatArrayOf(0f, 0.25f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        val rnd = Random(29L)
        val area = (w / density) * (h / density)
        starCount = (area / 900f).toInt().coerceIn(60, 260)
        stars = FloatArray(starCount * 6)
        for (i in 0 until starCount) {
            val k = i * 6
            stars[k] = rnd.nextFloat() * w
            stars[k + 1] = rnd.nextFloat() * h
            val big = rnd.nextInt(18) == 0
            stars[k + 2] = (if (big) 1.4f + rnd.nextFloat() * 0.8f else 0.4f + rnd.nextFloat() * 0.9f) * density
            stars[k + 3] = rnd.nextFloat() * 6.283f
            stars[k + 4] = 0.6f + rnd.nextFloat() * 2.2f
            stars[k + 5] = when {
                big -> 3f
                rnd.nextInt(7) == 0 -> 1f
                rnd.nextInt(11) == 0 -> 2f
                else -> 0f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Hud.BG)
        val bmp = nebula
        if (bmp != null) canvas.drawBitmap(bmp, bmpMatrix, bmpPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)

        val t = (SystemClock.uptimeMillis() - start) / 1000f
        for (i in 0 until starCount) {
            val k = i * 6
            val tw = 0.55f + 0.45f * sin(t * stars[k + 4] + stars[k + 3])
            val a = (255 * tw).toInt().coerceIn(30, 255)
            val kind = stars[k + 5].toInt()
            star.color = when (kind) {
                1 -> Color.argb(a, 150, 200, 255)
                2 -> Color.argb(a, 255, 170, 230)
                else -> Color.argb(a, 255, 255, 255)
            }
            val x = stars[k]
            val y = stars[k + 1]
            val r = stars[k + 2]
            canvas.drawCircle(x, y, r, star)
            if (kind == 3) {
                flare.color = Color.argb((a * 0.6f).toInt(), 200, 230, 255)
                flare.strokeWidth = 0.8f * density
                val len = r * 4.5f * tw
                canvas.drawLine(x - len, y, x + len, y, flare)
                canvas.drawLine(x, y - len, x, y + len, flare)
                star.color = Color.argb((a * 0.25f).toInt(), 160, 210, 255)
                canvas.drawCircle(x, y, r * 2.6f, star)
            }
        }
        if (visibleNow) postInvalidateDelayed(66L)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val was = visibleNow
        visibleNow = isVisible
        if (isVisible && !was) invalidate()
    }
}

/** Icono de radar animado (barrido que gira) para el encabezado. */
class RadarView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rot = Matrix()
    private var visibleNow = false
    private val start = SystemClock.uptimeMillis()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (46 * density).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sweep.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x002EE6FF, 0x002EE6FF, 0x552EE6FF, 0xCC2EE6FF.toInt()),
            floatArrayOf(0f, 0.70f, 0.95f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 3f * density
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val angle = (t * 140f) % 360f

        ring.color = Hud.CYAN
        ring.strokeWidth = 4f * density
        ring.alpha = 45
        canvas.drawCircle(cx, cy, r, ring)
        ring.strokeWidth = 1.6f * density
        ring.alpha = 255
        canvas.drawCircle(cx, cy, r, ring)
        ring.strokeWidth = 1f * density
        ring.alpha = 130
        canvas.drawCircle(cx, cy, r * 0.62f, ring)
        canvas.drawCircle(cx, cy, r * 0.28f, ring)
        ring.alpha = 70
        canvas.drawLine(cx - r, cy, cx + r, cy, ring)
        canvas.drawLine(cx, cy - r, cx, cy + r, ring)

        rot.setRotate(angle, cx, cy)
        sweep.shader?.setLocalMatrix(rot)
        canvas.drawCircle(cx, cy, r, sweep)

        // Puntos que se encienden cuando el barrido pasa por encima.
        for ((ang, dist) in BLIPS) {
            var diff = (angle - ang) % 360f
            if (diff < 0) diff += 360f
            val a = (255 * (1f - diff / 300f)).toInt().coerceIn(0, 255)
            if (a <= 0) continue
            val rad = Math.toRadians(ang.toDouble())
            val x = cx + (cos(rad) * r * dist).toFloat()
            val y = cy + (sin(rad) * r * dist).toFloat()
            dot.color = Color.argb(a / 3, 46, 230, 255)
            canvas.drawCircle(x, y, 4f * density, dot)
            dot.color = Color.argb(a, 220, 255, 255)
            canvas.drawCircle(x, y, 1.8f * density, dot)
        }
        if (visibleNow) postInvalidateDelayed(33L)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val was = visibleNow
        visibleNow = isVisible
        if (isVisible && !was) invalidate()
    }

    companion object {
        private val BLIPS = listOf(Pair(35f, 0.55f), Pair(200f, 0.8f), Pair(290f, 0.4f))
    }
}
