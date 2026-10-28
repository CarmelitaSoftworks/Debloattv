package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampPatternTest {
    private val vis = WinampColors.VIS
    private val chrome = WinampChrome.DEFAULT

    @Test
    fun everyPatternGivesOpaqueColorsOrBackground() {
        WinampSkins.ALL.forEach { skin ->
            WinampPattern.entries.forEach { p ->
                for (h in 1..WinampColors.HEIGHT) for (row in 0 until h) for (fire in listOf(false, true)) {
                    val c = p.pixel(skin.colors, skin.chrome, 5, 19, row, h, fire, row + 3)
                    assertTrue("${skin.name} ${p.label}", c == 0 || (c ushr 24) == 0xFF)
                }
            }
        }
    }

    @Test
    fun rowsMatchesOriginalWinamp() {
        for (row in 0 until 10) assertEquals(vis[WinampColors.barColorIndex(row, 10, false)], WinampPattern.ROWS.pixel(vis, chrome, 0, 19, row, 10, false, 0))
    }

    @Test
    fun solidPaintsWholeBarWithItsTopColor() {
        val top = vis[WinampColors.barColorIndex(7, 8, false)]
        for (row in 0 until 8) assertEquals(top, WinampPattern.SOLID.pixel(vis, chrome, 0, 19, row, 8, false, 0))
    }

    @Test
    fun bandsGoFromBottomColorToTopColor() {
        assertEquals(vis[17], WinampPattern.BANDS.pixel(vis, chrome, 0, 19, 3, 10, false, 0))
        assertEquals(vis[2], WinampPattern.BANDS.pixel(vis, chrome, 18, 19, 3, 10, false, 0))
    }

    /** Denon Antique alterna filas negras: «Línea» y «Bandas» no deben dejar barras invisibles. */
    @Test
    fun solidAndBandsSkipRowsThatMatchBackground() {
        val skin = WinampSkins.byName("Denon Antique")
        val bg = skin.colors[0]
        fun dist(a: Int) = listOf(16, 8, 0).sumOf { kotlin.math.abs(((a shr it) and 0xFF) - ((bg shr it) and 0xFF)) }
        for (h in 1..16) assertTrue(dist(WinampPattern.SOLID.pixel(skin.colors, skin.chrome, 0, 19, 0, h, false, 0)) >= 60)
        for (b in 0 until 19) assertTrue(dist(WinampPattern.BANDS.pixel(skin.colors, skin.chrome, b, 19, 0, 5, false, 0)) >= 60)
    }

    @Test
    fun lcdLeavesEveryOtherRowEmpty() {
        assertNotEquals(0, WinampPattern.LCD.pixel(vis, chrome, 0, 19, 0, 10, false, 0))
        assertEquals(0, WinampPattern.LCD.pixel(vis, chrome, 0, 19, 1, 10, false, 0))
    }

    @Test
    fun ditherDarkensAlternatePixels() {
        val a = WinampPattern.DITHER.pixel(vis, chrome, 0, 19, 4, 10, false, 0)
        val b = WinampPattern.DITHER.pixel(vis, chrome, 0, 19, 4, 10, false, 1)
        assertEquals(vis[WinampColors.barColorIndex(4, 10, false)], a)
        assertNotEquals(a, b)
    }

    @Test
    fun playlistUsesSkinListColors() {
        assertEquals(chrome[WinampChrome.LIST_CURRENT], WinampPattern.PLAYLIST.pixel(vis, chrome, 0, 19, 9, 10, false, 0))
        assertEquals(chrome[WinampChrome.LIST_TEXT], WinampPattern.PLAYLIST.pixel(vis, chrome, 0, 19, 2, 10, false, 0))
        assertEquals(chrome[WinampChrome.LIST_BG], WinampPattern.PLAYLIST.background(chrome))
        assertEquals(null, WinampPattern.ROWS.background(chrome))
    }

    @Test
    fun chromeGoesFromFaceToAccent() {
        val skin = WinampSkins.byName("Major Tom")
        val top = WinampPattern.CHROME.pixel(skin.colors, skin.chrome, 0, 19, 15, 16, false, 0)
        assertEquals(skin.chrome[WinampChrome.ACCENT], top)
    }

    @Test
    fun mixBlendsChannels() {
        assertEquals(0xFF808080.toInt(), WinampColors.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f + 1f / 510))
        assertEquals(0xFF000000.toInt(), WinampColors.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
    }
}
