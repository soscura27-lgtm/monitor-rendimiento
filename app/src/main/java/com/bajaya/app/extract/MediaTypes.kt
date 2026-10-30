package com.bajaya.app.extract

/** Clasificación general de un archivo; decide a qué carpeta pública de la galería va. */
enum class MediaKind { VIDEO, AUDIO, OTHER }

/** Tabla de extensiones y tipos MIME que la app reconoce como descargables. */
object MediaTypes {
    private val video = mapOf(
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "m4v" to "video/x-m4v",
        "3gp" to "video/3gpp",
        "ogv" to "video/ogg"
    )

    private val audio = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "flac" to "audio/flac"
    )

    val downloadableExtensions: Set<String> = video.keys + audio.keys

    /** Extensión en minúsculas, ignorando "?query" y "#fragmento". */
    fun extensionOf(name: String): String =
        name.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    fun isMediaFileName(name: String): Boolean = extensionOf(name) in downloadableExtensions

    fun mimeForExtension(extension: String): String? = video[extension] ?: audio[extension]

    /** Extensión típica para un tipo MIME (por ejemplo "video/mp4" -> "mp4"). */
    fun extensionForMime(mime: String): String? =
        (video + audio).entries.firstOrNull { it.value == mime.lowercase() }?.key

    fun kindOf(mime: String?, fileName: String): MediaKind {
        val type = mime?.lowercase().orEmpty()
        val ext = extensionOf(fileName)
        return when {
            type.startsWith("video/") -> MediaKind.VIDEO
            type.startsWith("audio/") -> MediaKind.AUDIO
            ext in video.keys -> MediaKind.VIDEO
            ext in audio.keys -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }
}
