package com.bajaya.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bajaya.app.MainActivity
import com.bajaya.app.R
import com.bajaya.app.util.formatBytes

/** Canales y notificaciones de las descargas (progreso, terminada y fallida). */
object DownloadNotifications {
    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_DONE = "downloads_done"

    const val ACTION_PAUSE = "com.bajaya.app.action.PAUSE"
    const val ACTION_CANCEL = "com.bajaya.app.action.CANCEL"
    const val EXTRA_ID = "download_id"

    /** Se llama una vez al iniciar la app. Crear un canal que ya existe no hace nada. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "Descargas en curso",
                NotificationManager.IMPORTANCE_LOW // sin sonido: solo muestra el progreso
            ).apply {
                description = "Progreso de las descargas activas"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                "Descargas terminadas",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Aviso cuando una descarga termina o falla"
            }
        )
    }

    /** Id de la notificación de progreso (siempre distinto de cero). */
    fun progressId(downloadId: Long): Int = 100 + downloadId.toInt()

    /** Id de la notificación de "terminada/fallida". */
    fun doneId(downloadId: Long): Int = 1_000_100 + downloadId.toInt()

    /** Notificación persistente con barra de progreso y botones Pausar / Cancelar. */
    fun buildProgress(
        context: Context,
        downloadId: Long,
        fileName: String,
        downloaded: Long,
        total: Long
    ) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle(fileName)
        .setContentText(progressText(downloaded, total))
        .setProgress(100, percent(downloaded, total), total <= 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openAppIntent(context))
        .addAction(0, "Pausar", actionIntent(context, ACTION_PAUSE, downloadId))
        .addAction(0, "Cancelar", actionIntent(context, ACTION_CANCEL, downloadId))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    /** Aviso final: descarga completa o con error. */
    @SuppressLint("MissingPermission") // se comprueba a mano justo debajo
    fun notifyFinished(
        context: Context,
        downloadId: Long,
        fileName: String,
        success: Boolean,
        detail: String?
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(fileName)
            .setContentText(if (success) "Descarga completa" else "No se pudo descargar: ${detail ?: "error desconocido"}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(if (success) "Descarga completa" else "No se pudo descargar: ${detail ?: "error desconocido"}")
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(doneId(downloadId), notification)
    }

    private fun percent(downloaded: Long, total: Long): Int =
        if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0

    private fun progressText(downloaded: Long, total: Long): String =
        if (total > 0) {
            "${formatBytes(downloaded)} de ${formatBytes(total)} · ${percent(downloaded, total)}%"
        } else {
            "${formatBytes(downloaded)} descargados"
        }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun actionIntent(context: Context, action: String, downloadId: Long): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, downloadId)
        }
        // requestCode distinto por descarga y acción, para que los PendingIntent no se mezclen.
        val requestCode = downloadId.toInt() * 2 + if (action == ACTION_PAUSE) 0 else 1
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
