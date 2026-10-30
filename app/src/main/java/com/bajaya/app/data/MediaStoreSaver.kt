package com.bajaya.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.bajaya.app.extract.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Guarda el archivo terminado en la galería usando MediaStore.
 *
 * - Android 10+ (API 29+): se inserta en MediaStore. NO requiere permisos de almacenamiento
 *   porque la app es la dueña de los archivos que crea.
 * - Android 8 y 9 (API 26-28): MediaStore sin permisos de almacenamiento no es posible, así
 *   que el archivo se guarda en la carpeta propia de la app (Android/data/<paquete>/files/...).
 */
class MediaStoreSaver(private val context: Context) {

    /** Devuelve la Uri (como texto) del archivo guardado. */
    suspend fun save(
        source: File,
        displayName: String,
        mimeType: String,
        kind: MediaKind,
        folder: String
    ): String = withContext(Dispatchers.IO) {
        val safeFolder = SettingsRepository.sanitizeFolder(folder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(source, displayName, mimeType, kind, safeFolder)
        } else {
            saveInAppFolder(source, displayName, kind, safeFolder)
        }
    }

    /** Borra un archivo guardado antes. Devuelve true si se eliminó. */
    suspend fun delete(savedUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(savedUri)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() } ?: false
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            // Si el usuario ya lo borró desde otra app o no tenemos acceso, no es un error grave.
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(
        source: File,
        displayName: String,
        mimeType: String,
        kind: MediaKind,
        folder: String
    ): String {
        val resolver = context.contentResolver

        // Cada tipo va a su colección y a su carpeta pública estándar.
        val (collection, baseDir) = when (kind) {
            MediaKind.VIDEO ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_MUSIC
            MediaKind.OTHER ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_DOWNLOADS
        }

        // IS_PENDING = 1 oculta el archivo de otras apps mientras se escribe.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("No se pudo crear el archivo en la galería.")

        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("No se pudo abrir el archivo de destino.")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out, COPY_BUFFER) }
            }
            // Ya está completo: lo hacemos visible.
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // No dejamos un archivo a medias en la galería.
            resolver.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    private fun saveInAppFolder(
        source: File,
        displayName: String,
        kind: MediaKind,
        folder: String
    ): String {
        val baseType = when (kind) {
            MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
            MediaKind.OTHER -> Environment.DIRECTORY_DOWNLOADS
        }
        val root = context.getExternalFilesDir(baseType) ?: context.filesDir
        val dir = File(root, folder).apply { mkdirs() }
        val destination = uniqueFile(dir, displayName)
        source.copyTo(destination, overwrite = false)
        return Uri.fromFile(destination).toString()
    }

    /** Si "video.mp4" ya existe, usa "video (1).mp4", "video (2).mp4"... */
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
    }
}
