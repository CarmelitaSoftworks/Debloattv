package cl.ecualizador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AutoProfileTest {
    @Test
    fun classifiesByName() {
        assertEquals(OutputKind.BT_PARTY_SPEAKER, AutoProfile.classifyBluetooth("GTK-XB5", null))
        assertEquals(OutputKind.BT_PARTY_SPEAKER, AutoProfile.classifyBluetooth("JBL PartyBox 310", null))
        assertEquals(OutputKind.BT_PORTABLE_SPEAKER, AutoProfile.classifyBluetooth("JBL Flip 6", null))
        assertEquals(OutputKind.BT_HEADPHONES, AutoProfile.classifyBluetooth("WH-1000XM5", null))
        assertEquals(OutputKind.BT_HEADPHONES, AutoProfile.classifyBluetooth("Galaxy Buds2 Pro", null))
        assertEquals(OutputKind.BT_CAR, AutoProfile.classifyBluetooth("Toyota Corolla", null))
        assertEquals(OutputKind.BT_TV_SOUNDBAR, AutoProfile.classifyBluetooth("[TV] Samsung 7 Series", null))
    }

    @Test
    fun fallsBackToBluetoothClass() {
        assertEquals(OutputKind.BT_HEADPHONES, AutoProfile.classifyBluetooth("Equipo X", 0x0418))
        assertEquals(OutputKind.BT_CAR, AutoProfile.classifyBluetooth("Equipo X", 0x0420))
        assertEquals(OutputKind.BT_PORTABLE_SPEAKER, AutoProfile.classifyBluetooth("Equipo X", 0x0414))
        assertEquals(OutputKind.BT_UNKNOWN, AutoProfile.classifyBluetooth("Equipo X", null))
    }

    @Test
    fun curvesAreValid() {
        OutputKind.entries.forEach { k ->
            val c = AutoProfile.curveFor(k)
            assertEquals(EqCore.BANDS, c.levels.size)
            assertTrue(c.levels.all { it in EqCore.MIN_DB..EqCore.MAX_DB })
            assertTrue(c.why.isNotBlank())
        }
        assertFalse(AutoProfile.canCalibrate(OutputKind.BT_HEADPHONES))
        assertTrue(AutoProfile.canCalibrate(OutputKind.BT_PARTY_SPEAKER))
    }
}

class CalibrationMathTest {
    @Test
    fun fftFindsASine() {
        val n = 1024
        val re = DoubleArray(n) { sin(2 * PI * 50 * it / n) }
        val im = DoubleArray(n)
        CalibrationMath.fft(re, im)
        val mags = (0 until n / 2).map { re[it] * re[it] + im[it] * im[it] }
        assertEquals(50, mags.indices.maxByOrNull { mags[it] })
    }

    @Test
    fun pinkNoiseIsFlatPerOctave() {
        val rate = 48000
        val levels = CalibrationMath.octaveLevels(CalibrationMath.pinkNoise(rate * 6), rate)
        val ref = (2..7).map { levels[it] }.average()
        for (i in CalibrationMath.TRUSTED) {
            assertTrue("banda ${EqCore.label(i)}: ${levels[i] - ref} dB", abs(levels[i] - ref) < 2.5)
        }
        // Y entonces la corrección para ruido rosa sin alterar es casi nula.
        assertTrue(CalibrationMath.correction(levels).all { abs(it) <= 2f })
    }

    @Test
    fun correctionCutsPeaksAndLimitsBoosts() {
        val measured = FloatArray(10) { 0f }
        measured[3] = 6f   // pico en 250 Hz
        measured[7] = -12f // hueco en 4 kHz
        val c = CalibrationMath.correction(measured)
        assertTrue("debe recortar el pico", c[3] < -2f)
        assertTrue("el refuerzo queda limitado", c[7] <= 5f)
        assertEquals(0f, c[0], 0f)
        assertEquals(0f, c[9], 0f)
    }

    @Test
    fun silenceIsDetected() {
        assertTrue(CalibrationMath.rmsDb(FloatArray(1000)) < -100f)
    }
}
