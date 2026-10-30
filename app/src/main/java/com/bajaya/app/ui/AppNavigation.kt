package com.bajaya.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bajaya.app.AppContainer
import com.bajaya.app.ui.downloads.DownloadsScreen
import com.bajaya.app.ui.home.HomeScreen
import com.bajaya.app.ui.player.PlayerScreen
import com.bajaya.app.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** Rutas de navegación. */
object Routes {
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{id}"
    fun player(id: Long) = "player/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Inicio", Icons.Default.Home),
    Tab(Routes.DOWNLOADS, "Descargas", Icons.AutoMirrored.Filled.List),
    Tab(Routes.SETTINGS, "Ajustes", Icons.Default.Settings)
)

/** Raíz de la interfaz: barra de pestañas inferior y el grafo de navegación. */
@Composable
fun AppRoot(container: AppContainer, sharedText: MutableStateFlow<String?>) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    NotificationPermissionRequest()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        },
        contentWindowInsets = NoInsets
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    sharedText = sharedText,
                    onOpenDownloads = { navController.navigateToTab(Routes.DOWNLOADS) }
                )
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    container = container,
                    onPlay = { id -> navController.navigate(Routes.player(id)) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container = container)
            }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                PlayerScreen(
                    container = container,
                    downloadId = entry.arguments?.getLong("id") ?: -1L,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/** Cambia de pestaña conservando el estado de cada una. */
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pide el permiso de notificaciones (Android 13+) una vez, al abrir la app. */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
