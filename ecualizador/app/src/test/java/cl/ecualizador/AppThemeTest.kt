package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun everySkinGivesReadableTheme() {
        WinampSkins.BUILT_IN.forEach { skin ->
            val p = AppTheme.from(skin)
            assertTrue("${skin.name}: texto", AppTheme.contrast(p.ink, p.card) >= 7.0)
            assertTrue("${skin.name}: texto sobre fondo", AppTheme.contrast(p.ink, p.bg) >= 4.5)
            assertTrue("${skin.name}: acento", AppTheme.contrast(p.accent, p.card) >= 3.0)
            assertTrue("${skin.name}: aviso", AppTheme.contrast(p.warn, p.card) >= 4.5)
            assertTrue("${skin.name}: secundario", AppTheme.contrast(p.muted, p.card) >= 3.0)
            assertTrue("${skin.name}: opaco", listOf(p.bg, p.card, p.track, p.accent, p.ink, p.muted, p.warn).all { (it ushr 24) == 0xFF })
        }
    }

    @Test
    fun lightSkinsGiveLightThemeAndDarkSkinsDark() {
        assertTrue(AppTheme.from(WinampSkins.byName("Mac OS clásico")).light)
        assertFalse(AppTheme.from(WinampSkins.DEFAULT).light)
    }

    @Test
    fun accentKeepsSkinIdentity() {
        // Major Tom: rayo amarillo.
        val tom = AppTheme.from(WinampSkins.byName("Major Tom")).accent
        assertTrue(((tom shr 16) and 0xFF) > 150 && ((tom shr 8) and 0xFF) > 120 && (tom and 0xFF) < 100)
    }

    @Test
    fun contrastOfBlackAndWhiteIs21() {
        assertEquals(21.0, AppTheme.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }
}
