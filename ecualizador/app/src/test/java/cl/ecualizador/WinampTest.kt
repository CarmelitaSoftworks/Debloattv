package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampTest {
    @Test
    fun viscolorTableIsComplete() {
        assertEquals(24, WinampColors.VIS.size)
        assertEquals(0xFF000000.toInt(), WinampColors.VIS[0])
        assertEquals(0xFFEF3110.toInt(), WinampColors.VIS[2])  // rojo arriba
        assertEquals(0xFF188408.toInt(), WinampColors.VIS[17]) // verde abajo
    }

    @Test
    fun normalModeColorsByAbsoluteHeight() {
        assertEquals(17, WinampColors.barColorIndex(0, 5, fire = false))
        assertEquals(2, WinampColors.barColorIndex(15, 16, fire = false))
        // Dos barras de distinta altura comparten el color en la misma fila.
        assertEquals(WinampColors.barColorIndex(3, 5, false), WinampColors.barColorIndex(3, 12, false))
    }

    @Test
    fun fireModeAlwaysHasARedTip() {
        assertEquals(2, WinampColors.barColorIndex(4, 5, fire = true))
        assertEquals(2, WinampColors.barColorIndex(11, 12, fire = true))
        assertTrue(WinampColors.barColorIndex(0, 12, fire = true) > 2)
    }

    @Test
    fun winampStylesUseFixedBands() {
        assertEquals(19, SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC, bars = 96).effectiveBars)
        assertEquals(75, SpectrumSettings(style = SpectrumStyle.WINAMP_LINES).effectiveBars)
        assertEquals(32, SpectrumSettings(style = SpectrumStyle.BARS, bars = 32).effectiveBars)
        assertTrue(SpectrumPalette.entries.size >= 16)
    }
}
