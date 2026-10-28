package cl.ecualizador

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Cálculos de la calibración acústica: ruido rosa, FFT, nivel por octava y corrección. */
object CalibrationMath {

    /** Ruido rosa (misma energía por octava) con el filtro de Paul Kellet sobre ruido blanco. */
    fun pinkNoise(samples: Int, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val out = FloatArray(samples)
        for (i in 0 until samples) {
            val white = rnd.nextDouble(-1.0, 1.0)
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            out[i] = ((b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11).toFloat()
            b6 = white * 0.115926
        }
        return out
    }

    /** FFT radix-2 en el lugar (re, im de largo potencia de 2). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Nivel (dB) en cada banda de octava de [EqCore.CENTERS], promediando ventanas Hann de 8192
     * muestras con 50 % de solapamiento (método de Welch).
     */
    fun octaveLevels(signal: FloatArray, sampleRate: Int, fftSize: Int = 8192): FloatArray {
        val power = DoubleArray(fftSize / 2)
        val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
        var frames = 0
        var start = 0
        while (start + fftSize <= signal.size) {
            val re = DoubleArray(fftSize) { signal[start + it] * window[it] }
            val im = DoubleArray(fftSize)
            fft(re, im)
            for (k in power.indices) power[k] += re[k] * re[k] + im[k] * im[k]
            frames++
            start += fftSize / 2
        }
        val binHz = sampleRate.toDouble() / fftSize
        return FloatArray(EqCore.BANDS) { b ->
            val fc = EqCore.CENTERS[b].toDouble()
            val lo = (fc / sqrt(2.0) / binHz).toInt().coerceAtLeast(1)
            val hi = (fc * sqrt(2.0) / binHz).toInt().coerceAtMost(power.size - 1)
            var sum = 0.0
            for (k in lo..hi) sum += power[k]
            (10 * log10(sum / maxOf(frames, 1) + 1e-12)).toFloat()
        }
    }

    /** Nivel RMS en dBFS, para saber si el micrófono escuchó el ruido. */
    fun rmsDb(signal: FloatArray): Float {
        if (signal.isEmpty()) return -120f
        var sum = 0.0
        for (s in signal) sum += s * s
        return (10 * log10(sum / signal.size + 1e-12)).toFloat()
    }

    /** Bandas que un micrófono de teléfono mide con confianza (62 Hz a 8 kHz). */
    val TRUSTED = 1..8

    /**
     * Corrección propuesta: lo medido se normaliza a la media de 125 Hz–4 kHz y se invierte hacia
     * una respuesta plana. Se suaviza con las bandas vecinas y se limita: hasta −8 dB para recortar
     * picos y solo +5 dB para rellenar huecos (subir mucho una banda ausente solo distorsiona).
     * Las bandas extremas (31 Hz y 16 kHz) no se tocan: el micrófono no las mide bien.
     */
    fun correction(measured: FloatArray): List<Float> {
        val ref = (2..7).map { measured[it] }.average().toFloat()
        val raw = FloatArray(EqCore.BANDS) { i -> if (i in TRUSTED) ref - measured[i] else 0f }
        val smooth = FloatArray(EqCore.BANDS) { i ->
            if (i !in TRUSTED) 0f
            else {
                val prev = if (i - 1 in TRUSTED) raw[i - 1] else raw[i]
                val next = if (i + 1 in TRUSTED) raw[i + 1] else raw[i]
                0.25f * prev + 0.5f * raw[i] + 0.25f * next
            }
        }
        return smooth.map { (round(it.coerceIn(-8f, 5f) * 2) / 2f) }
    }
}
