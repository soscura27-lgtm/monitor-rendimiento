package com.rendimiento.monitor

/**
 * Consumo por aplicacion: CPU (%), RAM (MB) y red (KB/s) agrupados por paquete.
 *
 * Usa la tabla de procesos del servicio de shell (Shizuku). Un paquete puede tener varios
 * procesos (por ejemplo "com.juego:remote"); todos se suman. La RAM es RSS, asi que suma
 * tambien las librerias compartidas: es una aproximacion, no el valor exacto de Ajustes.
 */
class AppMonitor(
    private val shellProvider: () -> IShellService?
) {

    class AppStat(
        val pkg: String,
        val uid: Int,
        val cpuPct: Float,
        val ramMb: Float,
        val rxKbS: Float,
        val txKbS: Float,
        val procs: Int
    )

    private var prevProcJiffies = HashMap<Int, Long>()
    private var prevUidBytes = HashMap<Int, Pair<Long, Long>>()
    private var prevAt = 0L

    private var uidToPkg: Map<Int, String> = emptyMap()
    private var uidMapAt = 0L

    @Volatile
    private var stats: List<AppStat> = emptyList()

    /** Ultima lista calculada, ordenada por CPU. Nunca bloquea. */
    fun current(): List<AppStat> = stats

    /** UID de un paquete conocido, o null si aun no se leyo la tabla de UIDs. */
    fun uidOf(pkg: String): Int? = uidToPkg.entries.firstOrNull { it.value == pkg }?.key

    /**
     * Recalcula. [totalJiffiesDelta] es el avance de /proc/stat desde la lectura anterior
     * (todas las CPUs), o -1 si no se conoce; sin el no hay porcentaje de CPU.
     */
    fun update(totalJiffiesDelta: Long, now: Long) {
        val sh = shellProvider() ?: run {
            stats = emptyList()
            return
        }
        if (now - uidMapAt >= UID_MAP_EVERY_MS || uidToPkg.isEmpty()) {
            uidMapAt = now
            uidToPkg = readUidMap(sh)
        }

        val raw = try {
            sh.procTable()
        } catch (e: Throwable) {
            return
        }
        val dtSec = if (prevAt == 0L) 0f else (now - prevAt) / 1000f
        prevAt = now

        val procJ = HashMap<Int, Long>()
        val cpuByPkg = HashMap<String, Long>()
        val ramByPkg = HashMap<String, Long>()
        val procsByPkg = HashMap<String, Int>()
        val uidBytes = HashMap<Int, Pair<Long, Long>>()
        val uidOfPkg = HashMap<String, Int>()

        for (line in raw.lineSequence()) {
            val f = line.split('|')
            if (f.size < 8) continue
            val pid = f[0].toIntOrNull() ?: continue
            val uid = f[1].toIntOrNull() ?: continue
            val ut = f[2].toLongOrNull() ?: continue
            val st = f[3].toLongOrNull() ?: continue
            val rssKb = f[4].toLongOrNull() ?: 0L
            val rx = f[5].toLongOrNull() ?: -1L
            val tx = f[6].toLongOrNull() ?: -1L
            val cmd = f[7]

            val jiffies = ut + st
            procJ[pid] = jiffies
            val prev = prevProcJiffies[pid]
            val delta = if (prev != null && jiffies >= prev) jiffies - prev else 0L

            val pkg = packageOf(uid, cmd)
            cpuByPkg[pkg] = (cpuByPkg[pkg] ?: 0L) + delta
            ramByPkg[pkg] = (ramByPkg[pkg] ?: 0L) + rssKb
            procsByPkg[pkg] = (procsByPkg[pkg] ?: 0) + 1
            uidOfPkg[pkg] = uid

            if (rx >= 0 && tx >= 0 && !uidBytes.containsKey(uid)) {
                uidBytes[uid] = Pair(rx, tx)
            }
        }

        val out = ArrayList<AppStat>()
        for ((pkg, jiff) in cpuByPkg) {
            val uid = uidOfPkg[pkg] ?: -1
            val cpu = if (totalJiffiesDelta > 0L) {
                (jiff.toFloat() / totalJiffiesDelta.toFloat() * 100f).coerceIn(0f, 100f)
            } else {
                Float.NaN
            }
            var rxRate = Float.NaN
            var txRate = Float.NaN
            val nowBytes = uidBytes[uid]
            val before = prevUidBytes[uid]
            if (nowBytes != null && before != null && dtSec > 0f) {
                rxRate = ((nowBytes.first - before.first).coerceAtLeast(0L)) / 1024f / dtSec
                txRate = ((nowBytes.second - before.second).coerceAtLeast(0L)) / 1024f / dtSec
            }
            out.add(
                AppStat(
                    pkg = pkg,
                    uid = uid,
                    cpuPct = cpu,
                    ramMb = (ramByPkg[pkg] ?: 0L) / 1024f,
                    rxKbS = rxRate,
                    txKbS = txRate,
                    procs = procsByPkg[pkg] ?: 0
                )
            )
        }

        prevProcJiffies = procJ
        prevUidBytes = uidBytes
        stats = out.sortedWith(
            compareByDescending<AppStat> { if (it.cpuPct.isNaN()) -1f else it.cpuPct }
                .thenByDescending { it.ramMb }
        )
    }

    private fun packageOf(uid: Int, cmd: String): String {
        val base = cmd.substringBefore(':').trim()
        if (uid < APP_UID_MIN) {
            return if (base.isEmpty()) "sistema (uid $uid)" else "sistema: $base"
        }
        return uidToPkg[uid] ?: base.ifEmpty { "uid $uid" }
    }

    private fun readUidMap(sh: IShellService): Map<Int, String> {
        val out = try {
            sh.exec(arrayOf("/system/bin/cmd", "package", "list", "packages", "-U"))
        } catch (e: Throwable) {
            return uidToPkg
        }
        val map = HashMap<Int, String>()
        for (line in out.lineSequence()) {
            // Formato: "package:com.ejemplo uid:10123"
            val m = UID_LINE.find(line) ?: continue
            val uid = m.groupValues[2].toIntOrNull() ?: continue
            map[uid] = m.groupValues[1]
        }
        return if (map.isEmpty()) uidToPkg else map
    }

    companion object {
        private const val APP_UID_MIN = 10000
        private const val UID_MAP_EVERY_MS = 60000L
        private val UID_LINE = Regex("package:(\\S+)\\s+uid:(\\d+)")
    }
}
