package com.rendimiento.monitor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Dibujos del tema estelar: marcos con esquinas cortadas y borde neon, botones,
 * casillas, barras de los deslizadores y pestanas. Todo se dibuja en codigo, sin imagenes.
 */

/** Recorta las 4 esquinas de un rectangulo (cortes grandes arriba-izquierda y abajo-derecha). */
internal fun chamferPath(path: Path, l: Float, t: Float, r: Float, b: Float, big: Float, small: Float) {
    val maxCut = minOf(r - l, b - t) / 2f
    val cb = big.coerceAtMost(maxCut)
    val cs = small.coerceAtMost(maxCut)
    path.reset()
    path.moveTo(l + cb, t)
    path.lineTo(r - cs, t)
    path.lineTo(r, t + cs)
    path.lineTo(r, b - cb)
    path.lineTo(r - cb, b)
    path.lineTo(l + cs, b)
    path.lineTo(l, b - cs)
    path.lineTo(l, t + cb)
    path.close()
}

/** Marco de tarjeta: relleno de vidrio oscuro, borde con degradado y brillo, adornos en las esquinas. */
class HudFrameDrawable(
    private val density: Float,
    var strokeStart: Int = Hud.CYAN,
    var strokeEnd: Int = Hud.PURPLE,
    var fillTop: Int = 0xC0101848.toInt(),
    var fillBottom: Int = 0xD0070B2C.toInt(),
    var cutBigDp: Float = 14f,
    var cutSmallDp: Float = 6f,
    var strokeDp: Float = 1.5f,
    var glow: Boolean = true,
    var accents: Boolean = true
) : Drawable() {

    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private var l = 0f
    private var t = 0f
    private var r = 0f
    private var b = 0f
    private var alphaAll = 255

    /** Margen que deja el brillo alrededor del borde; las vistas deben sumar este padding. */
    fun margin(): Float = strokeDp * density * 2.6f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    fun rebuild() {
        val bounds = bounds
        val m = margin()
        l = bounds.left + m
        t = bounds.top + m
        r = bounds.right - m
        b = bounds.bottom - m
        if (r <= l || b <= t) return
        chamferPath(path, l, t, r, b, cutBigDp * density, cutSmallDp * density)
        fill.shader = LinearGradient(0f, t, 0f, b, fillTop, fillBottom, Shader.TileMode.CLAMP)
        val g = LinearGradient(l, t, r, b, strokeStart, strokeEnd, Shader.TileMode.CLAMP)
        stroke.shader = g
        glowPaint.shader = g
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (r <= l || b <= t) return
        fill.alpha = alphaAll
        canvas.drawPath(path, fill)
        val sw = strokeDp * density
        if (glow) {
            glowPaint.strokeWidth = sw * 5f
            glowPaint.alpha = 22 * alphaAll / 255
            canvas.drawPath(path, glowPaint)
            glowPaint.strokeWidth = sw * 2.6f
            glowPaint.alpha = 55 * alphaAll / 255
            canvas.drawPath(path, glowPaint)
        }
        stroke.strokeWidth = sw
        stroke.alpha = alphaAll
        canvas.drawPath(path, stroke)
        if (accents) drawAccents(canvas, sw)
    }

    private fun drawAccents(canvas: Canvas, sw: Float) {
        val d = density
        val cb = (cutBigDp * d).coerceAtMost(minOf(r - l, b - t) / 2f)
        accent.strokeWidth = sw * 2.4f
        // Segmento brillante arriba, junto al corte grande.
        accent.color = strokeStart
        accent.alpha = alphaAll
        val len = minOf(46f * d, (r - l) * 0.25f)
        canvas.drawLine(l + cb + 4f * d, t, l + cb + 4f * d + len, t, accent)
        // Segmento abajo a la derecha.
        accent.color = strokeEnd
        accent.alpha = alphaAll
        canvas.drawLine(r - cb - 4f * d - len, b, r - cb - 4f * d, b, accent)
        // Marcas inclinadas arriba a la derecha (////).
        accent.strokeWidth = sw
        accent.color = strokeStart
        accent.alpha = 150 * alphaAll / 255
        val x0 = r - (cutSmallDp + 34f) * d
        if (x0 > l + cb + len + 12f * d) {
            for (i in 0 until 4) {
                val x = x0 + i * 5f * d
                canvas.drawLine(x, t + 3f * d, x + 3f * d, t + 0.5f * d, accent)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaAll = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Boton neon con extremos en angulo, doble borde y estado presionado/desactivado. */
class NeonButtonDrawable(
    private val density: Float,
    private val primary: Boolean = true,
    private val colorA: Int = Hud.CYAN,
    private val colorB: Int = if (primary) Hud.PURPLE else Hud.CYAN
) : Drawable() {

    private val path = Path()
    private val inner = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val deco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var pressed = false
    private var enabled = true
    private var selectedState = false
    private var l = 0f
    private var t = 0f
    private var r = 0f
    private var b = 0f

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        val e = state.contains(android.R.attr.state_enabled)
        val s = state.contains(android.R.attr.state_selected) || state.contains(android.R.attr.state_checked)
        if (p != pressed || e != enabled || s != selectedState) {
            pressed = p
            enabled = e
            selectedState = s
            invalidateSelf()
            return true
        }
        return false
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val m = 3f * density
        l = bounds.left + m
        t = bounds.top + m
        r = bounds.right - m
        b = bounds.bottom - m
        if (r <= l || b <= t) return
        val h = b - t
        val cut = h * 0.32f
        path.reset()
        path.moveTo(l + cut, t)
        path.lineTo(r - cut, t)
        path.lineTo(r, t + cut)
        path.lineTo(r, b - cut)
        path.lineTo(r - cut, b)
        path.lineTo(l + cut, b)
        path.lineTo(l, b - cut)
        path.lineTo(l, t + cut)
        path.close()
        val i = 3.2f * density
        val ic = cut * 0.75f
        inner.reset()
        inner.moveTo(l + i + ic, t + i)
        inner.lineTo(r - i - ic, t + i)
        inner.lineTo(r - i, t + i + ic)
        inner.lineTo(r - i, b - i - ic)
        inner.lineTo(r - i - ic, b - i)
        inner.lineTo(l + i + ic, b - i)
        inner.lineTo(l + i, b - i - ic)
        inner.lineTo(l + i, t + i + ic)
        inner.close()
        val g = LinearGradient(l, t, r, b, colorA, colorB, Shader.TileMode.CLAMP)
        stroke.shader = g
        thin.shader = g
    }

    override fun draw(canvas: Canvas) {
        if (r <= l || b <= t) return
        val a = if (enabled) 255 else 110
        val active = pressed || selectedState
        fill.shader = LinearGradient(
            0f, t, 0f, b,
            Hud.alpha(if (active) colorA else 0xFF101A50.toInt(), if (active) 120 else 210),
            Hud.alpha(if (active) colorB else 0xFF070B2C.toInt(), if (active) 80 else 220),
            Shader.TileMode.CLAMP
        )
        fill.alpha = a
        canvas.drawPath(path, fill)
        val sw = 1.6f * density
        stroke.strokeWidth = sw * 4f
        stroke.alpha = (if (active) 70 else 30) * a / 255
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = sw
        stroke.alpha = a
        canvas.drawPath(path, stroke)
        thin.strokeWidth = 0.8f * density
        thin.alpha = 90 * a / 255
        canvas.drawPath(inner, thin)
        // Adornos: rayitas al centro arriba y lineas cortas en los extremos.
        deco.strokeWidth = 1.2f * density
        deco.color = colorA
        deco.alpha = 170 * a / 255
        val cx = (l + r) / 2f
        val d = density
        for (k in -3..3) {
            val x = cx + k * 5f * d
            canvas.drawLine(x - 1.5f * d, t + 2.5f * d, x + 1.5f * d, t + 0.8f * d, deco)
        }
        val mid = (t + b) / 2f
        deco.strokeWidth = 2f * density
        canvas.drawLine(l + 7f * d, mid - 5f * d, l + 7f * d, mid + 5f * d, deco)
        deco.color = colorB
        deco.alpha = 170 * a / 255
        canvas.drawLine(r - 7f * d, mid - 5f * d, r - 7f * d, mid + 5f * d, deco)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Casilla de verificacion neon (se usa como buttonDrawable de un CheckBox). */
class NeonCheckDrawable(private val density: Float) : Drawable() {

    private var checked = false
    private var enabled = true
    private val box = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val check = Path()

    override fun getIntrinsicWidth(): Int = (26 * density).toInt()

    override fun getIntrinsicHeight(): Int = (26 * density).toInt()

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked)
        val e = state.contains(android.R.attr.state_enabled)
        if (c != checked || e != enabled) {
            checked = c
            enabled = e
            invalidateSelf()
            return true
        }
        return false
    }

    override fun draw(canvas: Canvas) {
        val bnd = bounds
        val size = 18f * density
        val cx = bnd.exactCenterX()
        val cy = bnd.exactCenterY()
        box.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val rad = 3.5f * density
        val a = if (enabled) 255 else 110
        if (checked) {
            fill.color = Hud.alpha(Hud.CYAN, 60 * a / 255)
            canvas.drawRoundRect(box, rad, rad, fill)
        }
        stroke.color = Hud.CYAN
        stroke.strokeWidth = 4.5f * density
        stroke.alpha = (if (checked) 60 else 30) * a / 255
        canvas.drawRoundRect(box, rad, rad, stroke)
        stroke.strokeWidth = 1.6f * density
        stroke.alpha = a
        canvas.drawRoundRect(box, rad, rad, stroke)
        if (checked) {
            check.reset()
            check.moveTo(box.left + size * 0.22f, box.top + size * 0.52f)
            check.lineTo(box.left + size * 0.43f, box.top + size * 0.72f)
            check.lineTo(box.left + size * 0.80f, box.top + size * 0.30f)
            mark.color = Hud.CYAN
            mark.strokeWidth = 4.2f * density
            mark.alpha = 70 * a / 255
            canvas.drawPath(check, mark)
            mark.color = 0xFFE9FDFF.toInt()
            mark.strokeWidth = 2.2f * density
            mark.alpha = a
            canvas.drawPath(check, mark)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Linea horizontal centrada (pista y progreso de los deslizadores). */
class LineDrawable(
    private val density: Float,
    private val colorA: Int,
    private val colorB: Int,
    private val thicknessDp: Float,
    private val glow: Boolean
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        paint.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f, colorA, colorB, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val bnd = bounds
        val y = bnd.exactCenterY()
        val pad = thicknessDp * density
        val x0 = bnd.left + pad
        val x1 = bnd.right - pad
        if (x1 <= x0) return
        if (glow) {
            paint.strokeWidth = thicknessDp * density * 3.2f
            paint.alpha = 50
            canvas.drawLine(x0, y, x1, y, paint)
        }
        paint.strokeWidth = thicknessDp * density
        paint.alpha = 255
        canvas.drawLine(x0, y, x1, y, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Perilla del deslizador: circulo cian con brillo y centro blanco. */
class NeonThumbDrawable(private val density: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth(): Int = (26 * density).toInt()

    override fun getIntrinsicHeight(): Int = (26 * density).toInt()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val d = density
        paint.style = Paint.Style.FILL
        paint.color = Hud.alpha(Hud.CYAN, 45)
        canvas.drawCircle(cx, cy, 12f * d, paint)
        paint.color = Hud.alpha(Hud.CYAN, 90)
        canvas.drawCircle(cx, cy, 9.5f * d, paint)
        paint.color = Hud.CYAN
        canvas.drawCircle(cx, cy, 7.5f * d, paint)
        paint.color = 0xFFF2FEFF.toInt()
        canvas.drawCircle(cx, cy, 3.6f * d, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Fondo de pestana o segmento: borde fino y relleno cian cuando esta seleccionada. */
class TabDrawable(private val density: Float) : Drawable() {

    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var selectedState = false
    private var pressed = false

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val s = state.contains(android.R.attr.state_selected)
        val p = state.contains(android.R.attr.state_pressed)
        if (s != selectedState || p != pressed) {
            selectedState = s
            pressed = p
            invalidateSelf()
            return true
        }
        return false
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val m = 2f * density
        val l = bounds.left + m
        val t = bounds.top + m
        val r = bounds.right - m
        val b = bounds.bottom - m
        if (r <= l || b <= t) return
        chamferPath(path, l, t, r, b, 7f * density, 3f * density)
        stroke.shader = LinearGradient(l, t, r, b, Hud.CYAN, Hud.PURPLE, Shader.TileMode.CLAMP)
        fill.shader = LinearGradient(
            l, t, r, b, Hud.alpha(Hud.CYAN, 95), Hud.alpha(Hud.PURPLE, 70), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (selectedState || pressed) {
            fill.alpha = if (selectedState) 255 else 130
            canvas.drawPath(path, fill)
        }
        stroke.strokeWidth = (if (selectedState) 1.6f else 1f) * density
        stroke.alpha = if (selectedState) 255 else 120
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
