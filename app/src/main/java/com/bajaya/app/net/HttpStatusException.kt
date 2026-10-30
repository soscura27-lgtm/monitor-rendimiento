package com.bajaya.app.net

import java.io.IOException

/** El enlace devolvió una página web en lugar de un archivo. Reintentar no cambia nada. */
class NotMediaException : IOException("El enlace devuelve una página web, no un archivo de audio o video.")

/** El servidor respondió con un código HTTP de error. */
class HttpStatusException(val code: Int) : IOException("HTTP $code") {
    /** Errores que suelen ser pasajeros y merecen un reintento automático. */
    val isTransient: Boolean get() = code >= 500 || code == 408 || code == 429
}
