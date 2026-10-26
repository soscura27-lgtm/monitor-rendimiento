package com.rendimiento.monitor

/**
 * Consumo en vivo por aplicacion: CPU (%) y RAM (MB), agrupados por paquete.
 *
 * Usa la tabla de procesos del servicio de shell (Shizuku). Un paquete puede tener varios
 * procesos ("com.juego:remote", procesos aislados de Chrome, etc.); todos se suman.
 * La RAM rapida es memoria propia (anonima + compartida propia + swap), no cuenta librerias
 * compartidas; la pantalla de consumo puede pedir la RAM exacta (PSS) con MemInfo.
 */
class AppMonitor(
    private val shellProvider: () -> IShellService?,
    private val uidResolver: (Int) -> String?
) {

    class AppStat(
        val pkg: String,
        val uid: Int,
        val cpuPct: Float,
        val ramMb: Float,
        val procs: Int,
        val system: Boolean
    )

    private var prevProc = HashMap<Int, Long>()
    private var prevTotal = -1L

    @Volatile
    private var stats: List<AppStat> = emptyList()

    @Volatile
    var updatedAt = 0L
        private set

    /** Ultima lista, ordenada por CPU y luego RAM. Nunca bloquea. */
    fun current(): List<AppStat> = stats

    @Synchronized
    fun update() {
        val sh = shellProvider() ?: run {
            stats = emptyList()
            return
        }
        val statText = try {
            sh.readFiles(arrayOf("/proc/stat"))?.firstOrNull() ?: ""
        } catch (e: Throwable) {
            ""
        }
        val total = totalJiffies(statText)
        val dTotal = if (prevTotal >= 0L && total > prevTotal) total - prevTotal else -1L
        if (total > 0L) prevTotal = total

        val raw = try {
            sh.procTable() ?: ""
        } catch (e: Throwable) {
            return
        }

        val procJ = HashMap<Int, Long>()
        val cpuBy = HashMap<String, Long>()
        val ramBy = HashMap<String, Long>()
        val procsBy = HashMap<String, Int>()
        val uidBy = HashMap<String, Int>()

        for (line in raw.lineSequence()) {
            val f = line.split('|', limit = 9)
            if (f.size < 9) continue
            val pid = f[0].toIntOrNull() ?: continue
            val uid = f[1].toIntOrNull() ?: continue
            val ut = f[2].toLongOrNull() ?: continue
            val st = f[3].toLongOrNull() ?: continue
            val rss = f[4].toLongOrNull() ?: 0L
            val anon = f[5].toLongOrNull() ?: 0L
            val shmem = f[6].toLongOrNull() ?: 0L
            val swap = f[7].toLongOrNull() ?: 0L
            val cmd = f[8]

            val jiffies = ut + st
            procJ[pid] = jiffies
            val prev = prevProc[pid]
            val delta = if (prev != null && jiffies >= prev) jiffies - prev else 0L

            val key = packageOf(uid, cmd)
            val ownKb = if (anon > 0L || shmem > 0L) anon + shmem + swap else rss + swap
            cpuBy[key] = (cpuBy[key] ?: 0L) + delta
            ramBy[key] = (ramBy[key] ?: 0L) + ownKb
            procsBy[key] = (procsBy[key] ?: 0) + 1
            if (!uidBy.containsKey(key)) uidBy[key] = uid
        }

        val out = ArrayList<AppStat>(cpuBy.size)
        for ((key, jiff) in cpuBy) {
            val cpu = if (dTotal > 0L) (jiff.toFloat() / dTotal.toFloat() * 100f).coerceIn(0f, 100f) else Float.NaN
            val uid = uidBy[key] ?: -1
            out.add(
                AppStat(
                    pkg = key,
                    uid = uid,
                    cpuPct = cpu,
                    ramMb = (ramBy[key] ?: 0L) / 1024f,
                    procs = procsBy[key] ?: 0,
                    system = key.startsWith(SYSTEM_PREFIX)
                )
            )
        }
        prevProc = procJ
        stats = out.sortedWith(
            compareByDescending<AppStat> { if (it.cpuPct.isNaN()) -1f else it.cpuPct }.thenByDescending { it.ramMb }
        )
        updatedAt = System.currentTimeMillis()
    }

    /** Nombre del grupo: paquete para apps; "sistema:nombre" para procesos del sistema. */
    private fun packageOf(uid: Int, cmd: String): String {
        val base = cmd.substringBefore(':').substringAfterLast('/').trim()
        val appId = uid % 100000
        if (appId in 10000 until 90000) {
            // El UID dice exactamente de que app es (el nombre del proceso puede ser otro, p. ej. "...gms.persistent").
            return uidResolver(uid) ?: (if (PKG_NAME.matches(base)) base else "uid $uid")
        }
        if (appId >= 90000) {
            // Procesos aislados y zygotes de apps: se reconocen por el nombre de paquete del proceso.
            return if (PKG_NAME.matches(base)) base else (uidResolver(uid) ?: "uid $uid")
        }
        if (base.startsWith("[")) return SYSTEM_PREFIX + "kernel"
        return SYSTEM_PREFIX + base.ifEmpty { "uid $uid" }
    }

    companion object {
        const val SYSTEM_PREFIX = "sistema:"
        private val PKG_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val WS = Regex("\\s+")

        /** Suma de jiffies de todas las CPUs (linea "cpu " de /proc/stat), o -1. */
        fun totalJiffies(stat: String): Long {
            val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return -1L
            val v = line.trim().split(WS).drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size < 4) return -1L
            var total = 0L
            for (k in 0 until minOf(v.size, 8)) total += v[k]
            return total
        }
    }
}
