package com.bajaya.app.util

import com.bajaya.app.net.HttpStatusException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/** Traduce una excepción técnica a un mensaje comprensible para el usuario. */
fun Throwable.userMessage(): String = when (this) {
    is UnknownHostException -> "No se encontró el servidor. Revisa el enlace o tu conexión."
    is SocketTimeoutException -> "El servidor tardó demasiado en responder."
    is SSLException -> "No se pudo establecer una conexión segura con el servidor."
    is UnknownServiceException ->
        "El servidor redirige a un enlace http sin cifrar, bloqueado por seguridad."
    is HttpStatusException -> when (code) {
        401, 403 -> "El servidor no permite descargar este archivo (HTTP $code)."
        404 -> "El archivo no existe o fue eliminado (404)."
        else -> "El servidor respondió con el código $code."
    }
    is IOException -> message?.takeIf { it.isNotBlank() } ?: "Error de red."
    else -> message?.takeIf { it.isNotBlank() } ?: "Ocurrió un error inesperado."
}
