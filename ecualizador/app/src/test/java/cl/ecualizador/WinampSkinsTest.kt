package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampSkinsTest {
    @Test
    fun everySkinHas24OpaqueColors() {
        assertTrue(WinampSkins.ALL.size >= 80)
        WinampSkins.ALL.forEach { s ->
            assertEquals("${s.name}: colores", 24, s.colors.size)
            assertTrue("${s.name}: todos opacos", s.colors.all { (it ushr 24) == 0xFF })
            assertEquals("${s.name}: chasis", 8, s.chrome.size)
            assertTrue("${s.name}: chasis opaco", s.chrome.all { (it ushr 24) == 0xFF })
        }
        assertEquals(WinampSkins.ALL.size, WinampSkins.ALL.map { it.name }.toSet().size)
    }

    /** El chasis distingue skins de analizador parecido: casi todos tienen una combinación propia. */
    @Test
    fun chromeMakesSkinsDistinct() {
        val keys = WinampSkins.ALL.map { s -> listOf(WinampChrome.FACE, WinampChrome.ACCENT, WinampChrome.LIST_TEXT).map { s.chrome[it] } }
        assertTrue(keys.toSet().size >= WinampSkins.ALL.size - 3)
    }

    @Test
    fun unknownNameFallsBackToOriginal() {
        assertEquals(WinampSkins.DEFAULT, WinampSkins.byName("no existe"))
        assertEquals(WinampSkins.DEFAULT, WinampSkins.byName(null))
        assertEquals("Vault-Tec", WinampSkins.byName("Vault-Tec").name)
    }
}
