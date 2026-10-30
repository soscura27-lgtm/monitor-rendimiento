package com.bajaya.app.extract

import okhttp3.HttpUrl

/**
 * Un archivo concreto que se puede descargar (resultado de analizar un enlace).
 *
 * @property url enlace directo al archivo.
 * @property label texto que se muestra en la lista de selección.
 */
data class MediaCandidate(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val label: String
) {
    val kind: MediaKind get() = MediaTypes.kindOf(mimeType, fileName)
}

sealed interface ExtractionResult {
    data class Found(val candidates: List<MediaCandidate>) : ExtractionResult
    data class NothingFound(val reason: String) : ExtractionResult
}

/**
 * "Conector" de una fuente de descargas. Para añadir un sitio nuevo basta con implementar
 * esta interfaz y registrarla en [AppContainer] (ver README). Solo debe devolver archivos
 * que el sitio ofrece para descargar de forma pública.
 */
interface MediaExtractor {
    /** Nombre legible de la fuente. */
    val name: String

    /** ¿Este extractor sabe analizar este enlace? */
    fun canHandle(url: HttpUrl): Boolean

    /** Analiza el enlace. Puede lanzar IOException si falla la red. */
    suspend fun extract(url: HttpUrl): ExtractionResult
}
