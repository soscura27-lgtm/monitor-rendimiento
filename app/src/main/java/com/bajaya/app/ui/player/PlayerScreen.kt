package com.bajaya.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bajaya.app.AppContainer
import com.bajaya.app.data.db.DownloadEntity
import com.bajaya.app.ui.NoInsets

/** Reproductor integrado (Media3 / ExoPlayer) para ver o escuchar lo ya descargado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(container: AppContainer, downloadId: Long, onBack: () -> Unit) {
    var item by remember { mutableStateOf<DownloadEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Se busca la descarga en la base de datos (una sola vez).
    LaunchedEffect(downloadId) {
        item = container.downloadDao.getById(downloadId)
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.fileName ?: "Reproductor", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        },
        contentWindowInsets = NoInsets
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                // Esta pantalla no tiene barra inferior: evita que los controles queden bajo la barra del sistema.
                .navigationBarsPadding()
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val current = item
            val savedUri = current?.savedUri
            when {
                !loaded -> CircularProgressIndicator()
                savedUri == null -> Text(
                    "Este archivo no está disponible para reproducir.",
                    style = MaterialTheme.typography.bodyLarge
                )
                else -> MediaPlayerView(uriString = savedUri)
            }
        }
    }
}

/**
 * Crea el ExoPlayer, lo enlaza al ciclo de vida y lo libera al salir.
 * Se pausa al enviar la app a segundo plano para no gastar batería ni sonar con la pantalla apagada.
 */
@Composable
private fun MediaPlayerView(uriString: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var errorText by remember { mutableStateOf<String?>(null) }

    val player = remember(uriString) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uriString.toUri()))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = "No se pudo reproducir este archivo."
            }
        }
        player.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release() // imprescindible: libera el decodificador y la memoria
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null }
        )
        errorText?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
