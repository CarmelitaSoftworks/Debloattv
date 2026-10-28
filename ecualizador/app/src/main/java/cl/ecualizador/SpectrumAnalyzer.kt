package cl.ecualizador

import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/**
 * Convierte el FFT de android.media.audiofx.Visualizer en barras 0..1 que se vean bien:
 *  - energía de cada banda logarítmica (no el pico de un solo bin), interpolando en los graves,
 *    donde una barra es más angosta que un bin y si no salen «escalones» iguales;
 *  - escala en dB con rango dinámico fijo y ganancia automática que sigue al volumen de la música,
 *    para que las barras respiren en vez de formar una pared (30 dB: lo útil de un FFT de 8 bits);
 * *  - un suavizado leve entre barras vecinas que respeta los sonidos puntuales.
 * Formato del FFT: [R0, R(n/2), R1, I1, R2, I2, …] con valores de 8 bits.
 */
private const val NOISE_FLOOR = 2f

class SpectrumAnalyzer(
    private val bars: Int,
    private val rangeDb: Float = 30f,
    private val lowHz: Float = SpectrumScale.LOW_HZ,
    private val highHz: Float = SpectrumScale.HIGH_HZ,
) {
    /** Referencia de la ganancia automática (dB del máximo reciente). */
    private var reference = 30f

    fun process(fft: ByteArray, samplingRateHz: Int): FloatArray {
        val out = FloatArray(bars)
        val n = fft.size
        if (n < 8 || samplingRateHz <= 0) return out
        val half = n / 2
        // Se descuenta el piso de ruido de cuantización (datos de 8 bits) para que los silencios bajen a cero.
        val mags = FloatArray(half) { k ->
            if (k == 0) 0f else (hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()) - NOISE_FLOOR).coerceAtLeast(0f)
        }
        val binHz = samplingRateHz.toFloat() / n
        val hi = minOf(highHz, samplingRateHz / 2f)
        fun magAt(hz: Float): Float { // interpolación lineal entre bins
            val pos = (hz / binHz).coerceIn(1f, half - 1.001f)
            val k = pos.toInt()
            val t = pos - k
            return mags[k] * (1 - t) + mags[k + 1] * t
        }
        val db = FloatArray(bars)
        var frameMax = -100f
        for (b in 0 until bars) {
            val f0 = lowHz * (hi / lowHz).pow(b / bars.toFloat())
            val f1 = lowHz * (hi / lowHz).pow((b + 1) / bars.toFloat())
            val k0 = (f0 / binHz).toInt()
            val k1 = (f1 / binHz).toInt()
            // Banda más angosta que dos bins: se interpola; si no, se suma la energía de sus bins.
            val power = if (k1 - k0 < 2) {
                val m = magAt((f0 + f1) / 2); m * m
            } else {
                var sum = 0f
                for (k in k0.coerceAtLeast(1) until k1.coerceAtMost(half)) sum += mags[k] * mags[k]
                sum
            }
            db[b] = if (power <= 0f) -100f else 10f * log10(power)
            if (db[b] > frameMax) frameMax = db[b]
        }
        // Ganancia automática: sube al instante con los golpes y baja despacio (~3 dB por cuadro como mucho).
        if (frameMax < 0f) return out // silencio
        reference = if (frameMax > reference) frameMax else maxOf(frameMax, reference - 0.15f).coerceAtLeast(12f)
        for (b in 0 until bars) out[b] = ((db[b] - (reference - rangeDb)) / rangeDb).coerceIn(0f, 1f)
        // Suavizado leve entre vecinas que no aplasta un sonido concentrado en una sola barra.
        val smooth = FloatArray(bars) { b ->
            val l = out[maxOf(0, b - 1)]; val r = out[minOf(bars - 1, b + 1)]
            0.75f * out[b] + 0.125f * (l + r)
        }
        return smooth
    }
}
