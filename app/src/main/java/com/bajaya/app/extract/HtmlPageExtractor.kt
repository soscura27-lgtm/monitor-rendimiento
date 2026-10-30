package com.bajaya.app.extract

import android.net.Uri
import com.bajaya.app.net.HttpStatusException
import com.bajaya.app.net.await
import com.bajaya.app.util.sanitizeFileName
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Extractor genérico: lee el HTML de una página (con jsoup) y busca archivos de audio o
 * video que la página ofrece de forma abierta:
 *  - etiquetas <video> / <audio> (y sus <source>),
 *  - metadatos Open Graph (og:video, og:audio),
 *  - enlaces <a href> que apuntan a un .mp4, .mp3, .webm, etc.
 *
 * Solo lee el marcado público. No ejecuta scripts ni descifra nada, así que las páginas que
 * entregan el contenido solo como transmisión protegida no darán resultados.
 */
class HtmlPageExtractor(private val client: OkHttpClient) : MediaExtractor {
    override val name = "Página web"

    // Es el extractor de último recurso: acepta cualquier enlace https.
    override fun canHandle(url: HttpUrl): Boolean = true

    override suspend fun extract(url: HttpUrl): ExtractionResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,video/*;q=0.9,audio/*;q=0.9,*/*;q=0.5")
            .build()

        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)

            val contentType = response.header("Content-Type")
                ?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val finalUrl = response.request.url

            // Caso 1: el enlace ya es el archivo (aunque no termine en .mp4, por ejemplo).
            if (contentType.startsWith("video/") || contentType.startsWith("audio/")) {
                return@use ExtractionResult.Found(listOf(candidateFromHeaders(finalUrl, contentType, response.header("Content-Disposition"), response.body?.contentLength())))
            }

            if (!contentType.contains("html")) {
                return@use ExtractionResult.NothingFound(
                    "El enlace no es un archivo de audio o video ni una página web."
                )
            }

            // Caso 2: es una página. Se lee hasta 2 MB de HTML.
            val html = response.peekBody(MAX_HTML_BYTES).string()
            val candidates = scanHtml(html, finalUrl.toString())

            if (candidates.isEmpty()) {
                ExtractionResult.NothingFound(
                    "No encontré archivos de audio o video descargables en esa página. " +
                        "Muchas plataformas entregan el contenido solo en streaming y no ofrecen un archivo para guardar."
                )
            } else {
                ExtractionResult.Found(candidates)
            }
        }
    }

    /** Busca candidatos en el HTML. Los enlaces relativos se convierten en absolutos. */
    private fun scanHtml(html: String, baseUrl: String): List<MediaCandidate> {
        val doc = Jsoup.parse(html, baseUrl)
        val found = LinkedHashMap<String, MediaCandidate>()

        fun add(rawUrl: String, hint: MediaKind?) {
            val candidate = candidateFromAttribute(rawUrl, hint) ?: return
            found.putIfAbsent(candidate.url, candidate)
        }

        doc.select("video[src], video source[src]").forEach { add(it.absUrl("src"), MediaKind.VIDEO) }
        doc.select("audio[src], audio source[src]").forEach { add(it.absUrl("src"), MediaKind.AUDIO) }

        doc.select(
            "meta[property='og:video'], meta[property='og:video:url'], " +
                "meta[property='og:video:secure_url'], meta[name='twitter:player:stream']"
        ).forEach { add(it.absUrl("content"), MediaKind.VIDEO) }

        doc.select(
            "meta[property='og:audio'], meta[property='og:audio:url'], meta[property='og:audio:secure_url']"
        ).forEach { add(it.absUrl("content"), MediaKind.AUDIO) }

        // Enlaces normales: solo si apuntan claramente a un archivo multimedia.
        doc.select("a[href]").forEach { link ->
            val href = link.absUrl("href")
            if (MediaTypes.isMediaFileName(href)) add(href, null)
        }

        return found.values.toList()
    }

    /** Convierte un enlace encontrado en la página en candidato; devuelve null si no sirve. */
    private fun candidateFromAttribute(rawUrl: String, hint: MediaKind?): MediaCandidate? {
        if (rawUrl.isBlank()) return null
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        // La app solo descarga por https (los enlaces "blob:" o "data:" ni siquiera llegan aquí).
        if (url.scheme != "https") return null
        val path = url.encodedPath.lowercase()
        // Listas de reproducción de streaming: no son un archivo descargable.
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return null

        var fileName = DirectLinkExtractor.fileNameFromUrl(url)
        if (!MediaTypes.isMediaFileName(fileName)) {
            // Sin extensión reconocible: se asume por el tipo de etiqueta (<video> o <audio>).
            val extension = if (hint == MediaKind.AUDIO) "mp3" else "mp4"
            val base = fileName.substringBeforeLast('.', fileName).ifBlank { "archivo" }
            fileName = sanitizeFileName("$base.$extension")
        }
        val mime = MediaTypes.mimeForExtension(MediaTypes.extensionOf(fileName)) ?: return null

        return MediaCandidate(
            url = url.toString(),
            fileName = fileName,
            mimeType = mime,
            sizeBytes = null,
            label = fileName
        )
    }

    /** Candidato para cuando el propio enlace devuelve un archivo de audio/video. */
    private fun candidateFromHeaders(
        url: HttpUrl,
        mime: String,
        contentDisposition: String?,
        size: Long?
    ): MediaCandidate {
        var fileName = fileNameFromDisposition(contentDisposition)
            ?: DirectLinkExtractor.fileNameFromUrl(url)
        if (!MediaTypes.isMediaFileName(fileName)) {
            val extension = MediaTypes.extensionForMime(mime) ?: "mp4"
            fileName = sanitizeFileName("${fileName.substringBeforeLast('.', fileName)}.$extension")
        }
        return MediaCandidate(
            url = url.toString(),
            fileName = fileName,
            mimeType = mime,
            sizeBytes = size?.takeIf { it > 0 },
            label = fileName
        )
    }

    /** Lee el nombre de `Content-Disposition: attachment; filename="video.mp4"`. */
    private fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val regex = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
        val raw = regex.find(header)?.groupValues?.get(1)?.trim() ?: return null
        return sanitizeFileName(Uri.decode(raw))
    }

    private companion object {
        const val MAX_HTML_BYTES = 2L * 1024 * 1024
    }
}
