package com.rendimiento.monitor

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Piezas de interfaz con el estilo estelar (se arman en codigo, sin XML). */
class UiKit(private val ctx: Context) {

    val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun dpf(v: Float): Float = v * density

    fun text(
        content: CharSequence,
        sizeSp: Float,
        color: Int,
        tf: Typeface = Hud.body(ctx),
        glow: Boolean = false
    ): TextView {
        val t = TextView(ctx)
        t.text = content
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        t.typeface = tf
        t.includeFontPadding = false
        t.setLineSpacing(0f, 1.08f)
        if (glow) t.setShadowLayer(dpf(8f), 0f, 0f, Hud.alpha(color, 200))
        return t
    }

    /** Titulo de seccion dentro de una tarjeta. */
    fun sectionTitle(content: String): TextView {
        val t = text(content, 19f, Hud.TEXT, Hud.bodyBold(ctx))
        t.setShadowLayer(dpf(6f), 0f, 0f, Hud.alpha(Hud.CYAN, 110))
        t.setPadding(0, 0, 0, dp(6))
        return t
    }

    fun hint(content: String): TextView {
        val t = text(content, 13.5f, Hud.MUTED, Hud.body(ctx))
        t.setPadding(0, dp(6), 0, dp(2))
        return t
    }

    /** Tarjeta vertical con marco neon. */
    fun card(cutBig: Float = 14f): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val bg = HudFrameDrawable(density, cutBigDp = cutBig)
        box.background = bg
        val m = bg.margin().toInt()
        box.setPadding(dp(16) + m, dp(14) + m, dp(16) + m, dp(14) + m)
        return box
    }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top)
        return p
    }

    fun space(heightDp: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        return v
    }

    fun icon(res: Int, sizeDp: Int, color: Int): ImageView {
        val iv = ImageView(ctx)
        iv.setImageResource(res)
        iv.imageTintList = ColorStateList.valueOf(color)
        iv.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return iv
    }

    /** Linea de estado con icono (alerta o palomita) y texto de color. */
    fun statusLine(): Pair<LinearLayout, TextView> {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val iv = icon(R.drawable.ic_alert, 20, Hud.RED)
        iv.id = View.generateViewId()
        row.addView(iv)
        val t = text("", 15f, Hud.RED, Hud.bodySemi(ctx))
        t.setPadding(dp(8), 0, 0, 0)
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setPadding(0, dp(2), 0, dp(10))
        row.tag = iv
        return Pair(row, t)
    }

    fun setStatus(row: LinearLayout, tv: TextView, ok: Boolean, warn: Boolean, msg: String) {
        val color = if (ok) Hud.GREEN else if (warn) Hud.YELLOW else Hud.RED
        tv.text = msg
        tv.setTextColor(color)
        val iv = row.tag as? ImageView
        iv?.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_alert)
        iv?.imageTintList = ColorStateList.valueOf(color)
    }

    /** Boton neon (TextView con fondo dibujado). */
    fun button(label: String, primary: Boolean, iconRes: Int = 0, onClick: () -> Unit): TextView {
        val b = TextView(ctx)
        b.text = label.uppercase()
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        b.typeface = Hud.bodyBold(ctx)
        b.letterSpacing = 0.05f
        b.setTextColor(Hud.TEXT)
        b.setShadowLayer(dpf(6f), 0f, 0f, Hud.alpha(Hud.CYAN, 150))
        b.gravity = Gravity.CENTER
        b.minHeight = dp(54)
        b.setPadding(dp(22), dp(10), dp(22), dp(10))
        b.background = NeonButtonDrawable(density, primary)
        b.isClickable = true
        b.isFocusable = true
        b.maxLines = 2
        b.ellipsize = TextUtils.TruncateAt.END
        if (iconRes != 0) {
            val d: Drawable? = ctx.getDrawable(iconRes)?.mutate()
            if (d != null) {
                d.setTint(Hud.CYAN_SOFT)
                d.setBounds(0, 0, dp(20), dp(20))
                b.setCompoundDrawablesRelative(d, null, null, null)
                b.compoundDrawablePadding = dp(8)
            }
        }
        b.setOnClickListener { onClick() }
        return b
    }

    /** Casilla neon conectada a una preferencia booleana. */
    fun check(prefs: SharedPreferences, label: String, key: String, def: Boolean, onChange: (() -> Unit)? = null): CheckBox {
        val c = CheckBox(ctx)
        c.text = label
        c.setTextColor(Hud.TEXT)
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        c.typeface = Hud.bodySemi(ctx)
        c.buttonTintList = null
        c.buttonDrawable = NeonCheckDrawable(density)
        c.setPadding(dp(6), dp(4), 0, dp(4))
        c.background = null
        c.isChecked = prefs.getBoolean(key, def)
        c.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            onChange?.invoke()
        }
        return c
    }

    /** Deslizador con titulo y valor, conectado a una preferencia entera. */
    fun slider(prefs: SharedPreferences, label: String, key: String, lo: Int, hi: Int, def: Int, unit: String): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val current = prefs.getInt(key, def).coerceIn(lo, hi)
        val title = text("$label: $current$unit", 15f, Hud.TEXT, Hud.bodySemi(ctx))
        title.setPadding(0, dp(10), 0, dp(2))
        val bar = SeekBar(ctx)
        bar.max = hi - lo
        bar.progress = current - lo
        val track = LineDrawable(density, Hud.alpha(0xFF3A4A8F.toInt(), 255), Hud.alpha(0xFF3A4A8F.toInt(), 255), 3f, false)
        val prog = LineDrawable(density, Hud.CYAN, Hud.CYAN_SOFT, 3.4f, true)
        val layers = LayerDrawable(arrayOf(track, ClipDrawable(prog, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        bar.progressDrawable = layers
        bar.thumb = NeonThumbDrawable(density)
        bar.progressTintList = null
        bar.thumbTintList = null
        bar.splitTrack = false
        bar.setPadding(dp(14), dp(8), dp(14), dp(8))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + lo
                title.text = "$label: $value$unit"
                if (fromUser) prefs.edit().putInt(key, value).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        box.addView(title)
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        return box
    }

    /** Selector de opciones en fila (segmentos). */
    fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val items = ArrayList<TextView>()
        for ((i, label) in options.withIndex()) {
            val t = text(label.uppercase(), 13.5f, Hud.TEXT, Hud.bodyBold(ctx))
            t.gravity = Gravity.CENTER
            t.letterSpacing = 0.04f
            t.background = TabDrawable(density)
            t.setPadding(dp(8), dp(11), dp(8), dp(11))
            t.isSelected = i == selected
            t.isClickable = true
            t.setOnClickListener {
                for ((k, other) in items.withIndex()) other.isSelected = k == i
                onSelect(i)
            }
            items.add(t)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(6)
            row.addView(t, p)
        }
        return row
    }

    /** Linea divisoria fina con brillo. */
    fun divider(): View {
        val v = View(ctx)
        v.background = LineDrawable(density, Hud.alpha(Hud.CYAN, 0), Hud.alpha(Hud.PURPLE, 160), 0.8f, false)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
        return v
    }
}
