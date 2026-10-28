package cl.ecualizador

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class EqUiState(
    val enabled: Boolean = false,
    val engine: String = "",
    val error: String? = null,
    val hasControl: Boolean = true,
    val profileKey: String = "speaker",
    val profileName: String = "Altavoz del teléfono",
    val levels: List<Float> = List(EqCore.BANDS) { 0f },
    val bass: Float = 0f,
    val boost: Float = 0f,
    val balance: Float = 0f,
    /** DynamicsProcessing activo: permite refuerzo de volumen y balance. */
    val advanced: Boolean = false,
    val preset: String? = "Plano",
    val avoidClipping: Boolean = true,
    /** Compensación de volumen bajo (loudness). */
    val loudness: Boolean = false,
    val canUndo: Boolean = false,
    val autoReclaim: Boolean = true,
    val customPresets: List<String> = emptyList(),
)

/**
 * Ecualizador global de 10 bandas sobre la sesión de audio 0 (toda la salida del teléfono):
 *  - DynamicsProcessing (Android 9+): preEQ de 10 bandas + limitador para no saturar.
 *  - Si el teléfono no lo permite: android.media.audiofx.Equalizer con la curva interpolada.
 *  - Perfil distinto para cada salida (equipo Bluetooth por nombre, audífonos, altavoz).
 *  - Si otra app toma la sesión 0, intenta recuperarla (opcional).
 */
class EqEngine private constructor(private val ctx: Context) {
    companion object {
        private const val PRIORITY = Int.MAX_VALUE
        private const val RAMP_STEPS = 8
        private const val RAMP_STEP_MS = 20L
        @Volatile private var instance: EqEngine? = null
        fun get(ctx: Context): EqEngine =
            instance ?: synchronized(this) { instance ?: EqEngine(ctx.applicationContext).also { instance = it } }
    }

    val state = MutableStateFlow(EqUiState())
    private val prefs = ctx.getSharedPreferences("eq2", Context.MODE_PRIVATE)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var dp: DynamicsProcessing? = null
    private var legacy: Equalizer? = null
    private var reclaimAttempts = 0
    /** Durante la calibración el efecto queda plano para medir el equipo sin ecualizar. */
    @Volatile private var bypass = false


    // ---------- Perfiles por salida ----------

    private fun currentOutput(): Pair<String, String> {
        val outs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let {
            val name = it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth"
            return "bt:$name" to name
        }
        val wired = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        if (outs.any { it.type in wired }) return "wired" to "Audífonos con cable / USB"
        return "speaker" to "Altavoz del teléfono"
    }

    private fun onOutputChanged() {
        val out = currentOutput()
        if (out.first != state.value.profileKey) {
            switchProfile(out)
            apply(smooth = true)
            if (state.value.enabled) EqService.start(ctx)
        }
    }

    private fun switchProfile(out: Pair<String, String>) {
        // El historial de deshacer pertenece al perfil anterior: no debe aplicarse a otra salida.
        history.clear()
        val json = prefs.getString("profile_${out.first}", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val levels = json?.optJSONArray("levels")?.let { a -> List(EqCore.BANDS) { a.optDouble(it, 0.0).toFloat() } }
            ?: List(EqCore.BANDS) { 0f }
        state.update {
            it.copy(
                profileKey = out.first, profileName = out.second, levels = levels,
                bass = json?.optDouble("bass", 0.0)?.toFloat() ?: 0f,
                boost = json?.optDouble("boost", 0.0)?.toFloat() ?: 0f,
                balance = json?.optDouble("balance", 0.0)?.toFloat() ?: 0f,
                preset = json?.optString("preset")?.takeIf { p -> p.isNotEmpty() } ?: if (json == null) "Plano" else null,
                canUndo = false,
            )
        }
    }

    private fun saveProfile() {
        val s = state.value
        val json = JSONObject()
            .put("levels", JSONArray(s.levels.map { it.toDouble() }))
            .put("bass", s.bass.toDouble())
            .put("boost", s.boost.toDouble())
            .put("balance", s.balance.toDouble())
            .put("preset", s.preset ?: "")
        prefs.edit().putString("profile_${s.profileKey}", json.toString()).apply()
    }

    // ---------- Efecto de audio ----------

    private fun createEffect() {
        release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && createDynamics()) return
        createLegacy()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDynamics(): Boolean = try {
        val n = EqCore.BANDS
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, n, false, 0, false, 0, true,
        ).build()
        val effect = DynamicsProcessing(PRIORITY, 0, config)
        effect.setControlStatusListener { _, granted -> main.post { onControl(granted) } }
        dp = effect
        state.update {
            it.copy(engine = "DynamicsProcessing · 10 bandas + limitador", error = null, hasControl = effect.hasControl(), advanced = true)
        }
        apply()
        effect.enabled = true
        true
    } catch (t: Throwable) {
        dp = null
        false
    }

    private fun createLegacy() {
        try {
            val e = Equalizer(PRIORITY, 0)
            e.setControlStatusListener { _, granted -> main.post { onControl(granted) } }
            legacy = e
            state.update {
                it.copy(engine = "Ecualizador básico · ${e.numberOfBands} bandas", error = null, hasControl = e.hasControl(), advanced = false)
            }
            apply()
            e.enabled = true
        } catch (t: Throwable) {
            legacy = null
            state.update {
                it.copy(engine = "", error = "Este teléfono no permite un ecualizador global (${t.message ?: t.javaClass.simpleName}).")
            }
        }
    }

    private fun onControl(granted: Boolean) {
        state.update { it.copy(hasControl = granted) }
        if (granted) {
            reclaimAttempts = 0
            apply()
        } else if (state.value.enabled && state.value.autoReclaim && reclaimAttempts < 5) {
            // Otra app tomó la sesión 0: reintentar con espera creciente (puede causar un corte breve).
            reclaimAttempts++
            main.postDelayed({ if (state.value.enabled && !state.value.hasControl) createEffect() }, 3000L * reclaimAttempts)
        }
    }

    /** Últimas ganancias enviadas al efecto: punto de partida de las transiciones suaves. */
    private var appliedGains = List(EqCore.BANDS) { 0f }
    private var rampStep = 0
    private val rampRunnable = object : Runnable {
        var from = appliedGains
        override fun run() {
            rampStep++
            val t = rampStep / RAMP_STEPS.toFloat()
            push(EqCore.ramp(from, targetGains(), t))
            if (rampStep < RAMP_STEPS) main.postDelayed(this, RAMP_STEP_MS)
        }
    }

    /** Curva que debe sonar ahora: la elegida, los graves y la compensación de volumen bajo. */
    private fun targetGains(): List<Float> {
        val s = state.value
        if (bypass) return List(EqCore.BANDS) { 0f }
        val base = EqCore.finalGains(s.levels, s.bass)
        if (!s.loudness) return base
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).toFloat()
        return base.zip(EqCore.loudness(volume)) { g, l -> g + l }
    }

    /**
     * Envía la curva actual al efecto. Con [smooth] la lleva en ~160 ms desde la anterior, para que
     * un cambio de preajuste no suene como un salto o un clic.
     */
    private fun apply(smooth: Boolean = false) {
        main.removeCallbacks(rampRunnable)
        if (!smooth) {
            push(targetGains())
            return
        }
        rampRunnable.from = appliedGains
        rampStep = 0
        main.post(rampRunnable)
    }

    private fun push(gains: List<Float>) {
        appliedGains = gains
        val s = if (bypass) state.value.copy(boost = 0f, balance = 0f, avoidClipping = false) else state.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
            dp?.let { d ->
                val cut = EqCore.cutoffs()
                val eq = DynamicsProcessing.Eq(true, true, EqCore.BANDS)
                for (i in 0 until EqCore.BANDS) eq.setBand(i, DynamicsProcessing.EqBand(true, cut[i], gains[i]))
                d.setPreEqAllChannelsTo(eq)
                val (left, right) = EqCore.channelGains(
                    if (s.avoidClipping) EqCore.autoPreamp(gains) else 0f, s.boost, s.balance
                )
                d.setInputGainbyChannel(0, left)
                d.setInputGainbyChannel(1, right)
                // El limitador siempre actúa si se potencia el volumen, para que no distorsione.
                d.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, s.avoidClipping || s.boost > 0f, 0, 1f, 60f, 10f, -1f, 0f)
                )
            }
        }
        runCatching {
            legacy?.let { e ->
                val range = e.bandLevelRange
                // Sin preamplificador ni limitador: para evitar distorsión se baja toda la curva.
                val shift = if (s.avoidClipping) EqCore.autoPreamp(gains) else 0f
                for (b in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000f
                    val mb = ((EqCore.interpolate(gains, hz) + shift) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    e.setBandLevel(b.toShort(), mb.toShort())
                }
            }
        }
    }

    // ---------- Volumen (para la compensación de volumen bajo) ----------

    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (state.value.enabled) apply(smooth = true)
        }
    }

    private fun watchVolume(on: Boolean) {
        if (on) {
            androidx.core.content.ContextCompat.registerReceiver(
                ctx, volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            runCatching { ctx.unregisterReceiver(volumeReceiver) }
        }
    }

    // ---------- Deshacer ----------

    private val history = ArrayDeque<Pair<List<Float>, Float>>()
    private var lastSnapshot = 0L

    /** Guarda el estado antes de un cambio; los arrastres seguidos cuentan como un solo cambio. */
    private fun snapshot(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastSnapshot > 800) {
            val s = state.value
            if (history.lastOrNull() != (s.levels to s.bass)) history.addLast(s.levels to s.bass)
            while (history.size > 30) history.removeFirst()
        }
        lastSnapshot = now
        state.update { it.copy(canUndo = history.isNotEmpty()) }
    }

    fun undo() {
        val (levels, bass) = history.removeLastOrNull() ?: return
        lastSnapshot = 0
        state.update { it.copy(levels = levels, bass = bass, preset = null, canUndo = history.isNotEmpty()) }
        apply(smooth = true); saveProfile()
    }

    private fun release() {
        runCatching { dp?.release() }
        runCatching { legacy?.release() }
        dp = null
        legacy = null
    }

    // ---------- Acciones de la interfaz ----------

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        state.update { it.copy(enabled = on) }
        reclaimAttempts = 0
        if (on) createEffect() else release()
        if (on) EqService.start(ctx) else EqService.stop(ctx)
        EqTileService.refresh(ctx)
    }

    fun setBand(i: Int, db: Float) {
        snapshot()
        state.update { s -> s.copy(levels = s.levels.toMutableList().also { it[i] = db.coerceIn(EqCore.MIN_DB, EqCore.MAX_DB) }, preset = null) }
        apply(); saveProfile()
    }

    fun setBass(v: Float) {
        snapshot()
        state.update { it.copy(bass = v.coerceIn(0f, 1f)) }
        apply(); saveProfile()
    }

    fun setBoost(db: Float) {
        state.update { it.copy(boost = db.coerceIn(0f, EqCore.BOOST_MAX_DB)) }
        apply(); saveProfile()
    }

    fun setBalance(v: Float) {
        state.update { it.copy(balance = v.coerceIn(-1f, 1f)) }
        apply(); saveProfile()
    }

    fun setBypass(on: Boolean) {
        bypass = on
        apply()
    }

    /** Aplica una curva completa al perfil actual (perfil automático o calibrado). */
    fun applyCurve(levels: List<Float>, bass: Float, name: String) {
        snapshot(force = true)
        state.update {
            it.copy(levels = levels.map { l -> l.coerceIn(EqCore.MIN_DB, EqCore.MAX_DB) }, bass = bass.coerceIn(0f, 1f), preset = name)
        }
        apply(smooth = true); saveProfile()
        if (!state.value.enabled) setEnabled(true)
    }

    /** Deja el perfil de la salida actual como recién instalado. */
    fun resetProfile() {
        snapshot(force = true)
        state.update { it.copy(levels = EqCore.PRESETS.getValue("Plano"), bass = 0f, boost = 0f, balance = 0f, preset = "Plano") }
        apply(smooth = true); saveProfile()
    }

    fun usePreset(name: String) {
        val curve = EqCore.PRESETS[name] ?: customPresets()[name] ?: return
        snapshot(force = true)
        state.update { it.copy(levels = curve, preset = name) }
        apply(smooth = true); saveProfile()
        if (!state.value.enabled) setEnabled(true)
    }

    fun setAvoidClipping(on: Boolean) {
        prefs.edit().putBoolean("avoidClipping", on).apply()
        state.update { it.copy(avoidClipping = on) }
        apply()
    }

    fun setLoudness(on: Boolean) {
        prefs.edit().putBoolean("loudness", on).apply()
        state.update { it.copy(loudness = on) }
        watchVolume(on)
        apply(smooth = true)
    }

    fun setAutoReclaim(on: Boolean) {
        prefs.edit().putBoolean("autoReclaim", on).apply()
        state.update { it.copy(autoReclaim = on) }
    }

    /** Pide recuperar la sesión 0 ahora mismo. */
    fun reclaimNow() {
        reclaimAttempts = 0
        if (state.value.enabled) createEffect()
    }

    private fun customPresets(): Map<String, List<Float>> {
        val json = runCatching { JSONObject(prefs.getString("customPresets", "{}")!!) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { k ->
            val a = json.getJSONArray(k); List(EqCore.BANDS) { a.optDouble(it, 0.0).toFloat() }
        }
    }

    fun saveCustomPreset(name: String) {
        val trimmed = name.trim().take(24).ifEmpty { return }
        // Un nombre igual a uno incluido quedaría inaccesible (los incluidos se buscan primero).
        val clean = if (trimmed in EqCore.PRESETS) "$trimmed (mío)" else trimmed
        val json = runCatching { JSONObject(prefs.getString("customPresets", "{}")!!) }.getOrNull() ?: JSONObject()
        json.put(clean, JSONArray(state.value.levels.map { it.toDouble() }))
        prefs.edit().putString("customPresets", json.toString()).apply()
        state.update { it.copy(customPresets = customPresets().keys.toList(), preset = clean) }
        saveProfile()
    }

    fun deleteCustomPreset(name: String) {
        val json = runCatching { JSONObject(prefs.getString("customPresets", "{}")!!) }.getOrNull() ?: return
        json.remove(name)
        prefs.edit().putString("customPresets", json.toString()).apply()
        state.update { it.copy(customPresets = customPresets().keys.toList()) }
    }

    // Va al final: Kotlin inicializa en orden y el bloque usa propiedades declaradas arriba.
    init {
        state.update {
            it.copy(
                enabled = prefs.getBoolean("enabled", false),
                avoidClipping = prefs.getBoolean("avoidClipping", true),
                autoReclaim = prefs.getBoolean("autoReclaim", true),
                loudness = prefs.getBoolean("loudness", false),
                customPresets = customPresets().keys.toList(),
            )
        }
        switchProfile(currentOutput())
        if (state.value.loudness) watchVolume(true)
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onOutputChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onOutputChanged()
        }, main)
        if (state.value.enabled) createEffect()
    }
}
