package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqExtrasTest {
    @Test
    fun loudnessOnlyAtLowVolume() {
        assertTrue(EqCore.loudness(0.8f).all { it == 0f })
        val low = EqCore.loudness(0.1f)
        assertEquals(6f, low[0], 0.001f)
        assertEquals(0f, low[5], 0.001f)
        assertTrue(EqCore.loudness(0.35f)[0] in 0.1f..5.9f)
    }

    @Test
    fun rampGoesFromStartToEnd() {
        val a = List(10) { 0f }
        val b = List(10) { 6f }
        assertEquals(a, EqCore.ramp(a, b, 0f))
        assertEquals(b, EqCore.ramp(a, b, 1f))
        assertEquals(3f, EqCore.ramp(a, b, 0.5f)[0], 0.001f)
    }

    @Test
    fun graphicEqRoundTrip() {
        val curve = EqCore.PRESETS.getValue("Graves y agudos")
        val text = EqCore.toGraphicEq(curve)
        assertTrue(text.startsWith("GraphicEQ: 31 5.0; 62 4.0"))
        assertEquals(curve, EqCore.fromGraphicEq(text))
    }

    @Test
    fun importsAutoEqStyleCurve() {
        // AutoEQ entrega muchos puntos; se llevan a las 10 bandas por interpolación logarítmica.
        val text = "GraphicEQ: 20 6.0; 50 5.0; 100 3.0; 300 0.0; 1000 0.0; 3000 -2.0; 6000 1.0; 10000 2.0; 20000 -3.0"
        val c = EqCore.fromGraphicEq(text)!!
        assertEquals(10, c.size)
        assertTrue(c[0] > c[3])
        assertEquals(0f, c[5], 0.5f)
        assertNull(EqCore.fromGraphicEq("hola"))
        assertNull(EqCore.fromGraphicEq("GraphicEQ: 100"))
    }
}
