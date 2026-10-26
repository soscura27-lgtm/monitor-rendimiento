package com.rendimiento.monitor

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import java.util.Calendar

/** Nombre, icono y UID de las apps instaladas (con cache). */
object AppInfoCache {

    class Info(val pkg: String, val label: String, val uid: Int, val system: Boolean)

    private val infos = HashMap<String, Info?>()
    private val uidToPkg = HashMap<Int, String?>()
    private var launcherPkg: String? = null
    private var launcherAt = 0L

    @Synchronized
    fun info(ctx: Context, pkg: String): Info? {
        if (infos.containsKey(pkg)) return infos[pkg]
        val pm = ctx.packageManager
        val res = try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            Info(pkg, pm.getApplicationLabel(ai).toString(), ai.uid, sys)
        } catch (e: Throwable) {
            null
        }
        infos[pkg] = res
        return res
    }

    /** Nombre bonito para mostrar: "Chrome", "Sistema · surfaceflinger", o el final del paquete. */
    fun label(ctx: Context, key: String): String {
        if (key.startsWith(AppMonitor.SYSTEM_PREFIX)) {
            val n = key.substring(AppMonitor.SYSTEM_PREFIX.length)
            return if (n == "kernel") "Kernel de Android" else "Sistema · $n"
        }
        info(ctx, key)?.let { return it.label }
        return key.substringAfterLast('.')
    }

    fun icon(ctx: Context, key: String): Drawable? {
        if (key.startsWith(AppMonitor.SYSTEM_PREFIX) || key.startsWith("uid ")) return null
        return try {
            ctx.packageManager.getApplicationIcon(key)
        } catch (e: Throwable) {
            null
        }
    }

    fun uidOf(ctx: Context, pkg: String): Int? = info(ctx, pkg)?.uid

    @Synchronized
    fun packageForUid(ctx: Context, uid: Int): String? {
        if (uidToPkg.containsKey(uid)) return uidToPkg[uid]
        val pkgs = try {
            ctx.packageManager.getPackagesForUid(uid)
        } catch (e: Throwable) {
            null
        }
        val p = pkgs?.minByOrNull { it.length }
        uidToPkg[uid] = p
        return p
    }

    /** Paquete del lanzador (pantalla de inicio): no cuenta como juego. */
    @Synchronized
    fun launcher(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - launcherAt < 60000L) return launcherPkg
        launcherAt = now
        launcherPkg = try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (e: Throwable) {
            null
        }
        return launcherPkg
    }
}

/** RAM real de cada app (PSS, la misma que muestra Ajustes) a partir de "dumpsys meminfo". */
object MemInfo {

    /** Devuelve MB por paquete, o vacio si no se pudo leer. Tarda 1-3 s: llamar en segundo plano. */
    fun pssByPackage(sh: IShellService): Map<String, Float> {
        val out = try {
            sh.sh("dumpsys meminfo", 20000) ?: ""
        } catch (e: Throwable) {
            ""
        }
        val pss = section(out, "Total PSS by process")
        val rows = if (pss.isNotEmpty()) pss else section(out, "Total RSS by process")
        val map = HashMap<String, Float>()
        for ((name, kb) in rows) {
            val pkg = name.substringBefore(':')
            map[pkg] = (map[pkg] ?: 0f) + kb / 1024f
        }
        return map
    }

    private fun section(out: String, header: String): List<Pair<String, Long>> {
        val res = ArrayList<Pair<String, Long>>()
        var inside = false
        for (line in out.lineSequence()) {
            if (!inside) {
                if (line.trim().startsWith(header)) inside = true
                continue
            }
            if (line.isBlank()) {
                if (res.isNotEmpty()) break else continue
            }
            if (line.trim().startsWith("Total ")) break
            val m = ROW.find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").replace(".", "").toLongOrNull() ?: continue
            res.add(Pair(m.groupValues[2], kb))
        }
        return res
    }

    private val ROW = Regex("^\\s*([0-9][0-9,.]*)K: (\\S+) \\(pid \\d+")
}

/** Datos que Android guarda por app: almacenamiento, datos moviles/Wi-Fi, bateria y tiempo de uso. */
object UsageData {

    class Storage(val pkg: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
        val total: Long get() = appBytes + dataBytes
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val aom = ctx.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** Con Shizuku la app se da sola el acceso a datos de uso (como "adb shell appops set"). */
    fun grantUsageAccess(ctx: Context, sh: IShellService): Boolean {
        val p = ctx.packageName
        try {
            sh.sh("appops set $p GET_USAGE_STATS allow; pm grant $p android.permission.PACKAGE_USAGE_STATS", 6000)
        } catch (e: Throwable) {
        }
        return hasUsageAccess(ctx)
    }

    fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Almacenamiento por app (app + datos + cache). Tarda unos segundos con muchas apps. */
    fun storage(ctx: Context): List<Storage> {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val pm = ctx.packageManager
        val user = Process.myUserHandle()
        val out = ArrayList<Storage>()
        @Suppress("DEPRECATION")
        val apps = try {
            pm.getInstalledApplications(0)
        } catch (e: Throwable) {
            emptyList()
        }
        for (ai in apps) {
            try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                out.add(Storage(ai.packageName, st.appBytes, st.dataBytes, st.cacheBytes))
            } catch (e: Throwable) {
                // Sin acceso o app desinstalada: se omite.
            }
        }
        return out.sortedByDescending { it.total }
    }

    /** Bytes de hoy por UID: [Wi-Fi, moviles]. */
    fun dataToday(ctx: Context): Map<Int, LongArray> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return emptyMap()
        val start = startOfToday()
        val end = System.currentTimeMillis()
        val map = HashMap<Int, LongArray>()
        val types = intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)
        for (i in types.indices) {
            try {
                @Suppress("DEPRECATION")
                val stats = nsm.querySummary(types[i], null, start, end)
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val arr = map.getOrPut(b.uid) { LongArray(2) }
                    arr[i] += b.rxBytes + b.txBytes
                }
                stats.close()
            } catch (e: Throwable) {
            }
        }
        return map
    }

    /** Tiempo en pantalla de hoy por paquete (ms). */
    fun usageToday(ctx: Context): Map<String, Long> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        return try {
            usm.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())
                .mapValues { it.value.totalTimeInForeground }
                .filterValues { it > 0L }
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    /**
     * Consumo de bateria por UID (mAh desde la ultima carga completa), segun el calculo de Android
     * ("dumpsys batterystats"). Necesita Shizuku.
     */
    fun batteryByUid(sh: IShellService): Map<Int, Float> {
        val map = HashMap<Int, Float>()
        val cmds = arrayOf(
            "dumpsys batterystats --usage 2>/dev/null | grep -iE '^ *uid [u0-9]'",
            "dumpsys batterystats 2>/dev/null | grep -iE '^ *uid [u0-9]'"
        )
        for (c in cmds) {
            val out = try {
                sh.sh(c, 30000) ?: ""
            } catch (e: Throwable) {
                ""
            }
            for (line in out.lineSequence()) {
                val m = BAT_ROW.find(line) ?: continue
                val uid = parseUid(m.groupValues[1]) ?: continue
                val mah = m.groupValues[2].replace(",", "").toFloatOrNull() ?: continue
                if (mah > (map[uid] ?: -1f)) map[uid] = mah
            }
            if (map.isNotEmpty()) break
        }
        return map
    }

    /** "u0a123" -> 10123, "u10a5" -> 1010005, "u0i7" -> 99007, "1000" -> 1000. */
    fun parseUid(s: String): Int? {
        val m = UID_FORMAT.matchEntire(s)
        if (m != null) {
            val user = m.groupValues[1].toIntOrNull() ?: return null
            val n = m.groupValues[3].toIntOrNull() ?: return null
            val base = if (m.groupValues[2] == "a") 10000 + n else 99000 + n
            return user * 100000 + base
        }
        return s.toIntOrNull()
    }

    private val BAT_ROW = Regex("^\\s*[Uu][Ii][Dd] (u\\d+[ai]\\d+|\\d+): ([0-9][0-9,]*(?:\\.[0-9]+)?)")
    private val UID_FORMAT = Regex("u(\\d+)([ai])(\\d+)")
}
