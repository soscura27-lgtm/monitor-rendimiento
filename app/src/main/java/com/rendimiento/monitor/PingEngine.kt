package com.rendimiento.monitor

import android.os.SystemClock
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * Mide el ping en tiempo real (una vez por segundo), en su propio hilo.
 *
 * Servidor: se buscan las conexiones del juego en /proc/net/tcp, tcp6, udp y udp6. Antes solo se
 * leian tcp y udp (IPv4), pero Android abre casi todos los sockets como IPv6 (con la IPv4 adentro,
 * ::ffff:a.b.c.d), asi que el juego nunca aparecia y la barra se quedaba en "buscando servidor".
 * Se prefiere UDP con puerto distinto de 443 (trafico de partida), luego TCP.
 *
 * Medicion, de la mas real a la mas general:
 * 1. RTT que el kernel ya calculo para la conexion TCP del juego ("ss -ti"), sin mandar nada.
 * 2. Ping ICMP (ping / ping6).
 * 3. Si el servidor bloquea ICMP (pasa mucho en juegos): tiempo del saludo TCP al servidor.
 * 4. Si no hay juego o su servidor no responde: ping a internet (1.1.1.1 / 8.8.8.8), marcado asi.
 *
 * Tambien calcula promedio, jitter y perdida de paquetes de los ultimos 20 intentos.
 */
class PingEngine(
    private val shellProvider: () -> IShellService?,
    private val uidProvider: () -> Int?
) {

    class Result(
        val target: String?,
        val kind: Int,
        val method: Int,
        val lastMs: Float,
        val avgMs: Float,
        val jitterMs: Float,
        val lossPct: Float,
        val status: String
    ) {
        /** Texto para mostrar de donde sale el ping. */
        fun label(): String {
            val k = when (kind) {
                KIND_SERVER -> "servidor del juego"
                KIND_GAME_TCP -> "servidor de la app"
                KIND_INTERNET -> "internet"
                else -> ""
            }
            val m = when (method) {
                M_TCP -> " · TCP"
                M_KERNEL -> " · conexión"
                else -> ""
            }
            return k + m
        }

        fun shortLabel(): String = when (kind) {
            KIND_SERVER -> "SRV"
            KIND_GAME_TCP -> "APP"
            KIND_INTERNET -> "NET"
            else -> ""
        }

        companion object {
            val EMPTY = Result(null, KIND_NONE, M_NONE, Float.NaN, Float.NaN, Float.NaN, Float.NaN, "")
        }
    }

    private class Target(val ip: String, val kind: Int, val port: Int, val udp: Boolean) {
        var icmpFails = 0
        var icmpOk = false
        var tcpFails = 0
        var tcpPort = -1
        var lastMethod = M_NONE
    }

    private class Candidate(val ip: String) {
        var score = 0
        var udp = false
        var udpPort = 0
        var tcpPort = 0
    }

    @Volatile
    private var result = Result.EMPTY

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private var target: Target? = null
    private val samples = FloatArray(WINDOW)
    private var count = 0
    private var pos = 0
    private val banned = HashMap<String, Long>()
    private var lastScan = 0L
    private var tick = 0L
    private var internetIdx = 0
    private var ssState = 0

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
    }

    /** Lectura actual; nunca bloquea. */
    fun current(): Result = result

    private fun loop() {
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                step()
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                // Un error de red no debe detener el hilo.
            }
            val spent = SystemClock.elapsedRealtime() - t0
            try {
                Thread.sleep((PING_EVERY_MS - spent).coerceAtLeast(150L))
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun step() {
        tick++
        val now = SystemClock.elapsedRealtime()
        val sh = shellProvider()
        if (target == null || now - lastScan >= SCAN_EVERY_MS) {
            lastScan = now
            scan(sh, now)
        }
        val t = target ?: return
        measure(sh, t, now)
    }

    // ------------------------------------------------------------------ Elegir servidor

    private fun scan(sh: IShellService?, now: Long) {
        val uid = uidProvider()
        val cands = if (sh != null && uid != null) candidates(sh, uid) else emptyList()
        val usable = cands.filter { (banned[it.ip] ?: 0L) <= now }
        val best = usable.maxByOrNull { it.score }
        val cur = target
        if (cur != null && cur.kind != KIND_INTERNET) {
            val same = usable.firstOrNull { it.ip == cur.ip }
            // Se mantiene el servidor actual mientras siga conectado y no haya otro mucho mejor.
            if (same != null && (best == null || same.score * 10 >= best.score * 7)) return
        }
        if (best != null && best.score >= MIN_SCORE) {
            val kind = if (best.udp) KIND_SERVER else KIND_GAME_TCP
            val port = if (best.udp) best.udpPort else best.tcpPort
            setTarget(Target(best.ip, kind, port, best.udp))
            return
        }
        if (cur == null || cur.kind != KIND_INTERNET) setTarget(internetTarget())
    }

    private fun candidates(sh: IShellService, uid: Int): List<Candidate> {
        val raw = try {
            sh.sockets() ?: ""
        } catch (e: Throwable) {
            ""
        }
        val map = HashMap<String, Candidate>()
        for (line in raw.lineSequence()) {
            val f = line.split('|')
            if (f.size != 6) continue
            if (f[0].toIntOrNull() != uid) continue
            val proto = f[1]
            val state = f[2]
            val ip = f[3]
            val rport = f[4].toIntOrNull() ?: continue
            if (rport == 0 || rport == 53 || rport == 853 || rport == 5353) continue
            if (!NetUtil.isPublic(ip)) continue
            val c = map.getOrPut(ip) { Candidate(ip) }
            if (proto == "udp") {
                if (rport == 443) {
                    c.score += 4
                } else {
                    // UDP conectado a un puerto de juego: casi seguro es la partida.
                    c.score += 100
                    c.udp = true
                    c.udpPort = rport
                }
            } else {
                val established = state == "01"
                val web = rport == 443 || rport == 80 || rport == 8080
                c.score += when {
                    !established -> 1
                    web -> 8
                    else -> 40
                }
                if (established && (c.tcpPort == 0 || !web)) c.tcpPort = rport
            }
        }
        return map.values.toList()
    }

    private fun internetTarget(): Target {
        val ip = INTERNET_HOSTS[internetIdx % INTERNET_HOSTS.size]
        return Target(ip, KIND_INTERNET, 443, false)
    }

    private fun setTarget(t: Target) {
        target = t
        count = 0
        pos = 0
        result = Result(t.ip, t.kind, M_NONE, Float.NaN, Float.NaN, Float.NaN, Float.NaN, "Midiendo…")
    }

    // ------------------------------------------------------------------ Medir

    private fun measure(sh: IShellService?, t: Target, now: Long) {
        var ms = Float.NaN
        var method = M_NONE
        var attempted = false

        // 1) RTT del kernel para la conexion TCP del juego.
        if (sh != null && t.kind == KIND_GAME_TCP && ssState >= 0 && !NetUtil.isIpv6(t.ip)) {
            ms = kernelRtt(sh, t.ip)
            if (!ms.isNaN()) {
                method = M_KERNEL
                attempted = true
            }
        }

        // 2) ICMP.
        val icmpUsable = sh != null && (if (t.icmpOk) t.icmpFails < 6 else t.icmpFails < 3)
        if (ms.isNaN() && icmpUsable && sh != null) {
            attempted = true
            ms = icmp(sh, t.ip)
            if (ms.isNaN()) {
                t.icmpFails++
            } else {
                t.icmpFails = 0
                t.icmpOk = true
                method = M_ICMP
            }
        }

        // 3) Saludo TCP (cada 2 s para no molestar al servidor).
        if (ms.isNaN() && !icmpUsable) {
            if (t.lastMethod == M_TCP && tick % 2L != 0L) return
            attempted = true
            ms = tcpPing(t)
            if (ms.isNaN()) t.tcpFails++ else {
                t.tcpFails = 0
                method = M_TCP
            }
            if (ms.isNaN() && t.tcpFails >= 3) {
                // Este destino no responde de ninguna forma: se descarta un rato.
                if (t.kind == KIND_INTERNET) internetIdx++ else banned[t.ip] = now + BAN_MS
                target = null
                lastScan = 0L
                return
            }
        }

        if (!attempted) return
        if (method != M_NONE) t.lastMethod = method
        push(ms)
        publish(t, ms)
    }

    private fun icmp(sh: IShellService, ip: String): Float {
        val v6 = NetUtil.isIpv6(ip)
        var out = try {
            sh.exec(arrayOf(if (v6) "/system/bin/ping6" else "/system/bin/ping", "-c", "1", "-W", "1", ip)) ?: ""
        } catch (e: Throwable) {
            ""
        }
        if (v6 && TIME.find(out) == null && (out.isEmpty() || out.contains("not found"))) {
            out = try {
                sh.exec(arrayOf("/system/bin/ping", "-6", "-c", "1", "-W", "1", ip)) ?: ""
            } catch (e: Throwable) {
                ""
            }
        }
        return TIME.find(out)?.groupValues?.get(1)?.toFloatOrNull() ?: Float.NaN
    }

    /** RTT suavizado que el kernel mantiene para la conexion TCP (solo si tuvo trafico reciente). */
    private fun kernelRtt(sh: IShellService, ip: String): Float {
        val out = try {
            sh.sh("ss -tin 2>&1 | grep -F -A1 '$ip'", 2500) ?: ""
        } catch (e: Throwable) {
            ""
        }
        if (out.contains("not found") || out.contains("inaccessible") || out.contains("denied")) {
            ssState = -1
            return Float.NaN
        }
        var best = Float.NaN
        val lines = out.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.contains("$ip:") && !line.contains("$ip]:")) continue
            val info = lines.getOrNull(i + 1) ?: continue
            val rtt = RTT.find(info)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val lastRcv = LAST_RCV.find(info)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
            if (lastRcv > 3000L) continue
            ssState = 1
            if (best.isNaN() || rtt < best) best = rtt
        }
        return best
    }

    /** Tiempo hasta que el servidor contesta el saludo TCP (SYN-ACK) o lo rechaza (RST). */
    private fun tcpPing(t: Target): Float {
        val ports = ArrayList<Int>()
        if (t.tcpPort > 0) ports.add(t.tcpPort)
        if (t.port > 0 && !ports.contains(t.port)) ports.add(t.port)
        if (!ports.contains(443)) ports.add(443)
        for (p in ports) {
            val ms = connectTime(t.ip, p)
            if (!ms.isNaN()) {
                t.tcpPort = p
                return ms
            }
        }
        return Float.NaN
    }

    private fun connectTime(ip: String, port: Int): Float {
        val s = Socket()
        val t0 = System.nanoTime()
        return try {
            s.setSoLinger(true, 0)
            s.connect(InetSocketAddress(InetAddress.getByName(ip), port), TCP_TIMEOUT_MS)
            (System.nanoTime() - t0) / 1_000_000f
        } catch (e: ConnectException) {
            // "Connection refused" tambien es una respuesta del servidor: sirve como medicion.
            val ms = (System.nanoTime() - t0) / 1_000_000f
            val msg = e.message ?: ""
            if (ms < TCP_TIMEOUT_MS * 0.9f && (msg.contains("ECONNREFUSED") || msg.contains("refused", true))) ms else Float.NaN
        } catch (e: Throwable) {
            Float.NaN
        } finally {
            try {
                s.close()
            } catch (e: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ Estadisticas

    private fun push(ms: Float) {
        samples[pos] = ms
        pos = (pos + 1) % WINDOW
        if (count < WINDOW) count++
    }

    private fun publish(t: Target, ms: Float) {
        var ok = 0
        var sum = 0f
        var jitSum = 0f
        var jitN = 0
        var prev = Float.NaN
        // Recorre en orden cronologico.
        for (k in 0 until count) {
            val idx = (pos - count + k + WINDOW) % WINDOW
            val v = samples[idx]
            if (v.isNaN()) continue
            ok++
            sum += v
            if (!prev.isNaN()) {
                jitSum += abs(v - prev)
                jitN++
            }
            prev = v
        }
        val avg = if (ok > 0) sum / ok else Float.NaN
        val jitter = if (jitN > 0) jitSum / jitN else Float.NaN
        val loss = if (count > 0) (count - ok) * 100f / count else Float.NaN
        val status = if (ms.isNaN()) "Sin respuesta" else ""
        result = Result(t.ip, t.kind, t.lastMethod, ms, avg, jitter, loss, status)
    }

    companion object {
        const val KIND_NONE = 0
        const val KIND_SERVER = 1
        const val KIND_GAME_TCP = 2
        const val KIND_INTERNET = 3

        const val M_NONE = 0
        const val M_ICMP = 1
        const val M_TCP = 2
        const val M_KERNEL = 3

        private val INTERNET_HOSTS = arrayOf("1.1.1.1", "8.8.8.8")
        private const val WINDOW = 20
        private const val PING_EVERY_MS = 1000L
        private const val SCAN_EVERY_MS = 4000L
        private const val BAN_MS = 90000L
        private const val MIN_SCORE = 8
        private const val TCP_TIMEOUT_MS = 1200
        private val TIME = Regex("time[=<]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms")
        private val RTT = Regex("\\brtt:([0-9.]+)/")
        private val LAST_RCV = Regex("\\blastrcv:(\\d+)")
    }
}
