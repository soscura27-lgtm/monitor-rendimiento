package com.rendimiento.monitor

import android.os.Build
import android.os.SystemClock
import kotlin.math.ceil

/**
 * Calcula los FPS de la app que esta al frente, con permisos de shell (Shizuku).
 *
 * Dos fuentes, de la mas confiable a la menos:
 * 1. FPS del task (Android 13+): WindowManager/SurfaceFlinger reportan los FPS de todas las capas
 *    del juego cada ~0,5 s. Funciona en Android 13, 14, 15 y 16.
 * 2. "dumpsys SurfaceFlinger --latency <capa>": tiempos de cada fotograma. Da tambien el tiempo por
 *    fotograma y el 1% low, pero en Android 15/16 muchas veces ya no devuelve datos.
 *
 * Antes solo se usaba la 2 y ademas el nombre de la capa salia mal en Android 15+
 * ("dumpsys SurfaceFlinger --list" ahora escribe "RequestedLayerState{nombre parentId=...}"),
 * por eso la barra se quedaba en "Esperando fotogramas".
 */
class FpsEngine(
    private val ownPackage: String,
    private val shellProvider: () -> IShellService?
) {

    class Result(
        val pkg: String?,
        val taskId: Int,
        val fps: Float,
        val frameMs: Float,
        val low1: Float,
        val source: Int,
        val status: String
    )

    private class LayerSample(
        val stamps: LongArray,
        val lastPresent: Long,
        val fps: Float,
        val countLast1s: Int
    )

    // App al frente
    private var fgPkg: String? = null
    private var fgTask = -1
    private var fgCheckedAt = 0L

    // Fuente 1: FPS del task
    private var taskSupported = Build.VERSION.SDK_INT >= 33
    private var watchedTask = -1
    private var watchedAt = 0L
    private var watchTriedAt = 0L
    private var watchError = ""
    private var taskSilent = false

    // Fuente 2: capas de SurfaceFlinger
    private var layers: List<String> = emptyList()
    private var layersAt = 0L
    private var fullScanAt = 0L
    private var layerFailAt = 0L
    private var chosenLayer: String? = null
    private var lastPresent = 0L
    private var layerFps = Float.NaN

    private val ring = LongArray(RING_SIZE)
    private var ringCount = 0
    private var ringPos = 0

    /** Paquete y task de la app al frente (los usan el ping y el historial). */
    val currentPkg: String?
        get() = fgPkg

    fun poll(): Result {
        val sh = shellProvider() ?: return empty(null, "Sin Shizuku")
        val now = SystemClock.elapsedRealtime()

        if (now - fgCheckedAt >= FG_EVERY_MS) {
            fgCheckedAt = now
            val fg = detectForeground(sh)
            val p = fg?.first
            val t = fg?.second ?: -1
            if (p != fgPkg || t != fgTask) {
                fgPkg = p
                fgTask = t
                watchTriedAt = 0L
                taskSilent = false
                resetLayers()
            }
        }

        val pkg = fgPkg ?: return empty(null, "Buscando app al frente…")
        if (pkg == ownPackage) return empty(pkg, "Abre un juego")

        // ---- Fuente 1: FPS del task
        var taskFps = Float.NaN
        if (taskSupported && fgTask >= 0) {
            if (watchedTask != fgTask && now - watchTriedAt >= WATCH_RETRY_MS) {
                watchTriedAt = now
                val err = try {
                    sh.watchTaskFps(fgTask) ?: ""
                } catch (e: Throwable) {
                    "RemoteException: " + (e.message ?: "")
                }
                if (err.isEmpty()) {
                    watchedTask = fgTask
                    watchedAt = now
                    watchError = ""
                } else {
                    watchedTask = -1
                    watchError = err
                    // Si el sistema no tiene la funcion o no da permiso, no se reintenta en cada tick.
                    if (err.contains("NoSuchMethod") || err.contains("ClassNotFound") ||
                        err.contains("SecurityException")
                    ) {
                        taskSupported = false
                    }
                }
            }
            if (watchedTask == fgTask) {
                val st = try {
                    sh.taskFps() ?: ""
                } catch (e: Throwable) {
                    ""
                }
                val f = st.split('|')
                if (f.size >= 5 && f[0].toIntOrNull() == fgTask) {
                    val v = f[1].toFloatOrNull() ?: -1f
                    val age = f[2].toLongOrNull() ?: -1L
                    val repeats = f[4].toIntOrNull() ?: 0
                    when {
                        // Registrado pero aun sin reportes.
                        age < 0L -> taskSilent = now - watchedAt > SILENT_MS
                        age <= TASK_STALE_MS -> {
                            taskSilent = false
                            // Si el valor se repite exacto muchas veces, el juego no esta dibujando:
                            // SurfaceFlinger sigue promediando fotogramas viejos.
                            if (v >= 0f) taskFps = if (repeats >= STALE_REPEATS) 0f else v
                        }
                        age <= REREGISTER_MS -> taskFps = 0f
                        // Demasiado tiempo sin reportes: se vuelve a registrar.
                        else -> watchedTask = -1
                    }
                } else {
                    // El proceso de shell se reinicio y perdio el registro: hay que repetirlo.
                    watchedTask = -1
                }
            }
        }

        // ---- Fuente 2: capas (si la 1 funciona, solo se usa para ms y 1% low)
        val layerOk = pollLayers(sh, pkg, now, !taskFps.isNaN())
        val low = if (layerOk) lowFps() else Float.NaN

        if (!taskFps.isNaN()) {
            val ms = if (taskFps > 0f) 1000f / taskFps else Float.NaN
            return Result(pkg, fgTask, taskFps, ms, low, SRC_TASK, if (taskFps <= 0f) "Sin fotogramas nuevos" else "")
        }
        if (layerOk && !layerFps.isNaN()) {
            val ms = if (layerFps > 0f) 1000f / layerFps else Float.NaN
            return Result(pkg, fgTask, layerFps, ms, low, SRC_LAYER, if (layerFps <= 0f) "Sin fotogramas nuevos" else "")
        }

        val why = when {
            watchedTask == fgTask && fgTask >= 0 && taskSilent -> "El sistema no reporta FPS"
            watchedTask == fgTask && fgTask >= 0 -> "Esperando fotogramas…"
            watchError.isNotEmpty() && layers.isEmpty() -> "FPS no disponible (" + watchError.substringBefore(':') + ")"
            layers.isEmpty() -> "Sin capa de video para esta app"
            else -> "Esperando fotogramas…"
        }
        return empty(pkg, why)
    }

    private fun empty(pkg: String?, why: String): Result =
        Result(pkg, fgTask, Float.NaN, Float.NaN, Float.NaN, SRC_NONE, why)

    // ------------------------------------------------------------------ Capas de SurfaceFlinger

    private fun resetLayers() {
        layers = emptyList()
        layersAt = 0L
        fullScanAt = 0L
        layerFailAt = 0L
        chosenLayer = null
        layerFps = Float.NaN
        resetStats()
    }

    private fun resetStats() {
        ringCount = 0
        ringPos = 0
        lastPresent = 0L
    }

    /** Devuelve true si hay datos recientes de una capa (layerFps y el anillo de tiempos). */
    private fun pollLayers(sh: IShellService, pkg: String, now: Long, taskWorks: Boolean): Boolean {
        // Si un barrido completo no encontro datos, no se repite en cada tick (ahorra CPU y no
        // molesta al juego). Si los FPS del task ya funcionan, se reintenta aun menos.
        val retry = if (taskWorks) LAYER_RETRY_TASK_MS else LAYER_RETRY_MS
        if (chosenLayer == null && layerFailAt != 0L && now - layerFailAt < retry) return false

        if (layers.isEmpty() || now - layersAt >= LAYERS_EVERY_MS) {
            layersAt = now
            layers = findLayers(sh, pkg)
        }
        if (layers.isEmpty()) {
            layerFailAt = now
            return false
        }

        val picked = pick(sh, now)
        if (picked == null) {
            chosenLayer = null
            layerFailAt = now
            return false
        }
        val name = picked.first
        val sample = picked.second
        if (name != chosenLayer) {
            chosenLayer = name
            resetStats()
        }

        var prev = 0L
        for (t in sample.stamps) {
            if (t > lastPresent && prev > 0L) {
                val d = t - prev
                if (d > 0L && d < MAX_DELTA_NS) pushDelta(d)
            }
            prev = t
        }
        val stale = sample.lastPresent <= lastPresent
        if (sample.lastPresent > lastPresent) lastPresent = sample.lastPresent
        layerFps = if (stale) 0f else sample.fps
        return true
    }

    private fun pushDelta(d: Long) {
        ring[ringPos] = d
        ringPos = (ringPos + 1) % RING_SIZE
        if (ringCount < RING_SIZE) ringCount++
    }

    private fun lowFps(): Float {
        if (ringCount < MIN_FOR_LOW) return Float.NaN
        val copy = LongArray(ringCount)
        for (i in 0 until ringCount) copy[i] = ring[i]
        copy.sort()
        var idx = ceil(ringCount * 0.99).toInt() - 1
        if (idx < 0) idx = 0
        if (idx >= ringCount) idx = ringCount - 1
        val ms = copy[idx] / 1_000_000f
        return if (ms > 0f) 1000f / ms else Float.NaN
    }

    /** Elige la capa del juego: la mas reciente y con mas fotogramas. */
    private fun pick(sh: IShellService, now: Long): Pair<String, LayerSample>? {
        val chosen = chosenLayer
        if (chosen != null && layers.contains(chosen) && now - fullScanAt < FULL_SCAN_MS) {
            val s = sampleLayer(sh, chosen)
            if (s != null && s.lastPresent > lastPresent) return Pair(chosen, s)
        }

        fullScanAt = now
        var maxLast = 0L
        val found = ArrayList<Pair<String, LayerSample>>()
        for (name in layers) {
            val s = sampleLayer(sh, name) ?: continue
            found.add(Pair(name, s))
            if (s.lastPresent > maxLast) maxLast = s.lastPresent
        }

        var best: Pair<String, LayerSample>? = null
        for (p in found) {
            if (p.second.lastPresent < maxLast - ACTIVE_WINDOW_NS) continue
            val b = best
            if (b == null ||
                p.second.countLast1s > b.second.countLast1s ||
                (p.second.countLast1s == b.second.countLast1s && p.second.lastPresent > b.second.lastPresent)
            ) {
                best = p
            }
        }
        return best
    }

    private fun sampleLayer(sh: IShellService, name: String): LayerSample? {
        val out: String = try {
            sh.exec(arrayOf("/system/bin/dumpsys", "SurfaceFlinger", "--latency", name)) ?: ""
        } catch (e: Throwable) {
            return null
        }
        if (out.isEmpty()) return null

        val list = ArrayList<Long>(130)
        for (line in out.lineSequence()) {
            val parts = line.trim().split(WS)
            if (parts.size != 3) continue
            val t = parts[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            list.add(t)
        }
        if (list.size < 2) return null

        val arr = list.toLongArray()
        arr.sort()
        val last = arr[arr.size - 1]
        var firstIdx = arr.size - 1
        while (firstIdx > 0 && last - arr[firstIdx - 1] <= ONE_SEC_NS) firstIdx--
        val n = arr.size - firstIdx
        val span = last - arr[firstIdx]
        val fps: Float = if (n >= 2 && span > 0L) {
            (n - 1) * 1_000_000_000f / span
        } else {
            val d = last - arr[arr.size - 2]
            if (d > 0L && d <= 5_000_000_000L) 1_000_000_000f / d else 0f
        }
        return LayerSample(arr, last, fps, n)
    }

    private fun findLayers(sh: IShellService, pkg: String): List<String> {
        val out = try {
            sh.exec(arrayOf("/system/bin/dumpsys", "SurfaceFlinger", "--list")) ?: ""
        } catch (e: Throwable) {
            ""
        }
        val all = LinkedHashSet<String>()
        for (raw in out.lines()) {
            val name = layerName(raw) ?: continue
            if (!name.contains(pkg)) continue
            if (isIgnored(name)) continue
            all.add(name)
        }
        val blast = all.filter { it.contains("SurfaceView[") && it.contains("BLAST") }
        val surfaceViews = all.filter { it.contains("SurfaceView[") && !it.contains("BLAST") }
        val windows = all.filter { !it.contains("SurfaceView[") && it.contains("$pkg/") }
        val others = all.filter { !it.contains("SurfaceView[") && !it.contains("$pkg/") }
        return (blast + surfaceViews + windows + others).take(MAX_LAYERS)
    }

    // ------------------------------------------------------------------ App al frente

    /** (paquete, taskId) de la actividad al frente. taskId = -1 si no se pudo leer. */
    private fun detectForeground(sh: IShellService): Pair<String, Int>? {
        val a = try {
            sh.sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity|mFocusedApp' | head -n 6", 5000) ?: ""
        } catch (e: Throwable) {
            ""
        }
        ACT_REGEX.find(a)?.let { return Pair(it.groupValues[1], it.groupValues[2].toIntOrNull() ?: -1) }
        PKG_REGEX.find(a)?.let { return Pair(it.groupValues[1], -1) }

        val w = try {
            sh.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4", 5000) ?: ""
        } catch (e: Throwable) {
            ""
        }
        ACT_REGEX.find(w)?.let { return Pair(it.groupValues[1], it.groupValues[2].toIntOrNull() ?: -1) }
        PKG_REGEX.find(w)?.let { return Pair(it.groupValues[1], -1) }
        return null
    }

    companion object {
        const val SRC_NONE = 0
        const val SRC_TASK = 1
        const val SRC_LAYER = 2

        private const val RING_SIZE = 1800
        private const val MIN_FOR_LOW = 30
        private const val MAX_LAYERS = 6

        private const val FG_EVERY_MS = 2000L
        private const val LAYERS_EVERY_MS = 6000L
        private const val FULL_SCAN_MS = 10000L
        private const val LAYER_RETRY_MS = 10000L
        private const val LAYER_RETRY_TASK_MS = 45000L
        private const val WATCH_RETRY_MS = 4000L
        private const val TASK_STALE_MS = 3000L
        private const val REREGISTER_MS = 12000L
        private const val SILENT_MS = 8000L
        private const val STALE_REPEATS = 6

        private const val ONE_SEC_NS = 1_000_000_000L
        private const val MAX_DELTA_NS = 1_000_000_000L
        private const val ACTIVE_WINDOW_NS = 1_500_000_000L

        private val WS = Regex("\\s+")
        private val ACT_REGEX = Regex("ActivityRecord\\{[0-9a-fA-F]+ u\\d+ ([A-Za-z0-9_.]+)/[^\\s}]+ t(\\d+)")
        private val PKG_REGEX = Regex("\\bu\\d+\\s+([A-Za-z0-9_.]+)/")

        private val END_MARKERS = arrayOf(
            " parentId=", " relativeParentId=", " mirrorId=", " !handle", " z=", " layerStack="
        )

        /**
         * Saca el nombre de la capa de una linea de "dumpsys SurfaceFlinger --list".
         * Android 14 o menos: la linea es el nombre. Android 15+: "RequestedLayerState{nombre parentId=..}".
         */
        fun layerName(line: String): String? {
            var s = line.trim()
            if (s.isEmpty()) return null
            val prefix = "RequestedLayerState{"
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length)
                var end = if (s.endsWith("}")) s.length - 1 else s.length
                for (m in END_MARKERS) {
                    val i = s.indexOf(m)
                    if (i in 0 until end) end = i
                }
                s = s.substring(0, end).trim()
            }
            if (s.startsWith("Display ") || s.contains(" HWC layers")) return null
            return s.ifEmpty { null }
        }

        private fun isIgnored(name: String): Boolean {
            val n = name
            return n.contains("Background for") || n.contains("Bounds for") || n.contains("Dim layer") ||
                n.contains("Letterbox") || n.contains("Splash Screen") || n.startsWith("ActivityRecord{") ||
                n.contains("ActivityRecordInputSink") || n.contains("InputSink") || n.startsWith("Task=") ||
                n.startsWith("WindowToken") || n.contains("Leaf:")
        }
    }
}
