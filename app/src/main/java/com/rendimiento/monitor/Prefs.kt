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

    const val TEXT_SIZE = "text_size"
    const val OPACITY = "opacity"

    const val POS_X = "pos_x"
    const val POS_Y = "pos_y"

    fun of(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
