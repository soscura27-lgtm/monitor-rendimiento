package com.rendimiento.monitor

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/** Pantalla de ajustes: permisos, estado de Shizuku, opciones de la barra y boton de iniciar/detener. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvOverlay: TextView
    private lateinit var btnOverlay: Button
    private lateinit var tvShizuku: TextView
    private lateinit var btnShizuku: Button
    private lateinit var btnToggle: Button

    private val handler = Handler(Looper.getMainLooper())

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refresh() }
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refresh() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        setContentView(buildUi())

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Estado

    /** 0 = no instalado, 1 = instalado pero apagado, 2 = falta permiso, 3 = listo. */
    private fun shizukuState(): Int {
        val installed = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        if (!running) return if (installed) 1 else 0
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
        return if (granted) 3 else 2
    }

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        if (canOverlay) {
            tvOverlay.text = "✔ Permiso de superposición concedido"
            tvOverlay.setTextColor(GREEN)
        } else {
            tvOverlay.text = "✘ Falta el permiso de superposición"
            tvOverlay.setTextColor(RED)
        }
        btnOverlay.isEnabled = !canOverlay

        when (shizukuState()) {
            0 -> {
                tvShizuku.text = "✘ Shizuku no está instalado"
                tvShizuku.setTextColor(RED)
                btnShizuku.text = "Instalar Shizuku"
                btnShizuku.isEnabled = true
            }
            1 -> {
                tvShizuku.text = "✘ Shizuku está instalado pero apagado"
                tvShizuku.setTextColor(YELLOW)
                btnShizuku.text = "Abrir Shizuku"
                btnShizuku.isEnabled = true
            }
            2 -> {
                tvShizuku.text = "! Shizuku funciona, falta darle permiso a esta app"
                tvShizuku.setTextColor(YELLOW)
                btnShizuku.text = "Dar permiso a Monitor de rendimiento"
                btnShizuku.isEnabled = true
            }
            else -> {
                tvShizuku.text = "✔ Shizuku listo: se verán FPS, CPU y GPU"
                tvShizuku.setTextColor(GREEN)
                btnShizuku.text = "Shizuku listo"
                btnShizuku.isEnabled = false
            }
        }

        btnToggle.text = if (OverlayService.running) "Detener barra flotante" else "Iniciar barra flotante"
    }

    // ------------------------------------------------------------------ Acciones

    private fun onOverlayPermissionClick() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun onShizukuClick() {
        when (shizukuState()) {
            0 -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                }
            }
            1 -> {
                val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launch != null) startActivity(launch)
            }
            2 -> {
                try {
                    Shizuku.requestPermission(REQUEST_SHIZUKU)
                } catch (e: Throwable) {
                    Toast.makeText(this, "No se pudo pedir el permiso. Hazlo desde la app Shizuku.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onToggleClick() {
        val service = Intent(this, OverlayService::class.java)
        if (OverlayService.running) {
            stopService(service)
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Primero concede el permiso de superposición", Toast.LENGTH_LONG).show()
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
            startForegroundService(service)
        }
        handler.postDelayed({ refresh() }, 600)
    }

    // ------------------------------------------------------------------ Interfaz

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(content: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = content
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun space(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        return v
    }

    private fun check(label: String, key: String, def: Boolean): CheckBox {
        val c = CheckBox(this)
        c.text = label
        c.setTextColor(Color.WHITE)
        c.isChecked = prefs.getBoolean(key, def)
        c.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
        return c
    }

    private fun slider(label: String, key: String, lo: Int, hi: Int, def: Int, unit: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val current = prefs.getInt(key, def).coerceIn(lo, hi)
        val title = text("$label: $current$unit", 14f, Color.WHITE, false)

        val bar = SeekBar(this)
        bar.max = hi - lo
        bar.progress = current - lo
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
        box.addView(bar)
        return box
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi(): View {
        val pad = dp(16)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        col.addView(text("Monitor de rendimiento", 24f, Color.WHITE, true))
        col.addView(text("Barra flotante con FPS, CPU, GPU, RAM, temperatura y batería.", 14f, GREY, false))
        col.addView(space(12))

        col.addView(text("1. Permiso de superposición", 16f, Color.WHITE, true))
        tvOverlay = text("", 14f, Color.WHITE, false)
        btnOverlay = button("Permitir superposición") { onOverlayPermissionClick() }
        col.addView(tvOverlay)
        col.addView(btnOverlay)
        col.addView(space(12))

        col.addView(text("2. Shizuku (para FPS de juegos, CPU y GPU)", 16f, Color.WHITE, true))
        tvShizuku = text("", 14f, Color.WHITE, false)
        btnShizuku = button("") { onShizukuClick() }
        col.addView(tvShizuku)
        col.addView(btnShizuku)
        col.addView(
            text(
                "Para iniciar Shizuku: abre la app, activa la depuración inalámbrica en Opciones de " +
                    "desarrollador, empareja con el código y toca Iniciar. Hay que repetirlo cada vez " +
                    "que reinicies el celular. Sin Shizuku solo se ven RAM, batería, estado térmico y Hz de pantalla.",
                12f, GREY, false
            )
        )
        col.addView(space(12))

        col.addView(text("3. Barra flotante", 16f, Color.WHITE, true))
        btnToggle = button("") { onToggleClick() }
        col.addView(btnToggle)
        col.addView(text("Arrastra la barra para moverla. Para cerrarla usa este botón o la notificación.", 12f, GREY, false))
        col.addView(button("Ver consumo por app (CPU, RAM, red)") {
            startActivity(Intent(this, ConsumoActivity::class.java))
        })
        col.addView(space(12))

        col.addView(text("Qué mostrar", 16f, Color.WHITE, true))
        col.addView(check("FPS y tiempo por fotograma", Prefs.SHOW_FPS, true))
        col.addView(check("Gráfica de FPS", Prefs.SHOW_GRAPH, true))
        col.addView(check("Ping al servidor del juego", Prefs.SHOW_PING, true))
        col.addView(check("App que más CPU y RAM consume", Prefs.SHOW_APPS, true))
        col.addView(check("CPU", Prefs.SHOW_CPU, true))
        col.addView(check("GPU", Prefs.SHOW_GPU, true))
        col.addView(check("RAM", Prefs.SHOW_RAM, true))
        col.addView(check("Batería", Prefs.SHOW_BAT, true))
        col.addView(check("Temperaturas y estado térmico", Prefs.SHOW_TEMP, true))
        col.addView(space(8))
        col.addView(slider("Tamaño del texto", Prefs.TEXT_SIZE, 9, 20, 12, " sp"))
        col.addView(slider("Opacidad del fondo", Prefs.OPACITY, 10, 100, 70, " %"))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#121212"))
        scroll.isFillViewport = true
        scroll.fitsSystemWindows = true
        scroll.addView(col)
        return scroll
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_SHIZUKU = 100
        private const val REQUEST_NOTIFICATIONS = 101

        private val GREEN = Color.parseColor("#66BB6A")
        private val YELLOW = Color.parseColor("#FFCA28")
        private val RED = Color.parseColor("#EF5350")
        private val GREY = Color.parseColor("#B0BEC5")
    }
}
