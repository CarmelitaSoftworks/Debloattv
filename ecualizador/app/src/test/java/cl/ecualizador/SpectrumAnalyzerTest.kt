package cl.ecualizador

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpectrumAnalyzerTest {
    private val rate = 44100
    private val n = 1024

    private fun tone(hz: Float, amp: Int): ByteArray {
        val fft = ByteArray(n)
        val k = Math.round(hz / (rate.toFloat() / n))
        fft[2 * k] = amp.toByte()
        return fft
    }

    @Test
    fun lowToneLandsOnTheLeftAndRestStaysLow() {
        val a = SpectrumAnalyzer(48)
        var bars = FloatArray(48)
        repeat(5) { bars = a.process(tone(80f, 100), rate) }
        val loudest = bars.indices.maxByOrNull { bars[it] }!!
        assertTrue("el tono de 80 Hz debe estar a la izquierda (barra $loudest)", loudest < 16)
        assertTrue("la barra del tono debe estar alta", bars[loudest] > 0.8f)
        assertTrue("las barras lejanas deben quedar bajas, no una pared", bars.takeLast(16).all { it < 0.3f })
    }

    @Test
    fun noStaircaseInTheBass() {
        // Ruido «plano» en todos los bins: las barras graves no deben repetir el mismo valor en bloques.
        val fft = ByteArray(n) { if (it >= 2) ((it * 37) % 40 + 20).toByte() else 0 }
        val bars = SpectrumAnalyzer(96).process(fft, rate)
        val low = bars.take(24)
        val repeatedRuns = low.zipWithNext().count { (x, y) -> abs(x - y) < 1e-6f }
        assertTrue("demasiadas barras iguales seguidas: $repeatedRuns", repeatedRuns < 12)
    }

    @Test
    fun silenceIsEmpty() {
        val bars = SpectrumAnalyzer(32).process(ByteArray(n), rate)
        assertTrue(bars.all { it == 0f })
    }

    @Test
    fun automaticGainFollowsQuietMusic() {
        val a = SpectrumAnalyzer(32)
        repeat(3) { a.process(tone(1000f, 120), rate) }   // fuerte
        var quiet = FloatArray(32)
        repeat(400) { quiet = a.process(tone(1000f, 12), rate) } // luego suave por varios segundos
        assertTrue("tras unos segundos la música suave debe verse alta", quiet.max() > 0.7f)
    }
}
