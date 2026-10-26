package com.rendimiento.monitor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import java.util.Locale

/** Version visible (igual que versionName en app/build.gradle.kts). */
object BuildInfo {
    const val VERSION = "2.0"
}

/**
 * Tema "estelar": colores neon, fuentes y utilidades de formato que comparten
 * la pantalla principal, la pantalla de consumo y la barra flotante.
 */
object Hud {

    // ------------------------------------------------------------------ Colores
    const val BG = 0xFF050A24.toInt()
    const val CYAN = 0xFF2EE6FF.toInt()
    const val CYAN_SOFT = 0xFF8AF3FF.toInt()
    const val BLUE = 0xFF3D8BFF.toInt()
    const val PURPLE = 0xFF9D5CFF.toInt()
    const val MAGENTA = 0xFFFF4FD8.toInt()
    const val TEXT = 0xFFEAF2FF.toInt()
    const val MUTED = 0xFFA6B3E0.toInt()
    const val DIM = 0xFF6C78A8.toInt()
    const val GREEN = 0xFF3DFF8C.toInt()
    const val YELLOW = 0xFFFFD84A.toInt()
    const val ORANGE = 0xFFFF9F43.toInt()
    const val RED = 0xFFFF4D6D.toInt()
    const val TRACK = 0xFF1B2459.toInt()

    val THERMAL_NAMES = arrayOf("Normal", "Leve", "Moderado", "Severo", "Crítico", "Emergencia", "Apagado")

    // ------------------------------------------------------------------ Fuentes
    @Volatile
    private var fonts: Array<Typeface>? = null

    private fun fontSet(ctx: Context): Array<Typeface> {
        fonts?.let { return it }
        val app = ctx.applicationContext ?: ctx
        val set = arrayOf(
            load(app, R.font.orbitron_bold, Typeface.DEFAULT_BOLD),
            load(app, R.font.orbitron_medium, Typeface.DEFAULT_BOLD),
            load(app, R.font.rajdhani_medium, Typeface.DEFAULT),
            load(app, R.font.rajdhani_semibold, Typeface.DEFAULT),
            load(app, R.font.rajdhani_bold, Typeface.DEFAULT_BOLD)
        )
        fonts = set
        return set
    }

    private fun load(ctx: Context, id: Int, fallback: Typeface): Typeface =
        try {
            ctx.resources.getFont(id)
        } catch (e: Throwable) {
            fallback
        }

    /** Titulos y numeros grandes (Orbitron Bold). */
    fun title(ctx: Context): Typeface = fontSet(ctx)[0]

    /** Numeros medianos (Orbitron Medium). */
    fun display(ctx: Context): Typeface = fontSet(ctx)[1]

    /** Texto normal (Rajdhani Medium). */
    fun body(ctx: Context): Typeface = fontSet(ctx)[2]

    /** Texto con algo mas de peso (Rajdhani SemiBold). */
    fun bodySemi(ctx: Context): Typeface = fontSet(ctx)[3]

    /** Etiquetas y botones (Rajdhani Bold). */
    fun bodyBold(ctx: Context): Typeface = fontSet(ctx)[4]

    // ------------------------------------------------------------------ Medidas
    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun dpi(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    // ------------------------------------------------------------------ Formato
    fun fmt(v: Float, decimals: Int): String =
        when (decimals) {
            0 -> String.format(Locale.US, "%.0f", v)
            1 -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }

    /** 1536 MB -> "1.5 GB", 820 -> "820 MB". */
    fun mb(v: Float): String =
        if (v >= 1024f) fmt(v / 1024f, 1) + " GB" else fmt(v, 0) + " MB"

    fun bytes(b: Long): String {
        val v = b.toDouble()
        return when {
            v >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024 * 1024))
            v >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024.0 * 1024))
            v >= 1024.0 -> String.format(Locale.US, "%.0f KB", v / 1024.0)
            else -> "$b B"
        }
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> (s / 3600).toString() + " h " + ((s % 3600) / 60).toString() + " min"
            s >= 60 -> (s / 60).toString() + " min " + (s % 60).toString() + " s"
            else -> s.toString() + " s"
        }
    }

    // ------------------------------------------------------------------ Colores por valor
    fun fpsColor(fps: Float): Int = when {
        fps.isNaN() -> MUTED
        fps >= 50f -> GREEN
        fps >= 30f -> YELLOW
        else -> RED
    }

    fun pingColor(ms: Float): Int = when {
        ms.isNaN() -> MUTED
        ms < 60f -> GREEN
        ms < 120f -> YELLOW
        else -> RED
    }

    fun loadColor(pct: Float): Int = when {
        pct.isNaN() -> CYAN
        pct < 70f -> CYAN
        pct < 90f -> YELLOW
        else -> RED
    }

    fun tempColor(c: Float): Int = when {
        c.isNaN() -> MUTED
        c < 45f -> CYAN_SOFT
        c < 60f -> YELLOW
        c < 75f -> ORANGE
        else -> RED
    }

    fun batTempColor(c: Float): Int = when {
        c.isNaN() -> MUTED
        c < 38f -> CYAN_SOFT
        c < 43f -> YELLOW
        else -> RED
    }

    fun thermalColor(status: Int): Int = when {
        status <= 0 -> GREEN
        status == 1 -> YELLOW
        status == 2 -> ORANGE
        else -> RED
    }

    fun thermalName(status: Int): String {
        if (status < 0) return "—"
        val i = if (status < THERMAL_NAMES.size) status else THERMAL_NAMES.size - 1
        return THERMAL_NAMES[i]
    }

    fun alpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Mezcla dos colores (t=0 -> a, t=1 -> b). */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }
}
