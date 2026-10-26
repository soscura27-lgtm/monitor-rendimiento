package com.rendimiento.monitor

import android.content.Context
import java.io.File
import kotlin.system.exitProcess

/**
 * Servicio que Shizuku ejecuta en un proceso aparte con permisos de "shell" (como adb).
 * Desde aqui se pueden leer archivos del sistema y correr dumpsys, cosas que una app normal no puede.
 */
class ShellService() : IShellService.Stub() {

    @Suppress("unused")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun readFiles(paths: Array<String>): Array<String> {
        return Array(paths.size) { i ->
            try {
                File(paths[i]).inputStream().use { input ->
                    val text = String(input.readBytes())
                    if (text.length > MAX_FILE) text.substring(0, MAX_FILE) else text
                }
            } catch (e: Throwable) {
                ""
            }
        }
    }

    override fun listDir(path: String): String {
        return try {
            val names = File(path).list()
            if (names == null) "" else names.joinToString("\n")
        } catch (e: Throwable) {
            ""
        }
    }

    override fun exec(args: Array<String>): String {
        return try {
            val process = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
            val sb = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sb.length < MAX_OUT) sb.append(buf, 0, n)
                }
            }
            process.waitFor()
            sb.toString()
        } catch (e: Throwable) {
            ""
        }
    }

    override fun procTable(): String {
        val sb = StringBuilder()
        val dirs = File("/proc").listFiles() ?: return ""
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                val stat = File(d, "stat").readText()
                // El nombre del proceso va entre parentesis y puede tener espacios: se salta hasta el ultimo ')'.
                val after = stat.substring(stat.lastIndexOf(')') + 2).trim().split(' ')
                // Campos tras el nombre: [0]=estado ... [11]=utime, [12]=stime (campos 14 y 15 de stat).
                val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L

                var uid = -1
                var rssKb = 0L
                for (line in File(d, "status").readLines()) {
                    if (line.startsWith("Uid:")) {
                        uid = line.substring(4).trim().split(WS).firstOrNull()?.toIntOrNull() ?: -1
                    } else if (line.startsWith("VmRSS:")) {
                        rssKb = line.substring(6).trim().split(WS).firstOrNull()?.toLongOrNull() ?: 0L
                    }
                }
                if (uid < 0) continue

                val cmd = try {
                    File(d, "cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
                } catch (e: Throwable) {
                    ""
                }

                var rx = -1L
                var tx = -1L
                if (uid >= APP_UID_MIN) {
                    rx = readLongOrNeg("/proc/uid_stat/$uid/tcp_rcv")
                    tx = readLongOrNeg("/proc/uid_stat/$uid/tcp_snd")
                }

                sb.append(pid).append('|').append(uid).append('|')
                    .append(utime).append('|').append(stime).append('|')
                    .append(rssKb).append('|').append(rx).append('|').append(tx).append('|')
                    .append(cmd.replace('|', '_').replace('\n', ' ')).append('\n')
            } catch (e: Throwable) {
                // El proceso pudo terminar mientras se leia: se ignora.
            }
            if (sb.length >= MAX_OUT) break
        }
        return sb.toString()
    }

    override fun sockets(): String {
        val sb = StringBuilder()
        for ((file, proto) in listOf(
            "/proc/net/tcp" to "tcp", "/proc/net/udp" to "udp"
        )) {
            appendSockets(sb, file, proto)
        }
        return sb.toString()
    }

    private fun appendSockets(sb: StringBuilder, path: String, proto: String) {
        val lines = try {
            File(path).readLines()
        } catch (e: Throwable) {
            return
        }
        for (line in lines.drop(1)) {
            val f = line.trim().split(WS)
            if (f.size < 8) continue
            val uid = f[7].toIntOrNull() ?: continue
            if (uid < APP_UID_MIN) continue
            val localPort = parsePort(f[1]) ?: continue
            val state = f[3]
            val remoteParts = f[2].split(':')
            if (remoteParts.size != 2) continue
            val ipHex = remoteParts[0]
            val remotePort = remoteParts[1].toIntOrNull(16) ?: continue
            if (ipHex.length != 8) continue
            // /proc/net/tcp y udp guardan la IPv4 en orden little-endian.
            val b = (0 until 4).map { ipHex.substring(it * 2, it * 2 + 2).toInt(16) }
            val ip = "${b[3]}.${b[2]}.${b[1]}.${b[0]}"
            // Los UDP sin conexion (0.0.0.0:0) se incluyen igual: su puerto local sirve para cruzar con conntrack.
            sb.append(uid).append('|').append(proto).append('|').append(state).append('|')
                .append(ip).append('|').append(remotePort).append('|').append(localPort).append('\n')
        }
    }

    private fun parsePort(addr: String): Int? = addr.substringAfter(':', "").toIntOrNull(16)

    override fun conntrack(): String {
        val lines = try {
            File("/proc/net/nf_conntrack").readLines()
        } catch (e: Throwable) {
            return ""
        }
        val sb = StringBuilder()
        for (line in lines) {
            val f = line.trim().split(WS)
            if (f.size < 4) continue
            val proto = f[2]
            if (proto != "tcp" && proto != "udp") continue
            // La primera aparicion de cada clave es la direccion original (la que sale del telefono).
            val sport = f.firstOrNull { it.startsWith("sport=") }?.substringAfter('=') ?: continue
            val dst = f.firstOrNull { it.startsWith("dst=") }?.substringAfter('=') ?: continue
            if (dst.contains(':')) continue
            sb.append(proto).append('|').append(sport).append('|').append(dst).append('|')
                .append(f.firstOrNull { it.startsWith("dport=") }?.substringAfter('=') ?: "0")
                .append('\n')
            if (sb.length >= MAX_OUT) break
        }
        return sb.toString()
    }

    private fun readLongOrNeg(path: String): Long =
        try {
            File(path).readText().trim().toLong()
        } catch (e: Throwable) {
            -1L
        }

    companion object {
        private const val MAX_FILE = 20000
        private const val MAX_OUT = 200000
        private const val APP_UID_MIN = 10000
        private val WS = Regex("\\s+")
    }
}
