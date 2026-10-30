package com.bajaya.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bajaya.app.AppContainer
import com.bajaya.app.BajaYaApp
import com.bajaya.app.data.DownloadFiles
import com.bajaya.app.data.db.DownloadEntity
import com.bajaya.app.data.db.DownloadStatus
import com.bajaya.app.net.HttpStatusException
import com.bajaya.app.net.NotMediaException
import com.bajaya.app.util.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.UnknownHostException

/**
 * Ejecuta UNA descarga. WorkManager la mantiene viva en segundo plano y, al llamar a
 * [setForeground], la convierte en un servicio en primer plano con notificación visible.
 *
 * Pausar/cancelar = WorkManager cancela este worker; la corrutina recibe una
 * CancellationException y la descarga se detiene (el estado ya lo fijó el repositorio).
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val container: AppContainer
        get() = (applicationContext as BajaYaApp).container

    private val downloadId: Long = params.inputData.getLong(KEY_ID, -1L)

    /** Requerido para trabajos "expedited" en Android 11 e inferiores. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val item = container.downloadDao.getById(downloadId)
        return foregroundInfo(
            downloadId,
            item?.fileName ?: "Descarga",
            item?.downloadedBytes ?: 0L,
            item?.totalBytes ?: -1L
        )
    }

    override suspend fun doWork(): Result {
        val dao = container.downloadDao
        val item = dao.getById(downloadId) ?: return Result.failure()

        // Si el usuario ya pausó o canceló antes de que arrancara, no hay nada que hacer.
        if (item.status != DownloadStatus.QUEUED && item.status != DownloadStatus.DOWNLOADING) {
            return Result.success()
        }

        dao.setStatus(item.id, DownloadStatus.DOWNLOADING, null, System.currentTimeMillis())
        showProgress(item, item.downloadedBytes, item.totalBytes)

        val partFile = DownloadFiles.partFile(applicationContext, item.id)

        try {
            val bytes = container.downloader.download(
                url = item.url,
                target = partFile,
                savedEtag = item.etag,
                onHeaders = { total, etag -> dao.setRemoteInfo(item.id, total, etag) },
                onProgress = { downloaded, total ->
                    dao.updateProgress(item.id, downloaded, total, System.currentTimeMillis())
                    showProgress(item, downloaded, total)
                }
            )

            // Descarga completa: se copia a la galería (MediaStore) y se limpia el parcial.
            val folder = container.settings.currentFolder()
            val savedUri = container.mediaSaver.save(partFile, item.fileName, item.mimeType, item.kind, folder)
            partFile.delete()

            dao.markCompleted(item.id, savedUri, bytes, System.currentTimeMillis())
            DownloadNotifications.notifyFinished(applicationContext, item.id, item.fileName, true, null)
            return Result.success()
        } catch (e: CancellationException) {
            // Pausa, cancelación o el sistema detuvo el trabajo: no se toca el estado.
            throw e
        } catch (e: Exception) {
            // Si en realidad nos cancelaron (la red cortada por la cancelación lanza IOException),
            // esta línea relanza la cancelación en lugar de marcar la descarga como fallida.
            currentCoroutineContext().ensureActive()

            // Si mientras tanto el usuario pausó o canceló, ese estado manda: no lo pisamos con "fallida".
            val latest = dao.getById(item.id)
            if (latest == null || latest.status != DownloadStatus.DOWNLOADING) return Result.success()

            val message = e.userMessage()

            // Errores pasajeros (servidor caído, corte de red): hasta 3 intentos automáticos.
            // El parcial se conserva, así que cada intento continúa donde quedó.
            if (isTransient(e) && runAttemptCount < MAX_ATTEMPTS - 1) {
                dao.setStatus(item.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
                return Result.retry()
            }

            dao.setStatus(item.id, DownloadStatus.FAILED, message, System.currentTimeMillis())
            DownloadNotifications.notifyFinished(applicationContext, item.id, item.fileName, false, message)
            return Result.failure()
        }
    }

    private fun isTransient(e: Exception): Boolean = when (e) {
        is HttpStatusException -> e.isTransient
        // Si el dominio no existe o el enlace es una página web, reintentar no ayuda.
        is UnknownHostException -> false
        is NotMediaException -> false
        is IOException -> true
        else -> false
    }

    /** Actualiza la notificación de primer plano. Si el sistema no lo permite, se sigue sin ella. */
    private suspend fun showProgress(item: DownloadEntity, downloaded: Long, total: Long) {
        try {
            setForeground(foregroundInfo(item.id, item.fileName, downloaded, total))
        } catch (e: IllegalStateException) {
            // Android 12+ puede negar iniciar un servicio en primer plano desde segundo plano.
            // La descarga continúa igualmente como trabajo normal de WorkManager.
        }
    }

    private fun foregroundInfo(id: Long, fileName: String, downloaded: Long, total: Long): ForegroundInfo {
        val notification = DownloadNotifications.buildProgress(applicationContext, id, fileName, downloaded, total)
        val notificationId = DownloadNotifications.progressId(id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_ID = "download_id"
        private const val MAX_ATTEMPTS = 3
    }
}
