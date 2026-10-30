package com.bajaya.app.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resultado de analizar lo que el usuario escribió o pegó. */
sealed interface ResolveOutcome {
    data class Found(val candidates: List<MediaCandidate>) : ResolveOutcome
    data class NothingFound(val reason: String) : ResolveOutcome
    data class Invalid(val message: String) : ResolveOutcome
}

/**
 * Prueba los extractores en orden y devuelve el primero que encuentre archivos.
 * El orden importa: lo más específico primero y el genérico (HTML) al final.
 */
class ExtractorRegistry(private val extractors: List<MediaExtractor>) {

    /** Puede lanzar IOException (red); quien la llame debe capturarla. */
    suspend fun resolve(rawInput: String): ResolveOutcome = withContext(Dispatchers.IO) {
        val url = parse(rawInput)
            ?: return@withContext ResolveOutcome.Invalid("Eso no parece un enlace válido.")

        if (url.scheme != "https") {
            return@withContext ResolveOutcome.Invalid(
                "La app solo admite enlaces https:// (los http:// viajan sin cifrar). Prueba cambiando el enlace a https."
            )
        }

        var lastReason = "No encontré archivos de audio o video descargables en ese enlace."
        for (extractor in extractors) {
            if (!extractor.canHandle(url)) continue
            when (val result = extractor.extract(url)) {
                is ExtractionResult.Found -> return@withContext ResolveOutcome.Found(result.candidates)
                is ExtractionResult.NothingFound -> lastReason = result.reason
            }
        }
        ResolveOutcome.NothingFound(lastReason)
    }

    private fun parse(raw: String): HttpUrl? {
        val text = raw.trim()
        text.toHttpUrlOrNull()?.let { return it }
        // Si escribió "sitio.com/video.mp4" sin el esquema, asumimos https.
        if (!text.contains("://") && text.contains('.') && !text.contains(' ')) {
            return "https://$text".toHttpUrlOrNull()
        }
        return null
    }
}
