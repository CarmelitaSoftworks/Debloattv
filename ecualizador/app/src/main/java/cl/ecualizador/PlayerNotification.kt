package cl.ecualizador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews

/**
 * Reproductor de Winamp en la barra de notificaciones, como el de Spotify: la ventana del skin
 * elegido con lo que suena (título, tiempo, posición y volumen) y sus botones funcionando. Lo
 * mantiene al día [NowPlayingService] (el mismo acceso que «lo que suena»).
 */
object PlayerNotification {
    /** Prioridad normal sin sonido (el canal anterior, «player», era de prioridad baja: «Silenciadas»). */
    private const val CHANNEL = "player_winamp"
    private const val OLD_CHANNEL = "player"
    private const val ID = 7
    private const val PREFS = "player"

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notification", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("notification", on).apply()
        update(ctx)
    }

    private val renderer by lazy { SpectrumRenderer() }

    /** Publica, actualiza o quita la notificación según lo que suene. */
    @Synchronized
    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val track = NowPlaying.track.value
        if (!isEnabled(ctx) || track == null || NowPlaying.controller == null) {
            nm.cancel(ID)
            return
        }
        SpectrumPrefs.load(ctx)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // Android no deja subir la prioridad de un canal existente: se crea otro y se borra el viejo
            runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.player_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
            )
        }
        val skinName = SpectrumPrefs.settings.value.winampSkin
        val skin = WinampSkins.byName(skinName)
        // Sin desplazar el título: la notificación es una foto por segundo y quedaría cortado a la mitad
        val settings = SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC, winampSkin = skinName, winampFrame = true, winampMarquee = false)
        val bars = if (Spectrum.running.value) SpectrumShape.resample(Spectrum.bars.value, settings.effectiveBars) else FloatArray(settings.effectiveBars)

        // RGB 565: la mitad de memoria; las notificaciones tienen un límite de tamaño
        val window = render(2, WinampWindow.Part.FULL, settings, bars)

        val expanded = RemoteViews(ctx.packageName, R.layout.player_expanded).apply {
            setImageViewBitmap(R.id.player_window, window)
            setOnClickPendingIntent(R.id.player_display, action(ctx, "toggle"))
            setOnClickPendingIntent(R.id.player_previous, action(ctx, "previous"))
            setOnClickPendingIntent(R.id.player_play, action(ctx, "play"))
            setOnClickPendingIntent(R.id.player_pause, action(ctx, "pause"))
            setOnClickPendingIntent(R.id.player_stop, action(ctx, "stop"))
            setOnClickPendingIntent(R.id.player_next, action(ctx, "next"))
            openPlayer(ctx)?.let { setOnClickPendingIntent(R.id.player_eject, it) }
        }
        val playing = track.state == PlayingTrack.State.PLAYING
        val pos = PlayingTrack.clock(track.positionAt(android.os.SystemClock.elapsedRealtime()))
        val time = if (track.durationMs > 0) "$pos / ${PlayingTrack.clock(track.durationMs)}" else pos
        val collapsed = RemoteViews(ctx.packageName, R.layout.player_collapsed).apply {
            setTextViewText(R.id.player_title, track.title)
            setTextViewText(R.id.player_subtitle, if (track.artist.isBlank()) time else "${track.artist} · $time")
            buttons(skin, playing)?.let { setImageViewBitmap(R.id.player_buttons, it) }
            setOnClickPendingIntent(R.id.player_previous_small, action(ctx, "previous"))
            setOnClickPendingIntent(R.id.player_toggle_small, action(ctx, "toggle"))
            setOnClickPendingIntent(R.id.player_next_small, action(ctx, "next"))
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentTitle(track.winampTitle())
            .setContentIntent(openPlayer(ctx))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun render(scale: Int, part: WinampWindow.Part, s: SpectrumSettings, bars: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(part.w * scale, part.h * scale, Bitmap.Config.RGB_565)
        renderer.draw(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), bars, null, s.copy(winampPart = part))
        return bmp
    }

    /**
     * Tira de botones del skin, tal como se ven en su ventana y juntos: anterior, reproducir (o pausa
     * si suena) y siguiente, al triple para que se vean nítidos.
     */
    private fun buttons(skin: WinampSkin, playing: Boolean): Bitmap? {
        val atlas = WinampWindows.bitmap(skin) ?: return null
        val parts = listOf(WinampWindow.Button.PREVIOUS, if (playing) WinampWindow.Button.PAUSE else WinampWindow.Button.PLAY, WinampWindow.Button.NEXT)
        val strip = Bitmap.createBitmap(parts.sumOf { it.w }, 18, Bitmap.Config.ARGB_8888)
        val c = Canvas(strip)
        var x = 0f
        for (b in parts) {
            c.drawBitmap(Bitmap.createBitmap(atlas, b.x, b.y, b.w, b.h), x, 0f, null)
            x += b.w
        }
        return Bitmap.createScaledBitmap(strip, strip.width * 3, strip.height * 3, false)
    }

    /**
     * Abre la app de música directamente (desde Android 12 una notificación no puede abrir apps
     * pasando por un receptor): su propia pantalla de reproducción o, si no la declara, su ícono.
     */
    private fun openPlayer(ctx: Context): PendingIntent? {
        val c = NowPlaying.controller ?: return null
        c.sessionActivity?.let { return it }
        val pkg = c.packageName ?: return null
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        return PendingIntent.getActivity(ctx, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, name: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, name.hashCode(),
        Intent(ctx, PlayerActionReceiver::class.java).setAction("cl.ecualizador.player.$name"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Lo que hace cada botón de la notificación. */
    fun hitFor(action: String?): WinampWindow.Hit? = when (action?.substringAfterLast('.')) {
        "previous" -> WinampWindow.Hit.Press(WinampWindow.Button.PREVIOUS)
        "play" -> WinampWindow.Hit.Press(WinampWindow.Button.PLAY)
        "pause" -> WinampWindow.Hit.Press(WinampWindow.Button.PAUSE)
        "stop" -> WinampWindow.Hit.Press(WinampWindow.Button.STOP)
        "next" -> WinampWindow.Hit.Press(WinampWindow.Button.NEXT)
        "toggle" -> WinampWindow.Hit.TogglePlay
        else -> null
    }
}

/** Botones del reproductor de la notificación. */
class PlayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hit = PlayerNotification.hitFor(intent.action) ?: return
        NowPlaying.perform(context, hit)
    }
}
