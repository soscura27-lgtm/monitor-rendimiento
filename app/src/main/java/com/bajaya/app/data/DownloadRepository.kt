package com.bajaya.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bajaya.app.data.db.DownloadDao
import com.bajaya.app.data.db.DownloadEntity
import com.bajaya.app.data.db.DownloadStatus
import com.bajaya.app.extract.MediaCandidate
import com.bajaya.app.util.sanitizeFileName
import com.bajaya.app.work.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Punto único para crear y controlar descargas. Las pantallas y las notificaciones
 * hablan con esta clase; ella coordina la base de datos (Room) y WorkManager.
 *
 * Pausar = cancelar el trabajo pero conservar el archivo parcial.
 * Reanudar = volver a encolar; el worker pide al servidor solo los bytes que faltan (Range).
 */
class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val workManager: WorkManager,
    private val saver: MediaStoreSaver
) {
    /** Historial completo, siempre actualizado. */
    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    /** Registra una descarga nueva y la manda a la cola. Devuelve su id. */
    suspend fun enqueue(candidate: MediaCandidate): Long {
        val id = dao.insert(
            DownloadEntity(
                url = candidate.url,
                fileName = sanitizeFileName(candidate.fileName),
                mimeType = candidate.mimeType,
                kind = candidate.kind
            )
        )
        schedule(id)
        return id
    }

    /** Pausa una descarga activa. El archivo parcial se conserva para reanudar. */
    suspend fun pause(id: Long) {
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.DOWNLOADING && item.status != DownloadStatus.QUEUED) return
        // Primero el estado (el worker ya no podrá pisarlo) y luego se detiene el trabajo.
        dao.setStatus(id, DownloadStatus.PAUSED, null, now())
        workManager.cancelUniqueWork(uniqueName(id))
    }

    /** Reanuda (o reintenta) una descarga pausada, fallida o cancelada. */
    suspend fun resume(id: Long) {
        val item = dao.getById(id) ?: return
        val resumable = item.status == DownloadStatus.PAUSED ||
            item.status == DownloadStatus.FAILED ||
            item.status == DownloadStatus.CANCELED
        if (!resumable) return
        if (item.status == DownloadStatus.CANCELED) dao.resetProgress(id, now())
        dao.setStatus(id, DownloadStatus.QUEUED, null, now())
        schedule(id)
    }

    /** Cancela y descarta lo descargado hasta ahora. La fila queda como CANCELED. */
    suspend fun cancel(id: Long) {
        val item = dao.getById(id) ?: return
        if (item.status == DownloadStatus.COMPLETED) return
        // Primero el estado: desde ese momento el worker ya no puede escribir progreso (ver DAO).
        dao.setStatus(id, DownloadStatus.CANCELED, null, now())
        workManager.cancelUniqueWork(uniqueName(id))
        withContext(Dispatchers.IO) { DownloadFiles.partFile(context, id).delete() }
        dao.resetProgress(id, now())
    }

    /** Quita una descarga del historial; opcionalmente borra también el archivo guardado. */
    suspend fun delete(id: Long, deleteFile: Boolean) {
        val item = dao.getById(id) ?: return
        workManager.cancelUniqueWork(uniqueName(id))
        withContext(Dispatchers.IO) {
            DownloadFiles.partFile(context, id).delete()
            if (deleteFile) item.savedUri?.let { saver.delete(it) }
        }
        dao.delete(id)
    }

    /** Vacía del historial lo terminado, fallido o cancelado (no toca descargas activas ni pausadas). */
    suspend fun clearFinished() {
        withContext(Dispatchers.IO) {
            dao.getFailedOrCanceledIds().forEach { DownloadFiles.partFile(context, it).delete() }
        }
        dao.deleteFinished()
    }

    /**
     * Al abrir la app: si una fila dice "en curso" pero WorkManager ya no tiene trabajo vivo
     * para ella (por ejemplo, el sistema lo mató), la dejamos en pausa para que el usuario
     * pueda reanudarla en lugar de verla "descargando" para siempre.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        dao.getActive().forEach { item ->
            val infos = workManager.getWorkInfosForUniqueWork(uniqueName(item.id)).get()
            val hasLiveWork = infos.any { !it.state.isFinished }
            if (!hasLiveWork) dao.setStatus(item.id, DownloadStatus.PAUSED, null, now())
        }
    }

    private fun schedule(id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // Intenta arrancar de inmediato; si el sistema no lo permite, corre como trabajo normal.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // REPLACE: un solo trabajo por descarga; si había uno viejo, se sustituye.
        workManager.enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun uniqueName(id: Long) = "download-$id"

    private fun now() = System.currentTimeMillis()
}
