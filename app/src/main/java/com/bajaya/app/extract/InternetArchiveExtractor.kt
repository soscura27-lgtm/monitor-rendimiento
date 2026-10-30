package com.bajaya.app.extract

import com.bajaya.app.net.HttpStatusException
import com.bajaya.app.net.await
import com.bajaya.app.util.formatBytes
import com.bajaya.app.util.sanitizeFileName
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Internet Archive (archive.org). Usa su API pública de metadatos
 * (https://archive.org/metadata/<id>) para listar los archivos de audio y video de un
 * elemento y arma los enlaces oficiales de descarga (https://archive.org/download/...).
 */
class InternetArchiveExtractor(private val client: OkHttpClient) : MediaExtractor {
    override val name = "Internet Archive"

    override fun canHandle(url: HttpUrl): Boolean {
        val host = url.host
        if (host != "archive.org" && !host.endsWith(".archive.org")) return false
        val segments = url.pathSegments
        // /details/<id>, /embed/<id> o /download/<id> (sin archivo concreto).
        // Los enlaces /download/<id>/<archivo>.mp4 los resuelve el extractor de enlace directo.
        return segments.size >= 2 &&
            segments[0] in setOf("details", "embed", "download") &&
            segments[1].isNotBlank() &&
            !MediaTypes.isMediaFileName(url.encodedPath)
    }

    override suspend fun extract(url: HttpUrl): ExtractionResult {
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return ExtractionResult.NothingFound("No se pudo identificar el elemento de Internet Archive.")

        val metadataUrl = HttpUrl.Builder()
            .scheme("https")
            .host("archive.org")
            .addPathSegment("metadata")
            .addPathSegment(id)
            .build()

        val request = Request.Builder()
            .url(metadataUrl)
            .header("Accept", "application/json")
            .build()

        val json = client.newCall(request).await().use { response ->
            if (response.code == 404) {
                return ExtractionResult.NothingFound("Ese elemento de Internet Archive no existe.")
            }
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            // Límite de 10 MB por seguridad: el JSON de elementos enormes puede ser grande.
            response.peekBody(MAX_JSON_BYTES).string()
        }

        val root = JSONObject(json)
        val files = root.optJSONArray("files")
            ?: return ExtractionResult.NothingFound("Ese elemento de Internet Archive no tiene archivos.")

        val found = LinkedHashMap<String, MediaCandidate>()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val path = file.optString("name")
            if (path.isBlank() || !MediaTypes.isMediaFileName(path)) continue
            // Los archivos marcados como privados no se pueden descargar sin cuenta.
            if (file.optString("private") == "true") continue

            val mime = MediaTypes.mimeForExtension(MediaTypes.extensionOf(path)) ?: continue
            val size = file.optString("size").toLongOrNull()
            val format = file.optString("format")
            val shortName = path.substringAfterLast('/')

            // addPathSegments respeta subcarpetas ("carpeta/archivo.mp4") y codifica espacios.
            val downloadUrl = HttpUrl.Builder()
                .scheme("https")
                .host("archive.org")
                .addPathSegment("download")
                .addPathSegment(id)
                .addPathSegments(path)
                .build()
                .toString()

            val label = buildString {
                append(shortName)
                if (size != null) append(" · ").append(formatBytes(size))
                if (format.isNotBlank()) append(" · ").append(format)
            }

            found.putIfAbsent(
                downloadUrl,
                MediaCandidate(
                    url = downloadUrl,
                    fileName = sanitizeFileName(shortName),
                    mimeType = mime,
                    sizeBytes = size,
                    label = label
                )
            )
        }

        if (found.isEmpty()) {
            return ExtractionResult.NothingFound(
                "Ese elemento de Internet Archive no contiene archivos de audio o video descargables."
            )
        }

        // Primero video, luego audio; dentro de cada grupo, los más grandes (mejor calidad) arriba.
        val sorted = found.values.sortedWith(
            compareBy<MediaCandidate>({ it.kind.ordinal }, { -(it.sizeBytes ?: 0L) })
        )
        return ExtractionResult.Found(sorted)
    }

    private companion object {
        const val MAX_JSON_BYTES = 10L * 1024 * 1024
    }
}
