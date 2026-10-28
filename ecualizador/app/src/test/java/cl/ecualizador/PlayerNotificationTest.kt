package cl.ecualizador

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.session.MediaSession
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerNotificationTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    private fun playing() {
        WinampWindows.init(ctx)
        NowPlaying.controller = MediaSession(ctx, "prueba").controller
        NowPlaying.track.value = PlayingTrack(
            "My Way (feat. Monty)", "Fetty Wap, Monty", 213_000, 41_000, android.os.SystemClock.elapsedRealtime(), 1f, PlayingTrack.State.PLAYING,
        )
    }

    @Test
    fun postsCustomPlayerWhileMusicPlays() {
        playing()
        PlayerNotification.update(ctx)
        val n = Shadows.shadowOf(nm).allNotifications.single()
        assertEquals(R.layout.player_expanded, n.bigContentView.layoutId)
        assertEquals(R.layout.player_collapsed, n.contentView.layoutId)
        // Fuera de «Silenciadas»: prioridad normal, pero sin sonido
        val channel = nm.getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        // Sin música, se quita
        NowPlaying.track.value = null
        PlayerNotification.update(ctx)
        assertEquals(0, Shadows.shadowOf(nm).allNotifications.size)
        NowPlaying.controller = null
    }

    @Test
    fun canBeTurnedOff() {
        playing()
        PlayerNotification.setEnabled(ctx, false)
        assertEquals(0, Shadows.shadowOf(nm).allNotifications.size)
        PlayerNotification.setEnabled(ctx, true)
        assertNotNull(Shadows.shadowOf(nm).allNotifications.singleOrNull())
        NowPlaying.track.value = null; NowPlaying.controller = null
        PlayerNotification.update(ctx)
    }

    /** Con -DpreviewDir: la notificación expandida a 360 dp, con las zonas táctiles marcadas. */
    @Test
    fun expandedPreview() {
        val out = File(System.getProperty("previewDir")?.takeIf { it.isNotEmpty() } ?: return)
        playing()
        PlayerNotification.update(ctx)
        val n = Shadows.shadowOf(nm).allNotifications.single()
        val parent = FrameLayout(ctx)
        val view = n.bigContentView.apply(ctx, parent)
        val w = (360 * ctx.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        view.draw(c)
        val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.MAGENTA }
        for (id in listOf(R.id.player_display, R.id.player_previous, R.id.player_play, R.id.player_pause, R.id.player_stop, R.id.player_next, R.id.player_eject)) {
            val v = view.findViewById<View>(id)
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            var x = 0; var y = 0; var cur: View? = v
            while (cur != null && cur !== view) { x += cur.left; y += cur.top; cur = cur.parent as? View }
            c.drawRect(x.toFloat(), y.toFloat(), (x + v.width).toFloat(), (y + v.height).toFloat(), p)
        }
        File(out, "notificacion_reproductor.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // La plegada, a 330 dp (el ancho que queda junto al ícono de la app)
        val small = n.contentView.apply(ctx, FrameLayout(ctx))
        val sw = (330 * ctx.resources.displayMetrics.density).toInt()
        small.measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        small.layout(0, 0, small.measuredWidth, small.measuredHeight)
        val sb = Bitmap.createBitmap(small.measuredWidth, small.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(sb).apply { drawColor(Color.rgb(40, 40, 44)); small.draw(this) }
        File(out, "notificacion_plegada.png").outputStream().use { sb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        NowPlaying.track.value = null; NowPlaying.controller = null
        assertNull(null)
    }
}
