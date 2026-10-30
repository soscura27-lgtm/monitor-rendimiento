package com.bajaya.app.data

import android.content.Context
import java.io.File

/**
 * Ubicación de los archivos parciales (.part). Viven en el almacenamiento INTERNO de la app,
 * por eso no hace falta ningún permiso. Al terminar la descarga se copian a la galería.
 */
object DownloadFiles {
    fun partFile(context: Context, downloadId: Long): File =
        File(File(context.filesDir, "partial"), "$downloadId.part")
}
