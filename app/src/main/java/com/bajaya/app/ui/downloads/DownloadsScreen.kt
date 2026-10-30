package com.bajaya.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bajaya.app.AppContainer
import com.bajaya.app.data.db.DownloadEntity
import com.bajaya.app.data.db.DownloadStatus
import com.bajaya.app.extract.MediaKind
import com.bajaya.app.ui.NoInsets
import com.bajaya.app.ui.simpleFactory
import com.bajaya.app.util.formatBytes

/** Historial de descargas con progreso y controles (pausar, reanudar, cancelar, borrar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(container: AppContainer, onPlay: (Long) -> Unit) {
    val viewModel: DownloadsViewModel = viewModel(factory = simpleFactory { DownloadsViewModel(container) })
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    var toDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Descargas") },
                actions = {
                    // Solo tiene sentido si hay algo terminado, fallido o cancelado que limpiar.
                    val hasFinished = downloads.orEmpty().any {
                        it.status == DownloadStatus.COMPLETED ||
                            it.status == DownloadStatus.FAILED ||
                            it.status == DownloadStatus.CANCELED
                    }
                    if (hasFinished) {
                        TextButton(onClick = { confirmClear = true }) { Text("Limpiar") }
                    }
                }
            )
        },
        contentWindowInsets = NoInsets
    ) { padding ->
        val list = downloads
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    text = "Aún no hay descargas.\nPega un enlace en la pantalla de inicio.",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.id }) { item ->
                        DownloadCard(
                            item = item,
                            onPause = { viewModel.pause(item.id) },
                            onResume = { viewModel.resume(item.id) },
                            onCancel = { viewModel.cancel(item.id) },
                            onPlay = { onPlay(item.id) },
                            onDelete = { toDelete = item }
                        )
                    }
                }
            }
        }
    }

    // Confirmación para borrar un elemento.
    toDelete?.let { item ->
        val hasFile = item.status == DownloadStatus.COMPLETED && item.savedUri != null
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminar descarga") },
            text = {
                Text(
                    if (hasFile) "¿Quieres quitarla del historial o también borrar el archivo guardado?"
                    else "¿Quitar \"${item.fileName}\" del historial?"
                )
            },
            confirmButton = {
                if (hasFile) {
                    TextButton(onClick = { viewModel.delete(item.id, deleteFile = true); toDelete = null }) {
                        Text("Borrar también el archivo")
                    }
                } else {
                    TextButton(onClick = { viewModel.delete(item.id, deleteFile = false); toDelete = null }) {
                        Text("Eliminar")
                    }
                }
            },
            dismissButton = {
                Row {
                    if (hasFile) {
                        TextButton(onClick = { viewModel.delete(item.id, deleteFile = false); toDelete = null }) {
                            Text("Solo del historial")
                        }
                    }
                    TextButton(onClick = { toDelete = null }) { Text("Cancelar") }
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpiar historial") },
            text = {
                Text("Se quitarán de la lista las descargas terminadas, fallidas y canceladas. Los archivos guardados en tu galería no se borran.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearFinished(); confirmClear = false }) { Text("Limpiar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2
            )

            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            // Barra de progreso mientras la descarga no ha terminado.
            if (item.status in listOf(
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED
                )
            ) {
                val known = item.totalBytes > 0
                if (known) {
                    LinearProgressIndicator(
                        progress = { (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                    // Tamaño desconocido: barra indeterminada.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                        TextButton(onClick = onPause) { Text("Pausar") }
                        TextButton(onClick = onCancel) { Text("Cancelar") }
                    }

                    DownloadStatus.PAUSED -> {
                        TextButton(onClick = onResume) { Text("Reanudar") }
                        TextButton(onClick = onCancel) { Text("Cancelar") }
                    }

                    DownloadStatus.FAILED -> TextButton(onClick = onResume) { Text("Reintentar") }
                    DownloadStatus.CANCELED -> TextButton(onClick = onResume) { Text("Descargar de nuevo") }

                    DownloadStatus.COMPLETED -> {
                        // El reproductor sirve para video y audio; otros tipos no se reproducen.
                        if (item.kind != MediaKind.OTHER && item.savedUri != null) {
                            IconButton(onClick = onPlay) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir")
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f))

                // Las descargas activas se cancelan; el resto se puede quitar del historial.
                if (item.status != DownloadStatus.QUEUED && item.status != DownloadStatus.DOWNLOADING) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                    }
                }
            }
        }
    }
}

/** Línea de estado legible bajo el nombre del archivo. */
private fun statusLine(item: DownloadEntity): String = when (item.status) {
    DownloadStatus.QUEUED -> "En cola…"
    DownloadStatus.DOWNLOADING ->
        if (item.totalBytes > 0) {
            "${formatBytes(item.downloadedBytes)} de ${formatBytes(item.totalBytes)} · " +
                "${(item.downloadedBytes * 100 / item.totalBytes).coerceIn(0, 100)}%"
        } else {
            "${formatBytes(item.downloadedBytes)} descargados"
        }

    DownloadStatus.PAUSED ->
        if (item.totalBytes > 0) {
            "En pausa · ${formatBytes(item.downloadedBytes)} de ${formatBytes(item.totalBytes)}"
        } else {
            "En pausa · ${formatBytes(item.downloadedBytes)}"
        }

    DownloadStatus.COMPLETED -> "Completa · ${formatBytes(item.totalBytes)}"
    DownloadStatus.FAILED -> item.errorMessage ?: "La descarga falló."
    DownloadStatus.CANCELED -> "Cancelada"
}
