package com.bajaya.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bajaya.app.AppContainer
import com.bajaya.app.data.SettingsRepository
import com.bajaya.app.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ajustes: tema y carpeta de descarga. */
class SettingsViewModel(container: AppContainer) : ViewModel() {

    private val settings = container.settings

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    /** null mientras se carga el valor guardado. */
    val folder: StateFlow<String?> = settings.downloadFolder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setFolder(name: String) {
        viewModelScope.launch { settings.setDownloadFolder(name) }
    }

    /** Vista previa del nombre ya limpio, para mostrarlo antes de guardar. */
    fun previewFolder(raw: String): String = SettingsRepository.sanitizeFolder(raw)
}
