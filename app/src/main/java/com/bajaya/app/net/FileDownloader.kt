package com.bajaya.app.net

import android.os.SystemClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Descarga un archivo a disco con soporte de REANUDACIÓN.
 *
 * Si ya existe un archivo parcial, pide al servidor solo lo que falta con la cabecera
 * `Range: bytes=N-`. Si el servidor no soporta rangos (responde 200) o el archivo cambió
 * (`If-Range` con el ETag guardado), empieza de nuevo desde cero de forma automática.
 */
class FileDownloader(private val client: OkHttpClient) {

    /**
     * @param onHeaders se llama una vez con el tamaño total (-1 si se desconoce) y el ETag.
     * @param onProgress se llama periódicamente con (bytes descargados, bytes totales).
     * @return bytes finales del archivo en disco.
     */
    suspend fun download(
        url: String,
        target: File,
        savedEtag: String?,
        onHeaders: suspend (totalBytes: Long, etag: String?) -> Unit,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): Long = coroutineScope {
        val callHolder = AtomicReference<Call?>(null)

        // Vigilante: en cuanto la corrutina se cancela (pausa/cancelar), corta la conexión
        // HTTP. Sin esto, una lectura bloqueada tardaría en notar la cancelación.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                callHolder.get()?.cancel()
            }
        }

        try {
            withContext(Dispatchers.IO) {
                suspend fun attempt(): Long? =
                    transferOnce(url, target, savedEtag, callHolder, onHeaders, onProgress)

                // null = hay que empezar de cero (el servidor no pudo continuar el parcial).
                attempt() ?: attempt()
                    ?: throw IOException("El servidor no permite reanudar la descarga.")
            }
        } finally {
            watcher.cancel()
        }
    }

    /** Un intento de descarga. Devuelve null si hay que reiniciar desde cero. */
    private suspend fun transferOnce(
        url: String,
        target: File,
        savedEtag: String?,
        callHolder: AtomicReference<Call?>,
        onHeaders: suspend (Long, String?) -> Unit,
        onProgress: suspend (Long, Long) -> Unit
    ): Long? {
        target.parentFile?.mkdirs()
        val existing = if (target.exists()) target.length() else 0L

        val request = Request.Builder()
            .url(url)
            // Sin compresión: así el tamaño y los rangos coinciden con el archivo real.
            .header("Accept-Encoding", "identity")
            .apply {
                if (existing > 0) {
                    header("Range", "bytes=$existing-")
                    // Solo continúa si el archivo en el servidor sigue siendo el mismo.
                    if (!savedEtag.isNullOrBlank()) header("If-Range", savedEtag)
                }
            }
            .build()

        val call = client.newCall(request)
        callHolder.set(call)

        return call.execute().use { response ->
            // Los ETag débiles ("W/...") no sirven para validar rangos.
            val etag = response.header("ETag")?.takeIf { !it.startsWith("W/") }

            // Si el "archivo" en realidad es una página web (error, login, aviso...), no lo guardamos
            // como si fuera un video o un audio.
            if (response.isSuccessful &&
                response.header("Content-Type")?.trimStart()?.startsWith("text/html", ignoreCase = true) == true
            ) {
                target.delete()
                throw NotMediaException()
            }

            when (response.code) {
                200 -> {
                    // Respuesta completa. Si teníamos parcial, el servidor ignoró el rango.
                    if (existing > 0) target.delete()
                    val body = response.body ?: throw IOException("Respuesta vacía del servidor.")
                    val total = body.contentLength()
                    onHeaders(total, etag)
                    copyBody(body, target, append = false, startOffset = 0L, total = total, onProgress = onProgress)
                }

                206 -> {
                    val body = response.body ?: throw IOException("Respuesta vacía del servidor.")
                    val range = parseContentRange(response.header("Content-Range"))
                    val rangeStart = range?.start
                    if (rangeStart == null || rangeStart != existing) {
                        // El servidor empezó en otro byte: no se puede unir. Reiniciar.
                        target.delete()
                        null
                    } else {
                        val remaining = body.contentLength()
                        val total = range?.total ?: if (remaining >= 0) existing + remaining else -1L
                        onHeaders(total, etag ?: savedEtag)
                        copyBody(body, target, append = true, startOffset = existing, total = total, onProgress = onProgress)
                    }
                }

                416 -> {
                    // "Rango no satisfacible": o el archivo ya estaba completo, o cambió.
                    val total = parseContentRange(response.header("Content-Range"))?.total
                    when {
                        existing > 0 && total == existing -> existing
                        existing > 0 -> {
                            target.delete()
                            null
                        }
                        else -> throw HttpStatusException(416)
                    }
                }

                else -> throw HttpStatusException(response.code)
            }
        }
    }

    /** Copia el cuerpo de la respuesta al archivo, informando el progreso. */
    private suspend fun copyBody(
        body: ResponseBody,
        target: File,
        append: Boolean,
        startOffset: Long,
        total: Long,
        onProgress: suspend (Long, Long) -> Unit
    ): Long {
        var downloaded = startOffset
        var lastReport = SystemClock.elapsedRealtime()

        FileOutputStream(target, append).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    // Si se pausó o canceló, lanza CancellationException aquí.
                    currentCoroutineContext().ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= REPORT_INTERVAL_MS) {
                        lastReport = now
                        onProgress(downloaded, total)
                    }
                }
                out.flush()
            }
        }

        onProgress(downloaded, total)
        // Si el servidor cortó antes de tiempo, el parcial se conserva para reanudar.
        if (total > 0 && downloaded != total) {
            throw IOException("Descarga incompleta: $downloaded de $total bytes.")
        }
        return downloaded
    }

    private data class ContentRange(val start: Long?, val total: Long?)

    /** Entiende "bytes 100-999/1000" y "bytes * / 1000" (416). */
    private fun parseContentRange(header: String?): ContentRange? {
        val match = RANGE_REGEX.find(header ?: return null) ?: return null
        return ContentRange(
            start = match.groupValues[1].toLongOrNull(),
            total = match.groupValues[2].toLongOrNull()
        )
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val REPORT_INTERVAL_MS = 500L
        val RANGE_REGEX = Regex("bytes\\s+(?:(\\d+)-\\d+|\\*)/(\\d+|\\*)")
    }
}
