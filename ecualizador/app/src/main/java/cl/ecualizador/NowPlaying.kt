package cl.ecualizador

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow

/** Lo que suena: título, artista y dónde va la canción. */
data class PlayingTrack(
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** Posición cuando el reproductor la informó, en [updatedAt] (SystemClock.elapsedRealtime). */
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val state: State,
) {
    enum class State { PLAYING, PAUSED, STOPPED }

    /** Posición ahora, avanzando sola mientras suena (como hace el propio Android). */
    fun positionAt(now: Long): Long {
        val p = if (state == State.PLAYING) positionMs + ((now - updatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /** Como en Winamp: «Artista - Título (3:45)». */
    fun winampTitle(): String {
        val name = if (artist.isBlank()) title else "$artist - $title"
        return if (durationMs > 0) "$name (${clock(durationMs)})" else name
    }

    companion object {
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/**
 * La canción que suena en cualquier app, leída de las sesiones multimedia de Android. Para eso
 * Android exige el acceso a las notificaciones ([NowPlayingService]); sin él, [track] queda en null.
 */
object NowPlaying {
    val track = MutableStateFlow<PlayingTrack?>(null)

    /** La sesión que se muestra (la que reproduce, o la más reciente), para los botones del skin. */
    @Volatile
    var controller: MediaController? = null

    /** Ejecuta lo que se tocó en el skin sobre el reproductor que suena. Falso si no hay ninguno. */
    fun perform(hit: WinampWindow.Hit): Boolean {
        val c = controller ?: return false
        val t = c.transportControls
        return runCatching {
            when (hit) {
                is WinampWindow.Hit.Press -> when (hit.button) {
                    WinampWindow.Button.PREVIOUS -> t.skipToPrevious()
                    WinampWindow.Button.PLAY -> t.play()
                    WinampWindow.Button.PAUSE -> t.pause()
                    WinampWindow.Button.STOP -> t.stop()
                    WinampWindow.Button.NEXT -> t.skipToNext()
                }
                is WinampWindow.Hit.Seek -> {
                    val d = track.value?.durationMs ?: 0L
                    if (d > 0) t.seekTo((d * hit.fraction).toLong())
                }
                WinampWindow.Hit.TogglePlay ->
                    if (track.value?.state == PlayingTrack.State.PLAYING) t.pause() else t.play()
            }
        }.isSuccess
    }

    /** Si el usuario dio el acceso a las notificaciones a la app. */
    fun hasAccess(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun from(controller: MediaController): PlayingTrack? {
        val md = controller.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val ps = controller.playbackState
        val state = when (ps?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> PlayingTrack.State.PLAYING
            PlaybackState.STATE_PAUSED -> PlayingTrack.State.PAUSED
            else -> PlayingTrack.State.STOPPED
        }
        return PlayingTrack(
            title = title, artist = artist,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = ps?.position ?: 0L,
            updatedAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            state = state,
        )
    }
}

/**
 * Servicio de acceso a notificaciones: no lee notificaciones, solo lo usa Android como permiso para
 * ver qué suena (MediaSessionManager.getActiveSessions). Sigue la sesión que esté reproduciendo.
 */
class NowPlayingService : NotificationListenerService() {
    private var sessions: MediaSessionManager? = null
    private val controllers = mutableListOf<MediaController>()
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = msm
        val me = ComponentName(this, NowPlayingService::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me)
            follow(msm.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        follow(emptyList())
        NowPlaying.controller = null
        sessions = null
    }

    private fun follow(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers.clear()
        controllers += list
        controllers.forEach { runCatching { it.registerCallback(callback) } }
        refresh()
    }

    /** La sesión que reproduce; si ninguna, la primera (la más reciente según Android). */
    private fun refresh() {
        val tracks = controllers.mapNotNull { c -> runCatching { NowPlaying.from(c) }.getOrNull()?.let { c to it } }
        val chosen = tracks.firstOrNull { it.second.state == PlayingTrack.State.PLAYING } ?: tracks.firstOrNull()
        NowPlaying.controller = chosen?.first
        NowPlaying.track.value = chosen?.second
    }
}
