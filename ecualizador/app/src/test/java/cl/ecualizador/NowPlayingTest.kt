package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTest {
    private fun track(state: PlayingTrack.State, pos: Long = 30_000, at: Long = 1_000, duration: Long = 225_000, speed: Float = 1f) =
        PlayingTrack("Stumble", "Just Plain Ant", duration, pos, at, speed, state)

    @Test
    fun positionAdvancesOnlyWhilePlaying() {
        assertEquals(35_000, track(PlayingTrack.State.PLAYING).positionAt(6_000))
        assertEquals(40_000, track(PlayingTrack.State.PLAYING, speed = 2f).positionAt(6_000))
        assertEquals(30_000, track(PlayingTrack.State.PAUSED).positionAt(6_000))
        // Nunca pasa del final
        assertEquals(225_000, track(PlayingTrack.State.PLAYING).positionAt(10_000_000))
    }

    @Test
    fun titleLooksLikeWinamp() {
        assertEquals("Just Plain Ant - Stumble (3:45)", track(PlayingTrack.State.PLAYING).winampTitle())
        assertEquals("Radio", PlayingTrack("Radio", "", 0, 0, 0, 1f, PlayingTrack.State.PLAYING).winampTitle())
        assertEquals("0:07", PlayingTrack.clock(7_999))
    }
}
