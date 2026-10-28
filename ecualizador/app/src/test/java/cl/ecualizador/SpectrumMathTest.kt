package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumMathTest {
    @Test
    fun risesFastFallsSlowly() {
        val s = SpectrumSmoother(1)
        repeat(10) { s.step(floatArrayOf(1f), 16) }
        assertTrue("debe subir casi al máximo en ~160 ms", s.values[0] > 0.95f)
        s.step(floatArrayOf(0f), 100)
        assertTrue("no debe caer de golpe", s.values[0] > 0.8f)
        repeat(60) { s.step(floatArrayOf(0f), 16) }
        assertEquals(0f, s.values[0], 0.001f)
    }

    @Test
    fun peakHoldsThenFalls() {
        val s = SpectrumSmoother(1)
        repeat(20) { s.step(floatArrayOf(1f), 16) }
        val peak = s.peaks[0]
        repeat(20) { s.step(floatArrayOf(0f), 16) } // 320 ms: aún sostenido
        assertEquals(peak, s.peaks[0], 0.001f)
        repeat(100) { s.step(floatArrayOf(0f), 16) } // 1,9 s después: ya cayó
        assertTrue(s.peaks[0] < peak)
    }

    @Test
    fun scaleAndPalette() {
        assertEquals(0f, SpectrumScale.xOf(SpectrumScale.LOW_HZ), 0.001f)
        assertEquals(1f, SpectrumScale.xOf(16000f), 0.001f)
        assertTrue(SpectrumScale.barHz(0, 64) > SpectrumScale.LOW_HZ && SpectrumScale.barHz(63, 64) < 16000f)
        assertEquals(256, SpectrumScale.HEAT.size)
        assertEquals(0xFF0F1115.toInt(), SpectrumScale.heat(0f))
        assertEquals(0xFFFFEB3B.toInt(), SpectrumScale.heat(1f))
    }
}
