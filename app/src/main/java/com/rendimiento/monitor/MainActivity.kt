package com.rendimiento.monitor

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla principal con tema espacial: panel en vivo, permisos, estado de Shizuku,
 * opciones de la barra flotante e historial de sesiones de juego.
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var ui: UiKit

    private lateinit var column: LinearLayout
    private lateinit var overlayRow: LinearLayout
    private lateinit var overlayStatus: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var shizukuRow: LinearLayout
    private lateinit var shizukuStatus: TextView
    private lateinit var btnShizuku: TextView
    private lateinit var btnToggle: TextView
    private lateinit var usageRow: LinearLayout
    private lateinit var usageStatus: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var livePanel: StellarPanelView

    private val handler = Handler(Looper.getMainLooper())
    private var sampleThread: HandlerThread? = null
    private var sampleWorker: Handler? = null
    private var sampler: Sampler? = null
    private val liveState = OverlayState()
    private var grantTried = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val shellListener = Runnable { refresh() }
    private val overlayListener = Runnable { refresh() }

    private val liveTick = object : Runnable {
        override fun run() {
            try {
                ShellClient.ensure()
                val s = sampler?.sample()
                if (s != null) handler.post { renderLive(s) }
            } catch (e: Throwable) {
            }
            sampleWorker?.postDelayed(this, 1000L)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { renderLive(liveState.snap, false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        ui = UiKit(this)
        setupWindow()
        setContentView(buildUi())

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        ShellClient.addListener(shellListener)
        ShellClient.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        OverlayService.stateListener = overlayListener
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
        renderHistory()
        startLive()
    }

    override fun onPause() {
        stopLive()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (OverlayService.stateListener === overlayListener) OverlayService.stateListener = null
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        ShellClient.removeListener(shellListener)
        ShellClient.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Panel en vivo

    private fun startLive() {
        if (sampleWorker != null) return
        val s = Sampler(this, { ShellClient.shell }, { prefs.getBoolean(Prefs.SHOW_APPS, true) }, false)
        sampler = s
        s.startBackground()
        val t = HandlerThread("monitor-live")
        t.start()
        sampleThread = t
        val w = Handler(t.looper)
        sampleWorker = w
        w.post(liveTick)
    }

    private fun stopLive() {
        sampleWorker?.removeCallbacksAndMessages(null)
        sampleThread?.quitSafely()
        sampleThread = null
        sampleWorker = null
        sampler?.stopBackground()
        sampler = null
    }

    private fun renderLive(s: Snapshot, push: Boolean = true) {
        if (push) liveState.push(s)
        livePanel.update(liveState, HudConfig(prefs))
    }

    // ------------------------------------------------------------------ Estado

    private fun refresh() {
        if (!::btnToggle.isInitialized) return
        val canOverlay = Settings.canDrawOverlays(this)
        ui.setStatus(
            overlayRow, overlayStatus, canOverlay, false,
            if (canOverlay) "Permiso de superposición concedido" else "Falta el permiso de superposición"
        )
        btnOverlay.visibility = if (canOverlay) View.GONE else View.VISIBLE

        when (ShellClient.state(this)) {
            0 -> {
                ui.setStatus(shizukuRow, shizukuStatus, false, false, "Shizuku no está instalado")
                btnShizuku.text = "INSTALAR SHIZUKU"
                btnShizuku.visibility = View.VISIBLE
            }
            1 -> {
                ui.setStatus(shizukuRow, shizukuStatus, false, true, "Shizuku está instalado pero apagado")
                btnShizuku.text = "ABRIR SHIZUKU"
                btnShizuku.visibility = View.VISIBLE
            }
            2 -> {
                ui.setStatus(shizukuRow, shizukuStatus, false, true, "Shizuku funciona, falta darle permiso a esta app")
                btnShizuku.text = "DAR PERMISO A MONITOR DE RENDIMIENTO"
                btnShizuku.visibility = View.VISIBLE
            }
            else -> {
                val linked = ShellClient.shell != null
                ui.setStatus(
                    shizukuRow, shizukuStatus, linked, !linked,
                    if (linked) "Shizuku listo: FPS, ping, CPU y GPU activos" else "Shizuku listo, conectando…"
                )
                btnShizuku.visibility = View.GONE
                ensureUsageAccess()
            }
        }

        val usage = UsageData.hasUsageAccess(this)
        ui.setStatus(
            usageRow, usageStatus, usage, true,
            if (usage) "Acceso a datos de uso activo (almacenamiento, datos y tiempo por app)"
            else "Sin acceso a datos de uso: se activa solo con Shizuku o desde Ajustes"
        )

        btnToggle.text = if (OverlayService.running) "DETENER BARRA FLOTANTE" else "INICIAR BARRA FLOTANTE"
    }

    /** Con Shizuku listo, la app se da sola el acceso a "datos de uso" (una vez por visita). */
    private fun ensureUsageAccess() {
        if (grantTried || UsageData.hasUsageAccess(this)) return
        val sh = ShellClient.shell ?: return
        grantTried = true
        Thread {
            UsageData.grantUsageAccess(this, sh)
            handler.post { refresh() }
        }.start()
    }

    // ------------------------------------------------------------------ Acciones

    private fun onOverlayPermissionClick() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Throwable) {
            Toast.makeText(this, "Abre Ajustes > Apps > Monitor > Mostrar sobre otras apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun onShizukuClick() {
        when (ShellClient.state(this)) {
            0 -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ShellClient.SHIZUKU_PACKAGE)))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                }
            }
            1 -> {
                val launch = packageManager.getLaunchIntentForPackage(ShellClient.SHIZUKU_PACKAGE)
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

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Hud.BG
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** Alto de la barra de estado y de navegacion (arriba, abajo). */
    @Suppress("DEPRECATION")
    private fun barInsets(insets: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(i.top, i.bottom)
        }
        return intArrayOf(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(SpaceBackgroundView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val pad = ui.dp(16)
        column.setPadding(pad, pad, pad, pad)
        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = barInsets(insets)
            column.setPadding(pad, pad + b[0], pad, pad + b[1])
            insets
        }

        // Encabezado
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = ui.text("Monitor de rendimiento", 29f, 0xFF7FF1FF.toInt(), Hud.bodyBold(this), true)
        titles.addView(title)
        val sub = ui.text("Barra flotante con FPS, ping, CPU, GPU, RAM, temperatura y batería.", 14.5f, Hud.MUTED, Hud.body(this))
        sub.setPadding(0, ui.dp(4), 0, 0)
        titles.addView(sub)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val radar = RadarView(this)
        val rp = LinearLayout.LayoutParams(ui.dp(50), ui.dp(50))
        rp.leftMargin = ui.dp(8)
        header.addView(radar, rp)
        column.addView(header)

        // Panel en vivo (el mismo dibujo que la barra en modo panel)
        livePanel = StellarPanelView(this)
        column.addView(livePanel, ui.lp(top = 14))

        // 1. Superposicion
        val c1 = ui.card()
        c1.addView(ui.sectionTitle("1. Permiso de superposición"))
        val s1 = ui.statusLine()
        overlayRow = s1.first
        overlayStatus = s1.second
        c1.addView(overlayRow)
        btnOverlay = ui.button("Permitir superposición", true) { onOverlayPermissionClick() }
        c1.addView(btnOverlay, ui.lp())
        column.addView(c1, ui.lp(top = 14))

        // 2. Shizuku
        val c2 = ui.card()
        c2.addView(ui.sectionTitle("2. Shizuku (para FPS de juegos, CPU y GPU)"))
        val s2 = ui.statusLine()
        shizukuRow = s2.first
        shizukuStatus = s2.second
        c2.addView(shizukuRow)
        btnShizuku = ui.button("", false) { onShizukuClick() }
        c2.addView(btnShizuku, ui.lp())
        c2.addView(
            ui.hint(
                "Para iniciar Shizuku: abre la app, activa la depuración inalámbrica en Opciones de desarrollador, " +
                    "empareja con el código y toca Iniciar. Hay que repetirlo cada vez que reinicies el celular. " +
                    "Sin Shizuku solo se ven RAM, batería, estado térmico, Hz de pantalla y ping a internet."
            )
        )
        val s3 = ui.statusLine()
        usageRow = s3.first
        usageStatus = s3.second
        usageRow.setPadding(0, ui.dp(10), 0, 0)
        c2.addView(usageRow)
        column.addView(c2, ui.lp(top = 14))

        // 3. Barra flotante
        val c3 = ui.card()
        c3.addView(ui.sectionTitle("3. Barra flotante"))
        btnToggle = ui.button("Iniciar barra flotante", true, R.drawable.ic_play) { onToggleClick() }
        c3.addView(btnToggle, ui.lp())
        c3.addView(
            ui.hint(
                "Arrastra la barra para moverla. Tócala para cambiar entre compacta y panel; mantenla presionada " +
                    "para abrir esta app. También puedes agregar el mosaico \"Monitor\" en los ajustes rápidos."
            )
        )
        c3.addView(
            ui.button("Ver consumo por app (CPU, RAM, datos…)", false, R.drawable.ic_chart) {
                startActivity(Intent(this, ConsumoActivity::class.java))
            },
            ui.lp(top = 10)
        )
        column.addView(c3, ui.lp(top = 14))

        // 4. Que mostrar
        val c4 = ui.card()
        c4.addView(ui.sectionTitle("Qué mostrar"))
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        left.addView(ui.check(prefs, "FPS y tiempo por fotograma", Prefs.SHOW_FPS, true))
        left.addView(ui.check(prefs, "Ping al servidor del juego", Prefs.SHOW_PING, true))
        left.addView(ui.check(prefs, "App que más CPU y RAM", Prefs.SHOW_APPS, true))
        left.addView(ui.check(prefs, "Temperaturas y estado térmico", Prefs.SHOW_TEMP, true))
        right.addView(ui.check(prefs, "Gráfica de FPS", Prefs.SHOW_GRAPH, true))
        right.addView(ui.check(prefs, "CPU", Prefs.SHOW_CPU, true))
        right.addView(ui.check(prefs, "GPU", Prefs.SHOW_GPU, true))
        right.addView(ui.check(prefs, "RAM", Prefs.SHOW_RAM, true))
        right.addView(ui.check(prefs, "Batería", Prefs.SHOW_BAT, true))
        grid.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f))
        grid.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c4.addView(grid)
        c4.addView(ui.divider())

        c4.addView(ui.text("Estilo de la barra", 15f, Hud.TEXT, Hud.bodySemi(this)))
        c4.addView(
            ui.segmented(listOf("Compacta", "Panel estelar"), prefs.getInt(Prefs.STYLE, Prefs.STYLE_COMPACT)) {
                prefs.edit().putInt(Prefs.STYLE, it).apply()
            },
            ui.lp(top = 8)
        )
        val refreshOptions = listOf(500, 1000, 2000)
        val curRefresh = refreshOptions.indexOf(prefs.getInt(Prefs.REFRESH_MS, 1000)).let { if (it < 0) 1 else it }
        c4.addView(ui.text("Actualizar cada", 15f, Hud.TEXT, Hud.bodySemi(this)), ui.lp(top = 14))
        c4.addView(
            ui.segmented(listOf("0.5 s", "1 s", "2 s"), curRefresh) {
                prefs.edit().putInt(Prefs.REFRESH_MS, refreshOptions[it]).apply()
            },
            ui.lp(top = 8)
        )
        c4.addView(ui.slider(prefs, "Tamaño del texto", Prefs.TEXT_SIZE, 9, 20, 12, " sp"), ui.lp(top = 6))
        c4.addView(ui.slider(prefs, "Opacidad del fondo", Prefs.OPACITY, 10, 100, 70, " %"))
        column.addView(c4, ui.lp(top = 14))

        // 5. Historial
        val c5 = ui.card()
        val hh = LinearLayout(this)
        hh.orientation = LinearLayout.HORIZONTAL
        hh.gravity = Gravity.CENTER_VERTICAL
        hh.addView(ui.icon(R.drawable.ic_history, 22, Hud.CYAN_SOFT))
        val ht = ui.sectionTitle("Historial de sesiones")
        ht.setPadding(ui.dp(8), 0, 0, 0)
        hh.addView(ht, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c5.addView(hh)
        historyBox = LinearLayout(this)
        historyBox.orientation = LinearLayout.VERTICAL
        c5.addView(historyBox, ui.lp(top = 6))
        c5.addView(ui.check(prefs, "Guardar el resumen de cada juego", Prefs.SESSIONS, true), ui.lp(top = 6))
        c5.addView(
            ui.button("Borrar historial", false, R.drawable.ic_delete) {
                SessionStore.clear(this)
                renderHistory()
            },
            ui.lp(top = 6)
        )
        column.addView(c5, ui.lp(top = 14))

        val footer = ui.text("v" + BuildInfo.VERSION + " // com.rendimiento.monitor", 12.5f, Hud.DIM, Hud.bodySemi(this))
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, ui.dp(18), 0, ui.dp(8))
        column.addView(footer, ui.lp())
        return root
    }

    private fun renderHistory() {
        if (!::historyBox.isInitialized) return
        historyBox.removeAllViews()
        val list = SessionStore.load(this)
        if (list.isEmpty()) {
            historyBox.addView(
                ui.hint("Todavía no hay sesiones. Juega con la barra activa (más de 30 s) y aquí verás FPS promedio, mínimo, ping y temperatura de cada juego.")
            )
            return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        for (s in list.take(8)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, ui.dp(8), 0, ui.dp(8))
            val iv = ImageView(this)
            val icon = AppInfoCache.icon(this, s.pkg)
            if (icon != null) iv.setImageDrawable(icon) else {
                iv.setImageResource(R.drawable.ic_gamepad)
                iv.imageTintList = android.content.res.ColorStateList.valueOf(Hud.CYAN_SOFT)
            }
            row.addView(iv, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding(ui.dp(12), 0, ui.dp(8), 0)
            val name = ui.text(AppInfoCache.label(this, s.pkg), 16f, Hud.TEXT, Hud.bodyBold(this))
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(name)
            val details = ArrayList<String>()
            details.add(fmt.format(Date(s.start)))
            details.add(Hud.duration(s.durationMs))
            if (!s.pingAvg.isNaN()) details.add("ping " + Hud.fmt(s.pingAvg, 0) + " ms")
            if (!s.cpuTempMax.isNaN()) details.add("CPU máx " + Hud.fmt(s.cpuTempMax, 0) + "°C")
            if (s.batUsed >= 0) details.add("batería -" + s.batUsed + "%")
            val d = ui.text(details.joinToString(" · "), 13f, Hud.MUTED, Hud.body(this))
            texts.addView(d)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val right = LinearLayout(this)
            right.orientation = LinearLayout.VERTICAL
            right.gravity = Gravity.END
            val fps = ui.text(Hud.fmt(s.fpsAvg, 0), 22f, Hud.fpsColor(s.fpsAvg), Hud.title(this), true)
            fps.gravity = Gravity.END
            right.addView(fps)
            val low = if (!s.low1.isNaN()) "1% " + Hud.fmt(s.low1, 0) else if (!s.fpsMin.isNaN()) "mín " + Hud.fmt(s.fpsMin, 0) else ""
            val small = ui.text("FPS prom" + (if (low.isNotEmpty()) " · $low" else ""), 12f, Hud.MUTED, Hud.bodySemi(this))
            small.gravity = Gravity.END
            right.addView(small)
            row.addView(right)
            historyBox.addView(row)
            historyBox.addView(ui.divider())
        }
    }

    companion object {
        private const val REQUEST_SHIZUKU = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}
