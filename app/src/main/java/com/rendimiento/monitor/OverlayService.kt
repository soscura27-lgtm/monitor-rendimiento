package com.rendimiento.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Servicio en primer plano que dibuja la barra flotante con las metricas
 * y las actualiza una vez por segundo.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var userServiceArgs: Shizuku.UserServiceArgs
    private lateinit var sampler: Sampler

    private var root: LinearLayout? = null
    private lateinit var tvApp: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvLow: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvTop: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvGpu: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvBat: TextView
    private lateinit var tvThermal: TextView
    private lateinit var graph: GraphView

    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    @Volatile
    private var shell: IShellService? = null

    @Volatile
    private var binding = false

    /** Cuando empezo el enlace con Shizuku; si pasa de BIND_TIMEOUT_MS sin respuesta se reintenta. */
    @Volatile
    private var bindStartedAt = 0L

    private var last: Snapshot = Snapshot()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post {
            applyStyle()
            render(last)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            shell = if (service != null && service.pingBinder()) {
                IShellService.Stub.asInterface(service)
            } else {
                null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            binding = false
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                ensureShell()
                val s = sampler.sample()
                ui.post { render(s) }
            } catch (e: Throwable) {
                // Una lectura fallida no debe detener la barra.
            }
            worker?.postDelayed(this, SAMPLE_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.of(this)
        userServiceArgs = Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            // Version 2: la interfaz AIDL cambio (procTable y sockets). Subirla obliga a Shizuku a reiniciar el proceso.
            .version(2)
        sampler = Sampler(this) { shell }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android exige llamar a startForeground cada vez que se arranca el servicio.
        startAsForeground()
        if (running) return START_NOT_STICKY

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta el permiso de superposición", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            buildOverlay()
        } catch (e: Throwable) {
            Toast.makeText(this, "No se pudo mostrar la barra", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val t = HandlerThread("monitor-sampler")
        t.start()
        thread = t
        val w = Handler(t.looper)
        worker = w
        w.post(tick)
        sampler.startBackground()

        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Throwable) {
        }
        sampler.stopBackground()
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null

        try {
            if (shell != null || binding) {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        } catch (e: Throwable) {
        }
        shell = null

        val r = root
        if (r != null) {
            try {
                windowManager.removeView(r)
            } catch (e: Throwable) {
            }
        }
        root = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Shizuku

    private fun ensureShell() {
        // Si el enlace quedo colgado (Shizuku nunca respondio), se libera para reintentar.
        if (binding && System.currentTimeMillis() - bindStartedAt > BIND_TIMEOUT_MS) {
            binding = false
        }
        if (shell != null || binding) return
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            binding = true
            bindStartedAt = System.currentTimeMillis()
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            binding = false
        }
    }

    // ----------------------------------------------------------- Notificacion

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitor de rendimiento", NotificationManager.IMPORTANCE_LOW)
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat), "Detener", stopIntent
        ).build()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Monitor de rendimiento activo")
            .setContentText("Toca para abrir los ajustes")
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------- Ventana

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun newText(): TextView {
        val t = TextView(this)
        t.typeface = Typeface.MONOSPACE
        t.setTextColor(Color.WHITE)
        t.setShadowLayer(2f, 0f, 0f, Color.BLACK)
        t.includeFontPadding = false
        return t
    }

    private fun buildOverlay() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(6), dp(8), dp(6))

        tvApp = newText()
        tvApp.maxWidth = dp(200)
        tvApp.isSingleLine = true
        tvApp.ellipsize = TextUtils.TruncateAt.MIDDLE
        tvApp.setTextColor(GREY)

        tvFps = newText()
        tvFps.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        tvLow = newText()
        tvPing = newText()
        tvTop = newText()
        tvTop.setTextColor(GREY)
        tvTop.isSingleLine = true
        tvTop.maxWidth = dp(260)
        tvTop.ellipsize = TextUtils.TruncateAt.END
        tvCpu = newText()
        tvGpu = newText()
        tvRam = newText()
        tvBat = newText()
        tvThermal = newText()
        graph = GraphView(this)

        box.addView(tvApp)
        box.addView(tvFps)
        box.addView(tvLow)
        box.addView(tvPing)
        box.addView(tvCpu)
        box.addView(tvGpu)
        box.addView(tvRam)
        box.addView(tvBat)
        box.addView(tvThermal)
        box.addView(tvTop)
        box.addView(
            graph,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        )

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt(Prefs.POS_X, dp(12))
        params.y = prefs.getInt(Prefs.POS_Y, dp(60))

        box.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try {
                            windowManager.updateViewLayout(box, params)
                        } catch (e: Throwable) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit().putInt(Prefs.POS_X, params.x).putInt(Prefs.POS_Y, params.y).apply()
                        return true
                    }
                }
                return false
            }
        })

        root = box
        applyStyle()
        windowManager.addView(box, params)
        render(last)
    }

    private fun applyStyle() {
        val size = prefs.getInt(Prefs.TEXT_SIZE, 12).toFloat()
        val lines = listOf(tvLow, tvPing, tvCpu, tvGpu, tvRam, tvBat, tvThermal, tvTop)
        for (t in lines) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tvFps.setTextSize(TypedValue.COMPLEX_UNIT_SP, size + 4f)
        tvApp.setTextSize(TypedValue.COMPLEX_UNIT_SP, size - 2f)

        val alpha = (prefs.getInt(Prefs.OPACITY, 70) * 255 / 100).coerceIn(0, 255)
        val bg = GradientDrawable()
        bg.setColor(Color.argb(alpha, 0, 0, 0))
        bg.cornerRadius = dp(10).toFloat()
        root?.background = bg
    }

    // ------------------------------------------------------------------ Texto

    private fun fmt(v: Float, decimals: Int): String =
        String.format(Locale.US, if (decimals == 0) "%.0f" else "%.1f", v)

    /** "com.ejemplo.juego" -> "juego": cabe mejor en la barra. */
    private fun shortName(pkg: String): String = pkg.substringAfterLast('.')

    private fun colorForFps(fps: Float): Int = when {
        fps >= 50f -> GREEN
        fps >= 30f -> YELLOW
        else -> RED
    }

    private fun render(s: Snapshot) {
        last = s
        if (root == null) return

        val showFps = prefs.getBoolean(Prefs.SHOW_FPS, true)
        val showCpu = prefs.getBoolean(Prefs.SHOW_CPU, true)
        val showGpu = prefs.getBoolean(Prefs.SHOW_GPU, true)
        val showRam = prefs.getBoolean(Prefs.SHOW_RAM, true)
        val showBat = prefs.getBoolean(Prefs.SHOW_BAT, true)
        val showTemp = prefs.getBoolean(Prefs.SHOW_TEMP, true)
        val showGraph = prefs.getBoolean(Prefs.SHOW_GRAPH, true)

        // App al frente
        val pkgText = if (s.shellOk) (s.pkg ?: "") else ""
        tvApp.text = pkgText
        tvApp.visibility = if (showFps && pkgText.isNotEmpty()) View.VISIBLE else View.GONE

        // FPS
        tvFps.visibility = if (showFps) View.VISIBLE else View.GONE
        if (!s.shellOk) {
            tvFps.setTextColor(GREY)
            tvFps.text = "FPS —  (necesita Shizuku)"
        } else if (s.fps.isNaN()) {
            tvFps.setTextColor(GREY)
            // Antes solo salia "FPS —". Ahora se dice la causa (sin app detectada, sin capa, etc.).
            tvFps.text = "FPS —" + (if (s.fpsStatus.isNotEmpty()) "  " + s.fpsStatus else "")
        } else {
            tvFps.setTextColor(colorForFps(s.fps))
            val ms = if (s.frameMs.isNaN()) "" else "  " + fmt(s.frameMs, 1) + " ms"
            tvFps.text = "FPS " + fmt(s.fps, 0) + ms
        }

        val lowParts = ArrayList<String>()
        if (!s.low1.isNaN()) lowParts.add("1% low " + fmt(s.low1, 0))
        if (!s.refreshHz.isNaN()) lowParts.add(fmt(s.refreshHz, 0) + " Hz")
        tvLow.text = lowParts.joinToString(" · ")
        tvLow.visibility = if (showFps && lowParts.isNotEmpty()) View.VISIBLE else View.GONE

        // Ping: independiente de los FPS, funciona con cualquier juego que tenga conexion de red.
        val showPing = prefs.getBoolean(Prefs.SHOW_PING, true)
        tvPing.text = when {
            !s.shellOk -> "Ping —  (necesita Shizuku)"
            s.pingIp == null -> "Ping —  buscando servidor"
            s.pingMs.isNaN() -> "Ping —  sin respuesta"
            else -> "Ping " + fmt(s.pingMs, 0) + " ms · prom " + fmt(s.pingAvgMs, 0)
        }
        tvPing.setTextColor(
            when {
                s.pingMs.isNaN() -> GREY
                s.pingMs < 60f -> GREEN
                s.pingMs < 120f -> YELLOW
                else -> RED
            }
        )
        tvPing.visibility = if (showPing) View.VISIBLE else View.GONE

        // Categoria de consumo: que app usa mas CPU y mas RAM (sin contar la propia barra).
        val showApps = prefs.getBoolean(Prefs.SHOW_APPS, true)
        val others = s.apps.filter { it.pkg != packageName }
        val topCpu = others.firstOrNull { !it.cpuPct.isNaN() && it.cpuPct >= 1f }
        val topRam = others.maxByOrNull { it.ramMb }
        val topParts = ArrayList<String>()
        if (topCpu != null) topParts.add("CPU " + shortName(topCpu.pkg) + " " + fmt(topCpu.cpuPct, 0) + "%")
        if (topRam != null && topRam.ramMb > 0f) topParts.add("RAM " + shortName(topRam.pkg) + " " + fmt(topRam.ramMb, 0) + " MB")
        tvTop.text = topParts.joinToString(" · ")
        tvTop.visibility = if (showApps && topParts.isNotEmpty()) View.VISIBLE else View.GONE

        // Grafica
        if (!s.fps.isNaN() && s.shellOk) {
            val maxHz = if (s.refreshHz.isNaN()) 60f else maxOf(60f, s.refreshHz)
            graph.push(s.fps, maxHz)
        }
        graph.visibility = if (showFps && showGraph && s.shellOk) View.VISIBLE else View.GONE

        // CPU
        val cpu = ArrayList<String>()
        if (!s.cpuPct.isNaN()) cpu.add(fmt(s.cpuPct, 0) + "%")
        if (s.cpuMhz > 0) cpu.add(s.cpuMhz.toString() + " MHz")
        if (showTemp && !s.cpuTemp.isNaN()) cpu.add(fmt(s.cpuTemp, 0) + "°")
        tvCpu.text = "CPU " + (if (cpu.isEmpty()) "—" else cpu.joinToString(" · "))
        tvCpu.visibility = if (showCpu) View.VISIBLE else View.GONE

        // GPU
        val gpu = ArrayList<String>()
        if (!s.gpuPct.isNaN()) gpu.add(fmt(s.gpuPct, 0) + "%")
        if (s.gpuMhz > 0) gpu.add(s.gpuMhz.toString() + " MHz")
        if (showTemp && !s.gpuTemp.isNaN()) gpu.add(fmt(s.gpuTemp, 0) + "°")
        tvGpu.text = "GPU " + (if (gpu.isEmpty()) "—" else gpu.joinToString(" · "))
        tvGpu.visibility = if (showGpu) View.VISIBLE else View.GONE

        // RAM
        if (s.ramTotalMb > 0L) {
            val pct = s.ramUsedMb * 100f / s.ramTotalMb
            tvRam.text = "RAM " + fmt(s.ramUsedMb / 1024f, 1) + "/" + fmt(s.ramTotalMb / 1024f, 1) +
                " GB · " + fmt(pct, 0) + "%"
        } else {
            tvRam.text = "RAM —"
        }
        tvRam.visibility = if (showRam) View.VISIBLE else View.GONE

        // Bateria
        val bat = ArrayList<String>()
        if (s.batPct >= 0) bat.add((if (s.charging) "⚡" else "") + s.batPct + "%")
        if (showTemp && !s.batTemp.isNaN()) bat.add(fmt(s.batTemp, 1) + "°")
        if (s.batMa != Int.MIN_VALUE) bat.add(s.batMa.toString() + " mA")
        if (!s.batW.isNaN()) bat.add(fmt(s.batW, 1) + " W")
        tvBat.text = "BAT " + (if (bat.isEmpty()) "—" else bat.joinToString(" · "))
        tvBat.visibility = if (showBat) View.VISIBLE else View.GONE

        // Estado termico del sistema
        if (s.thermalStatus >= 0) {
            val idx = if (s.thermalStatus < THERMAL_NAMES.size) s.thermalStatus else THERMAL_NAMES.size - 1
            tvThermal.text = "Térmico: " + THERMAL_NAMES[idx]
            tvThermal.setTextColor(
                when {
                    s.thermalStatus <= 0 -> GREEN
                    s.thermalStatus == 1 -> YELLOW
                    else -> RED
                }
            )
            tvThermal.visibility = if (showTemp) View.VISIBLE else View.GONE
        } else {
            tvThermal.visibility = View.GONE
        }
    }

    companion object {
        const val ACTION_STOP = "com.rendimiento.monitor.action.STOP"

        private const val CHANNEL_ID = "monitor_rendimiento"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_EVERY_MS = 1000L
        private const val BIND_TIMEOUT_MS = 10000L

        private val GREEN = Color.parseColor("#66BB6A")
        private val YELLOW = Color.parseColor("#FFCA28")
        private val RED = Color.parseColor("#EF5350")
        private val GREY = Color.parseColor("#B0BEC5")

        private val THERMAL_NAMES = arrayOf(
            "Normal", "Leve", "Moderado", "Severo", "Crítico", "Emergencia", "Apagado"
        )

        @Volatile
        var running: Boolean = false
    }
}
