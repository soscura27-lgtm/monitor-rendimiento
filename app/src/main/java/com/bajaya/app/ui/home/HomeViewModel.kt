package com.bajaya.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bajaya.app.AppContainer
import com.bajaya.app.data.db.DownloadStatus
import com.bajaya.app.extract.MediaCandidate
import com.bajaya.app.extract.ResolveOutcome
import com.bajaya.app.util.extractFirstUrl
import com.bajaya.app.util.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Todo lo que la pantalla de inicio necesita para dibujarse. */
data class HomeUiState(
    val input: String = "",
    val isResolving: Boolean = false,
    /** Error o aviso negativo. */
    val message: String? = null,
    /** Aviso positivo (por ejemplo, "Descarga iniciada"). */
    val info: String? = null,
    /** Si es true se ofrece "Abrir en otra app". */
    val canHandoff: Boolean = false,
    /** Si no está vacía se muestra el diálogo para elegir qué descargar. */
    val candidates: List<MediaCandidate> = emptyList(),
    /** Enlace detectado en el portapapeles, listo para usar. */
    val clipboardLink: String? = null
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Cantidad de descargas en curso (para el aviso de la pantalla de inicio). */
    val activeCount: StateFlow<Int> = container.downloads.downloads
        .map { list ->
            list.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Para no volver a sugerir un enlace que el usuario ya usó o descartó. */
    private var handledClipboardLink: String? = null

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, message = null, info = null, canHandoff = false) }
    }

    /** Se llama cuando la ventana gana el foco con el texto actual del portapapeles. */
    fun onClipboardText(text: String?) {
        val link = extractFirstUrl(text)
        _state.update { current ->
            val show = link != null && link != handledClipboardLink && link != current.input.trim()
            current.copy(clipboardLink = if (show) link else null)
        }
    }

    fun useClipboardLink() {
        val link = _state.value.clipboardLink ?: return
        handledClipboardLink = link
        _state.update {
            it.copy(input = link, clipboardLink = null, message = null, info = null, canHandoff = false)
        }
    }

    fun dismissClipboardLink() {
        handledClipboardLink = _state.value.clipboardLink
        _state.update { it.copy(clipboardLink = null) }
    }

    /** Enlace recibido con "Compartir": se rellena y se analiza de inmediato. */
    fun onSharedText(text: String) {
        val link = extractFirstUrl(text) ?: return
        handledClipboardLink = link
        _state.update {
            it.copy(input = link, clipboardLink = null, message = null, info = null, canHandoff = false)
        }
        download()
    }

    /** Botón "Descargar": analiza el enlace y, si hay un solo archivo, lo descarga ya. */
    fun download() {
        val input = _state.value.input.trim()
        if (input.isEmpty() || _state.value.isResolving) return

        viewModelScope.launch {
            _state.update { it.copy(isResolving = true, message = null, info = null, canHandoff = false) }
            try {
                when (val outcome = container.extractors.resolve(input)) {
                    is ResolveOutcome.Invalid ->
                        _state.update { it.copy(isResolving = false, message = outcome.message) }

                    is ResolveOutcome.NothingFound ->
                        _state.update {
                            it.copy(isResolving = false, message = outcome.reason, canHandoff = true)
                        }

                    is ResolveOutcome.Found ->
                        if (outcome.candidates.size == 1) {
                            enqueue(outcome.candidates)
                        } else {
                            // Varios archivos: que el usuario elija.
                            _state.update { it.copy(isResolving = false, candidates = outcome.candidates) }
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isResolving = false, message = e.userMessage(), canHandoff = true)
                }
            }
        }
    }

    /** El usuario eligió archivos en el diálogo. */
    fun confirmSelection(selected: List<MediaCandidate>) {
        viewModelScope.launch { enqueue(selected) }
    }

    fun dismissSelection() {
        _state.update { it.copy(candidates = emptyList()) }
    }

    private suspend fun enqueue(list: List<MediaCandidate>) {
        list.forEach { container.downloads.enqueue(it) }
        _state.update {
            it.copy(
                isResolving = false,
                candidates = emptyList(),
                input = "",
                info = if (list.size == 1) {
                    "Descarga iniciada: ${list.first().fileName}"
                } else {
                    "Se iniciaron ${list.size} descargas."
                }
            )
        }
    }
}
