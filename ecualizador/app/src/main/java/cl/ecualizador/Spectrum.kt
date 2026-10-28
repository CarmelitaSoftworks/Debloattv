package cl.ecualizador

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Captura en vivo de toda la salida de audio (Visualizer sobre la sesión 0), compartida por la app,
 * el widget y el fondo animado: se enciende con el primer usuario y se apaga con el último.
 * Vigila que sigan llegando datos: al pausar deja caer las barras y, si al reanudar el Visualizer
 * quedó sin datos, lo reinicia ([SpectrumHealth]).
 * Android exige el permiso de micrófono para esto, aunque no se graba nada.
 */
object Spectrum {
    /** Último análisis, siempre en [SpectrumSettings.SOURCE_BARS] barras (0..1). */
    val bars = MutableStateFlow(FloatArray(SpectrumSettings.SOURCE_BARS))
    /** Forma de onda reciente (−1..1, [WAVE_POINTS] puntos) para los osciloscopios. */
    val wave = MutableStateFlow(FloatArray(WAVE_POINTS))
    const val WAVE_POINTS = 256
    /** Aumenta con cada análisis nuevo (para la cascada). */
    val frames = MutableStateFlow(0L)
    val running = MutableStateFlow(false)
    private var visualizer: Visualizer? = null
    private val users = HashSet<String>()
    /** Quién usa el espectro ahora, observable por la interfaz. */
    val activeUsers = MutableStateFlow<Set<String>>(emptySet())
    private val analyzer = SpectrumAnalyzer(SpectrumSettings.SOURCE_BARS)
    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    @Volatile private var lastFrameAt = 0L
    private var lastRestartAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (users.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            when (SpectrumHealth.decide(
                idleMs = now - lastFrameAt,
                musicActive = audio?.isMusicActive == true,
                showingData = bars.value.any { it > 0f },
                sinceRestartMs = now - lastRestartAt,
            )) {
                SpectrumHealth.Action.RESTART -> restart()
                SpectrumHealth.Action.CLEAR -> {
                    bars.value = FloatArray(SpectrumSettings.SOURCE_BARS)
                    wave.value = FloatArray(WAVE_POINTS)
                }
                SpectrumHealth.Action.NONE -> {}
            }
            // Sin datos nuevos, la cascada sigue avanzando con filas en silencio en vez de congelarse.
            if (now - lastFrameAt > SpectrumHealth.STALE_MS) frames.value++
            main.postDelayed(this, 250)
        }
    }

    /** Android avisa cuando una app empieza, pausa o reanuda: se revisa enseguida. */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 400)
        }
    }

    /** [user] identifica a quien lo usa ("app", "widget", "wallpaper"). Devuelve un error o null. */
    @Synchronized
    fun acquire(user: String, ctx: Context): String? {
        users += user
        activeUsers.value = users.toSet()
        if (visualizer != null) return null
        if (audio == null) {
            audio = ctx.applicationContext.getSystemService(AudioManager::class.java)
            audio?.registerAudioPlaybackCallback(playbackCallback, main)
        }
        val error = create()
        if (error != null) {
            users -= user
            activeUsers.value = users.toSet()
            if (users.isEmpty()) shutdown()
            return error
        }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 250)
        return null
    }

    @Synchronized
    fun release(user: String) {
        users -= user
        activeUsers.value = users.toSet()
        if (users.isEmpty()) shutdown()
    }

    private fun create(): String? = try {
        val v = Visualizer(0)
        configureCaptureSize(v)
        v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                waveform ?: return
                // Bytes sin signo con 128 como silencio.
                wave.value = FloatArray(WAVE_POINTS) { i ->
                    ((waveform[i * waveform.size / WAVE_POINTS].toInt() and 0xFF) - 128) / 128f
                }
            }
            override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                fft ?: return
                lastFrameAt = SystemClock.elapsedRealtime()
                // samplingRate viene en mHz
                bars.value = analyzer.process(fft, samplingRate / 1000)
                frames.value++
            }
        }, Visualizer.getMaxCaptureRate(), true, true)
        v.enabled = true
        visualizer = v
        lastFrameAt = SystemClock.elapsedRealtime()
        running.value = true
        null
    } catch (t: Throwable) {
        "Este teléfono no permite ver el espectro (${t.message ?: t.javaClass.simpleName})."
    }

    /**
     * El Visualizer de la sesión 0 es un solo efecto compartido con otras apps (y con la animación de
     * audio del sistema en algunos teléfonos). Si ya está activado, Android no deja cambiar el tamaño
     * de captura («setCaptureSize() called in wrong state: 2»): se intenta pausarlo un instante para
     * cambiarlo y, si quien lo controla es otra app, se usa el tamaño que ya tiene (el analizador
     * acepta cualquiera) en vez de no mostrar el espectro.
     */
    private fun configureCaptureSize(v: Visualizer) {
        configureCaptureSize(object : CaptureControl {
            override var enabled: Boolean
                get() = v.enabled
                set(value) { v.enabled = value }
            override var captureSize: Int
                get() = v.captureSize
                set(value) { v.captureSize = value }
        }, Visualizer.getCaptureSizeRange()[1].coerceAtMost(2048))
    }


    /** Recrea el Visualizer cuando quedó sin datos tras pausar y reanudar. */
    @Synchronized
    private fun restart() {
        if (users.isEmpty()) return
        lastRestartAt = SystemClock.elapsedRealtime()
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        if (create() != null) running.value = false
    }

    private fun shutdown() {
        main.removeCallbacks(watchdog)
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        running.value = false
        bars.value = FloatArray(SpectrumSettings.SOURCE_BARS)
    }
}

/** Lo mínimo del Visualizer que se usa para configurarlo (permite probarlo sin el efecto nativo). */
internal interface CaptureControl {
    var enabled: Boolean
    var captureSize: Int
}

/** Deja [wanted] como tamaño de captura si Android lo permite; si no, conserva el actual sin fallar. */
internal fun configureCaptureSize(v: CaptureControl, wanted: Int) {
    if (v.captureSize == wanted) return
    if (v.enabled) runCatching { v.enabled = false }
    runCatching { v.captureSize = wanted }
}
