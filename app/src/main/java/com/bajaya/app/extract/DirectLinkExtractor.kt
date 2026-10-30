package com.bajaya.app.extract

import com.bajaya.app.util.sanitizeFileName
import okhttp3.HttpUrl

/** Enlaces que terminan directamente en un archivo (.mp4, .mp3, .webm...). */
class DirectLinkExtractor : MediaExtractor {
    override val name = "Enlace directo"

    override fun canHandle(url: HttpUrl): Boolean = MediaTypes.isMediaFileName(url.encodedPath)

    override suspend fun extract(url: HttpUrl): ExtractionResult {
        val fileName = fileNameFromUrl(url)
        val mime = MediaTypes.mimeForExtension(MediaTypes.extensionOf(fileName))
            ?: "application/octet-stream"
        return ExtractionResult.Found(
            listOf(
                MediaCandidate(
                    url = url.toString(),
                    fileName = fileName,
                    mimeType = mime,
                    sizeBytes = null, // se conoce al empezar la descarga
                    label = fileName
                )
            )
        )
    }

    companion object {
        /** Último segmento de la ruta, ya decodificado y limpio. */
        fun fileNameFromUrl(url: HttpUrl): String {
            val last = url.pathSegments.lastOrNull { it.isNotBlank() } ?: "archivo"
            return sanitizeFileName(last)
        }
    }
}
