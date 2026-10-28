package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCoreTest {
    @Test
    fun cutoffsSitBetweenCenters() {
        val c = EqCore.cutoffs()
        assertEquals(EqCore.BANDS, c.size)
        for (i in 0 until EqCore.BANDS - 1) {
            assertTrue(c[i] > EqCore.CENTERS[i] && c[i] < EqCore.CENTERS[i + 1])
        }
        assertEquals(20000f, c.last())
    }

    @Test
    fun bassBoostOnlyTouchesLowBands() {
        val g = EqCore.finalGains(List(10) { 0f }, 1f)
        assertEquals(EqCore.BASS_MAX_DB, g[0], 0.001f)
        assertEquals(0f, g[5], 0.001f)
        assertTrue(g[1] < g[0] && g[2] < g[1])
    }

    @Test
    fun preampAvoidsClipping() {
        assertEquals(-7f, EqCore.autoPreamp(listOf(2f, 7f, -3f)), 0.001f)
        assertEquals(0f, EqCore.autoPreamp(listOf(-2f, -1f)), 0.001f)
    }

    /** Graves al máximo (+9 dB): antes se bajaba todo 9 dB; en equilibrado solo 3 dB. */
    @Test
    fun headroomModesDecideHowMuchVolumeIsLost() {
        val bass = EqCore.finalGains(List(EqCore.BANDS) { 0f }, 1f)
        assertEquals(9f, bass.max(), 0.001f)
        assertEquals(-9f, EqCore.preamp(bass, EqCore.Headroom.FULL, hasLimiter = true), 0.001f)
        assertEquals(-3f, EqCore.preamp(bass, EqCore.Headroom.BALANCED, hasLimiter = true), 0.001f)
        assertEquals(0f, EqCore.preamp(bass, EqCore.Headroom.LOUD, hasLimiter = true), 0.001f)
        // Subidas pequeñas no restan volumen en equilibrado
        assertEquals(0f, EqCore.preamp(listOf(2f, 3f, -1f), EqCore.Headroom.BALANCED, hasLimiter = true), 0.001f)
        // Sin limitador, equilibrado tiene que bajar todo para no saturar
        assertEquals(-9f, EqCore.preamp(bass, EqCore.Headroom.BALANCED, hasLimiter = false), 0.001f)
        // Si nada sube, nunca se baja el volumen
        assertEquals(0f, EqCore.preamp(listOf(-4f, -2f), EqCore.Headroom.FULL, hasLimiter = true), 0.001f)
    }

    @Test
    fun interpolationMatchesBands() {
        val levels = EqCore.PRESETS.getValue("Graves y agudos")
        EqCore.CENTERS.forEachIndexed { i, f -> assertEquals(levels[i], EqCore.interpolate(levels, f), 0.001f) }
        val mid = EqCore.interpolate(levels, 3000f)
        assertTrue(mid in levels[6]..levels[7])
    }

    @Test
    fun presetsHaveTenBandsInRange() {
        EqCore.PRESETS.values.forEach { p ->
            assertEquals(10, p.size)
            assertTrue(p.all { it in EqCore.MIN_DB..EqCore.MAX_DB })
        }
        assertEquals("1k", EqCore.label(5))
        assertEquals("31", EqCore.label(0))
    }
}

class EqCoreExtrasTest {
    @Test
    fun balanceOnlyAttenuatesTheOtherSide() {
        val (l, r) = EqCore.channelGains(preamp = -3f, boost = 2f, balance = 0.5f)
        assertEquals(-1f - 12f, l, 0.001f)
        assertEquals(-1f, r, 0.001f)
        val (l2, r2) = EqCore.channelGains(0f, 99f, -1f)
        assertEquals(EqCore.BOOST_MAX_DB, l2, 0.001f)
        assertEquals(EqCore.BOOST_MAX_DB - EqCore.BALANCE_RANGE_DB, r2, 0.001f)
    }

    @Test
    fun responseCurveSpansAudibleRange() {
        val curve = EqCore.responseCurve(EqCore.PRESETS.getValue("Graves extra"))
        assertEquals(20f, curve.first().first, 0.01f)
        assertEquals(20000f, curve.last().first, 1f)
        assertTrue(curve.first().second > curve.last().second)
    }
}
