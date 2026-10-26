package com.rendimiento.monitor

/**
 * Una lectura de todas las metricas. Los valores desconocidos usan NaN (decimales),
 * -1 (enteros) o Int.MIN_VALUE (corriente de bateria).
 */
data class Snapshot(
    val shellOk: Boolean = false,
    /** Paquete de la app al frente y su nombre visible. */
    val pkg: String? = null,
    val appLabel: String = "",
    val fps: Float = Float.NaN,
    val frameMs: Float = Float.NaN,
    val low1: Float = Float.NaN,
    /** FpsEngine.SRC_*: de donde salieron los FPS. */
    val fpsSource: Int = FpsEngine.SRC_NONE,
    /** Por que no hay FPS (texto corto), o "" si se estan leyendo bien. */
    val fpsStatus: String = "",
    /** Estadisticas de la sesion de juego actual. */
    val fpsAvg: Float = Float.NaN,
    val fpsMin: Float = Float.NaN,
    val fpsMax: Float = Float.NaN,
    val sessionMs: Long = 0L,
    val refreshHz: Float = Float.NaN,
    val cpuPct: Float = Float.NaN,
    val cpuMhz: Int = -1,
    val cpuMaxMhz: Int = -1,
    val cpuTemp: Float = Float.NaN,
    val gpuPct: Float = Float.NaN,
    val gpuMhz: Int = -1,
    val gpuTemp: Float = Float.NaN,
    val ramUsedMb: Long = -1L,
    val ramTotalMb: Long = -1L,
    val batPct: Int = -1,
    val batTemp: Float = Float.NaN,
    val batMa: Int = Int.MIN_VALUE,
    val batW: Float = Float.NaN,
    val batV: Float = Float.NaN,
    val charging: Boolean = false,
    val thermalStatus: Int = -1,
    val ping: PingEngine.Result = PingEngine.Result.EMPTY,
    /** App (sin contar esta) que mas CPU y mas RAM usa. */
    val topCpu: AppMonitor.AppStat? = null,
    val topRam: AppMonitor.AppStat? = null,
    val topCpuLabel: String = "",
    val topRamLabel: String = ""
)
