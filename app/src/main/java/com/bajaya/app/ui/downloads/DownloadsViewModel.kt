package com.bajaya.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bajaya.app.AppContainer
import com.bajaya.app.data.db.DownloadEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lista de descargas (historial). Cada acción delega en el repositorio. */
class DownloadsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.downloads

    /** null = todavía cargando; lista vacía = sin historial. */
    val downloads: StateFlow<List<DownloadEntity>?> = repository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pause(id: Long) = viewModelScope.launch { repository.pause(id) }

    fun resume(id: Long) = viewModelScope.launch { repository.resume(id) }

    fun cancel(id: Long) = viewModelScope.launch { repository.cancel(id) }

    /** Quita del historial; [deleteFile] borra además el archivo de la galería. */
    fun delete(id: Long, deleteFile: Boolean) = viewModelScope.launch { repository.delete(id, deleteFile) }

    fun clearFinished() = viewModelScope.launch { repository.clearFinished() }
}
