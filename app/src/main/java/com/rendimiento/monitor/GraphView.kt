package com.rendimiento.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Grafica pequena con el historial de FPS (ultimas 60 lecturas, una por segundo). */
class GraphView(context: Context) : View(context) {

    private val values = FloatArray(MAX_POINTS)
    private var count = 0
    private var maxValue = 60f

    private val density = context.resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF66BB6A.toInt()
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    private val basePaint = Paint().apply {
        color = 0x55FFFFFF
        strokeWidth = 1f
    }

    private val path = Path()

    fun push(value: Float, max: Float) {
        if (count < MAX_POINTS) {
            values[count] = value
            count++
        } else {
            System.arraycopy(values, 1, values, 0, MAX_POINTS - 1)
            values[MAX_POINTS - 1] = value
        }
        maxValue = if (max > 1f) max else 60f
        invalidate()
    }

    fun clear() {
        count = 0
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((160 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((36 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(0f, h - 1f, w, h - 1f, basePaint)
        if (count < 2) return

        path.reset()
        val step = w / (MAX_POINTS - 1)
        val offset = (MAX_POINTS - count) * step
        for (i in 0 until count) {
            val ratio = (values[i] / maxValue).coerceIn(0f, 1f)
            val x = offset + i * step
            val y = h - 2f - ratio * (h - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    companion object {
        private const val MAX_POINTS = 60
    }
}
