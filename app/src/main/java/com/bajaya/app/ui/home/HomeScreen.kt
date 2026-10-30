package com.bajaya.app.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bajaya.app.AppContainer
import com.bajaya.app.R
import com.bajaya.app.extract.MediaCandidate
import com.bajaya.app.ui.NoInsets
import com.bajaya.app.ui.simpleFactory
import com.bajaya.app.util.readClipboardText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter

/** Pantalla principal: pegar un enlace y descargar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    sharedText: MutableStateFlow<String?>,
    onOpenDownloads: () -> Unit
) {
    val viewModel: HomeViewModel = viewModel(factory = simpleFactory { HomeViewModel(container) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeCount by viewModel.activeCount.collectAsStateWithLifecycle()
    val shared by sharedText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current

    // Enlace recibido desde "Compartir": se procesa una sola vez.
    LaunchedEffect(shared) {
        shared?.let {
            viewModel.onSharedText(it)
            sharedText.value = null
        }
    }

    // Detección automática del portapapeles: Android solo permite leerlo cuando la ventana
    // tiene el foco, así que se revisa cada vez que la app lo recupera.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }
            .filter { it }
            .collect { viewModel.onClipboardText(readClipboardText(context)) }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        contentWindowInsets = NoInsets
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pega el enlace de un archivo de video o audio (.mp4, .webm, .mp3…) " +
                    "o de una página como Internet Archive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.clipboardLink?.let { link ->
                ClipboardCard(
                    link = link,
                    onUse = viewModel::useClipboardLink,
                    onDismiss = viewModel::dismissClipboardLink
                )
            }

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enlace (URL)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.download() }),
                trailingIcon = {
                    if (state.input.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onInputChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Borrar texto")
                        }
                    }
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.onInputChange(readClipboardText(context).orEmpty()) }
                ) { Text("Pegar") }

                Button(
                    onClick = viewModel::download,
                    enabled = state.input.isNotBlank() && !state.isResolving,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isResolving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Descargar")
                    }
                }
            }

            state.message?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (state.canHandoff) {
                            Spacer(Modifier.size(12.dp))
                            // "Delegar": si la app no puede con este enlace, lo abre otra app (navegador).
                            OutlinedButton(onClick = { openInOtherApp(context, state.input) }) {
                                Text("Abrir en otra app")
                            }
                        }
                    }
                }
            }

            state.info?.let { info ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = info,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        TextButton(onClick = onOpenDownloads) { Text("Ver descargas") }
                    }
                }
            }

            if (activeCount > 0) {
                Text(
                    text = if (activeCount == 1) "1 descarga en curso · ver" else "$activeCount descargas en curso · ver",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenDownloads)
                )
            }

            Text(
                text = "Descarga solo contenido que tengas derecho a guardar. " +
                    "BajaYa no tiene anuncios ni rastreadores.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (state.candidates.isNotEmpty()) {
        SelectionDialog(
            candidates = state.candidates,
            onConfirm = viewModel::confirmSelection,
            onDismiss = viewModel::dismissSelection
        )
    }
}

@Composable
private fun ClipboardCard(link: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Enlace detectado en el portapapeles",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = link,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUse) { Text("Usar enlace") }
                TextButton(onClick = onDismiss) { Text("Ignorar") }
            }
        }
    }
}

/** Diálogo para elegir uno o varios archivos cuando el enlace contiene varios. */
@Composable
private fun SelectionDialog(
    candidates: List<MediaCandidate>,
    onConfirm: (List<MediaCandidate>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember(candidates) { mutableStateListOf<Int>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elige qué descargar") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(candidates) { index, candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = index in selected,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    if (checked) selected.add(index) else selected.remove(index)
                                }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = index in selected, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(candidate.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected.sorted().map { candidates[it] }) }
            ) { Text("Descargar (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Abre el enlace con otra app (normalmente el navegador) para plataformas que BajaYa no soporta. */
private fun openInOtherApp(context: Context, rawUrl: String) {
    val link = rawUrl.trim().let { if ("://" in it) it else "https://$it" }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Abrir con…"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay ninguna app para abrir este enlace.", Toast.LENGTH_SHORT).show()
    }
}
