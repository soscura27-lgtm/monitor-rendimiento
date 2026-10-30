package com.bajaya.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Acceso a la tabla de descargas. Todas las consultas se ejecutan fuera del hilo principal. */
@Dao
interface DownloadDao {

    /** Lista reactiva: la interfaz se actualiza sola cuando cambia cualquier fila. */
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    /** Descargas que deberían estar corriendo (para reconciliar al abrir la app). */
    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' OR status = 'DOWNLOADING'")
    suspend fun getActive(): List<DownloadEntity>

    /** Ids de descargas fallidas o canceladas (para borrar sus archivos parciales). */
    @Query("SELECT id FROM downloads WHERE status = 'FAILED' OR status = 'CANCELED'")
    suspend fun getFailedOrCanceledIds(): List<Long>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, errorMessage = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String?, now: Long)

    /**
     * Actualiza el progreso SOLO si la descarga sigue en curso. Así, si el usuario pausa o
     * cancela, una actualización tardía del worker no pisa el nuevo estado.
     */
    @Query(
        "UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, updatedAt = :now " +
            "WHERE id = :id AND status = 'DOWNLOADING'"
    )
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long, now: Long)

    @Query("UPDATE downloads SET totalBytes = :total, etag = :etag WHERE id = :id")
    suspend fun setRemoteInfo(id: Long, total: Long, etag: String?)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', savedUri = :savedUri, downloadedBytes = :bytes, " +
            "totalBytes = :bytes, errorMessage = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun markCompleted(id: Long, savedUri: String, bytes: Long, now: Long)

    @Query(
        "UPDATE downloads SET downloadedBytes = 0, totalBytes = -1, etag = NULL, errorMessage = NULL, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun resetProgress(id: Long, now: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    /** Borra del historial todo lo que ya no está activo ni pausado. */
    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED', 'FAILED', 'CANCELED')")
    suspend fun deleteFinished()
}
