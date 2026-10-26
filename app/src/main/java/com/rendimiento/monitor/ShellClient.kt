package com.rendimiento.monitor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Unica conexion con el proceso de shell de Shizuku para toda la app (barra flotante,
 * pantalla principal y pantalla de consumo). Antes cada pantalla abria su propio proceso.
 * Cuenta quien lo usa; cuando nadie lo usa durante unos segundos, se cierra el proceso.
 */
object ShellClient {

    /** Subir este numero cada vez que cambie IShellService.aidl: obliga a Shizuku a reiniciar el proceso. */
    private const val SERVICE_VERSION = 4
    private const val BIND_TIMEOUT_MS = 10000L
    private const val RELEASE_DELAY_MS = 5000L

    @Volatile
    var shell: IShellService? = null
        private set

    @Volatile
    private var binding = false

    @Volatile
    private var bindAt = 0L
    private var users = 0
    private var args: Shizuku.UserServiceArgs? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            shell = if (service != null && service.pingBinder()) IShellService.Stub.asInterface(service) else null
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            binding = false
            notifyChanged()
        }
    }

    private val releaseNow = Runnable { unbindIfUnused() }

    fun acquire(ctx: Context) {
        synchronized(this) {
            users++
            if (args == null) {
                args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(false)
                    .version(SERVICE_VERSION)
            }
        }
        main.removeCallbacks(releaseNow)
        ensure()
    }

    fun release() {
        synchronized(this) {
            if (users > 0) users--
            if (users > 0) return
        }
        main.removeCallbacks(releaseNow)
        main.postDelayed(releaseNow, RELEASE_DELAY_MS)
    }

    fun addListener(r: Runnable) {
        listeners.add(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    private fun notifyChanged() {
        for (l in listeners) main.post(l)
    }

    private fun unbindIfUnused() {
        val a: Shizuku.UserServiceArgs
        synchronized(this) {
            if (users > 0) return
            a = args ?: return
        }
        try {
            if (shell != null || binding) Shizuku.unbindUserService(a, connection, true)
        } catch (e: Throwable) {
        }
        shell = null
        binding = false
    }

    /** Estado de Shizuku: 0 = no instalado/sin servicio, 1 = apagado, 2 = falta permiso, 3 = listo. */
    fun state(ctx: Context): Int {
        val installed = try {
            ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
        } catch (e: Throwable) {
            false
        }
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        if (!running) return if (installed) 1 else 0
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
        return if (granted) 3 else 2
    }

    /**
     * Devuelve el servicio si esta listo; si no, intenta conectarlo (sin bloquear).
     * Se puede llamar seguido: tambien detecta si el proceso de shell murio.
     */
    fun ensure(): IShellService? {
        val cur = shell
        if (cur != null) {
            val alive = try {
                cur.asBinder().pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (alive) return cur
            shell = null
            notifyChanged()
        }
        val a = args ?: return null
        if (binding && SystemClock.elapsedRealtime() - bindAt > BIND_TIMEOUT_MS) binding = false
        if (binding) return null
        try {
            if (!Shizuku.pingBinder()) return null
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
            binding = true
            bindAt = SystemClock.elapsedRealtime()
            Shizuku.bindUserService(a, connection)
        } catch (e: Throwable) {
            binding = false
        }
        return null
    }

    /** Ejecuta una linea de shell y devuelve la salida, o null si no hay Shizuku. */
    fun sh(command: String, timeoutMs: Int = 8000): String? {
        val s = shell ?: return null
        return try {
            s.sh(command, timeoutMs)
        } catch (e: Throwable) {
            null
        }
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}
