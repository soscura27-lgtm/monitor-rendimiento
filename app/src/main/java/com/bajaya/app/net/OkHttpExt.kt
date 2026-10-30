package com.bajaya.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ejecuta una llamada de OkHttp sin bloquear el hilo y se cancela junto con la corrutina.
 * Quien la use debe cerrar la respuesta (por ejemplo con `use { }`).
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                // La corrutina ya se canceló: no dejamos la conexión abierta.
                response.close()
            }
        }
    })
}
