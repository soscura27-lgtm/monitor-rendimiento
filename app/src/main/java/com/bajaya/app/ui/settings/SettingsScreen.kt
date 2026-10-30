package com.bajaya.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bajaya.app.AppContainer
import com.bajaya.app.data.ThemeMode
import com.bajaya.app.ui.NoInsets
import com.bajaya.app.ui.simpleFactory

/** Pantalla de ajustes: tema claro/oscuro y carpeta de descarga. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val viewModel: SettingsViewModel = viewModel(factory = simpleFactory { SettingsViewModel(container) })
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val savedFolder by viewModel.folder.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Ajustes") }) },
        contentWindowInsets = NoInsets
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- Tema ----------
            Text("Tema", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                ThemeOption("Seguir al sistema", themeMode == ThemeMode.SYSTEM) { viewModel.setTheme(ThemeMode.SYSTEM) }
                ThemeOption("Claro", themeMode == ThemeMode.LIGHT) { viewModel.setTheme(ThemeMode.LIGHT) }
                ThemeOption("Oscuro", themeMode == ThemeMode.DARK) { viewModel.setTheme(ThemeMode.DARK) }
            }

            HorizontalDivider()

            // ---------- Carpeta ----------
            Text("Carpeta de descarga", style = MaterialTheme.typography.titleMedium)

            // El campo se rellena cuando llega el valor guardado y luego lo edita el usuario.
            var folderText by remember { mutableStateOf("") }
            LaunchedEffect(savedFolder) { savedFolder?.let { folderText = it } }

            val cleaned = viewModel.previewFolder(folderText)
            val changed = savedFolder != null && cleaned != savedFolder

            OutlinedTextField(
                value = folderText,
                onValueChange = { folderText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nombre de la carpeta") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (changed) viewModel.setFolder(folderText) }),
                supportingText = {
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "Los videos se guardan en Películas/$cleaned, el audio en Música/$cleaned " +
                                "y el resto en Descargas/$cleaned."
                        } else {
                            "En Android 8 y 9 los archivos se guardan dentro de la carpeta propia de la app " +
                                "(Android/data/…/files), subcarpeta \"$cleaned\"."
                        }
                    )
                }
            )

            Button(
                onClick = { viewModel.setFolder(folderText) },
                enabled = changed,
                modifier = Modifier.align(Alignment.End)
            ) { Text("Guardar carpeta") }

            Text(
                "El cambio se aplica a las descargas nuevas; lo ya guardado no se mueve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ---------- Privacidad ----------
            Text("Privacidad", style = MaterialTheme.typography.titleMedium)
            Text(
                "BajaYa no incluye anuncios, rastreadores ni analítica. Solo se conecta a los enlaces " +
                    "que tú le pides y todo el historial se queda en tu dispositivo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Una fila con botón de opción: toda la fila es pulsable. */
@Composable
private fun ThemeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
