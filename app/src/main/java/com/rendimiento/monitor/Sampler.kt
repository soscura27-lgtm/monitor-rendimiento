package com.rendimiento.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import java.io.File
import kotlin.math.abs

/**
 * Junta todas las metricas en un Snapshot. Lo que una app normal no puede leer
 * (FPS de otros juegos, uso de CPU, GPU) se lee a traves del servicio de shell de Shizuku;
 * si no esta disponible, esos datos salen vacios y el resto (RAM, bateria, termico) sigue funcionando.
 */
class Sampler(
    private val ctx: Context,
    private val shellProvider: () -> IShellService?
) {

    private val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val fpsEngine = FpsEngine(ctx.packageName, shellProvider)
    private val appMonitor = AppMonitor(shellProvider)

    /** UID del ultimo juego (cualquier app que no sea la barra) que estuvo al frente. */
    @Volatile
    private var gameUid: Int? = null
    private val pingEngine = PingEngine(shellProvider) { gameUid }

    private var prevIdle = -1L
    private var prevTotal = -1L

    /** Arranca el hilo de ping. Llamar una vez al iniciar la barra. */
    fun startBackground() {
        pingEngine.start()
    }

    /** Detiene el hilo de ping al cerrar la barra. */
    fun stopBackground() {
        pingEngine.stop()
        gameUid = null
    }

    private var zonesViaShell: Boolean? = null
    private var cpuZones: List<String> = emptyList()
    private var gpuZones: List<String> = emptyList()

    fun sample(): Snapshot {
        val shellOk = shellProvider() != null
        ensureZones(shellOk)

        // Una sola lectura en lote para todos los archivos del sistema.
        val paths = ArrayList<String>()
        paths.add("/proc/stat")
        for (i in 0 until MAX_CPUS) {
            paths.add("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
        }
        val gpuBase = paths.size
        paths.add("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        paths.add("/sys/class/kgsl/kgsl-3d0/gpubusy")
        paths.add("/sys/class/kgsl/kgsl-3d0/gpuclk")
        paths.add("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
        val zoneBase = paths.size
        paths.addAll(cpuZones)
        paths.addAll(gpuZones)

        val r = readFiles(paths)

        // CPU: uso total a partir de /proc/stat (solo legible con shell).
        var cpuPct = Float.NaN
        var totalDelta = -1L
        val statLine = r[0].lineSequence().firstOrNull { it.startsWith("cpu ") }
        if (statLine != null) {
            val v = statLine.trim().split(WS).drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size >= 4) {
                val idle = v[3] + (if (v.size > 4) v[4] else 0L)
                var total = 0L
                for (k in 0 until minOf(v.size, 8)) total += v[k]
                if (prevTotal >= 0L && total > prevTotal) {
                    val dTotal = total - prevTotal
                    totalDelta = dTotal
                    val dIdle = idle - prevIdle
                    cpuPct = ((dTotal - dIdle).toFloat() / dTotal.toFloat() * 100f).coerceIn(0f, 100f)
                }
                prevTotal = total
                prevIdle = idle
            }
        }

        // CPU: frecuencia mas alta entre los nucleos (kHz -> MHz).
        var cpuMhz = -1
        for (i in 0 until MAX_CPUS) {
            val khz = r[1 + i].trim().toLongOrNull() ?: continue
            val mhz = (khz / 1000L).toInt()
            if (mhz > cpuMhz) cpuMhz = mhz
        }

        // GPU (Adreno / kgsl). Otros chips pueden no exponer estos archivos.
        var gpuPct = Float.NaN
        val busyPct = r[gpuBase].trim()
        if (busyPct.isNotEmpty()) {
            gpuPct = leadingNumber(busyPct)
        } else {
            val parts = r[gpuBase + 1].trim().split(WS)
            if (parts.size >= 2) {
                val busy = parts[0].toLongOrNull()
                val total = parts[1].toLongOrNull()
                if (busy != null && total != null && total > 0L) {
                    gpuPct = (busy.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f)
                }
            }
        }
        var gpuMhz = -1
        val clk = r[gpuBase + 2].trim().toLongOrNull() ?: r[gpuBase + 3].trim().toLongOrNull()
        if (clk != null) gpuMhz = toMhz(clk)

        // Temperaturas de las zonas termicas.
        var cpuTemp = Float.NaN
        for (k in cpuZones.indices) {
            val t = parseTemp(r[zoneBase + k])
            if (!t.isNaN() && (cpuTemp.isNaN() || t > cpuTemp)) cpuTemp = t
        }
        var gpuTemp = Float.NaN
        for (k in gpuZones.indices) {
            val t = parseTemp(r[zoneBase + cpuZones.size + k])
            if (!t.isNaN() && (gpuTemp.isNaN() || t > gpuTemp)) gpuTemp = t
        }

        // RAM
        val mi = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        val ramTotal = mi.totalMem / (1024L * 1024L)
        val ramUsed = (mi.totalMem - mi.availMem) / (1024L * 1024L)

        // Bateria
        var batPct = -1
        var batTemp = Float.NaN
        var voltage = 0
        var charging = false
        val bi: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batPct = level * 100 / scale
            val t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != Int.MIN_VALUE) batTemp = t / 10f
            voltage = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        var batMa = Int.MIN_VALUE
        val current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Int.MIN_VALUE && current != 0) {
            // Algunos telefonos reportan microamperios y otros miliamperios.
            batMa = if (abs(current) >= 20000) current / 1000 else current
        }
        var batW = Float.NaN
        if (batMa != Int.MIN_VALUE && voltage > 0) {
            batW = abs(batMa) / 1000f * (voltage / 1000f)
        }

        val thermal = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else -1

        val display: Display? = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val hz = display?.refreshRate ?: Float.NaN

        val f = fpsEngine.poll()

        // Ping y consumo por app. El ping sigue al ultimo juego: si la barra o el menu quedan
        // al frente, se mantiene la medicion del juego y no se pierde el dato.
        val fgPkg = f.pkg
        if (fgPkg != null && fgPkg != ctx.packageName) {
            appMonitor.uidOf(fgPkg)?.let { gameUid = it }
        }
        appMonitor.update(totalDelta, System.currentTimeMillis())
        val ping = pingEngine.current()

        return Snapshot(
            fpsStatus = f.status,
            pingMs = ping.lastMs,
            pingAvgMs = ping.avgMs,
            pingIp = ping.ip,
            pingKind = ping.kind,
            apps = appMonitor.current(),
            shellOk = shellOk,
            pkg = f.pkg,
            fps = f.fps,
            frameMs = f.frameMs,
            low1 = f.low1,
            refreshHz = hz,
            cpuPct = cpuPct,
            cpuMhz = cpuMhz,
            cpuTemp = cpuTemp,
            gpuPct = gpuPct,
            gpuMhz = gpuMhz,
            gpuTemp = gpuTemp,
            ramUsedMb = ramUsed,
            ramTotalMb = ramTotal,
            batPct = batPct,
            batTemp = batTemp,
            batMa = batMa,
            batW = batW,
            charging = charging,
            thermalStatus = thermal
        )
    }

    /** Busca las zonas termicas de CPU y GPU. Se repite cuando cambia el acceso a shell. */
    private fun ensureZones(viaShell: Boolean) {
        if (zonesViaShell == viaShell) return
        zonesViaShell = viaShell

        val names = listDir("/sys/class/thermal").filter { it.startsWith("thermal_zone") }
        val types = readFiles(names.map { "/sys/class/thermal/$it/type" })

        val cpu = ArrayList<String>()
        val gpu = ArrayList<String>()
        for (i in names.indices) {
            val type = types[i].trim().lowercase()
            val tempPath = "/sys/class/thermal/${names[i]}/temp"
            if (type.contains("gpu")) {
                if (gpu.size < 4) gpu.add(tempPath)
            } else if (type.contains("cpu") || type.contains("soc") ||
                type.contains("big") || type.contains("little")
            ) {
                if (cpu.size < 8) cpu.add(tempPath)
            }
        }
        cpuZones = cpu
        gpuZones = gpu
    }

    private fun readFiles(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val sh = shellProvider()
        if (sh != null) {
            try {
                val res = sh.readFiles(paths.toTypedArray())
                if (res != null && res.size == paths.size) return res.toList()
            } catch (e: Throwable) {
                // Si falla Shizuku, se intenta leer directo abajo.
            }
        }
        return paths.map { p ->
            try {
                File(p).readText()
            } catch (e: Throwable) {
                ""
            }
        }
    }

    private fun listDir(path: String): List<String> {
        val sh = shellProvider()
        if (sh != null) {
            try {
                val out = sh.listDir(path)
                if (out != null && out.isNotEmpty()) {
                    return out.split("\n").filter { it.isNotEmpty() }
                }
            } catch (e: Throwable) {
                // Se intenta directo abajo.
            }
        }
        return try {
            val names = File(path).list()
            if (names == null) emptyList() else names.toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun leadingNumber(s: String): Float {
        val m = NUMBER.find(s) ?: return Float.NaN
        return m.value.toFloatOrNull()?.coerceIn(0f, 100f) ?: Float.NaN
    }

    private fun toMhz(v: Long): Int {
        return when {
            v >= 10_000_000L -> (v / 1_000_000L).toInt()
            v >= 10_000L -> (v / 1000L).toInt()
            else -> v.toInt()
        }
    }

    private fun parseTemp(s: String): Float {
        val v = s.trim().toFloatOrNull() ?: return Float.NaN
        val c = if (abs(v) >= 1000f) v / 1000f else v
        return if (c >= -20f && c <= 150f) c else Float.NaN
    }

    companion object {
        private const val MAX_CPUS = 12
        private val WS = Regex("\\s+")
        private val NUMBER = Regex("-?\\d+(\\.\\d+)?")
    }
}
