package com.bajaya.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bajaya.app.extract.MediaKind

/** Estados posibles de una descarga. Room los guarda como texto (el nombre del valor). */
enum class DownloadStatus {
    QUEUED,       // esperando a que WorkManager la ejecute
    DOWNLOADING,  // en curso
    PAUSED,       // pausada por el usuario (el archivo parcial se conserva)
    COMPLETED,    // terminada y guardada en la galería
    FAILED,       // falló; si hay archivo parcial se puede reanudar
    CANCELED      // cancelada por el usuario (el archivo parcial se borró)
}

/** Una fila del historial de descargas. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Enlace directo al archivo que se descarga. */
    val url: String,
    /** Nombre con el que se guardará el archivo. */
    val fileName: String,
    val mimeType: String,
    val kind: MediaKind,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    /** Tamaño total en bytes; -1 si el servidor no lo informa. */
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    /** ETag (fuerte) del servidor; sirve para reanudar solo si el archivo no cambió. */
    val etag: String? = null,
    /** Uri (content:// o file://) del archivo ya guardado. */
    val savedUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
