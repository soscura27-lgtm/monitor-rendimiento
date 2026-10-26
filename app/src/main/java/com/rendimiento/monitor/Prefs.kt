package com.rendimiento.monitor

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val NAME = "monitor_prefs"

    const val SHOW_FPS = "show_fps"
    const val SHOW_CPU = "show_cpu"
    const val SHOW_GPU = "show_gpu"
    const val SHOW_RAM = "show_ram"
    const val SHOW_BAT = "show_bat"
    const val SHOW_TEMP = "show_temp"
    const val SHOW_GRAPH = "show_graph"
    const val SHOW_PING = "show_ping"
    const val SHOW_APPS = "show_apps"

    const val TEXT_SIZE = "text_size"
    const val OPACITY = "opacity"

    /** Estilo de la barra flotante: STYLE_COMPACT o STYLE_PANEL. */
    const val STYLE = "overlay_style"
    const val STYLE_COMPACT = 0
    const val STYLE_PANEL = 1

    /** Cada cuantos milisegundos se actualiza la barra (500, 1000 o 2000). */
    const val REFRESH_MS = "refresh_ms"

    const val POS_X = "pos_x"
    const val POS_Y = "pos_y"
    const val POS_X_LAND = "pos_x_land"
    const val POS_Y_LAND = "pos_y_land"

    /** Guardar resumen de cada sesion de juego. */
    const val SESSIONS = "save_sessions"

    fun of(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun refreshMs(p: SharedPreferences): Long =
        when (p.getInt(REFRESH_MS, 1000)) {
            500 -> 500L
            2000 -> 2000L
            else -> 1000L
        }
}
