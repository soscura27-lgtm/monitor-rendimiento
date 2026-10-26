package com.rendimiento.monitor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View

/** Historial que necesitan las vistas de la barra (graficas de FPS y ping). */
class OverlayState {
    var snap: Snapshot = Snapshot()
    val fpsHist = FloatArray(HIST)
    var fpsCount = 0
    val pingHist = FloatArray(PING_HIST)
    var pingCount = 0
    var maxHz = 60f

    fun push(s: Snapshot) {
        snap = s
        if (!s.refreshHz.isNaN()) maxHz = maxOf(60f, s.refreshHz)
        if (!s.fps.isNaN() && s.shellOk) add(fpsHist, s.fps, fpsCount).also { fpsCount = it }
        val p = s.ping.lastMs
        if (!p.isNaN()) add(pingHist, p, pingCount).also { pingCount = it }
    }

    fun clearFps() {
        fpsCount = 0
    }

    private fun add(arr: FloatArray, v: Float, count: Int): Int {
        return if (count < arr.size) {
            arr[count] = v
            count + 1
        } else {
            System.arraycopy(arr, 1, arr, 0, arr.size - 1)
            arr[arr.size - 1] = v
            count
        }
    }

    companion object {
        const val HIST = 60
        const val PING_HIST = 30
    }
}

/** Opciones de la barra leidas de las preferencias. */
class HudConfig(p: SharedPreferences) {
    val scale: Float = p.getInt(Prefs.TEXT_SIZE, 12).coerceIn(9, 20) / 12f
    val opacity: Int = p.getInt(Prefs.OPACITY, 70).coerceIn(10, 100)
    val showFps = p.getBoolean(Prefs.SHOW_FPS, true)
    val showGraph = p.getBoolean(Prefs.SHOW_GRAPH, true)
    val showPing = p.getBoolean(Prefs.SHOW_PING, true)
    val showApps = p.getBoolean(Prefs.SHOW_APPS, true)
    val showCpu = p.getBoolean(Prefs.SHOW_CPU, true)
    val showGpu = p.getBoolean(Prefs.SHOW_GPU, true)
    val showRam = p.getBoolean(Prefs.SHOW_RAM, true)
    val showBat = p.getBoolean(Prefs.SHOW_BAT, true)
    val showTemp = p.getBoolean(Prefs.SHOW_TEMP, true)
}

/** Herramientas de dibujo comunes: tarjetas de vidrio, iconos, barras y textos neon. */
abstract class HudBaseView(context: Context) : View(context) {

    protected val density = context.resources.displayMetrics.density
    protected var cfg: HudConfig = HudConfig(Prefs.of(context))
    protected var st: OverlayState = OverlayState()
    protected var scale = cfg.scale

    protected val fTitle: Typeface = Hud.title(context)
    protected val fDisplay: Typeface = Hud.display(context)
    protected val fBody: Typeface = Hud.bodySemi(context)
    protected val fBold: Typeface = Hud.bodyBold(context)

    protected val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val path = Path()
    protected val rect = RectF()

    private val icons = HashMap<Int, Drawable?>()
    private val appIcons = HashMap<String, Drawable?>()

    /** Recibe el estado nuevo; si cambia el tamano se vuelve a medir. */
    fun update(state: OverlayState, config: HudConfig) {
        st = state
        val sizeChanged = config.scale != cfg.scale || config.showFps != cfg.showFps ||
            config.showPing != cfg.showPing || config.showApps != cfg.showApps || config.showCpu != cfg.showCpu ||
            config.showGpu != cfg.showGpu || config.showRam != cfg.showRam || config.showBat != cfg.showBat ||
            config.showTemp != cfg.showTemp || config.showGraph != cfg.showGraph
        cfg = config
        scale = config.scale
        if (sizeChanged || needsRelayout()) requestLayout()
        invalidate()
    }

    /** Las subclases devuelven true si el contenido cambio de ancho (texto mas largo, etc.). */
    protected open fun needsRelayout(): Boolean = false

    protected fun u(v: Float): Float = v * density * scale

    protected fun glassAlpha(): Int = (cfg.opacity * 2.4f).toInt().coerceIn(20, 245)

    protected fun setText(tf: Typeface, sizeU: Float, color: Int, glow: Boolean = false) {
        text.shader = null
        text.typeface = tf
        text.textSize = u(sizeU)
        text.color = color
        if (glow) text.setShadowLayer(u(5f), 0f, 0f, Hud.alpha(color, 170))
        else text.setShadowLayer(u(1.6f), 0f, 0f, 0xAA000000.toInt())
    }

    /** Recorta el texto con "…" para que quepa en [maxW]. */
    protected fun fit(s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 0 && text.measureText(s, 0, end) + text.measureText("…") > maxW) end--
        return if (end <= 0) "" else s.substring(0, end).trimEnd() + "…"
    }

    /** Tarjeta de vidrio con esquinas cortadas y borde neon. */
    protected fun card(
        c: Canvas, l: Float, t: Float, r: Float, b: Float,
        cutBig: Float, cutSmall: Float,
        colorA: Int = Hud.CYAN, colorB: Int = Hud.PURPLE,
        fillScale: Float = 1f, glow: Boolean = true
    ) {
        chamferPath(path, l, t, r, b, u(cutBig), u(cutSmall))
        val a = (glassAlpha() * fillScale).toInt().coerceIn(0, 255)
        fill.shader = LinearGradient(
            0f, t, 0f, b, Hud.alpha(0xFF141C55.toInt(), a), Hud.alpha(0xFF060A26.toInt(), (a * 1.08f).toInt().coerceAtMost(255)),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, fill)
        fill.shader = null
        val g = LinearGradient(l, t, r, b, colorA, colorB, Shader.TileMode.CLAMP)
        stroke.shader = g
        if (glow) {
            stroke.strokeWidth = u(4.2f)
            stroke.alpha = 32
            c.drawPath(path, stroke)
        }
        stroke.strokeWidth = maxOf(1f, u(1.2f))
        stroke.alpha = 235
        c.drawPath(path, stroke)
        stroke.shader = null
        stroke.alpha = 255
    }

    protected fun icon(c: Canvas, res: Int, x: Float, y: Float, size: Float, color: Int) {
        val d = icons.getOrPut(res) {
            try {
                context.getDrawable(res)?.mutate()
            } catch (e: Throwable) {
                null
            }
        } ?: return
        d.setTint(color)
        d.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        d.draw(c)
    }

    protected fun appIcon(c: Canvas, pkg: String, x: Float, y: Float, size: Float): Boolean {
        val d = appIcons.getOrPut(pkg) {
            try {
                AppInfoCache.icon(context, pkg)?.mutate()
            } catch (e: Throwable) {
                null
            }
        } ?: return false
        d.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        d.draw(c)
        return true
    }

    /** Barra con degradado y marcador tipo "reloj de arena" en el valor. */
    protected fun bar(c: Canvas, l: Float, cy: Float, r: Float, h: Float, frac: Float, colorA: Int, colorB: Int) {
        val rad = h / 2f
        rect.set(l, cy - h / 2f, r, cy + h / 2f)
        fill.shader = null
        fill.color = Hud.alpha(Hud.TRACK, 230)
        c.drawRoundRect(rect, rad, rad, fill)
        stroke.color = Hud.alpha(Hud.CYAN, 70)
        stroke.strokeWidth = maxOf(1f, u(0.8f))
        c.drawRoundRect(rect, rad, rad, stroke)
        val f = if (frac.isNaN()) 0f else frac.coerceIn(0f, 1f)
        if (f > 0f) {
            val x = l + (r - l) * f
            rect.set(l, cy - h / 2f, maxOf(x, l + h), cy + h / 2f)
            fill.shader = LinearGradient(l, 0f, r, 0f, colorA, colorB, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rad, rad, fill)
            fill.shader = null
            marker(c, x, cy, h * 2.6f)
        }
    }

    /** Marcador del valor (dos triangulos unidos, como en el diseno). */
    protected fun marker(c: Canvas, x: Float, cy: Float, h: Float) {
        val w = h * 0.42f
        path.reset()
        path.moveTo(x - w / 2f, cy - h / 2f)
        path.lineTo(x + w / 2f, cy - h / 2f)
        path.lineTo(x + w * 0.12f, cy)
        path.lineTo(x + w / 2f, cy + h / 2f)
        path.lineTo(x - w / 2f, cy + h / 2f)
        path.lineTo(x - w * 0.12f, cy)
        path.close()
        fill.color = 0xFFDDE7FF.toInt()
        c.drawPath(path, fill)
        stroke.color = Hud.alpha(Hud.CYAN, 200)
        stroke.strokeWidth = maxOf(1f, u(0.8f))
        c.drawPath(path, stroke)
    }

    /** Barra de segmentos (RAM), coloreados de cian a verde y magenta. */
    protected fun segBar(c: Canvas, l: Float, cy: Float, r: Float, h: Float, frac: Float) {
        val n = 18
        val gap = u(1.6f)
        val w = (r - l - gap * (n - 1)) / n
        val f = if (frac.isNaN()) 0f else frac.coerceIn(0f, 1f)
        val on = Math.round(f * n)
        rect.set(l - u(2f), cy - h / 2f - u(2f), r + u(2f), cy + h / 2f + u(2f))
        fill.shader = null
        fill.color = Hud.alpha(0xFF0A1036.toInt(), 220)
        c.drawRoundRect(rect, u(2f), u(2f), fill)
        for (i in 0 until n) {
            val x = l + i * (w + gap)
            rect.set(x, cy - h / 2f, x + w, cy + h / 2f)
            if (i < on) {
                val t = i / (n - 1f)
                fill.color = if (t < 0.5f) Hud.mix(Hud.CYAN, Hud.GREEN, t * 2f) else Hud.mix(Hud.GREEN, Hud.MAGENTA, (t - 0.5f) * 2f)
            } else {
                fill.color = Hud.alpha(0xFF26305F.toInt(), 200)
            }
            c.drawRect(rect, fill)
        }
        if (f > 0f) marker(c, l + (r - l) * f, cy, h * 1.7f)
    }

    /** Grafica pequena (area con degradado) de los ultimos valores. */
    protected fun sparkline(
        c: Canvas, l: Float, t: Float, r: Float, b: Float,
        values: FloatArray, count: Int, max: Float, color: Int, area: Boolean
    ) {
        if (count < 2) {
            stroke.color = Hud.alpha(color, 70)
            stroke.strokeWidth = maxOf(1f, u(1f))
            c.drawLine(l, b - 1f, r, b - 1f, stroke)
            return
        }
        val n = values.size
        val step = (r - l) / (n - 1)
        val off = (n - count) * step
        path.reset()
        var lastX = l
        var lastY = b
        for (i in 0 until count) {
            val ratio = (values[i] / max).coerceIn(0f, 1f)
            val x = l + off + i * step
            val y = b - u(1f) - ratio * (b - t - u(2f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        if (area) {
            val fillPath = Path(path)
            fillPath.lineTo(lastX, b)
            fillPath.lineTo(l + off, b)
            fillPath.close()
            fill.shader = LinearGradient(0f, t, 0f, b, Hud.alpha(color, 120), Hud.alpha(color, 0), Shader.TileMode.CLAMP)
            c.drawPath(fillPath, fill)
            fill.shader = null
        }
        stroke.shader = null
        stroke.color = Hud.alpha(color, 60)
        stroke.strokeWidth = u(3.2f)
        stroke.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, stroke)
        stroke.color = color
        stroke.strokeWidth = maxOf(1f, u(1.4f))
        c.drawPath(path, stroke)
        fill.color = Color.WHITE
        c.drawCircle(lastX, lastY, u(2f), fill)
    }

    /** Bateria vertical dibujada con su nivel. */
    protected fun battery(c: Canvas, x: Float, y: Float, w: Float, h: Float, pct: Int, charging: Boolean) {
        val cap = h * 0.08f
        rect.set(x + w * 0.32f, y, x + w * 0.68f, y + cap)
        fill.shader = null
        fill.color = Hud.alpha(Hud.CYAN_SOFT, 220)
        c.drawRoundRect(rect, u(1f), u(1f), fill)
        rect.set(x, y + cap, x + w, y + h)
        stroke.color = Hud.CYAN_SOFT
        stroke.strokeWidth = maxOf(1f, u(1.4f))
        c.drawRoundRect(rect, u(3f), u(3f), stroke)
        val inner = u(2.6f)
        val f = if (pct < 0) 0f else (pct / 100f).coerceIn(0f, 1f)
        val top = y + cap + inner + (h - cap - inner * 2) * (1f - f)
        rect.set(x + inner, top, x + w - inner, y + h - inner)
        val low = pct in 0..19
        fill.shader = LinearGradient(
            0f, top, 0f, y + h,
            if (low) Hud.RED else Hud.MAGENTA, if (low) Hud.ORANGE else Hud.CYAN, Shader.TileMode.CLAMP
        )
        if (f > 0f) c.drawRoundRect(rect, u(1.5f), u(1.5f), fill)
        fill.shader = null
        if (charging) icon(c, R.drawable.ic_bolt, x + w * 0.12f, y + h * 0.3f, w * 0.76f, Color.WHITE)
    }
}

/**
 * Panel completo "Sistema de monitoreo estelar" (como la imagen de referencia): tarjetas de FPS
 * y ping, barras de CPU/GPU/RAM, bateria, estado termico, app que mas RAM usa y grafica de FPS.
 */
class StellarPanelView(context: Context) : HudBaseView(context) {

    /** En la pantalla principal no se muestra el subtitulo del juego. */
    var subtitle: String = "v" + BuildInfo.VERSION + " // com.rendimiento.monitor"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val w = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else u(BASE_W).toInt()
        setMeasuredDimension(w, layoutHeight(w.toFloat()).toInt())
    }

    private fun showRow1() = cfg.showFps || cfg.showPing
    private fun rowsMid(): Int = (if (cfg.showCpu) 1 else 0) + (if (cfg.showGpu) 1 else 0) + (if (cfg.showRam) 1 else 0)
    private fun showRow4() = cfg.showTemp || cfg.showApps
    private fun showGraphRow() = cfg.showFps && cfg.showGraph

    private fun layoutHeight(w: Float): Float {
        var h = u(PAD) + u(HEADER_H)
        if (showRow1()) h += u(ROW1_H) + u(GAP)
        val mid = rowsMid()
        if (mid > 0) h += u(MID_ROW_H) * mid + u(12f) + u(GAP)
        if (cfg.showBat) h += u(BAT_H) + u(GAP)
        if (showRow4()) h += u(ROW4_H) + u(GAP)
        if (showGraphRow()) h += u(GRAPH_H) + u(GAP)
        return h - u(GAP) + u(PAD) + u(4f)
    }

    override fun onDraw(canvas: Canvas) {
        val s = st.snap
        val w = width.toFloat()
        val h = height.toFloat()
        val m = u(3f)

        // Marco exterior
        card(canvas, m, m, w - m, h - m, 16f, 7f, Hud.CYAN, Hud.PURPLE, 1f, true)
        decorFrame(canvas, m, m, w - m, h - m)

        val l = u(PAD)
        val r = w - u(PAD)
        var y = u(PAD) - u(2f)

        // Encabezado
        setText(fTitle, 12.2f, Color.WHITE, true)
        text.textAlign = Paint.Align.CENTER
        val title = fit("SISTEMA DE MONITOREO ESTELAR", r - l)
        text.shader = LinearGradient(l, 0f, r, 0f, 0xFFE8F4FF.toInt(), Hud.CYAN_SOFT, Shader.TileMode.CLAMP)
        canvas.drawText(title, w / 2f, y + u(14f), text)
        text.shader = null
        setText(fBody, 9.6f, Hud.MUTED)
        text.textAlign = Paint.Align.CENTER
        val game = s.pkg
        val sub = if (game != null && game != context.packageName && s.shellOk) {
            val t = if (s.sessionMs >= 60000L) "  ·  " + Hud.duration(s.sessionMs) else ""
            "▶ " + s.appLabel + t
        } else {
            subtitle
        }
        canvas.drawText(fit(sub, r - l), w / 2f, y + u(27f), text)
        text.textAlign = Paint.Align.LEFT
        y += u(HEADER_H)

        // Fila 1: FPS y ping
        if (showRow1()) {
            val gap = u(8f)
            if (cfg.showFps && cfg.showPing) {
                val mid = (l + r) / 2f
                fpsCard(canvas, s, l, y, mid - gap / 2f, y + u(ROW1_H))
                pingCard(canvas, s, mid + gap / 2f, y, r, y + u(ROW1_H))
            } else if (cfg.showFps) {
                fpsCard(canvas, s, l, y, r, y + u(ROW1_H))
            } else {
                pingCard(canvas, s, l, y, r, y + u(ROW1_H))
            }
            y += u(ROW1_H) + u(GAP)
        }

        // Fila 2: CPU, GPU, RAM
        val mid = rowsMid()
        if (mid > 0) {
            val bh = u(MID_ROW_H) * mid + u(12f)
            card(canvas, l, y, r, y + bh, 10f, 4f, Hud.CYAN, Hud.PURPLE, 0.9f, false)
            var ry = y + u(6f)
            if (cfg.showCpu) {
                cpuRow(canvas, s, l, ry, r)
                ry += u(MID_ROW_H)
            }
            if (cfg.showGpu) {
                gpuRow(canvas, s, l, ry, r)
                ry += u(MID_ROW_H)
            }
            if (cfg.showRam) ramRow(canvas, s, l, ry, r)
            y += bh + u(GAP)
        }

        // Fila 3: bateria
        if (cfg.showBat) {
            batteryCard(canvas, s, l, y, r, y + u(BAT_H))
            y += u(BAT_H) + u(GAP)
        }

        // Fila 4: estado termico + app que mas RAM usa
        if (showRow4()) {
            val gap = u(8f)
            val b = y + u(ROW4_H)
            if (cfg.showTemp && cfg.showApps) {
                val mid = (l + r) / 2f
                thermalBadge(canvas, s, l, y, mid - gap / 2f, b)
                appCard(canvas, s, mid + gap / 2f, y, r, b)
            } else if (cfg.showTemp) {
                thermalBadge(canvas, s, l, y, r, b)
            } else {
                appCard(canvas, s, l, y, r, b)
            }
            y += u(ROW4_H) + u(GAP)
        }

        // Fila 4: grafica de FPS
        if (showGraphRow()) graphCard(canvas, s, l, y, r, y + u(GRAPH_H))
    }

    private fun decorFrame(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        // Pestana inferior central y clips laterales, como el marco de la imagen.
        val cx = (l + r) / 2f
        path.reset()
        path.moveTo(cx - u(48f), b)
        path.lineTo(cx - u(40f), b - u(5f))
        path.lineTo(cx + u(40f), b - u(5f))
        path.lineTo(cx + u(48f), b)
        stroke.color = Hud.alpha(Hud.CYAN, 200)
        stroke.strokeWidth = maxOf(1f, u(1.2f))
        c.drawPath(path, stroke)
        fill.shader = null
        fill.color = Hud.alpha(Hud.CYAN, 150)
        val cy = (t + b) / 2f
        rect.set(l - u(1.5f), cy - u(16f), l + u(1.5f), cy + u(16f))
        c.drawRect(rect, fill)
        rect.set(r - u(1.5f), cy - u(16f), r + u(1.5f), cy + u(16f))
        c.drawRect(rect, fill)
    }

    private fun fpsCard(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float, b: Float) {
        card(c, l, t, r, b, 9f, 4f, Hud.CYAN, Hud.BLUE, 0.9f, false)
        val isz = u(30f)
        icon(c, R.drawable.ic_gamepad, l + u(8f), (t + b) / 2f - isz / 2f, isz, Hud.CYAN_SOFT)
        val x = l + u(44f)
        val maxW = r - x - u(6f)
        setText(fBold, 12.5f, Hud.TEXT)
        val label = "FPS "
        c.drawText(label, x, t + u(22f), text)
        val lw = text.measureText(label)
        val noData = s.fps.isNaN() || !s.shellOk
        if (noData) {
            setText(fBold, 10.5f, Hud.CYAN_SOFT)
            val why = if (!s.shellOk) "SIN SHIZUKU" else s.fpsStatus.uppercase()
            c.drawText(fit(why, maxW - lw), x + lw, t + u(21f), text)
        } else {
            setText(fTitle, 17f, Hud.fpsColor(s.fps), true)
            c.drawText(Hud.fmt(s.fps, 0), x + lw, t + u(23f), text)
        }
        // Linea 2: Hz y ms por fotograma
        setText(fBold, 11.5f, Hud.TEXT)
        var lx = x
        c.drawText("Hz", lx, t + u(42f), text)
        lx += text.measureText("Hz") + u(3f)
        icon(c, R.drawable.ic_refresh, lx, t + u(32.5f), u(11f), Hud.CYAN_SOFT)
        lx += u(13f)
        setText(fDisplay, 11.5f, Hud.TEXT)
        val hz = if (s.refreshHz.isNaN()) "—" else Hud.fmt(s.refreshHz, 0)
        c.drawText(hz, lx, t + u(42.5f), text)
        lx += text.measureText(hz) + u(5f)
        if (!s.frameMs.isNaN()) {
            setText(fBody, 10f, Hud.MUTED)
            val ms = Hud.fmt(s.frameMs, 1) + " ms"
            val withLow = if (!s.low1.isNaN()) "$ms · 1% " + Hud.fmt(s.low1, 0) else ms
            val txt = if (text.measureText(withLow) <= r - lx - u(5f)) withLow else ms
            c.drawText(fit(txt, r - lx - u(5f)), lx, t + u(42f), text)
        }
    }

    private fun pingCard(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float, b: Float) {
        card(c, l, t, r, b, 4f, 9f, Hud.BLUE, Hud.PURPLE, 0.9f, false)
        val isz = u(30f)
        icon(c, R.drawable.ic_satellite, l + u(8f), (t + b) / 2f - isz / 2f, isz, Hud.CYAN_SOFT)
        val x = l + u(44f)
        val p = s.ping
        setText(fBold, 12.5f, Hud.TEXT)
        val label = "PING "
        c.drawText(label, x, t + u(22f), text)
        var ex = x + text.measureText(label)
        if (p.lastMs.isNaN()) {
            // Sin cifra todavia: icono de pulso y el motivo en la segunda linea (como el diseno).
            icon(c, R.drawable.ic_pulse, ex, t + u(11.5f), u(13f), Hud.CYAN_SOFT)
            setText(fBold, 10f, Hud.CYAN_SOFT)
            val why = when {
                p.status.isNotEmpty() && p.target != null -> p.status
                p.target == null -> "Buscando servidor"
                else -> "Midiendo…"
            }.uppercase()
            c.drawText(fit(why, r - x - u(6f)), x, t + u(37f), text)
            if (p.target != null) {
                setText(fBody, 9.5f, Hud.MUTED)
                c.drawText(fit(p.label().uppercase(), r - x - u(6f)), x, t + u(49f), text)
            }
            return
        }
        setText(fTitle, 15.5f, Hud.pingColor(p.lastMs), true)
        val v = Hud.fmt(p.lastMs, 0)
        c.drawText(v, ex, t + u(23f), text)
        ex += text.measureText(v)
        setText(fBold, 10f, Hud.MUTED)
        c.drawText(" ms", ex, t + u(22f), text)
        ex += text.measureText(" ms") + u(6f)
        // Mini grafica de pulso arriba a la derecha (solo si cabe).
        val gl = maxOf(ex, r - u(44f))
        if (r - u(7f) - gl >= u(18f)) {
            sparkline(c, gl, t + u(10f), r - u(7f), t + u(21f), st.pingHist, st.pingCount,
                maxOf(80f, maxOf1(st.pingHist, st.pingCount) * 1.2f), Hud.alpha(Hud.CYAN_SOFT, 200), false)
        }
        setText(fBody, 10f, Hud.MUTED)
        c.drawText(fit(p.label().uppercase(), r - x - u(6f)), x, t + u(36f), text)
        val parts = ArrayList<String>()
        if (!p.jitterMs.isNaN()) parts.add("jitter " + Hud.fmt(p.jitterMs, 0))
        if (!p.lossPct.isNaN()) parts.add("pérdida " + Hud.fmt(p.lossPct, 0) + "%")
        if (!p.avgMs.isNaN()) parts.add("prom " + Hud.fmt(p.avgMs, 0))
        if (parts.isNotEmpty()) c.drawText(fit(parts.joinToString(" · "), r - x - u(6f)), x, t + u(48f), text)
    }

    private fun maxOf1(arr: FloatArray, n: Int): Float {
        var m = 0f
        for (i in 0 until n) if (arr[i] > m) m = arr[i]
        return m
    }

    private fun rowLayout(l: Float, r: Float): FloatArray {
        // [inicio barra, fin barra, inicio texto]
        val barL = l + u(38f)
        val barR = l + (r - l) * 0.50f
        return floatArrayOf(barL, barR, barR + u(12f))
    }

    private fun cpuRow(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float) {
        val lay = rowLayout(l, r)
        val cy = t + u(MID_ROW_H) / 2f
        icon(c, R.drawable.ic_chip, l + u(8f), cy - u(11f), u(22f), Hud.CYAN_SOFT)
        val frac = when {
            !s.cpuPct.isNaN() -> s.cpuPct / 100f
            s.cpuMhz > 0 && s.cpuMaxMhz > 0 -> s.cpuMhz.toFloat() / s.cpuMaxMhz
            else -> Float.NaN
        }
        bar(c, lay[0], cy, lay[1], u(5f), frac, Hud.PURPLE, Hud.CYAN)
        val main = "CPU " + (if (!s.cpuPct.isNaN()) Hud.fmt(s.cpuPct, 0) + "%" else if (s.cpuMhz > 0) "${s.cpuMhz} MHz" else "—")
        val sub = ArrayList<String>()
        if (!s.cpuPct.isNaN() && s.cpuMhz > 0) sub.add("${s.cpuMhz} MHz")
        rowTexts(c, main, sub.joinToString(" · "), if (cfg.showTemp) s.cpuTemp else Float.NaN, lay[2], cy, r)
    }

    private fun gpuRow(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float) {
        val lay = rowLayout(l, r)
        val cy = t + u(MID_ROW_H) / 2f
        icon(c, R.drawable.ic_gpu, l + u(8f), cy - u(11f), u(22f), Hud.CYAN_SOFT)
        bar(c, lay[0], cy, lay[1], u(5f), if (s.gpuPct.isNaN()) Float.NaN else s.gpuPct / 100f, Hud.PURPLE, Hud.CYAN)
        val main = "GPU " + (if (!s.gpuPct.isNaN()) Hud.fmt(s.gpuPct, 0) + "%" else "—")
        val sub = if (s.gpuMhz > 0) "${s.gpuMhz} MHz" else ""
        rowTexts(c, main, sub, if (cfg.showTemp) s.gpuTemp else Float.NaN, lay[2], cy, r)
    }

    private fun ramRow(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float) {
        val lay = rowLayout(l, r)
        val cy = t + u(MID_ROW_H) / 2f
        icon(c, R.drawable.ic_ram, l + u(8f), cy - u(11f), u(22f), Hud.CYAN_SOFT)
        val frac = if (s.ramTotalMb > 0L) s.ramUsedMb.toFloat() / s.ramTotalMb else Float.NaN
        segBar(c, lay[0], cy, lay[1], u(9f), frac)
        if (s.ramTotalMb > 0L) {
            val main = "RAM " + Hud.fmt(s.ramUsedMb / 1024f, 1) + "/" + Hud.fmt(s.ramTotalMb / 1024f, 1) + " GB"
            rowTexts(c, main, Hud.fmt(frac * 100f, 0) + "% en uso", Float.NaN, lay[2], cy, r)
        } else {
            rowTexts(c, "RAM —", "", Float.NaN, lay[2], cy, r)
        }
    }

    private fun rowTexts(c: Canvas, main: String, sub: String, temp: Float, x: Float, cy: Float, r: Float) {
        setText(fDisplay, 11.5f, Hud.TEXT, true)
        val maxW = r - x - u(4f)
        c.drawText(fit(main, maxW), x, cy - u(1f), text)
        var sx = x
        if (sub.isNotEmpty()) {
            setText(fBody, 10f, Hud.MUTED)
            val sTxt = fit(sub, maxW * 0.62f)
            c.drawText(sTxt, sx, cy + u(11f), text)
            sx += text.measureText(sTxt) + u(8f)
        }
        if (!temp.isNaN()) {
            setText(fBody, 10f, Hud.MUTED)
            c.drawText("Temp:", sx, cy + u(11f), text)
            sx += text.measureText("Temp: ")
            setText(fBold, 10.5f, Hud.tempColor(temp))
            c.drawText(Hud.fmt(temp, 0) + "°C", sx, cy + u(11f), text)
        }
    }

    private fun batteryCard(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float, b: Float) {
        card(c, l, t, r, b, 9f, 4f, Hud.CYAN, Hud.PURPLE, 0.9f, false)
        battery(c, l + u(10f), t + u(7f), u(17f), b - t - u(14f), s.batPct, s.charging)
        val x = l + u(36f)
        setText(fBold, 12f, Hud.TEXT)
        c.drawText("BAT", x, t + u(18f), text)
        if (s.charging) {
            val bw = text.measureText("BAT ")
            setText(fBold, 9.5f, Hud.GREEN)
            c.drawText("CARGANDO", x + bw, t + u(18f), text)
        }
        setText(fTitle, 19f, if (s.batPct in 0..19) Hud.RED else Hud.TEXT, true)
        val pct = if (s.batPct >= 0) s.batPct.toString() + "%" else "—"
        c.drawText(pct, x, t + u(41f), text)
        // Detalles en columna a la derecha.
        val dx = maxOf(x + text.measureText(pct) + u(18f), l + u(118f))
        val lines = ArrayList<Pair<String, Int>>()
        if (cfg.showTemp && !s.batTemp.isNaN()) lines.add(Pair("Temp: " + Hud.fmt(s.batTemp, 1) + "°C", Hud.batTempColor(s.batTemp)))
        if (s.batMa != Int.MIN_VALUE) lines.add(Pair("Corriente: " + kotlin.math.abs(s.batMa) + " mA", Hud.TEXT))
        if (!s.batV.isNaN()) {
            val w = if (!s.batW.isNaN()) "  ·  " + Hud.fmt(s.batW, 1) + " W" else ""
            lines.add(Pair("Voltaje: " + Hud.fmt(s.batV, 2) + " V" + w, Hud.TEXT))
        }
        setText(fBody, 10.5f, Hud.TEXT)
        var ly = t + u(17f)
        for ((txt, col) in lines.take(3)) {
            text.color = col
            c.drawText(fit(txt, r - dx - u(6f)), dx, ly, text)
            ly += u(13f)
        }
    }

    private fun thermalBadge(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float, b: Float) {
        val col = if (s.thermalStatus < 0) Hud.MUTED else Hud.thermalColor(s.thermalStatus)
        rect.set(l, t, r, b)
        fill.shader = null
        fill.color = Hud.alpha(0xFF041A12.toInt(), glassAlpha())
        c.drawRect(rect, fill)
        // Esquinas tipo mira.
        stroke.color = col
        stroke.strokeWidth = maxOf(1f, u(1.6f))
        val k = u(7f)
        c.drawLine(l, t, l + k, t, stroke)
        c.drawLine(l, t, l, t + k, stroke)
        c.drawLine(r, t, r - k, t, stroke)
        c.drawLine(r, t, r, t + k, stroke)
        c.drawLine(l, b, l + k, b, stroke)
        c.drawLine(l, b, l, b - k, stroke)
        c.drawLine(r, b, r - k, b, stroke)
        c.drawLine(r, b, r, b - k, stroke)
        stroke.color = Hud.alpha(col, 90)
        stroke.strokeWidth = maxOf(1f, u(0.8f))
        c.drawRect(rect, stroke)
        val isz = minOf(u(19f), b - t - u(8f))
        val cy = (t + b) / 2f
        icon(c, R.drawable.ic_biohazard, l + u(7f), cy - isz / 2f, isz, col)
        val thW = isz * 0.75f
        icon(c, R.drawable.ic_thermometer, r - u(5f) - thW, cy - thW / 2f, thW, col)
        val tx = l + u(12f) + isz
        val maxW = r - u(8f) - thW - tx
        setText(fBold, 11.5f, col, true)
        val name = Hud.thermalName(s.thermalStatus).uppercase()
        val one = "TÉRMICO: $name"
        if (text.measureText(one) <= maxW) {
            c.drawText(one, tx, cy + u(4f), text)
        } else {
            // No cabe en una linea: etiqueta arriba y estado abajo.
            setText(fBold, 8.5f, Hud.alpha(col, 200))
            c.drawText("TÉRMICO", tx, cy - u(3f), text)
            setText(fBold, 12f, col, true)
            c.drawText(fit(name, maxW), tx, cy + u(10f), text)
        }
    }

    private fun appCard(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float, b: Float) {
        card(c, l, t, r, b, 4f, 8f, Hud.BLUE, Hud.PURPLE, 0.9f, false)
        val top = s.topRam
        val isz = minOf(u(24f), b - t - u(8f))
        val cy = (t + b) / 2f
        var x = l + u(7f)
        if (top != null && appIcon(c, top.pkg, x, cy - isz / 2f, isz)) x += isz + u(6f)
        else {
            icon(c, R.drawable.ic_apps, x, cy - isz / 2f, isz, Hud.CYAN_SOFT)
            x += isz + u(6f)
        }
        setText(fBody, 9f, Hud.MUTED)
        c.drawText(fit("USO DE RAM POR APP:", r - x - u(4f)), x, cy - u(3f), text)
        setText(fBold, 11.5f, Hud.TEXT)
        val line = if (top != null) s.topRamLabel + "  " + Hud.mb(top.ramMb) else if (s.shellOk) "Midiendo…" else "Necesita Shizuku"
        c.drawText(fit(line, r - x - u(4f)), x, cy + u(10f), text)
    }

    private fun graphCard(c: Canvas, s: Snapshot, l: Float, t: Float, r: Float, b: Float) {
        card(c, l, t, r, b, 6f, 6f, Hud.CYAN, Hud.MAGENTA, 0.8f, false)
        val gl = l + u(8f)
        val gr = r - u(8f)
        // Lineas guia
        stroke.color = Hud.alpha(Hud.CYAN, 40)
        stroke.strokeWidth = 1f
        val gt = t + u(14f)
        val gb = b - u(5f)
        c.drawLine(gl, (gt + gb) / 2f, gr, (gt + gb) / 2f, stroke)
        if (st.fpsCount >= 2) {
            sparkline(c, gl, gt, gr, gb, st.fpsHist, st.fpsCount, st.maxHz, Hud.CYAN, true)
        } else {
            setText(fBody, 10f, Hud.DIM)
            text.textAlign = Paint.Align.CENTER
            c.drawText("La gráfica aparece cuando hay FPS de un juego", (gl + gr) / 2f, (gt + gb) / 2f + u(8f), text)
            text.textAlign = Paint.Align.LEFT
        }
        setText(fBold, 9.5f, Hud.MUTED)
        val parts = ArrayList<String>()
        parts.add("FPS")
        if (!s.fpsAvg.isNaN()) parts.add("prom " + Hud.fmt(s.fpsAvg, 0))
        if (!s.fpsMin.isNaN()) parts.add("mín " + Hud.fmt(s.fpsMin, 0))
        if (!s.fpsMax.isNaN()) parts.add("máx " + Hud.fmt(s.fpsMax, 0))
        c.drawText(fit(parts.joinToString("  ·  "), gr - gl), gl, t + u(11.5f), text)
    }

    companion object {
        const val BASE_W = 316f
        private const val PAD = 12f
        private const val GAP = 8f
        private const val HEADER_H = 34f
        private const val ROW1_H = 56f
        private const val MID_ROW_H = 30f
        private const val BAT_H = 52f
        private const val ROW4_H = 40f
        private const val GRAPH_H = 46f
    }
}

/**
 * Barra compacta para jugar: una tira con FPS, ping, CPU, GPU, RAM, bateria, estado termico,
 * app que mas consume y mini grafica. Si no cabe a lo ancho, pasa a varias filas.
 */
class CompactHudView(context: Context) : HudBaseView(context) {

    /** Ancho maximo (lo pone el servicio segun la pantalla). */
    var maxWidthPx: Int = Int.MAX_VALUE

    private class Seg(
        val icon: Int,
        val value: String,
        val valueColor: Int,
        val unit: String,
        val extra: String,
        val extraColor: Int,
        val appPkg: String? = null,
        val graph: Boolean = false,
        /** Valor de texto (nombre de app, estado): se dibuja con la letra normal, no la de numeros. */
        val word: Boolean = false
    )

    private val segs = ArrayList<Seg>()
    private val segW = ArrayList<Float>()
    private var lastSignature = ""

    private fun buildSegments() {
        segs.clear()
        val s = st.snap
        if (cfg.showFps) {
            if (s.fps.isNaN() || !s.shellOk) {
                val why = if (!s.shellOk) "Shizuku" else shortStatus(s.fpsStatus)
                segs.add(Seg(R.drawable.ic_gamepad, "—", Hud.MUTED, "FPS", why, Hud.MUTED))
            } else {
                val ms = if (s.frameMs.isNaN()) "" else Hud.fmt(s.frameMs, 1) + "ms"
                segs.add(Seg(R.drawable.ic_gamepad, Hud.fmt(s.fps, 0), Hud.fpsColor(s.fps), "FPS", ms, Hud.MUTED))
            }
        }
        if (cfg.showPing) {
            val p = s.ping
            if (p.lastMs.isNaN()) {
                segs.add(Seg(R.drawable.ic_satellite, "—", Hud.MUTED, "ms", if (p.target == null) "buscando" else p.shortLabel(), Hud.MUTED))
            } else {
                segs.add(Seg(R.drawable.ic_satellite, Hud.fmt(p.lastMs, 0), Hud.pingColor(p.lastMs), "ms", p.shortLabel(), Hud.DIM))
            }
        }
        // CPU y GPU solo si hay algun dato (sin Shizuku no se pueden leer): ahorra espacio.
        if (cfg.showCpu && (!s.cpuPct.isNaN() || s.cpuMhz > 0 || !s.cpuTemp.isNaN())) {
            val v = if (!s.cpuPct.isNaN()) Hud.fmt(s.cpuPct, 0) else if (s.cpuMhz > 0) s.cpuMhz.toString() else "—"
            val unit = if (!s.cpuPct.isNaN()) "%" else if (s.cpuMhz > 0) "MHz" else ""
            val t = if (cfg.showTemp && !s.cpuTemp.isNaN()) Hud.fmt(s.cpuTemp, 0) + "°" else ""
            segs.add(Seg(R.drawable.ic_chip, v, Hud.loadColor(s.cpuPct), unit, t, Hud.tempColor(s.cpuTemp)))
        }
        if (cfg.showGpu && (!s.gpuPct.isNaN() || s.gpuMhz > 0 || !s.gpuTemp.isNaN())) {
            val v = if (!s.gpuPct.isNaN()) Hud.fmt(s.gpuPct, 0) else if (s.gpuMhz > 0) s.gpuMhz.toString() else "—"
            val unit = if (!s.gpuPct.isNaN()) "%" else if (s.gpuMhz > 0) "MHz" else ""
            val t = if (cfg.showTemp && !s.gpuTemp.isNaN()) Hud.fmt(s.gpuTemp, 0) + "°" else ""
            segs.add(Seg(R.drawable.ic_gpu, v, Hud.loadColor(s.gpuPct), unit, t, Hud.tempColor(s.gpuTemp)))
        }
        if (cfg.showRam) {
            if (s.ramTotalMb > 0L) {
                val pct = s.ramUsedMb * 100f / s.ramTotalMb
                segs.add(Seg(R.drawable.ic_ram, Hud.fmt(pct, 0), Hud.loadColor(pct), "%", Hud.fmt(s.ramUsedMb / 1024f, 1) + "G", Hud.MUTED))
            }
        }
        if (cfg.showBat && s.batPct >= 0) {
            val t = if (cfg.showTemp && !s.batTemp.isNaN()) Hud.fmt(s.batTemp, 0) + "°" else ""
            val ic = if (s.charging) R.drawable.ic_battery_charging else R.drawable.ic_battery
            segs.add(Seg(ic, s.batPct.toString(), if (s.batPct < 20) Hud.RED else Hud.TEXT, "%", t, Hud.batTempColor(s.batTemp)))
        }
        if (cfg.showTemp && s.thermalStatus >= 1) {
            segs.add(Seg(R.drawable.ic_biohazard, Hud.thermalName(s.thermalStatus).uppercase(), Hud.thermalColor(s.thermalStatus), "", "", Hud.MUTED, word = true))
        }
        if (cfg.showApps) {
            val top = s.topRam
            if (top != null) {
                segs.add(Seg(R.drawable.ic_apps, shortLabel(s.topRamLabel), Hud.TEXT, "", Hud.mb(top.ramMb), Hud.MUTED, top.pkg, word = true))
            }
        }
        if (cfg.showFps && cfg.showGraph && s.shellOk) {
            segs.add(Seg(0, "", 0, "", "", 0, null, true))
        }
    }

    private fun shortStatus(s: String): String = when {
        s.startsWith("Abre") -> "abre un juego"
        s.startsWith("Buscando") -> "buscando"
        s.startsWith("Sin fotogramas") -> "sin cuadros"
        s.isEmpty() -> ""
        else -> "espera"
    }

    private fun shortLabel(s: String): String = if (s.length > 12) s.substring(0, 11).trimEnd() + "…" else s

    private fun measureSeg(sg: Seg): Float {
        if (sg.graph) return u(54f)
        var w = u(PAD_X) + u(ICON) + u(4f)
        if (sg.word) setText(fBold, 12.5f, Color.WHITE) else setText(fDisplay, 13f, Color.WHITE)
        w += text.measureText(sg.value)
        if (sg.unit.isNotEmpty()) {
            setText(fBold, 9.5f, Color.WHITE)
            w += u(2f) + text.measureText(sg.unit)
        }
        if (sg.extra.isNotEmpty()) {
            setText(fBold, 10f, Color.WHITE)
            w += u(5f) + text.measureText(sg.extra)
        }
        return w + u(PAD_X)
    }

    private var rows = 1

    override fun needsRelayout(): Boolean {
        buildSegments()
        val sig = StringBuilder()
        for (sg in segs) sig.append(sg.value.length).append(sg.unit).append(sg.extra.length).append(sg.graph).append(',')
        val now = sig.toString()
        val changed = now != lastSignature
        lastSignature = now
        return changed
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        buildSegments()
        segW.clear()
        for (sg in segs) segW.add(measureSeg(sg))
        val maxW = minOf(maxWidthPx, MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED } ?: Int.MAX_VALUE).toFloat() - u(MARGIN * 2)
        var rowsN = 1
        var x = 0f
        var widest = 0f
        for (w in segW) {
            if (x > 0f && x + w > maxW) {
                widest = maxOf(widest, x)
                rowsN++
                x = 0f
            }
            x += w
        }
        widest = maxOf(widest, x)
        if (segs.isEmpty()) widest = u(60f)
        rows = rowsN
        val total = widest + u(MARGIN * 2) + u(6f)
        setMeasuredDimension(total.toInt(), (u(ROW_H) * rowsN + u(MARGIN * 2) + u(2f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = u(MARGIN) - u(1f)
        card(canvas, m, m, w - m, h - m, 8f, 4f, Hud.CYAN, Hud.PURPLE, 1f, true)
        if (segs.isEmpty()) return
        val maxW = w - u(MARGIN * 2) - u(6f)
        var x = u(MARGIN) + u(3f)
        var row = 0
        var rowX = 0f
        for (i in segs.indices) {
            val sg = segs[i]
            val sw = segW.getOrElse(i) { measureSeg(sg) }
            if (rowX > 0f && rowX + sw > maxW + 1f) {
                row++
                rowX = 0f
                x = u(MARGIN) + u(3f)
            }
            val top = u(MARGIN) + row * u(ROW_H)
            val cy = top + u(ROW_H) / 2f
            if (rowX > 0f) {
                stroke.shader = null
                stroke.color = Hud.alpha(Hud.CYAN, 80)
                stroke.strokeWidth = maxOf(1f, u(0.8f))
                canvas.drawLine(x, cy - u(7f), x, cy + u(7f), stroke)
            }
            drawSeg(canvas, sg, x, cy, sw)
            x += sw
            rowX += sw
        }
    }

    private fun drawSeg(c: Canvas, sg: Seg, x0: Float, cy: Float, w: Float) {
        if (sg.graph) {
            sparkline(c, x0 + u(6f), cy - u(8f), x0 + w - u(6f), cy + u(8f), st.fpsHist, st.fpsCount, st.maxHz, Hud.CYAN, true)
            return
        }
        var x = x0 + u(PAD_X)
        val isz = u(ICON)
        val pkg = sg.appPkg
        if (pkg == null || !appIcon(c, pkg, x, cy - isz / 2f, isz)) icon(c, sg.icon, x, cy - isz / 2f, isz, Hud.CYAN_SOFT)
        x += isz + u(4f)
        if (sg.word) setText(fBold, 12.5f, sg.valueColor, true) else setText(fDisplay, 13f, sg.valueColor, true)
        c.drawText(sg.value, x, cy + u(4.6f), text)
        x += text.measureText(sg.value)
        if (sg.unit.isNotEmpty()) {
            setText(fBold, 9.5f, Hud.MUTED)
            x += u(2f)
            c.drawText(sg.unit, x, cy + u(4.2f), text)
            x += text.measureText(sg.unit)
        }
        if (sg.extra.isNotEmpty()) {
            setText(fBold, 10f, sg.extraColor)
            x += u(5f)
            c.drawText(sg.extra, x, cy + u(4.2f), text)
        }
    }

    companion object {
        private const val ROW_H = 26f
        private const val MARGIN = 4f
        private const val PAD_X = 6f
        private const val ICON = 15f
    }
}
