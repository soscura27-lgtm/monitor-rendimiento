package com.rendimiento.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Resumen de cada sesion de juego: duracion, FPS, ping y temperaturas. Se guarda en preferencias. */
object SessionStore {

    private const val KEY = "sessions_v2"
    private const val MAX = 30

    class Session(
        val pkg: String,
        val start: Long,
        val durationMs: Long,
        val fpsAvg: Float,
        val fpsMin: Float,
        val fpsMax: Float,
        val low1: Float,
        val pingAvg: Float,
        val cpuTempMax: Float,
        val batTempMax: Float,
        val batUsed: Int
    )

    @Synchronized
    fun load(ctx: Context): List<Session> {
        val raw = Prefs.of(ctx).getString(KEY, null) ?: return emptyList()
        val out = ArrayList<Session>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Session(
                        pkg = o.optString("pkg"),
                        start = o.optLong("start"),
                        durationMs = o.optLong("dur"),
                        fpsAvg = f(o, "fpsAvg"),
                        fpsMin = f(o, "fpsMin"),
                        fpsMax = f(o, "fpsMax"),
                        low1 = f(o, "low1"),
                        pingAvg = f(o, "ping"),
                        cpuTempMax = f(o, "cpuT"),
                        batTempMax = f(o, "batT"),
                        batUsed = o.optInt("bat", -1)
                    )
                )
            }
        } catch (e: Throwable) {
        }
        return out
    }

    @Synchronized
    fun add(ctx: Context, s: Session) {
        val list = ArrayList<Session>()
        list.add(s)
        list.addAll(load(ctx))
        val arr = JSONArray()
        for (x in list.take(MAX)) {
            val o = JSONObject()
            o.put("pkg", x.pkg)
            o.put("start", x.start)
            o.put("dur", x.durationMs)
            put(o, "fpsAvg", x.fpsAvg)
            put(o, "fpsMin", x.fpsMin)
            put(o, "fpsMax", x.fpsMax)
            put(o, "low1", x.low1)
            put(o, "ping", x.pingAvg)
            put(o, "cpuT", x.cpuTempMax)
            put(o, "batT", x.batTempMax)
            o.put("bat", x.batUsed)
            arr.put(o)
        }
        Prefs.of(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        Prefs.of(ctx).edit().remove(KEY).apply()
    }

    private fun put(o: JSONObject, k: String, v: Float) {
        if (!v.isNaN()) o.put(k, v.toDouble())
    }

    private fun f(o: JSONObject, k: String): Float =
        if (o.has(k)) o.optDouble(k, Double.NaN).toFloat() else Float.NaN
}

/**
 * Sigue la sesion del juego al frente: junta FPS, ping y temperaturas mientras el juego esta
 * abierto y guarda el resumen cuando cambia de app o se cierra la barra.
 */
class SessionTracker(private val ctx: Context) {

    private var pkg: String? = null
    private var start = 0L
    private var lastSeen = 0L
    private var fpsSum = 0.0
    private var fpsN = 0
    private var fpsMin = Float.NaN
    private var fpsMax = Float.NaN
    private var low1 = Float.NaN
    private var pingSum = 0.0
    private var pingN = 0
    private var cpuTMax = Float.NaN
    private var batTMax = Float.NaN
    private var batStart = -1
    private var batLast = -1

    /** Promedio, minimo y maximo de FPS de la sesion actual (para mostrar en la barra). */
    val avg: Float get() = if (fpsN > 0) (fpsSum / fpsN).toFloat() else Float.NaN
    val min: Float get() = fpsMin
    val max: Float get() = fpsMax
    val elapsedMs: Long get() = if (pkg == null) 0L else lastSeen - start

    fun feed(s: Snapshot, now: Long) {
        val p = s.pkg
        val trackable = p != null && p != ctx.packageName && p != AppInfoCache.launcher(ctx) &&
            !p.startsWith("com.android.systemui") && p != "com.android.settings"
        if (!trackable) {
            // Volver a la barra o al menu no corta la sesion de inmediato.
            if (pkg != null && now - lastSeen > GRACE_MS) finish()
            return
        }
        if (p != pkg) {
            finish()
            begin(p!!, now, s)
        }
        lastSeen = now
        if (!s.fps.isNaN() && s.fps > 0f) {
            fpsSum += s.fps
            fpsN++
            if (fpsMin.isNaN() || s.fps < fpsMin) fpsMin = s.fps
            if (fpsMax.isNaN() || s.fps > fpsMax) fpsMax = s.fps
        }
        if (!s.low1.isNaN()) low1 = s.low1
        if (!s.ping.lastMs.isNaN()) {
            pingSum += s.ping.lastMs
            pingN++
        }
        if (!s.cpuTemp.isNaN() && (cpuTMax.isNaN() || s.cpuTemp > cpuTMax)) cpuTMax = s.cpuTemp
        if (!s.batTemp.isNaN() && (batTMax.isNaN() || s.batTemp > batTMax)) batTMax = s.batTemp
        if (s.batPct >= 0) batLast = s.batPct
    }

    private fun begin(p: String, now: Long, s: Snapshot) {
        pkg = p
        start = now
        lastSeen = now
        fpsSum = 0.0
        fpsN = 0
        fpsMin = Float.NaN
        fpsMax = Float.NaN
        low1 = Float.NaN
        pingSum = 0.0
        pingN = 0
        cpuTMax = Float.NaN
        batTMax = Float.NaN
        batStart = s.batPct
        batLast = s.batPct
    }

    fun finish() {
        val p = pkg ?: return
        pkg = null
        val dur = lastSeen - start
        if (dur < MIN_SESSION_MS || fpsN < MIN_SAMPLES) return
        if (!Prefs.of(ctx).getBoolean(Prefs.SESSIONS, true)) return
        val used = if (batStart >= 0 && batLast >= 0 && batStart >= batLast) batStart - batLast else -1
        SessionStore.add(
            ctx,
            SessionStore.Session(
                pkg = p,
                start = System.currentTimeMillis() - dur,
                durationMs = dur,
                fpsAvg = (fpsSum / fpsN).toFloat(),
                fpsMin = fpsMin,
                fpsMax = fpsMax,
                low1 = low1,
                pingAvg = if (pingN > 0) (pingSum / pingN).toFloat() else Float.NaN,
                cpuTempMax = cpuTMax,
                batTempMax = batTMax,
                batUsed = used
            )
        )
    }

    companion object {
        private const val GRACE_MS = 20000L
        private const val MIN_SESSION_MS = 30000L
        private const val MIN_SAMPLES = 15
    }
}
