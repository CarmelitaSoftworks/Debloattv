package cl.ecualizador

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

class WallpaperSurfaceSizeTest {
    @Test
    fun smallerSurfaceIsEnlargedToScreen() {
        assertEquals(1080 to 2400, WallpaperImage.fullResolutionSize(720, 1600, 1080, 2400))
        // Superficie girada respecto de la pantalla (lado largo con lado largo)
        assertEquals(2400 to 1080, WallpaperImage.fullResolutionSize(1600, 720, 1080, 2400))
    }

    @Test
    fun fullOrWiderSurfaceIsLeftAlone() {
        assertNull(WallpaperImage.fullResolutionSize(1080, 2400, 1080, 2400))
        assertNull(WallpaperImage.fullResolutionSize(1440, 3200, 1080, 2400))
        // Más ancha que la pantalla (fondo con desplazamiento): ya tiene resolución completa en un lado
        assertNull(WallpaperImage.fullResolutionSize(2160, 1600, 1080, 2400))
        assertNull(WallpaperImage.fullResolutionSize(0, 0, 1080, 2400))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperSaveTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun photo(name: String, w: Int, h: Int, format: Bitmap.CompressFormat): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, if ((x / 3 + y / 5) % 2 == 0) Color.RED else Color.rgb(x % 256, y % 256, 90))
        val f = File(ctx.cacheDir, name)
        f.outputStream().use { b.compress(format, 100, it) }
        return f
    }

    @Test
    fun chosenPhotoIsKeptByteForByte() {
        // Más grande que el límite de antes (2400 px) y en PNG: antes se reducía y recomprimía en JPEG
        val original = photo("foto.png", 5000, 300, Bitmap.CompressFormat.PNG)
        File(ctx.filesDir, "wallpaper.jpg").writeText("copia vieja")
        assertTrue(WallpaperPrefs.saveImage(ctx, Uri.fromFile(original)))
        val saved = WallpaperPrefs.currentImage(ctx)!!
        assertArrayEquals(original.readBytes(), saved.readBytes())
        assertFalse("se borra la copia reducida de versiones anteriores", File(ctx.filesDir, "wallpaper.jpg").exists())
        assertEquals(5000 to 300, WallpaperImage.uprightSize(saved.path))
        assertTrue(WallpaperPrefs.settings.value.hasImage)
    }

    @Test
    fun notAnImageIsRejected() {
        val f = File(ctx.cacheDir, "nada.txt").apply { writeText("no es una imagen") }
        assertFalse(WallpaperPrefs.saveImage(ctx, Uri.fromFile(f)))
    }

    @Test
    fun cameraRotationIsReadFromExif() {
        val f = photo("camara.jpg", 400, 300, Bitmap.CompressFormat.JPEG)
        android.media.ExifInterface(f.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertEquals(90, WallpaperImage.exifDegrees(f.path))
        assertEquals(300 to 400, WallpaperImage.uprightSize(f.path))
    }

    @Test
    fun renderInfoRoundTrips() {
        val r = WallpaperRender(720, 1600, 1080, 2400, true)
        assertEquals(r, WallpaperRender.decode(r.encode()))
        assertNull(WallpaperRender.decode("roto"))
    }
}
