package cl.ecualizador

import cl.ecualizador.SpectrumHealth.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumHealthTest {
    @Test
    fun freshDataNeedsNothing() {
        assertEquals(Action.NONE, SpectrumHealth.decide(50, true, true, 10_000))
    }

    @Test
    fun pausedMusicLetsBarsFall() {
        // Al pausar ya no llegan datos y la música no suena: se limpian las barras, sin reiniciar.
        assertEquals(Action.CLEAR, SpectrumHealth.decide(400, false, true, 10_000))
        assertEquals(Action.NONE, SpectrumHealth.decide(5_000, false, false, 10_000))
    }

    @Test
    fun resumedMusicWithoutDataRestarts() {
        assertEquals(Action.RESTART, SpectrumHealth.decide(1_600, true, false, 10_000))
    }

    @Test
    fun restartsAreRateLimited() {
        assertEquals(Action.CLEAR, SpectrumHealth.decide(1_600, true, true, 500))
        assertEquals(Action.NONE, SpectrumHealth.decide(1_600, true, false, 500))
    }
}
