package com.bajaya.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bajaya.app.BajaYaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Atiende los botones "Pausar" y "Cancelar" de la notificación de descarga. */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadNotifications.EXTRA_ID, -1L)
        if (id < 0) return

        val repository = (context.applicationContext as BajaYaApp).container.downloads

        // goAsync da unos segundos extra para terminar el trabajo con la base de datos.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    DownloadNotifications.ACTION_PAUSE -> repository.pause(id)
                    DownloadNotifications.ACTION_CANCEL -> repository.cancel(id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
