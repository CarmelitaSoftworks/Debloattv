package cl.ecualizador

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Con -DpreviewDir: el fondo animado con skins de Winamp tal como en un vivo V30 (1260×2800 px,
 * densidad 3), con la misma geometría que SpectrumWallpaperService. -DimportSkin agrega un .wsz
 * importado por el mismo camino que la app y -DwallpaperPhoto pone esa foto de fondo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoV30PreviewTest {
    @Test
    fun wallpaperOnVivoV30() {
        val out = File(System.getProperty("previewDir")?.takeIf { it.isNotEmpty() } ?: return)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        WinampWindows.init(ctx)
        WinampSkins.load(ctx)
        val names = mutableListOf(WinampSkins.DEFAULT.name)
        System.getProperty("importSkin")?.takeIf { it.isNotEmpty() }?.let { path ->
            val f = File(path)
            val skins = SkinImport.import(SkinArchive.read(f.inputStream()), "Paper-Amp", SkinImport.ANDROID_DECODER)
            names += WinampSkins.add(ctx, skins).map { it.name }
        }
        names += listOf("Major Tom", "Expensive Hi-Fi")
        val w = 1260; val h = 2800; val density = 3f
        val photo = System.getProperty("wallpaperPhoto")?.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeFile(it) }
        val values = FloatArray(SpectrumSettings.WINAMP_BARS_ORIGINAL) { i -> (0.95f - i * 0.04f + 0.15f * kotlin.math.sin(i * 1.3f)).coerceIn(0.05f, 1f) }
        val peaks = FloatArray(values.size) { (values[it] + 0.1f).coerceAtMost(1f) }
        val shots = names.map { name ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (photo != null) {
                val bg = WallpaperImage.cover(photo, w, h)
                c.drawBitmap(bg, 0f, 0f, null)
                c.drawColor(0x40000000)
            } else c.drawColor(0xFF15131F.toInt())
            // Misma geometría que el fondo animado: margen de 16 dp, 25 % de alto, abajo
            val margin = 16 * density
            val sh = h * 0.25f
            val top = h - sh - h * 0.14f
            c.save(); c.translate(margin, top)
            SpectrumRenderer().draw(c, w - 2 * margin, sh, values, peaks,
                SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC, winampSkin = name, grid = false), density)
            c.restore()
            File(out, "v30_${name.replace(Regex("[^A-Za-z0-9]+"), "_")}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp
        }
        // Hoja con las cuatro pantallas a la mitad, y un recorte a tamaño real del visor
        val sheet = Bitmap.createBitmap(shots.size * (w / 3 + 10) + 10, h / 3 + 20, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        sc.drawColor(0xFF0F1115.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        shots.forEachIndexed { i, b ->
            sc.drawBitmap(b, null, android.graphics.RectF(10f + i * (w / 3 + 10), 10f, 10f + i * (w / 3 + 10) + w / 3, 10f + h / 3), p)
        }
        File(out, "v30_fondos.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
