package com.rendimiento.monitor

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Seccion "Consumo por app": identifica que apps usan CPU, RAM, almacenamiento, datos,
 * bateria y tiempo de pantalla, con su icono y nombre. Tocar una app muestra todo su consumo
 * y permite forzar su detencion. Tambien libera RAM cerrando apps en segundo plano.
 */
class ConsumoActivity : Activity() {

    private lateinit var ui: UiKit
    private val handler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private val monitor = AppMonitor({ ShellClient.shell }) { uid -> AppInfoCache.packageForUid(this, uid) }

    // Datos por pestana (null = sin cargar)
    @Volatile
    private var cpuStats: List<AppMonitor.AppStat> = emptyList()

    @Volatile
    private var pss: Map<String, Float>? = null
    private var pssAt = 0L

    @Volatile
    private var storage: List<UsageData.Storage>? = null

    @Volatile
    private var data: Map<Int, LongArray>? = null

    @Volatile
    private var battery: Map<Int, Float>? = null

    @Volatile
    private var usage: Map<String, Long>? = null
    private val loading = HashSet<Int>()

    private var tab = TAB_CPU
    private var includeSystem = false

    private lateinit var status: TextView
    private lateinit var ramText: TextView
    private lateinit var ramMeter: MeterView
    private lateinit var cpuText: TextView
    private lateinit var freeResult: TextView
    private lateinit var note: TextView
    private lateinit var systemCheck: View
    private lateinit var permCard: LinearLayout
    private lateinit var permText: TextView
    private lateinit var permShizuku: TextView
    private lateinit var permSettings: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView
    private val tabViews = ArrayList<TextView>()
    private val rows = ArrayList<AppRow>()
    private val icons = HashMap<String, Drawable?>()
    private var totalCpu = Float.NaN
    private var prevIdle = -1L
    private var prevTotal = -1L

    private val tick = object : Runnable {
        override fun run() {
            try {
                ShellClient.ensure()
                val sh = ShellClient.shell
                if (sh != null) {
                    readCpuTotal(sh)
                    monitor.update()
                    cpuStats = monitor.current()
                    val now = System.currentTimeMillis()
                    if (tab == TAB_RAM && now - pssAt >= PSS_EVERY_MS) {
                        pssAt = now
                        val m = MemInfo.pssByPackage(sh)
                        if (m.isNotEmpty()) pss = m
                    }
                }
            } catch (e: Throwable) {
            }
            handler.post { render() }
            worker?.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Hud.BG
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(buildUi())
        ShellClient.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        if (worker == null) {
            val t = HandlerThread("monitor-consumo")
            t.start()
            thread = t
            val w = Handler(t.looper)
            worker = w
            w.post(tick)
        }
        loadTab(tab, false)
        render()
    }

    override fun onPause() {
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        super.onPause()
    }

    override fun onDestroy() {
        ShellClient.release()
        super.onDestroy()
    }

    private fun readCpuTotal(sh: IShellService) {
        val stat = try {
            sh.readFiles(arrayOf("/proc/stat"))?.firstOrNull() ?: ""
        } catch (e: Throwable) {
            ""
        }
        val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return
        val v = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return
        val idle = v[3] + (if (v.size > 4) v[4] else 0L)
        var total = 0L
        for (k in 0 until minOf(v.size, 8)) total += v[k]
        if (prevTotal >= 0L && total > prevTotal) {
            totalCpu = ((total - prevTotal - (idle - prevIdle)).toFloat() / (total - prevTotal) * 100f).coerceIn(0f, 100f)
        }
        prevTotal = total
        prevIdle = idle
    }

    // ------------------------------------------------------------------ Carga de pestanas

    private fun needsUsage(t: Int) = t == TAB_STORAGE || t == TAB_DATA || t == TAB_USAGE

    private fun loadTab(t: Int, force: Boolean) {
        if (t == TAB_CPU || t == TAB_RAM) {
            if (t == TAB_RAM) pssAt = 0L
            return
        }
        if (loading.contains(t)) return
        if (!force) {
            val loaded = when (t) {
                TAB_STORAGE -> storage != null
                TAB_DATA -> data != null
                TAB_BATTERY -> battery != null
                else -> usage != null
            }
            if (loaded) return
        }
        if (needsUsage(t) && !UsageData.hasUsageAccess(this)) return
        val sh = ShellClient.shell
        if (t == TAB_BATTERY && sh == null) return
        loading.add(t)
        render()
        Thread {
            try {
                when (t) {
                    TAB_STORAGE -> storage = UsageData.storage(this)
                    TAB_DATA -> data = UsageData.dataToday(this)
                    TAB_BATTERY -> if (sh != null) battery = UsageData.batteryByUid(sh)
                    TAB_USAGE -> usage = UsageData.usageToday(this)
                }
            } catch (e: Throwable) {
            }
            handler.post {
                loading.remove(t)
                render()
            }
        }.start()
    }

    // ------------------------------------------------------------------ Interfaz

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(SpaceBackgroundView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = ui.dp(14)
        col.setPadding(pad, pad, pad, 0)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = barInsets(insets)
            col.setPadding(pad, pad + b[0], pad, b[1])
            insets
        }

        // Encabezado
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = ui.icon(R.drawable.ic_back, 34, Hud.CYAN)
        back.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
        back.setOnClickListener { finish() }
        header.addView(back)
        val title = ui.text("Consumo por app", 26f, 0xFF7FF1FF.toInt(), Hud.bodyBold(this), true)
        title.setPadding(ui.dp(6), 0, 0, 0)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = ui.icon(R.drawable.ic_refresh, 30, Hud.CYAN_SOFT)
        refresh.setOnClickListener {
            pssAt = 0L
            loadTab(tab, true)
            Toast.makeText(this, "Actualizando…", Toast.LENGTH_SHORT).show()
        }
        header.addView(refresh)
        col.addView(header)

        status = ui.text("", 13.5f, Hud.MUTED, Hud.body(this))
        status.setPadding(0, ui.dp(4), 0, ui.dp(8))
        col.addView(status)

        // Resumen: RAM, CPU y liberar RAM
        val sum = ui.card(12f)
        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.addView(ui.icon(R.drawable.ic_ram, 24, Hud.CYAN_SOFT))
        ramText = ui.text("RAM —", 16f, Hud.TEXT, Hud.display(this), true)
        ramText.setPadding(ui.dp(10), 0, 0, 0)
        r1.addView(ramText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(ui.icon(R.drawable.ic_chip, 22, Hud.CYAN_SOFT))
        cpuText = ui.text("CPU —", 16f, Hud.TEXT, Hud.display(this), true)
        cpuText.setPadding(ui.dp(6), 0, 0, 0)
        r1.addView(cpuText)
        sum.addView(r1)
        ramMeter = MeterView(this)
        sum.addView(ramMeter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(12)).also { it.topMargin = ui.dp(8) })
        val free = ui.button("Liberar RAM", true, R.drawable.ic_rocket) { freeRam() }
        sum.addView(free, ui.lp(top = 10))
        freeResult = ui.text("Cierra las apps que están en segundo plano (como hace el sistema cuando falta memoria).", 12.5f, Hud.MUTED, Hud.body(this))
        freeResult.setPadding(0, ui.dp(4), 0, 0)
        sum.addView(freeResult)
        col.addView(sum, ui.lp())

        // Pestanas
        val tabsScroll = HorizontalScrollView(this)
        tabsScroll.isHorizontalScrollBarEnabled = false
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("CPU", "RAM", "Almacenamiento", "Datos hoy", "Batería", "Uso hoy")
        for ((i, n) in names.withIndex()) {
            val t = ui.text(n.uppercase(), 13.5f, Hud.TEXT, Hud.bodyBold(this))
            t.background = TabDrawable(ui.density)
            t.gravity = Gravity.CENTER
            t.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            t.isSelected = i == tab
            t.setOnClickListener { selectTab(i) }
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) p.leftMargin = ui.dp(6)
            tabs.addView(t, p)
            tabViews.add(t)
        }
        tabsScroll.addView(tabs)
        col.addView(tabsScroll, ui.lp(top = 12))

        note = ui.text("", 13f, Hud.MUTED, Hud.body(this))
        note.setPadding(ui.dp(2), ui.dp(8), 0, 0)
        col.addView(note)
        val prefsStub = getSharedPreferences("consumo_ui", Context.MODE_PRIVATE)
        includeSystem = prefsStub.getBoolean("include_system", false)
        systemCheck = ui.check(prefsStub, "Incluir procesos del sistema", "include_system", false) {
            includeSystem = prefsStub.getBoolean("include_system", false)
            render()
        }
        col.addView(systemCheck)

        // Tarjeta de permiso (cuando falta acceso a datos de uso o Shizuku)
        permCard = ui.card(10f)
        permText = ui.text("", 14f, Hud.YELLOW, Hud.bodySemi(this))
        permCard.addView(permText)
        permShizuku = ui.button("Conceder con Shizuku", true, R.drawable.ic_shield) { grantWithShizuku() }
        permCard.addView(permShizuku, ui.lp(top = 8))
        permSettings = ui.button("Abrir ajustes", false, R.drawable.ic_cog) { openUsageSettings() }
        permCard.addView(permSettings, ui.lp(top = 6))
        col.addView(permCard, ui.lp(top = 8))

        // Lista
        val listCard = ui.card(12f)
        listCard.setPadding(listCard.paddingLeft - ui.dp(6), listCard.paddingTop - ui.dp(6), listCard.paddingRight - ui.dp(6), listCard.paddingBottom)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        emptyText = ui.text("", 14f, Hud.MUTED, Hud.body(this))
        emptyText.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
        listBox.addView(emptyText)
        scroll.addView(listBox)
        listCard.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = ui.dp(8)
        lp.bottomMargin = ui.dp(10)
        col.addView(listCard, lp)
        return root
    }

    @Suppress("DEPRECATION")
    private fun barInsets(insets: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(i.top, i.bottom)
        }
        return intArrayOf(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
    }

    private fun selectTab(i: Int) {
        tab = i
        for ((k, v) in tabViews.withIndex()) v.isSelected = k == i
        loadTab(i, false)
        render()
    }

    // ------------------------------------------------------------------ Mostrar

    private class Row(val key: String, val value: Float, val valueText: String, val sub: String, val color: Int)

    private fun render() {
        if (!::listBox.isInitialized) return
        val shell = ShellClient.shell != null
        val shizuku = ShellClient.state(this)
        val usageOk = UsageData.hasUsageAccess(this)

        status.text = when {
            shell -> "Toca una app para ver todo su consumo o detenerla."
            shizuku == 3 -> "Conectando con Shizuku…"
            else -> "Sin Shizuku: solo se ven almacenamiento, datos y tiempo de uso (con acceso a datos de uso)."
        }

        // Resumen
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedMb = (mi.totalMem - mi.availMem) / (1024f * 1024f)
        val totalMb = mi.totalMem / (1024f * 1024f)
        ramText.text = "RAM " + Hud.fmt(usedMb / 1024f, 1) + " / " + Hud.fmt(totalMb / 1024f, 1) + " GB"
        ramMeter.setValue(if (totalMb > 0f) usedMb / totalMb else 0f)
        cpuText.text = if (totalCpu.isNaN()) "CPU —" else "CPU " + Hud.fmt(totalCpu, 0) + "%"
        cpuText.setTextColor(Hud.loadColor(totalCpu))

        // Nota y permisos
        note.text = when (tab) {
            TAB_CPU -> "En vivo, cada 2 s. Porcentaje del total del procesador (todos los núcleos)."
            TAB_RAM -> if (pss != null) "Memoria real (PSS, igual que en Ajustes). Se mide cada 10 s." else "Midiendo la memoria real… mientras tanto, valores aproximados."
            TAB_STORAGE -> "Tamaño de la app + sus datos (incluye caché)."
            TAB_DATA -> "Wi-Fi + datos móviles desde las 00:00 de hoy."
            TAB_BATTERY -> "Batería gastada desde la última carga completa (cálculo de Android)."
            else -> "Tiempo en pantalla de hoy."
        }
        systemCheck.visibility = if (tab == TAB_CPU || tab == TAB_RAM) View.VISIBLE else View.GONE

        var permMsg = ""
        if (needsUsage(tab) && !usageOk) {
            permMsg = "Android pide el permiso \"Acceso a datos de uso\" para ver almacenamiento, datos y tiempo de uso por app."
        } else if ((tab == TAB_CPU || tab == TAB_RAM || tab == TAB_BATTERY) && !shell) {
            permMsg = if (shizuku == 3) "Conectando con Shizuku…" else "Esta pestaña necesita Shizuku activo y con permiso para la app."
        }
        permCard.visibility = if (permMsg.isEmpty()) View.GONE else View.VISIBLE
        permText.text = permMsg
        permShizuku.visibility = if (needsUsage(tab) && !usageOk && shell) View.VISIBLE else View.GONE
        permSettings.visibility = if (needsUsage(tab) && !usageOk) View.VISIBLE else View.GONE

        val list = buildRows()
        val max = list.maxOfOrNull { it.value } ?: 0f
        val loadingNow = loading.contains(tab)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = when {
            loadingNow -> "Cargando…"
            permMsg.isNotEmpty() -> ""
            tab == TAB_CPU && cpuStats.isEmpty() -> "Esperando la primera lectura…"
            else -> "Sin datos."
        }
        while (rows.size < list.size) {
            val r = AppRow(this)
            rows.add(r)
            listBox.addView(r)
        }
        for (i in rows.indices) {
            val r = rows[i]
            if (i < list.size) {
                r.visibility = View.VISIBLE
                r.bind(list[i], max)
            } else {
                r.visibility = View.GONE
            }
        }
    }

    private fun visible(key: String): Boolean =
        includeSystem || !key.startsWith(AppMonitor.SYSTEM_PREFIX)

    private fun buildRows(): List<Row> {
        val out = ArrayList<Row>()
        when (tab) {
            TAB_CPU -> {
                for (a in cpuStats) {
                    if (a.cpuPct.isNaN() || a.cpuPct < 0.05f || !visible(a.pkg)) continue
                    val sub = (if (a.procs > 1) "${a.procs} procesos · " else "") + "RAM " + Hud.mb(a.ramMb)
                    out.add(Row(a.pkg, a.cpuPct, Hud.fmt(a.cpuPct, 1) + " %", sub, Hud.loadColor(a.cpuPct * 3f)))
                }
                out.sortByDescending { it.value }
            }
            TAB_RAM -> {
                val p = pss
                if (p != null) {
                    for ((name, mb) in p) {
                        val key = if (name.contains('.')) name else AppMonitor.SYSTEM_PREFIX + name
                        if (!visible(key) || mb < 1f) continue
                        out.add(Row(key, mb, Hud.mb(mb), "Memoria real (PSS)", Hud.CYAN_SOFT))
                    }
                } else {
                    for (a in cpuStats) {
                        if (!visible(a.pkg) || a.ramMb < 1f) continue
                        out.add(Row(a.pkg, a.ramMb, "≈ " + Hud.mb(a.ramMb), "Memoria propia (aprox.)", Hud.CYAN_SOFT))
                    }
                }
                out.sortByDescending { it.value }
            }
            TAB_STORAGE -> {
                for (s in storage ?: emptyList()) {
                    if (s.total <= 0L) continue
                    val sub = "App " + Hud.bytes(s.appBytes) + " · Datos " + Hud.bytes(s.dataBytes - s.cacheBytes) + " · Caché " + Hud.bytes(s.cacheBytes)
                    out.add(Row(s.pkg, s.total.toFloat(), Hud.bytes(s.total), sub, Hud.CYAN_SOFT))
                }
            }
            TAB_DATA -> {
                for ((uid, arr) in data ?: emptyMap()) {
                    val total = arr[0] + arr[1]
                    if (total <= 0L) continue
                    val key = keyForUid(uid)
                    out.add(Row(key, total.toFloat(), Hud.bytes(total), "Wi-Fi " + Hud.bytes(arr[0]) + " · Móvil " + Hud.bytes(arr[1]), Hud.CYAN_SOFT))
                }
                out.sortByDescending { it.value }
            }
            TAB_BATTERY -> {
                val b = battery ?: emptyMap()
                val sum = b.values.sum()
                for ((uid, mah) in b) {
                    if (mah < 0.1f) continue
                    val pct = if (sum > 0f) mah * 100f / sum else 0f
                    out.add(Row(keyForUid(uid), mah, Hud.fmt(mah, if (mah < 10f) 1 else 0) + " mAh", Hud.fmt(pct, 1) + "% de lo que gastaron las apps", Hud.loadColor(pct * 2f)))
                }
                out.sortByDescending { it.value }
            }
            else -> {
                for ((pkg, ms) in usage ?: emptyMap()) {
                    if (ms < 1000L) continue
                    out.add(Row(pkg, ms.toFloat(), Hud.duration(ms), "En pantalla hoy", Hud.CYAN_SOFT))
                }
                out.sortByDescending { it.value }
            }
        }
        return out.take(MAX_ROWS)
    }

    private fun keyForUid(uid: Int): String {
        val appId = uid % 100000
        return when {
            uid == -4 -> AppMonitor.SYSTEM_PREFIX + "apps desinstaladas"
            uid == -5 -> AppMonitor.SYSTEM_PREFIX + "compartir internet"
            appId == 1000 -> AppMonitor.SYSTEM_PREFIX + "Android"
            appId == 0 -> AppMonitor.SYSTEM_PREFIX + "kernel"
            appId < 10000 -> AppInfoCache.packageForUid(this, uid) ?: (AppMonitor.SYSTEM_PREFIX + "uid $uid")
            else -> AppInfoCache.packageForUid(this, uid) ?: "uid $uid"
        }
    }

    private fun iconFor(key: String): Drawable? = icons.getOrPut(key) { AppInfoCache.icon(this, key) }

    /** Fila de la lista: icono, nombre, detalle, valor y barra proporcional. */
    private inner class AppRow(ctx: Context) : LinearLayout(ctx) {
        private val icon = ImageView(ctx)
        private val label = ui.text("", 16f, Hud.TEXT, Hud.bodyBold(ctx))
        private val sub = ui.text("", 12.5f, Hud.MUTED, Hud.body(ctx))
        private val value = ui.text("", 15f, Hud.TEXT, Hud.display(ctx), true)
        private val meter = MeterView(ctx)
        private var key = ""

        init {
            orientation = VERTICAL
            setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
            val press = StateListDrawable()
            press.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Hud.alpha(Hud.CYAN, 40)))
            press.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            background = press
            isClickable = true
            val top = LinearLayout(ctx)
            top.orientation = HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(icon, LayoutParams(ui.dp(36), ui.dp(36)))
            val texts = LinearLayout(ctx)
            texts.orientation = VERTICAL
            texts.setPadding(ui.dp(10), 0, ui.dp(6), 0)
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            texts.addView(label)
            texts.addView(sub)
            top.addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(value)
            addView(top)
            val mp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(5))
            mp.topMargin = ui.dp(6)
            mp.leftMargin = ui.dp(46)
            addView(meter, mp)
            setOnClickListener { showDetails(key) }
        }

        fun bind(r: Row, max: Float) {
            key = r.key
            label.text = AppInfoCache.label(context, r.key)
            sub.text = r.sub
            value.text = r.valueText
            value.setTextColor(r.color)
            val d = iconFor(r.key)
            if (d != null) {
                icon.imageTintList = null
                icon.setImageDrawable(d)
            } else {
                icon.setImageResource(if (r.key.startsWith(AppMonitor.SYSTEM_PREFIX)) R.drawable.ic_cog else R.drawable.ic_apps)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(Hud.CYAN_SOFT)
            }
            meter.setValue(if (max > 0f) r.value / max else 0f)
        }
    }

    // ------------------------------------------------------------------ Acciones

    private fun grantWithShizuku() {
        val sh = ShellClient.shell
        if (sh == null) {
            Toast.makeText(this, "Shizuku no está conectado", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val ok = UsageData.grantUsageAccess(this, sh)
            handler.post {
                Toast.makeText(this, if (ok) "Acceso concedido" else "No se pudo; actívalo en Ajustes", Toast.LENGTH_LONG).show()
                loadTab(tab, true)
                render()
            }
        }.start()
    }

    private fun openUsageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Throwable) {
            Toast.makeText(this, "Abre Ajustes > Acceso a datos de uso", Toast.LENGTH_LONG).show()
        }
    }

    private fun freeRam() {
        val sh = ShellClient.shell
        if (sh == null) {
            Toast.makeText(this, "Necesita Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        val am = getSystemService(ActivityManager::class.java)
        freeResult.text = "Liberando…"
        freeResult.setTextColor(Hud.CYAN_SOFT)
        Thread {
            val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            try {
                sh.sh("am kill-all", 8000)
            } catch (e: Throwable) {
            }
            try {
                Thread.sleep(1800)
            } catch (e: InterruptedException) {
            }
            val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            val freed = (after - before) / (1024f * 1024f)
            handler.post {
                freeResult.text = if (freed >= 10f) "Se liberaron " + Hud.mb(freed) + " de RAM" else "No había apps en segundo plano para cerrar"
                freeResult.setTextColor(if (freed >= 10f) Hud.GREEN else Hud.MUTED)
                pssAt = 0L
                render()
            }
        }.start()
    }

    private fun forceStop(pkg: String, dialog: Dialog) {
        val sh = ShellClient.shell ?: return
        Thread {
            try {
                sh.sh("am force-stop $pkg", 6000)
            } catch (e: Throwable) {
            }
            handler.post {
                Toast.makeText(this, AppInfoCache.label(this, pkg) + " detenida", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                pssAt = 0L
            }
        }.start()
    }

    /** Ventana con todo el consumo de una app. */
    private fun showDetails(key: String) {
        if (key.isEmpty()) return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ui.card(14f)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val iv = ImageView(this)
        val ic = iconFor(key)
        if (ic != null) iv.setImageDrawable(ic) else {
            iv.setImageResource(R.drawable.ic_apps)
            iv.imageTintList = android.content.res.ColorStateList.valueOf(Hud.CYAN_SOFT)
        }
        head.addView(iv, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(ui.dp(12), 0, 0, 0)
        names.addView(ui.text(AppInfoCache.label(this, key), 20f, Hud.TEXT, Hud.bodyBold(this), true))
        val pkgText = if (key.startsWith(AppMonitor.SYSTEM_PREFIX)) "Proceso del sistema" else key
        names.addView(ui.text(pkgText, 12.5f, Hud.MUTED, Hud.body(this)))
        head.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)
        box.addView(ui.divider())

        val stat = cpuStats.firstOrNull { it.pkg == key }
        val uid = AppInfoCache.uidOf(this, key) ?: stat?.uid
        detail(box, R.drawable.ic_chip, "CPU ahora", if (stat != null && !stat.cpuPct.isNaN()) Hud.fmt(stat.cpuPct, 1) + " %" + (if (stat.procs > 1) " (${stat.procs} procesos)" else "") else "—")
        val ram = pss?.get(key)
        detail(box, R.drawable.ic_ram, "RAM", if (ram != null) Hud.mb(ram) + " (PSS)" else if (stat != null) "≈ " + Hud.mb(stat.ramMb) else "—")
        val st = storage?.firstOrNull { it.pkg == key }
        detail(box, R.drawable.ic_storage, "Almacenamiento", if (st != null) Hud.bytes(st.total) + " (caché " + Hud.bytes(st.cacheBytes) + ")" else "Abre la pestaña Almacenamiento")
        val dt = if (uid != null) data?.get(uid) else null
        detail(box, R.drawable.ic_data, "Datos hoy", if (dt != null) "Wi-Fi " + Hud.bytes(dt[0]) + " · Móvil " + Hud.bytes(dt[1]) else "Abre la pestaña Datos hoy")
        val bt = if (uid != null) battery?.get(uid) else null
        detail(box, R.drawable.ic_battery, "Batería", if (bt != null) Hud.fmt(bt, 1) + " mAh desde la última carga" else "Abre la pestaña Batería")
        val us = usage?.get(key)
        detail(box, R.drawable.ic_timer, "Uso hoy", if (us != null) Hud.duration(us) else "Abre la pestaña Uso hoy")

        val isApp = !key.startsWith(AppMonitor.SYSTEM_PREFIX) && !key.startsWith("uid ")
        if (isApp) {
            val launch = packageManager.getLaunchIntentForPackage(key)
            if (launch != null) {
                box.addView(ui.button("Abrir", false, R.drawable.ic_open) {
                    d.dismiss()
                    startActivity(launch)
                }, ui.lp(top = 10))
            }
            box.addView(ui.button("Info de la app", false, R.drawable.ic_info) {
                d.dismiss()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$key")))
                } catch (e: Throwable) {
                }
            }, ui.lp(top = 6))
            if (ShellClient.shell != null && key != packageName) {
                box.addView(ui.button("Forzar detención", true, R.drawable.ic_stop) { forceStop(key, d) }, ui.lp(top = 6))
            }
        }
        box.addView(ui.button("Cerrar", false, R.drawable.ic_close) { d.dismiss() }, ui.lp(top = 6))

        val scroll = ScrollView(this)
        scroll.addView(box)
        d.setContentView(scroll)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun detail(box: LinearLayout, icon: Int, label: String, value: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, ui.dp(5), 0, ui.dp(5))
        row.addView(ui.icon(icon, 20, Hud.CYAN_SOFT))
        val l = ui.text(label, 14.5f, Hud.MUTED, Hud.bodySemi(this))
        l.setPadding(ui.dp(10), 0, ui.dp(8), 0)
        row.addView(l, LinearLayout.LayoutParams(ui.dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = ui.text(value, 15f, Hud.TEXT, Hud.bodyBold(this))
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
    }

    companion object {
        private const val TAB_CPU = 0
        private const val TAB_RAM = 1
        private const val TAB_STORAGE = 2
        private const val TAB_DATA = 3
        private const val TAB_BATTERY = 4
        private const val TAB_USAGE = 5
        private const val REFRESH_MS = 2000L
        private const val PSS_EVERY_MS = 10000L
        private const val MAX_ROWS = 40
    }
}

/** Barra horizontal fina con degradado neon (uso relativo). */
class MeterView(context: Context) : View(context) {
    private var value = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setValue(v: Float) {
        val nv = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
        if (nv != value) {
            value = nv
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        paint.shader = null
        paint.color = Hud.alpha(Hud.TRACK, 230)
        canvas.drawRoundRect(rect, r, r, paint)
        if (value > 0f) {
            rect.set(0f, 0f, maxOf(h, w * value), h)
            paint.shader = LinearGradient(0f, 0f, w, 0f, Hud.PURPLE, Hud.CYAN, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.shader = null
        }
    }
}
