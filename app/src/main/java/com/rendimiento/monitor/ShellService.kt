package com.rendimiento.monitor

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Servicio que Shizuku ejecuta en un proceso aparte con permisos de "shell" (como adb).
 * Desde aqui se pueden leer archivos del sistema, correr dumpsys y pedirle a WindowManager
 * los FPS de un juego, cosas que una app normal no puede hacer.
 */
class ShellService() : IShellService.Stub() {

    @Suppress("unused")
    constructor(context: Context) : this()

    init {
        allowHiddenApi()
    }

    override fun destroy() {
        try {
            synchronized(fpsLock) { stopTaskFpsLocked() }
        } catch (e: Throwable) {
        }
        exitProcess(0)
    }

    // ------------------------------------------------------------------ Archivos y comandos

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

    override fun exec(args: Array<String>): String = run(args.toList(), EXEC_TIMEOUT_MS)

    override fun sh(command: String, timeoutMs: Int): String =
        run(listOf("/system/bin/sh", "-c", command), timeoutMs.toLong().coerceIn(300L, 60000L))

    /**
     * Corre un proceso y junta su salida. Si tarda mas de [timeoutMs] se mata: antes un
     * dumpsys colgado podia congelar la barra para siempre.
     */
    private fun run(cmd: List<String>, timeoutMs: Long): String {
        var process: Process? = null
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = p
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(sb) {
                                if (sb.length < MAX_OUT) sb.append(buf, 0, minOf(n, MAX_OUT - sb.length))
                            }
                        }
                    }
                } catch (e: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
            reader.join(800)
            synchronized(sb) { sb.toString() }
        } catch (e: Throwable) {
            try {
                process?.destroyForcibly()
            } catch (x: Throwable) {
            }
            ""
        }
    }

    // ------------------------------------------------------------------ Procesos

    override fun procTable(): String {
        val sb = StringBuilder(64 * 1024)
        val names = File("/proc").list() ?: return ""
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            try {
                val stat = File("/proc/$pid/stat").readText()
                val open = stat.indexOf('(')
                val close = stat.lastIndexOf(')')
                if (open < 0 || close < 0 || close + 2 > stat.length) continue
                val comm = stat.substring(open + 1, close)
                // Campos despues del nombre: [0]=estado ... [11]=utime, [12]=stime.
                val after = stat.substring(close + 2).split(' ')
                val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L

                var uid = -1
                var rss = 0L
                var anon = 0L
                var shmem = 0L
                var swap = 0L
                for (line in File("/proc/$pid/status").readLines()) {
                    when {
                        line.startsWith("Uid:") -> uid = firstNumber(line, 4).toInt()
                        line.startsWith("VmRSS:") -> rss = firstNumber(line, 6)
                        line.startsWith("RssAnon:") -> anon = firstNumber(line, 8)
                        line.startsWith("RssShmem:") -> shmem = firstNumber(line, 9)
                        line.startsWith("VmSwap:") -> swap = firstNumber(line, 7)
                    }
                }
                if (uid < 0) continue

                var cmd = try {
                    File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
                } catch (e: Throwable) {
                    ""
                }
                // Hilos del kernel: no tienen cmdline, se usa el nombre corto.
                if (cmd.isEmpty()) cmd = "[$comm]"

                sb.append(pid).append('|').append(uid).append('|')
                    .append(utime).append('|').append(stime).append('|')
                    .append(rss).append('|').append(anon).append('|')
                    .append(shmem).append('|').append(swap).append('|')
                    .append(cmd.replace('|', '_').replace('\n', ' ')).append('\n')
            } catch (e: Throwable) {
                // El proceso pudo terminar mientras se leia: se ignora.
            }
            if (sb.length >= MAX_OUT) break
        }
        return sb.toString()
    }

    private fun firstNumber(line: String, from: Int): Long {
        var i = from
        while (i < line.length && !line[i].isDigit()) i++
        var v = 0L
        while (i < line.length && line[i].isDigit()) {
            v = v * 10 + (line[i] - '0')
            i++
        }
        return v
    }

    // ------------------------------------------------------------------ Red

    override fun sockets(): String {
        val sb = StringBuilder()
        appendSockets(sb, "/proc/net/tcp", "tcp")
        appendSockets(sb, "/proc/net/tcp6", "tcp")
        appendSockets(sb, "/proc/net/udp", "udp")
        appendSockets(sb, "/proc/net/udp6", "udp")
        return sb.toString()
    }

    private fun appendSockets(sb: StringBuilder, path: String, proto: String) {
        val lines = try {
            File(path).readLines()
        } catch (e: Throwable) {
            return
        }
        for (i in 1 until lines.size) {
            val f = lines[i].trim().split(WS)
            if (f.size < 8) continue
            val uid = f[7].toIntOrNull() ?: continue
            if (uid < APP_UID_MIN) continue
            val local = f[1]
            val remote = f[2]
            val lc = local.lastIndexOf(':')
            val rc = remote.lastIndexOf(':')
            if (lc <= 0 || rc <= 0) continue
            val localPort = local.substring(lc + 1).toIntOrNull(16) ?: continue
            val remotePort = remote.substring(rc + 1).toIntOrNull(16) ?: continue
            val ip = NetUtil.hexToIp(remote.substring(0, rc)) ?: continue
            sb.append(uid).append('|').append(proto).append('|').append(f[3]).append('|')
                .append(ip).append('|').append(remotePort).append('|').append(localPort).append('\n')
            if (sb.length >= MAX_OUT) return
        }
    }

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
            val sport = f.firstOrNull { it.startsWith("sport=") }?.substringAfter('=') ?: continue
            val dst = f.firstOrNull { it.startsWith("dst=") }?.substringAfter('=') ?: continue
            sb.append(proto).append('|').append(sport).append('|').append(dst).append('|')
                .append(f.firstOrNull { it.startsWith("dport=") }?.substringAfter('=') ?: "0")
                .append('\n')
            if (sb.length >= MAX_OUT) break
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ FPS por task

    /*
     * Android 13+ tiene IWindowManager.registerTaskFpsCallback(taskId, callback): SurfaceFlinger
     * calcula los FPS de todas las capas del task (el juego) y los reporta cada ~0,5 s. Es lo que usa
     * el "Game Dashboard" del sistema. Necesita el permiso ACCESS_FPS_COUNTER, que el usuario shell
     * tiene, asi que funciona desde este proceso. Sirve aunque "dumpsys SurfaceFlinger --latency"
     * ya no de datos (Android 15/16).
     */
    private val fpsLock = Any()
    private var wm: Any? = null
    private var registerMethod: Method? = null
    private var unregisterMethod: Method? = null
    private var callbackClass: Class<*>? = null
    private var callback: Any? = null

    @Volatile
    private var fpsTask = -1

    @Volatile
    private var fpsValue = -1f

    @Volatile
    private var fpsAt = 0L

    @Volatile
    private var fpsReports = 0

    @Volatile
    private var fpsRepeats = 0

    private inner class FpsBinder(private val task: Int) : Binder() {
        init {
            attachInterface(null, TASK_FPS_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                try {
                    data.enforceInterface(TASK_FPS_DESCRIPTOR)
                    onFps(task, data.readFloat())
                } catch (e: Throwable) {
                }
                // Si la llamada no es "oneway", el sistema espera una respuesta sin error.
                try {
                    reply?.writeNoException()
                } catch (e: Throwable) {
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun onFps(task: Int, fps: Float) {
        if (task != fpsTask) return
        if (fps == fpsValue) fpsRepeats++ else fpsRepeats = 0
        fpsValue = fps
        fpsAt = SystemClock.elapsedRealtime()
        fpsReports++
    }

    override fun watchTaskFps(taskId: Int): String {
        synchronized(fpsLock) {
            if (taskId == fpsTask && callback != null) return ""
            stopTaskFpsLocked()
            if (taskId < 0) return ""
            return try {
                ensureWindowManager()
                val cbClass = callbackClass ?: return "sin ITaskFpsCallback"
                val binder = FpsBinder(taskId)
                val handler = InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "asBinder" -> binder
                        "onFpsReported" -> {
                            val v = args?.getOrNull(0)
                            if (v is Float) onFps(taskId, v)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "MonitorFpsCallback#$taskId"
                        else -> null
                    }
                }
                val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass), handler)
                fpsTask = taskId
                fpsValue = -1f
                fpsAt = 0L
                fpsReports = 0
                fpsRepeats = 0
                registerMethod!!.invoke(wm, taskId, cb)
                callback = cb
                ""
            } catch (e: Throwable) {
                fpsTask = -1
                callback = null
                val cause = if (e is InvocationTargetException && e.targetException != null) e.targetException else e
                cause.javaClass.simpleName + ": " + (cause.message ?: "")
            }
        }
    }

    override fun taskFps(): String {
        val t = fpsTask
        if (t < 0) return ""
        val at = fpsAt
        val age = if (at == 0L) -1L else SystemClock.elapsedRealtime() - at
        return t.toString() + "|" + String.format(Locale.US, "%.2f", fpsValue) + "|" + age + "|" +
            fpsReports + "|" + fpsRepeats
    }

    private fun stopTaskFpsLocked() {
        val cb = callback
        callback = null
        fpsTask = -1
        if (cb != null) {
            try {
                unregisterMethod?.invoke(wm, cb)
            } catch (e: Throwable) {
            }
        }
    }

    private fun ensureWindowManager() {
        if (wm != null) return
        val sm = Class.forName("android.os.ServiceManager")
        val b = sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val iwm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, b)
            ?: throw IllegalStateException("WindowManager no disponible")
        val iface = Class.forName("android.view.IWindowManager")
        val cb = Class.forName("android.window.ITaskFpsCallback")
        registerMethod = iface.getMethod("registerTaskFpsCallback", Int::class.javaPrimitiveType, cb)
        unregisterMethod = iface.getMethod("unregisterTaskFpsCallback", cb)
        callbackClass = cb
        wm = iwm
    }

    /** En el proceso de Shizuku casi nunca hay restricciones de API ocultas; por si acaso se quitan. */
    private fun allowHiddenApi() {
        try {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val runtime = vm.getDeclaredMethod("getRuntime").invoke(null)
            val set = vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            set.invoke(runtime, arrayOf("L"))
        } catch (e: Throwable) {
        }
    }

    companion object {
        private const val MAX_FILE = 400000
        private const val MAX_OUT = 600000
        private const val EXEC_TIMEOUT_MS = 8000L
        private const val APP_UID_MIN = 10000
        private const val TASK_FPS_DESCRIPTOR = "android.window.ITaskFpsCallback"
        private val WS = Regex("\\s+")
    }
}
