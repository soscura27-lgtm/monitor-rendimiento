package com.rendimiento.monitor

/**
 * Una lectura de todas las metricas. Los valores desconocidos usan NaN (decimales),
 * -1 (enteros) o Int.MIN_VALUE (corriente de bateria).
 */
data class Snapshot(
    val shellOk: Boolean = false,
    val pkg: String? = null,
    val fps: Float = Float.NaN,
    val frameMs: Float = Float.NaN,
    val low1: Float = Float.NaN,
    val refreshHz: Float = Float.NaN,
    val cpuPct: Float = Float.NaN,
    val cpuMhz: Int = -1,
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
    val charging: Boolean = false,
    val thermalStatus: Int = -1
)
