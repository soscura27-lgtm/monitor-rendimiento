package com.bajaya.app.util

import java.util.Locale

/** Carpeta de descarga por defecto (subcarpeta dentro de Movies/, Music/ o Download/). */
const val DEFAULT_DOWNLOAD_FOLDER = "BajaYa"

/**
 * Limpia el nombre de carpeta que escribe el usuario: sin caracteres prohibidos, sin ".."
 * (no se puede salir de la carpeta pública) y sin barras sobrantes. Se permiten
 * subcarpetas con "/" (por ejemplo "Cursos/Mate"). Si queda vacío, usa el valor por defecto.
 */
fun sanitizeFolderName(raw: String): String {
    val illegal = Regex("[:*?\"<>|\\p{Cntrl}]")
    val parts = raw.split('/', '\\')
        .map { it.replace(illegal, "").trim().trim('.') }
        .filter { it.isNotEmpty() }
    return parts.joinToString("/").ifEmpty { DEFAULT_DOWNLOAD_FOLDER }
}

/** Convierte bytes en texto legible: 1536 -> "1.5 KB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

private val URL_REGEX = Regex("""https?://[^\s<>"']+""")

/** Busca el primer enlace http(s) dentro de un texto (útil al compartir o pegar). */
fun extractFirstUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = URL_REGEX.find(text) ?: return null
    // Quita signos de puntuación que suelen pegarse al final de un enlace en un texto.
    return match.value.trimEnd('.', ',', ';', ')', ']', '!', '?')
}

/**
 * Deja un nombre de archivo seguro: sin caracteres prohibidos y con un largo razonable,
 * conservando siempre la extensión.
 */
fun sanitizeFileName(raw: String): String {
    var name = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
    if (name.isEmpty()) return "archivo"
    if (name.length > 120) {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 9) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        name = base.take(110) + ext
    }
    return name
}
