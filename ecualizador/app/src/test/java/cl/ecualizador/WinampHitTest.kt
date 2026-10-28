package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampHitTest {
    private val full = WinampWindow.Part.FULL
    private val display = WinampWindow.Part.DISPLAY

    @Test
    fun buttonsAreWhereWinampHasThem() {
        assertEquals(WinampWindow.Hit.Press(WinampWindow.Button.PREVIOUS), WinampWindow.hit(20f, 95f, full))
        assertEquals(WinampWindow.Hit.Press(WinampWindow.Button.PLAY), WinampWindow.hit(45f, 95f, full))
        assertEquals(WinampWindow.Hit.Press(WinampWindow.Button.PAUSE), WinampWindow.hit(70f, 95f, full))
        assertEquals(WinampWindow.Hit.Press(WinampWindow.Button.STOP), WinampWindow.hit(90f, 95f, full))
        assertEquals(WinampWindow.Hit.Press(WinampWindow.Button.NEXT), WinampWindow.hit(115f, 95f, full))
        assertNull(WinampWindow.hit(200f, 5f, full)) // barra de título
    }

    @Test
    fun positionBarSeeks() {
        val start = WinampWindow.hit(WinampWindow.POSBAR_X + 2f, 76f, full) as WinampWindow.Hit.Seek
        val end = WinampWindow.hit(WinampWindow.POSBAR_X + WinampWindow.POSBAR_W - 2f, 76f, full) as WinampWindow.Hit.Seek
        val middle = WinampWindow.hit(WinampWindow.POSBAR_X + WinampWindow.POSBAR_W / 2f, 76f, full) as WinampWindow.Hit.Seek
        assertEquals(0f, start.fraction, 0.01f)
        assertEquals(1f, end.fraction, 0.01f)
        assertEquals(0.5f, middle.fraction, 0.02f)
    }

    @Test
    fun displayTogglesPlayAndOnlyVisiblePartCounts() {
        assertEquals(WinampWindow.Hit.TogglePlay, WinampWindow.hit(60f, 45f, full))
        assertEquals(WinampWindow.Hit.TogglePlay, WinampWindow.hit(60f, 45f, display))
        // En «Solo el espectro» los botones no se ven: no se pueden tocar
        assertNull(WinampWindow.hit(45f, 95f, display))
        assertTrue(WinampWindow.ATLAS_HEIGHT > WinampWindow.PRESSED_Y)
    }

    @Test
    fun volumeEjectAndEqAreFunctional() {
        val low = WinampWindow.hit(WinampWindow.VOLUME_X + 1f, 62f, full) as WinampWindow.Hit.Volume
        val high = WinampWindow.hit(WinampWindow.VOLUME_X + WinampWindow.VOLUME_W - 1f, 62f, full) as WinampWindow.Hit.Volume
        assertEquals(0f, low.fraction, 0.01f)
        assertEquals(1f, high.fraction, 0.01f)
        assertEquals(WinampWindow.Hit.OpenPlayer, WinampWindow.hit(145f, 95f, full))  // expulsar
        assertEquals(WinampWindow.Hit.OpenPlayer, WinampWindow.hit(250f, 63f, full))  // PL
        assertEquals(WinampWindow.Hit.OpenEqualizer, WinampWindow.hit(228f, 63f, full)) // EQ
    }

    @Test
    fun notificationActionsMapToSkinButtons() {
        assertEquals(WinampWindow.Hit.Press(WinampWindow.Button.NEXT), PlayerNotification.hitFor("cl.ecualizador.player.next"))
        assertEquals(WinampWindow.Hit.TogglePlay, PlayerNotification.hitFor("cl.ecualizador.player.toggle"))
        assertNull(PlayerNotification.hitFor("otra.cosa"))
    }
}
