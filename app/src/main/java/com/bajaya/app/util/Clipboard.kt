package com.bajaya.app.util

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

/**
 * Lee el texto del portapapeles. Desde Android 10 solo funciona con la app en primer plano
 * y con la ventana enfocada, por eso se llama cuando la pantalla gana el foco.
 */
fun readClipboardText(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    // Comprobar el tipo primero evita leer contenido que no es texto.
    val description = manager.primaryClipDescription ?: return null
    val isText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    if (!isText) return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).text?.toString()
}
