package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumCaptureTest {
    /** Como android.media.audiofx.Visualizer: no deja cambiar el tamaño mientras está activado. */
    private class FakeVisualizer(enabled: Boolean, size: Int, private val othersInControl: Boolean = false) : CaptureControl {
        override var enabled = enabled
            set(value) { if (!othersInControl) field = value } // otra app lo controla: no se puede apagar
        override var captureSize = size
            set(value) {
                if (enabled) throw IllegalStateException("setCaptureSize() called in wrong state: 2")
                field = value
            }
    }

    @Test
    fun freshVisualizerGetsWantedSize() {
        val v = FakeVisualizer(enabled = false, size = 512)
        configureCaptureSize(v, 1024)
        assertEquals(1024, v.captureSize)
    }

    /** El error del teléfono: ya venía activado. Se pausa un instante y se cambia el tamaño. */
    @Test
    fun alreadyEnabledVisualizerIsPausedToChangeSize() {
        val v = FakeVisualizer(enabled = true, size = 512)
        configureCaptureSize(v, 1024)
        assertEquals(1024, v.captureSize)
    }

    /** Si otra app lo controla y no se puede pausar, se sigue con su tamaño en vez de fallar. */
    @Test
    fun visualizerControlledByAnotherAppKeepsItsSize() {
        val v = FakeVisualizer(enabled = true, size = 512, othersInControl = true)
        configureCaptureSize(v, 1024) // no lanza
        assertEquals(512, v.captureSize)
    }

    @Test
    fun alreadyRightSizeIsLeftAlone() {
        val v = FakeVisualizer(enabled = true, size = 1024, othersInControl = true)
        configureCaptureSize(v, 1024)
        assertEquals(1024, v.captureSize)
    }
}
