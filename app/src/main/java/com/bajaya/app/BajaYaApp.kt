package com.bajaya.app

import android.app.Application
import com.bajaya.app.work.DownloadNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Clase Application: crea el contenedor de dependencias y los canales de notificación. */
class BajaYaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DownloadNotifications.createChannels(this)

        // Corrige descargas que quedaron "en curso" si el sistema cerró la app.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.downloads.reconcile()
        }
    }
}
