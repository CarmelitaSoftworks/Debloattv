package cl.ecualizador

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CalibrationResult(
    /** Respuesta medida, en dB relativos a la media de 125 Hz–4 kHz. */
    val measured: List<Float>,
    /** Curva propuesta para corregirla. */
    val correction: List<Float>,
    val micLevelDb: Float,
)

class CalibrationException(message: String) : Exception(message)

/**
 * Calibración acústica: reproduce ruido rosa por la salida actual y lo graba con el micrófono del
 * teléfono, con el ecualizador en pausa para medir el equipo tal cual suena.
 */
class Calibrator(private val ctx: Context) {
    private val rate = 48000
    private val seconds = 9

    @SuppressLint("MissingPermission")
    suspend fun run(onProgress: (Float, String) -> Unit): CalibrationResult = withContext(Dispatchers.Default) {
        val engine = EqEngine.get(ctx)
        val audio = ctx.getSystemService(AudioManager::class.java)

        onProgress(0f, "Preparando el ruido de prueba…")
        val noise = CalibrationMath.pinkNoise(rate * seconds)
        val peak = noise.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val fade = rate / 3
        val pcm = ShortArray(noise.size) { i ->
            val env = minOf(1f, i / fade.toFloat(), (noise.size - i) / fade.toFloat())
            (noise[i] / peak * 0.5f * env * Short.MAX_VALUE).toInt().toShort()
        }

        // El micrófono «sin procesar» evita el control automático de volumen y la supresión de ruido.
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw CalibrationException("No se pudo usar el micrófono.")
        }
        val track = try {
            AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        } catch (e: Exception) {
            recorder.release()
            throw CalibrationException("No se pudo reproducir el sonido de prueba (${e.message}).")
        }

        val recorded = ShortArray(rate * (seconds + 1))
        engine.setBypass(true)
        try {
            track.write(pcm, 0, pcm.size)
            recorder.startRecording()
            track.play()
            var read = 0
            while (read < recorded.size) {
                ensureActive() // si se cierra la pantalla, se detiene la medición
                val n = recorder.read(recorded, read, minOf(4096, recorded.size - read))
                if (n <= 0) break
                read += n
                onProgress(read / recorded.size.toFloat(), "Midiendo… mantén el silencio")
            }
        } finally {
            runCatching { track.stop() }; track.release()
            runCatching { recorder.stop() }; recorder.release()
            engine.setBypass(false)
        }

        onProgress(1f, "Analizando…")
        // Se descarta el comienzo (retardo del Bluetooth, hasta ~0,5 s) y el final con el desvanecido.
        val from = (rate * 1.5).toInt()
        val to = rate * (seconds - 1)
        val analysis = FloatArray(to - from) { recorded[from + it] / 32768f }
        val level = CalibrationMath.rmsDb(analysis)
        if (level < -55f) {
            throw CalibrationException("El micrófono casi no escuchó el ruido (${"%.0f".format(level)} dBFS). Sube el volumen y acerca el teléfono al equipo.")
        }
        if (analysis.count { kotlin.math.abs(it) > 0.98f } > analysis.size / 200) {
            throw CalibrationException("El sonido llegó saturado al micrófono. Baja un poco el volumen y vuelve a intentarlo.")
        }
        val levels = CalibrationMath.octaveLevels(analysis, rate)
        val ref = (2..7).map { levels[it] }.average().toFloat()
        CalibrationResult(levels.map { it - ref }, CalibrationMath.correction(levels), level)
    }
}
