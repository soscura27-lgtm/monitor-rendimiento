package com.bajaya.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bajaya.app.data.ThemeMode
import com.bajaya.app.ui.AppRoot
import com.bajaya.app.ui.theme.BajaYaTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Única actividad: aloja toda la interfaz de Compose. */
class MainActivity : ComponentActivity() {

    /** Texto recibido con "Compartir" desde otra app (normalmente un enlace). */
    private val incomingText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Al girar la pantalla la actividad se recrea con el mismo Intent; no hay que
        // procesar el enlace compartido otra vez.
        if (savedInstanceState == null) handleIntent(intent)

        val container = (application as BajaYaApp).container
        setContent {
            val themeMode by container.settings.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            BajaYaTheme(themeMode = themeMode) {
                AppRoot(container = container, sharedText = incomingText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { incomingText.value = it }
        }
    }
}
