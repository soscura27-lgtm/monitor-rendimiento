package com.rendimiento.monitor

import android.os.SystemClock
import kotlin.math.ceil

/**
 * Calcula los FPS de la app que esta al frente. Con permisos de shell (Shizuku) lee los
 * tiempos de presentacion de cada fotograma que guarda SurfaceFlinger
 * ("dumpsys SurfaceFlinger --latency <capa>") y de ahi saca FPS, tiempo por fotograma
 * y el "1% low" (el percentil 99 del tiempo por fotograma).
 */
class FpsEngine(
    private val ownPackage: String,
    private val shellProvider: () -> IShellService?
) {

    class Result(
        val pkg: String?,
        val fps: Float,
        val frameMs: Float,
        val low1: Float
    )

    private class LayerSample(
        val stamps: LongArray,
        val lastPresent: Long,
        val fps: Float,
        val countLast1s: Int
    )

    private var fgPkg: String? = null
    private var fgCheckedAt = 0L
    private var layers: List<String> = emptyList()
    private var layersAt = 0L
    private var fullScanAt = 0L

    private var chosenLayer: String? = null
    private var lastPresent = 0L

    private val ring = LongArray(RING_SIZE)
    private var ringCount = 0
    private var ringPos = 0

    fun poll(): Result {
        val sh = shellProvider() ?: return empty(null)
        val now = SystemClock.elapsedRealtime()

        if (now - fgCheckedAt >= FG_EVERY_MS) {
            fgCheckedAt = now
            val p = detectForeground(sh)
            if (p != fgPkg) {
                fgPkg = p
                layers = emptyList()
                layersAt = 0L
                fullScanAt = 0L
                chosenLayer = null
                resetStats()
            }
        }

        val pkg = fgPkg ?: return empty(null)
        if (pkg == ownPackage) return empty(pkg)

        if (layers.isEmpty() || now - layersAt >= LAYERS_EVERY_MS) {
            layersAt = now
            layers = findLayers(sh, pkg)
        }
        if (layers.isEmpty()) return empty(pkg)

        val picked = pick(sh, now) ?: return empty(pkg)
        val name = picked.first
        val sample = picked.second

        if (name != chosenLayer) {
            chosenLayer = name
            resetStats()
        }

        // Guardar el tiempo entre fotogramas nuevos (para el 1% low).
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

        val fps = if (stale) 0f else sample.fps
        val frameMs = if (fps > 0f) 1000f / fps else Float.NaN
        val low = if (stale) Float.NaN else lowFps()
        return Result(pkg, fps, frameMs, low)
    }

    private fun empty(pkg: String?): Result = Result(pkg, Float.NaN, Float.NaN, Float.NaN)

    private fun resetStats() {
        ringCount = 0
        ringPos = 0
        lastPresent = 0L
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

    /** Elige la capa (ventana) del juego: la mas reciente y con mas fotogramas. */
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
                (p.second.countLast1s == b.second.countLast1s &&
                    p.second.lastPresent > b.second.lastPresent)
            ) {
                best = p
            }
        }
        return best
    }

    private fun sampleLayer(sh: IShellService, name: String): LayerSample? {
        val out: String = try {
            sh.exec(arrayOf("/system/bin/dumpsys", "SurfaceFlinger", "--latency", name))
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

    private fun detectForeground(sh: IShellService): String? {
        val a = try {
            sh.exec(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -n 4"
                )
            )
        } catch (e: Throwable) {
            ""
        }
        val m1 = PKG_REGEX.find(a)
        if (m1 != null) return m1.groupValues[1]

        val w = try {
            sh.exec(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4"
                )
            )
        } catch (e: Throwable) {
            ""
        }
        val m2 = PKG_REGEX.find(w)
        return if (m2 != null) m2.groupValues[1] else null
    }

    private fun findLayers(sh: IShellService, pkg: String): List<String> {
        val out = try {
            sh.exec(arrayOf("/system/bin/dumpsys", "SurfaceFlinger", "--list"))
        } catch (e: Throwable) {
            ""
        }
        val all = ArrayList<String>()
        for (raw in out.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || !line.contains(pkg)) continue
            if (line.startsWith("Background for") || line.startsWith("Bounds for")) continue
            if (line.startsWith("Dim layer") || line.contains("Letterbox")) continue
            if (line.contains("Splash Screen")) continue
            all.add(line)
        }
        val surfaceViews = all.filter { it.startsWith("SurfaceView") }
        val others = all.filter { !it.startsWith("SurfaceView") }
        return (surfaceViews + others).take(MAX_LAYERS)
    }

    companion object {
        private const val RING_SIZE = 1800
        private const val MIN_FOR_LOW = 30
        private const val MAX_LAYERS = 8

        private const val FG_EVERY_MS = 3000L
        private const val LAYERS_EVERY_MS = 6000L
        private const val FULL_SCAN_MS = 10000L

        private const val ONE_SEC_NS = 1_000_000_000L
        private const val MAX_DELTA_NS = 1_000_000_000L
        private const val ACTIVE_WINDOW_NS = 1_500_000_000L

        private val WS = Regex("\\s+")
        private val PKG_REGEX = Regex("\\bu\\d+\\s+([A-Za-z0-9_.]+)/")
    }
}
