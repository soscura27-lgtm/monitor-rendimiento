package com.rendimiento.monitor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Mosaico "Monitor" de los ajustes rapidos: enciende o apaga la barra flotante sin abrir la app
 * (util en pleno juego).
 */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                it.updateTile()
            }
            return
        }
        // Una actividad invisible arranca el servicio: asi Android 14+ permite el servicio en primer plano.
        val target = if (Settings.canDrawOverlays(this)) LaunchOverlayActivity::class.java else MainActivity::class.java
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(intent)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) {
            try {
                startActivity(intent)
            } catch (x: Throwable) {
            }
        }
    }

    private fun updateTile() {
        val t = qsTile ?: return
        t.state = if (OverlayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (OverlayService.running) "Activa" else "Apagada"
        t.updateTile()
    }
}

/** Actividad invisible que inicia la barra flotante y se cierra enseguida. */
class LaunchOverlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (!OverlayService.running) startForegroundService(Intent(this, OverlayService::class.java))
        } catch (e: Throwable) {
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
