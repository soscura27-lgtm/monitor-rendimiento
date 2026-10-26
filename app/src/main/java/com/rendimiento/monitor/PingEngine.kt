package com.rendimiento.monitor

import kotlin.math.roundToInt

/**
 * Mide el ping del juego que esta (o estuvo) al frente, para cualquier juego.
 *
 * Busca el servidor en tres pasos, del mas exacto al mas general:
 * 1. IP remota publica de los sockets del UID del juego (TCP y UDP conectado).
 * 2. Juegos con UDP sin conexion: sus puertos locales se cruzan con la tabla de conexiones
 *    del kernel (nf_conntrack), que si conoce a donde se envio cada paquete.
 * 3. Si nada de lo anterior aparece, ping a internet (1.1.1.1). Se marca como "internet"
 *    porque no es el servidor del juego, pero siempre da una cifra mientras el juego tenga red.
 *
 * Corre en su propio hilo para que un ping lento (hasta 1 s de espera) no retrase FPS ni CPU.
 */
class PingEngine(
    private val shellProvider: () -> IShellService?,
    private val uidProvider: () -> Int?
) {

    class Result(
        val ip: String?,
        val kind: Int,
        val lastMs: Float,
        val avgMs: Float
    )

    private val window = FloatArray(WINDOW)
    private var count = 0
    private var pos = 0

    @Volatile
    private var targetIp: String? = null

    @Volatile
    private var targetKind = KIND_NONE

    @Volatile
    private var lastMs = Float.NaN

    @Volatile
    private var avgMs = Float.NaN

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "monitor-ping")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        targetIp = null
        targetKind = KIND_NONE
        lastMs = Float.NaN
        avgMs = Float.NaN
        count = 0
        pos = 0
    }

    /** Lectura actual; nunca bloquea. */
    fun current(): Result = Result(targetIp, targetKind, lastMs, avgMs)

    private fun loop() {
        var lastTargetCheck = 0L
        while (running) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastTargetCheck >= TARGET_EVERY_MS || targetIp == null) {
                    lastTargetCheck = now
                    val t = findTarget()
                    val ip = t?.first
                    if (ip != targetIp) {
                        targetIp = ip
                        targetKind = t?.second ?: KIND_NONE
                        count = 0
                        pos = 0
                        lastMs = Float.NaN
                        avgMs = Float.NaN
                    }
                }
                val ip = targetIp
                if (ip != null) {
                    val ms = pingOnce(ip)
                    if (!ms.isNaN()) push(ms)
                    lastMs = ms
                    avgMs = average()
                }
                Thread.sleep(PING_EVERY_MS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                try {
                    Thread.sleep(PING_EVERY_MS)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    /** Devuelve (IP, tipo) del destino a medir, o null si no hay juego conocido. */
    private fun findTarget(): Pair<String, Int>? {
        val uid = uidProvider() ?: return null
        val sh = shellProvider() ?: return null

        val raw = try {
            sh.sockets()
        } catch (e: Throwable) {
            ""
        }
        val remoteCounts = HashMap<String, Int>()
        val localPorts = HashSet<Int>()
        for (line in raw.lineSequence()) {
            val f = line.split('|')
            if (f.size != 6) continue
            if (f[0].toIntOrNull() != uid) continue
            f[5].toIntOrNull()?.let { localPorts.add(it) }
            val ip = f[3]
            if (!isPublicIpv4(ip)) continue
            // TCP en ESTABLISHED (01) pesa mas que un UDP suelto.
            val weight = if (f[1] == "tcp" && f[2] == "01") 2 else 1
            remoteCounts[ip] = (remoteCounts[ip] ?: 0) + weight
        }
        remoteCounts.maxByOrNull { it.value }?.let { return Pair(it.key, KIND_SERVER) }

        if (localPorts.isNotEmpty()) {
            val ct = try {
                sh.conntrack()
            } catch (e: Throwable) {
                ""
            }
            val counts = HashMap<String, Int>()
            for (line in ct.lineSequence()) {
                val f = line.split('|')
                if (f.size != 4) continue
                val sport = f[1].toIntOrNull() ?: continue
                if (sport !in localPorts) continue
                val ip = f[2]
                if (!isPublicIpv4(ip)) continue
                counts[ip] = (counts[ip] ?: 0) + 1
            }
            counts.maxByOrNull { it.value }?.let { return Pair(it.key, KIND_SERVER) }
        }

        return Pair(FALLBACK_IP, KIND_INTERNET)
    }

    private fun pingOnce(ip: String): Float {
        val sh = shellProvider() ?: return Float.NaN
        val out = try {
            sh.exec(arrayOf("/system/bin/ping", "-c", "1", "-W", "1", ip))
        } catch (e: Throwable) {
            return Float.NaN
        }
        val m = TIME_REGEX.find(out) ?: return Float.NaN
        return m.groupValues[1].toFloatOrNull() ?: Float.NaN
    }

    private fun push(ms: Float) {
        window[pos] = ms
        pos = (pos + 1) % WINDOW
        if (count < WINDOW) count++
    }

    private fun average(): Float {
        if (count == 0) return Float.NaN
        var sum = 0f
        for (i in 0 until count) sum += window[i]
        return sum / count
    }

    companion object {
        const val KIND_NONE = 0
        const val KIND_SERVER = 1
        const val KIND_INTERNET = 2

        private const val FALLBACK_IP = "1.1.1.1"
        private const val WINDOW = 10
        private const val PING_EVERY_MS = 1000L
        private const val TARGET_EVERY_MS = 5000L
        private val TIME_REGEX = Regex("time[=<]([0-9.]+)\\s*ms")

        /** Filtra IPs privadas, loopback, multicast y reservadas: solo sirven servidores publicos. */
        fun isPublicIpv4(ip: String): Boolean {
            val p = ip.split('.')
            if (p.size != 4) return false
            val a = p[0].toIntOrNull() ?: return false
            val b = p[1].toIntOrNull() ?: return false
            return when {
                a == 0 || a == 10 || a == 127 -> false
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 168 -> false
                a == 100 && b in 64..127 -> false
                a >= 224 -> false
                else -> true
            }
        }

        fun fmtMs(v: Float): String = v.roundToInt().toString()
    }
}
