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

    companion object {
        private const val MAX_FILE = 20000
        private const val MAX_OUT = 200000
    }
}
