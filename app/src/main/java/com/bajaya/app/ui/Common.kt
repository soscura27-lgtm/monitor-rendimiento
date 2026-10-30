package com.bajaya.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Sin márgenes del sistema. El Scaffold exterior (barra inferior) y cada TopAppBar ya
 * gestionan los márgenes; así no se suman dos veces.
 */
val NoInsets = WindowInsets(0, 0, 0, 0)

/** Fábrica mínima de ViewModels que reciben dependencias por constructor. */
inline fun <VM : ViewModel> simpleFactory(crossinline builder: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = builder() as T
    }
