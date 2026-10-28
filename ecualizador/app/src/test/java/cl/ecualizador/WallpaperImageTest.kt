package cl.ecualizador

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

class WallpaperSampleSizeTest {
    @Test
    fun keepsImageAtLeastScreenSized() {
        assertEquals(1, WallpaperImage.sampleSizeFor(1200, 2600, 1080, 2400))
        assertEquals(2, WallpaperImage.sampleSizeFor(4000, 6000, 1080, 2400)) // 2000x3000 ≥ pantalla
        assertEquals(1, WallpaperImage.sampleSizeFor(8000, 3000, 1080, 2400)) // horizontal: manda el alto
        assertEquals(1, WallpaperImage.sampleSizeFor(0, 0, 1080, 2400))
    }
}

/** Compara el método anterior (un solo paso) con el nuevo sobre una imagen de detalle fino. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperImageTest {
    /** Líneas finas de 1 px: el peor caso para el escalado (aparece «moiré» y dentado). */
    private fun detailed(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(40, 80, 140))
        val p = Paint().apply { color = Color.WHITE; strokeWidth = 1f }
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x + h * 0.3f, h.toFloat(), p); x += 3f }
        return b
    }

    private fun oldMethod(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val m = Matrix().apply { setScale(scale, scale); postTranslate((w - src.width * scale) / 2, (h - src.height * scale) / 2) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
    }

    /** Variación entre píxeles vecinos: alta = dentado/moiré; la imagen bien reducida debe verse lisa. */
    private fun roughness(b: Bitmap): Double {
        var sum = 0.0
        for (y in 0 until b.height) for (x in 1 until b.width) {
            sum += abs(Color.green(b.getPixel(x, y)) - Color.green(b.getPixel(x - 1, y)))
        }
        return sum / (b.width * b.height)
    }

    @Test
    fun steppedDownscaleIsSmoother() {
        val src = detailed(3000, 4000)
        val old = oldMethod(src, 360, 800)
        val new = WallpaperImage.cover(src, 360, 800)
        assertEquals(360, new.width); assertEquals(800, new.height)
        val ro = roughness(old); val rn = roughness(new)
        assertTrue("nuevo $rn debe ser más liso que antiguo $ro", rn < ro * 0.6)
        System.getProperty("previewDir")?.takeIf { it.isNotEmpty() }?.let { dir ->
            val both = Bitmap.createBitmap(720 + 10, 800, Bitmap.Config.ARGB_8888)
            Canvas(both).apply { drawColor(Color.BLACK); drawBitmap(old, 0f, 0f, null); drawBitmap(new, 370f, 0f, null) }
            File(dir, "fondo_antes_despues.png").outputStream().use { both.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
