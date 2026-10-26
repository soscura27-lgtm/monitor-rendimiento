package com.rendimiento.monitor

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Pantalla "Consumo por app": tres categorias (CPU, RAM y red) con las apps que mas usan cada cosa.
 *
 * Usa su propio enlace con Shizuku y un proceso aparte ("shell-ui"), asi que cerrar esta pantalla
 * no detiene el servicio de la barra flotante.
 */
class ConsumoActivity : Activity() {

    @Volatile
    private var shell: IShellService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var bindStartedAt = 0L

    private var prevTotal = -1L
    private val appMonitor = AppMonitor { shell }

    private lateinit var status: TextView
    private lateinit var body: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell-ui")
            .debuggable(false)
            .version(2)
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
                val sh = shell
                if (sh != null) {
                    val total = readTotalJiffies(sh)
                    val delta = if (prevTotal >= 0L && total > prevTotal) total - prevTotal else -1L
                    if (total >= 0L) prevTotal = total
                    appMonitor.update(delta, System.currentTimeMillis())
                    val apps = appMonitor.current()
                    ui.post { render(apps, true) }
                } else {
                    ui.post { render(emptyList(), false) }
                }
            } catch (e: Throwable) {
                // Una lectura fallida no debe cerrar la pantalla.
            }
            worker?.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        col.addView(text("Consumo por app", 22f, Color.WHITE, true))
        status = text("", 13f, GREY, false)
        col.addView(status)
        col.addView(space(8))
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        col.addView(body)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#121212"))
        scroll.addView(col)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (worker != null) return
        val t = HandlerThread("monitor-consumo")
        t.start()
        thread = t
        val w = Handler(t.looper)
        worker = w
        w.post(tick)
    }

    override fun onPause() {
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        super.onPause()
    }

    override fun onDestroy() {
        try {
            if (shell != null || binding) {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        } catch (e: Throwable) {
        }
        shell = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Shizuku

    private fun ensureShell() {
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

    /** Suma de jiffies de todas las CPUs (linea "cpu" de /proc/stat), o -1 si no se pudo leer. */
    private fun readTotalJiffies(sh: IShellService): Long {
        val r = sh.readFiles(arrayOf("/proc/stat"))
        val stat = r.firstOrNull() ?: return -1L
        val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return -1L
        val v = line.trim().split(WS).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return -1L
        var total = 0L
        for (k in 0 until minOf(v.size, 8)) total += v[k]
        return total
    }

    // ------------------------------------------------------------------ Pantalla

    private fun render(apps: List<AppMonitor.AppStat>, linked: Boolean) {
        body.removeAllViews()
        if (!linked) {
            status.text = "Sin Shizuku: abre Shizuku y dale permiso a esta app para ver el consumo."
            status.setTextColor(YELLOW)
            return
        }
        status.text = "Se actualiza cada 1,5 s. Valores aproximados; la GPU y la bateria por app no se pueden leer sin root."
        status.setTextColor(GREY)

        val cpuRows = apps
            .filter { !it.cpuPct.isNaN() }
            .sortedByDescending { it.cpuPct }
            .take(TOP)
            .map { Pair(label(it), fmt(it.cpuPct, 1) + " %") }
        section("CPU: qué app usa más procesador", cpuRows, "Esperando la segunda lectura…")

        val ramRows = apps
            .filter { it.ramMb > 0f }
            .sortedByDescending { it.ramMb }
            .take(TOP)
            .map { Pair(label(it), fmt(it.ramMb, 0) + " MB") }
        section("RAM: qué app ocupa más memoria", ramRows, "Sin datos de memoria")

        val netRows = apps
            .filter { !it.rxKbS.isNaN() && !it.txKbS.isNaN() }
            .sortedByDescending { it.rxKbS + it.txKbS }
            .take(TOP)
            .map { Pair(label(it), "↓ " + fmt(it.rxKbS, 1) + " KB/s · ↑ " + fmt(it.txKbS, 1) + " KB/s") }
        section(
            "Red: qué app gasta más datos",
            netRows,
            "No disponible en este teléfono (el kernel no expone el trafico por app)"
        )
    }

    private fun section(title: String, rows: List<Pair<String, String>>, empty: String) {
        body.addView(text(title, 16f, Color.WHITE, true))
        if (rows.isEmpty()) {
            body.addView(text(empty, 13f, GREY, false))
        } else {
            for ((i, row) in rows.withIndex()) {
                body.addView(text((i + 1).toString() + ". " + row.first + "  —  " + row.second, 14f, Color.WHITE, false))
            }
        }
        body.addView(space(14))
    }

    private fun label(s: AppMonitor.AppStat): String =
        if (s.pkg.length > 40) s.pkg.take(37) + "…" else s.pkg

    private fun fmt(v: Float, decimals: Int): String =
        String.format(Locale.US, if (decimals == 0) "%.0f" else "%.1f", v)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(content: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = content
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(3), 0, dp(3))
        return t
    }

    private fun space(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        return v
    }

    companion object {
        private const val TOP = 6
        private const val REFRESH_MS = 1500L
        private const val BIND_TIMEOUT_MS = 10000L
        private val WS = Regex("\\s+")
        private val GREY = Color.parseColor("#B0BEC5")
        private val YELLOW = Color.parseColor("#FFCA28")
    }
}
