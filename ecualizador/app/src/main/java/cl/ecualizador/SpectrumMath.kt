package cl.ecualizador

import kotlin.math.ln
import kotlin.math.pow

/**
 * Animación del analizador: subida rápida, caída suave y marcas de pico que se sostienen un
 * momento antes de bajar. Se avanza una vez por fotograma con el tiempo transcurrido.
 */
class SpectrumSmoother(
    val size: Int,
    private val riseRatePerSec: Float = 30f,
    var fallPerSec: Float = 1.6f,
    private val peakHoldMs: Long = 700,
    private val peakFallPerSec: Float = 0.5f,
) {
    val values = FloatArray(size)
    val peaks = FloatArray(size)
    private val peakAgeMs = LongArray(size)

    fun step(target: FloatArray, dtMs: Long) {
        val dt = dtMs.coerceIn(0, 100) / 1000f
        val rise = (riseRatePerSec * dt).coerceAtMost(1f)
        for (i in 0 until size) {
            val t = target.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            values[i] = if (t > values[i]) values[i] + (t - values[i]) * rise
            else maxOf(t, values[i] - fallPerSec * dt)
            if (values[i] >= peaks[i]) {
                peaks[i] = values[i]
                peakAgeMs[i] = 0
            } else {
                peakAgeMs[i] += dtMs
                if (peakAgeMs[i] > peakHoldMs) peaks[i] = maxOf(values[i], peaks[i] - peakFallPerSec * dt)
            }
        }
    }

    fun reset() {
        values.fill(0f); peaks.fill(0f); peakAgeMs.fill(0)
    }
}

object SpectrumScale {
    // El FFT del Visualizer tiene un bin cada ~43 Hz: por debajo no hay información que mostrar.
    const val LOW_HZ = 40f
    const val HIGH_HZ = 16000f

    /** Posición horizontal (0..1) de una frecuencia en el eje logarítmico del analizador. */
    fun xOf(hz: Float): Float = ((ln(hz) - ln(LOW_HZ)) / (ln(HIGH_HZ) - ln(LOW_HZ))).coerceIn(0f, 1f)

    /** Frecuencia central de la barra [i] de [bars]. */
    fun barHz(i: Int, bars: Int): Float = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((i + 0.5f) / bars)

    /**
     * Paleta de calor de 256 colores ARGB para la cascada: negro → azul → violeta → naranjo → amarillo.
     */
    val HEAT: IntArray = IntArray(256) { i ->
        val stops = listOf(
            0f to intArrayOf(0x0F, 0x11, 0x15),
            0.25f to intArrayOf(0x1E, 0x3A, 0x8A),
            0.5f to intArrayOf(0x8E, 0x44, 0xEF),
            0.75f to intArrayOf(0xFF, 0x70, 0x43),
            1f to intArrayOf(0xFF, 0xEB, 0x3B),
        )
        val v = i / 255f
        val hi = stops.indexOfFirst { it.first >= v }.coerceAtLeast(1)
        val (p0, c0) = stops[hi - 1]
        val (p1, c1) = stops[hi]
        val t = if (p1 == p0) 0f else (v - p0) / (p1 - p0)
        val ch = IntArray(3) { (c0[it] + (c1[it] - c0[it]) * t).toInt().coerceIn(0, 255) }
        (0xFF shl 24) or (ch[0] shl 16) or (ch[1] shl 8) or ch[2]
    }

    fun heat(v: Float): Int = HEAT[(v.coerceIn(0f, 1f) * 255).toInt()]
}
