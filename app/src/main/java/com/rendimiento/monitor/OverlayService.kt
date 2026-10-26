package com.rendimiento.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs

/**
 * Servicio en primer plano que dibuja la barra flotante y la actualiza en tiempo real
 * (cada 0,5, 1 o 2 s segun los ajustes).
 *
 * Toque corto: cambia entre barra compacta y panel estelar. Mantener presionado: abre la app.
 * Arrastrar: mueve la barra (se guarda una posicion para vertical y otra para horizontal).
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var sampler: Sampler

    private var root: FrameLayout? = null
    private lateinit var compact: CompactHudView
    private lateinit var panel: StellarPanelView
    private val state = OverlayState()
    private var cfg: HudConfig? = null
    private var lastPkg: String? = null
    private var shellAcquired = false

    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        ui.post { onPrefsChanged(key) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val start = SystemClock.uptimeMillis()
            try {
                ShellClient.ensure()
                val s = sampler.sample()
                ui.post { render(s) }
            } catch (e: Throwable) {
                // Una lectura fallida no debe detener la barra.
            }
            val every = Prefs.refreshMs(prefs)
            val delay = (every - (SystemClock.uptimeMillis() - start)).coerceAtLeast(120L)
            worker?.postDelayed(this, delay)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.of(this)
        sampler = Sampler(this, { ShellClient.shell }, { prefs.getBoolean(Prefs.SHOW_APPS, true) }, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_STYLE -> {
                if (running) toggleStyle()
                return START_NOT_STICKY
            }
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

        ShellClient.acquire(this)
        shellAcquired = true
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val t = HandlerThread("monitor-sampler")
        t.start()
        thread = t
        val w = Handler(t.looper)
        worker = w
        w.post(tick)
        sampler.startBackground()

        running = true
        notifyState()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Throwable) {
        }
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        sampler.stopBackground()
        if (shellAcquired) {
            ShellClient.release()
            shellAcquired = false
        }
        val r = root
        if (r != null) {
            try {
                windowManager.removeView(r)
            } catch (e: Throwable) {
            }
        }
        root = null
        notifyState()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root == null) return
        // Al girar la pantalla se usa la posicion guardada para esa orientacion.
        compact.maxWidthPx = screenSize().x
        restorePosition()
        clampParams()
        updateLayout()
    }

    private fun notifyState() {
        try {
            TileService.requestListeningState(this, ComponentName(this, OverlayTileService::class.java))
        } catch (e: Throwable) {
        }
        val l = stateListener
        if (l != null) ui.post(l)
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
        val styleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_STYLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Monitor de rendimiento activo")
            .setContentText("Toca la barra para cambiar de vista · mantén para abrir la app")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_panel), "Cambiar vista", styleIntent).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Detener", stopIntent).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------- Ventana

    private fun buildOverlay() {
        val box = FrameLayout(this)
        compact = CompactHudView(this)
        panel = StellarPanelView(this)
        compact.maxWidthPx = screenSize().x
        box.addView(compact, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        box.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

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
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        params.title = "Monitor de rendimiento"
        restorePosition()

        box.setOnTouchListener(DragListener())
        box.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) {
                // Cambio de tamano (otra vista, texto mas grande): que no se salga de la pantalla.
                if (clampParams()) updateLayout()
            }
        }

        root = box
        cfg = HudConfig(prefs)
        applyStyle()
        windowManager.addView(box, params)
        render(Snapshot())
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var longDone = false
        private var slop = 12
        private val longPress = Runnable {
            if (!moved) {
                longDone = true
                openApp()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    longDone = false
                    ui.postDelayed(longPress, LONG_PRESS_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        ui.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampParams()
                        updateLayout()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (moved) savePosition() else if (!longDone) toggleStyle()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPress)
                    if (moved) savePosition()
                    return true
                }
            }
            return false
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
        }
    }

    private fun style(): Int = prefs.getInt(Prefs.STYLE, Prefs.STYLE_COMPACT)

    private fun toggleStyle() {
        val next = if (style() == Prefs.STYLE_PANEL) Prefs.STYLE_COMPACT else Prefs.STYLE_PANEL
        prefs.edit().putInt(Prefs.STYLE, next).apply()
        // El listener de preferencias aplica el cambio.
    }

    private fun applyStyle() {
        if (root == null) return
        val p = style() == Prefs.STYLE_PANEL
        panel.visibility = if (p) View.VISIBLE else View.GONE
        compact.visibility = if (p) View.GONE else View.VISIBLE
    }

    private fun onPrefsChanged(key: String?) {
        if (root == null) return
        cfg = HudConfig(prefs)
        if (key == Prefs.STYLE) applyStyle()
        render(state.snap, false)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun restorePosition() {
        val land = isLandscape()
        params.x = prefs.getInt(if (land) Prefs.POS_X_LAND else Prefs.POS_X, Hud.dpi(this, 12))
        params.y = prefs.getInt(if (land) Prefs.POS_Y_LAND else Prefs.POS_Y, Hud.dpi(this, if (land) 8 else 60))
    }

    private fun savePosition() {
        val land = isLandscape()
        prefs.edit()
            .putInt(if (land) Prefs.POS_X_LAND else Prefs.POS_X, params.x)
            .putInt(if (land) Prefs.POS_Y_LAND else Prefs.POS_Y, params.y)
            .apply()
    }

    private fun screenSize(): Point {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = windowManager.currentWindowMetrics.bounds
                Point(b.width(), b.height())
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(dm)
                Point(dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Throwable) {
            val dm = resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    /** Mantiene la barra dentro de la pantalla. Devuelve true si cambio la posicion. */
    private fun clampParams(): Boolean {
        val r = root ?: return false
        val sz = screenSize()
        val w = if (r.width > 0) r.width else r.measuredWidth
        val h = if (r.height > 0) r.height else r.measuredHeight
        val nx = params.x.coerceIn(0, maxOf(0, sz.x - w))
        val ny = params.y.coerceIn(0, maxOf(0, sz.y - h))
        val changed = nx != params.x || ny != params.y
        params.x = nx
        params.y = ny
        return changed
    }

    private fun updateLayout() {
        val r = root ?: return
        try {
            windowManager.updateViewLayout(r, params)
        } catch (e: Throwable) {
        }
    }

    // ------------------------------------------------------------------ Dibujar

    private fun render(s: Snapshot, push: Boolean = true) {
        if (root == null) return
        if (push) {
            if (s.pkg != lastPkg) {
                // Otro juego: la grafica de FPS empieza de cero.
                lastPkg = s.pkg
                state.clearFps()
            }
            state.push(s)
        }
        val c = cfg ?: HudConfig(prefs).also { cfg = it }
        if (style() == Prefs.STYLE_PANEL) panel.update(state, c) else compact.update(state, c)
    }

    companion object {
        const val ACTION_STOP = "com.rendimiento.monitor.action.STOP"
        const val ACTION_TOGGLE_STYLE = "com.rendimiento.monitor.action.TOGGLE_STYLE"

        private const val CHANNEL_ID = "monitor_rendimiento"
        private const val NOTIF_ID = 1001
        private const val LONG_PRESS_MS = 650L

        @Volatile
        var running: Boolean = false

        /** La pantalla principal se entera cuando la barra se enciende o se apaga. */
        @Volatile
        var stateListener: Runnable? = null
    }
}
