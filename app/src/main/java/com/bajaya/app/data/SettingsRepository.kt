package com.bajaya.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bajaya.app.util.DEFAULT_DOWNLOAD_FOLDER
import com.bajaya.app.util.sanitizeFolderName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Declarado una sola vez a nivel de archivo, como exige DataStore. */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Ajustes del usuario guardados con DataStore: tema y carpeta de descarga. */
class SettingsRepository(private val context: Context) {

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs: Preferences ->
        prefs[KEY_THEME]
            ?.let { saved -> runCatching { ThemeMode.valueOf(saved) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    /** Subcarpeta dentro de Movies/, Music/ o Download/ donde se guardan los archivos. */
    val downloadFolder: Flow<String> = context.settingsDataStore.data.map { prefs: Preferences ->
        prefs[KEY_FOLDER] ?: DEFAULT_FOLDER
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setDownloadFolder(rawName: String) {
        context.settingsDataStore.edit { it[KEY_FOLDER] = sanitizeFolder(rawName) }
    }

    suspend fun currentFolder(): String = downloadFolder.first()

    companion object {
        const val DEFAULT_FOLDER = DEFAULT_DOWNLOAD_FOLDER
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_FOLDER = stringPreferencesKey("download_folder")

        /** Atajo a [sanitizeFolderName] (la lógica vive en util para poder probarla en JVM). */
        fun sanitizeFolder(raw: String): String = sanitizeFolderName(raw)
    }
}
