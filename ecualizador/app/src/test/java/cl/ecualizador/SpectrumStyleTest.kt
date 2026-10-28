package cl.ecualizador

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumStyleTest {
    @Test
    fun paletteEndsMatchStops() {
        SpectrumPalette.entries.forEach { p ->
            assertEquals(p.stops.first().second, p.color(0f))
            assertEquals(p.stops.last().second, p.color(1f))
            assertEquals(0xFF, (p.color(0.5f) ushr 24) and 0xFF) // siempre opaco
        }
    }

    @Test
    fun resampleKeepsPeaks() {
        val src = FloatArray(96) { 0f }.also { it[10] = 1f }
        val out = SpectrumShape.resample(src, 24)
        assertEquals(24, out.size)
        assertEquals(1f, out[10 * 24 / 96], 0f)
        assertEquals(1f, out.sum(), 0f)
        assertArrayEquals(src, SpectrumShape.resample(src, 96), 0f)
    }

    @Test
    fun sensitivityCurve() {
        assertEquals(0.3f, SpectrumShape.applySensitivity(0.3f, 1f), 0.001f) // neutra
        assertTrue(SpectrumShape.applySensitivity(0.3f, 2f) > 0.3f)        // más sensible
        assertTrue(SpectrumShape.applySensitivity(0.3f, 0.5f) < 0.3f)      // menos sensible
        assertEquals(1f, SpectrumShape.applySensitivity(1f, 3f), 0.001f)
        assertEquals(0f, SpectrumShape.applySensitivity(0f, 3f), 0.001f)
    }
}
