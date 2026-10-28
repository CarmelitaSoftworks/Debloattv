package cl.ecualizador

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/** Genera imágenes de muestra con el analizador y el dibujador reales (solo si se pide previewDir). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererPreviewTest {
    private val rate = 44100
    private val n = 1024

    /** FFT simulado de música: bombo (~60 Hz), cuerpo (~200 Hz), voz (1–3 kHz) y platillos (8 kHz+). */
    private fun musicFft(t: Int): ByteArray {
        val rnd = Random(t)
        val kick = if (t % 8 < 2) 1f else 0.35f
        val fft = ByteArray(n)
        for (k in 1 until n / 2) {
            val hz = k * rate.toFloat() / n
            val lf = ln(hz)
            var a = 70f * kick * exp(-((lf - ln(60f)) / 0.35f).let { it * it }) +
                40f * exp(-((lf - ln(200f)) / 0.5f).let { it * it }) +
                30f * exp(-((lf - ln(1800f)) / 0.6f).let { it * it }) * (0.7f + 0.3f * sin(t * 0.9f)) +
                14f * exp(-((lf - ln(9000f)) / 0.5f).let { it * it }) +
                4f
            a *= 0.6f + rnd.nextFloat() * 0.8f
            fft[2 * k] = a.toInt().coerceIn(0, 127).toByte()
        }
        return fft
    }

    private fun analyzed(bars: Int): Pair<FloatArray, FloatArray> {
        val analyzer = SpectrumAnalyzer(SpectrumSettings.SOURCE_BARS)
        val smoother = SpectrumSmoother(bars)
        repeat(30) { t -> smoother.step(SpectrumShape.resample(analyzer.process(musicFft(t), rate), bars), 50) }
        return smoother.values.copyOf() to smoother.peaks.copyOf()
    }

    @Test
    fun renderAllStyles() {
        val out = File(System.getProperty("previewDir")?.takeIf { it.isNotEmpty() } ?: return)
        out.mkdirs()
        val r = SpectrumRenderer()
        val w = 720; val rowH = 170; val gap = 34
        val combos = listOf(
            SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC),
            SpectrumSettings(style = SpectrumStyle.WINAMP_FIRE),
            SpectrumSettings(style = SpectrumStyle.WINAMP_LINES),
            SpectrumSettings(style = SpectrumStyle.WINAMP_SCOPE),
            SpectrumSettings(style = SpectrumStyle.BARS, palette = SpectrumPalette.SYNTHWAVE),
            SpectrumSettings(style = SpectrumStyle.BLOCKS, palette = SpectrumPalette.RAINBOW, bars = 32),
            SpectrumSettings(style = SpectrumStyle.WAVE, palette = SpectrumPalette.CYBERPUNK),
            SpectrumSettings(style = SpectrumStyle.CIRCLE, palette = SpectrumPalette.AURORA),
            SpectrumSettings(style = SpectrumStyle.MIRROR, palette = SpectrumPalette.SUNSET),
        )
        val wave = FloatArray(Spectrum.WAVE_POINTS) { i ->
            (0.55f * sin(i * 0.11f) + 0.25f * sin(i * 0.53f + 1f) + 0.1f * sin(i * 1.9f)).coerceIn(-1f, 1f)
        }
        val sheet = Bitmap.createBitmap(w, combos.size * (rowH + gap) + gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF0F1115.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 22f }
        combos.forEachIndexed { k, s ->
            val (values, peaks) = analyzed(s.effectiveBars)
            c.save()
            c.translate(0f, (gap + k * (rowH + gap)).toFloat())
            c.drawText(if (s.style.winamp) s.style.label else "${s.style.label} · ${s.palette.label} · ${s.effectiveBars} barras", 12f, -8f, text)
            r.draw(c, w.toFloat(), rowH.toFloat(), values, peaks, s, 2f, wave)
            c.restore()
        }
        File(out, "estilos.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Muestrarios de skins de Winamp (estilo clásico con la ventana del skin), en 4 columnas.
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 18f }
        val (wv, wp) = analyzed(19)
        fun skinSheet(skins: List<WinampSkin>, file: String) {
            val cols = 4; val cw = 200; val ch = 96; val labelH = 26
            val rows = (skins.size + cols - 1) / cols
            val sk = Bitmap.createBitmap(cols * (cw + 8) + 8, rows * (ch + labelH + 8) + 8, Bitmap.Config.ARGB_8888)
            val kc = Canvas(sk)
            kc.drawColor(0xFF0F1115.toInt())
            skins.forEachIndexed { k, skin ->
                val x = 8f + (k % cols) * (cw + 8); val y = 8f + (k / cols) * (ch + labelH + 8)
                kc.drawText(skin.name.substringAfter(" · ").takeIf { skin.group != WinampSkins.GROUP_CLASSIC } ?: skin.name, x, y + 18f, small)
                kc.save(); kc.translate(x, y + labelH)
                SpectrumRenderer().draw(kc, cw.toFloat(), ch.toFloat(), wv, wp, SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC, winampSkin = skin.name), 1f)
                kc.restore()
            }
            File(out, file).outputStream().use { sk.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        skinSheet(WinampSkins.BUILT_IN.filter { it.group == WinampSkins.GROUP_CLASSIC }, "skins_winamp.png")
        skinSheet(WinampModernSkins.ALL.filter { it.group == "Winamp Modern" }, "temas_winamp_modern.png")
        skinSheet(WinampModernSkins.ALL.filter { it.group == "Bento" }, "temas_bento.png")

        // Patrones de color: una fila por patrón, con cuatro skins distintos.
        val sample = listOf("Winamp 2 (original)", "Major Tom", "Denon Antique", "Purple Glow").map { WinampSkins.byName(it) }
        val pats = WinampPattern.entries
        val pcw = 200; val pch = 64; val plab = 150
        val pt = Bitmap.createBitmap(plab + sample.size * (pcw + 8) + 8, pats.size * (pch + 10) + 40, Bitmap.Config.ARGB_8888)
        val ptc = Canvas(pt)
        ptc.drawColor(0xFF0F1115.toInt())
        sample.forEachIndexed { i, skin -> ptc.drawText(skin.name, plab + 8f + i * (pcw + 8), 26f, small) }
        pats.forEachIndexed { j, pat ->
            val y = 36f + j * (pch + 10)
            ptc.drawText(pat.label, 12f, y + pch / 2f + 8f, text)
            sample.forEachIndexed { i, skin ->
                ptc.save(); ptc.translate(plab + 8f + i * (pcw + 8), y)
                SpectrumRenderer().draw(
                    ptc, pcw.toFloat(), pch.toFloat(), wv, wp,
                    SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC, winampSkin = skin.name, winampPattern = pat, winampFrame = false),
                )
                ptc.restore()
            }
        }
        File(out, "patrones_winamp.png").outputStream().use { pt.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Maqueta del tema de la app con distintos skins: fondo, tarjeta, título, deslizadores y botón.
        val themed = listOf(
            "Winamp 2 (original)", "Major Tom", "Denon Antique", "Mac OS clásico", "Commodore 64", "Purple Glow",
            "Winamp Modern · Default", "Winamp Modern · Grapes", "Winamp Modern · Citrus", "Bento · Default", "Bento · Mint", "Bento · Lighten Up · Blue",
        ).map { WinampSkins.byName(it) }
        val tw = 260; val th = 200
        val app = Bitmap.createBitmap(3 * (tw + 10) + 10, 4 * (th + 36) + 10, Bitmap.Config.ARGB_8888)
        val ac = Canvas(app)
        ac.drawColor(0xFF0F1115.toInt())
        val pnt = Paint(Paint.ANTI_ALIAS_FLAG)
        themed.forEachIndexed { k, skin ->
            val pal = AppTheme.from(skin)
            val x = 10f + (k % 3) * (tw + 10); val y = 10f + (k / 3) * (th + 36)
            pnt.color = Color.LTGRAY; pnt.textSize = 16f
            ac.drawText(skin.name, x, y + 16f, pnt)
            val top = y + 24f
            pnt.color = pal.bg; ac.drawRect(x, top, x + tw, top + th, pnt)
            pnt.color = pal.ink; pnt.textSize = 22f; pnt.isFakeBoldText = true
            ac.drawText("Ecualizador", x + 12f, top + 30f, pnt)
            pnt.isFakeBoldText = false; pnt.textSize = 13f; pnt.color = pal.accent
            ac.drawText("Activo en todo el teléfono", x + 12f, top + 48f, pnt)
            pnt.color = pal.card; ac.drawRoundRect(x + 10f, top + 58f, x + tw - 10f, top + th - 10f, 14f, 14f, pnt)
            for (b in 0 until 10) {
                val bx = x + 26f + b * 22.5f
                val cy = top + 118f
                val v = kotlin.math.sin(b * 0.8f) * 30f
                pnt.color = pal.track; ac.drawRect(bx - 3f, top + 72f, bx + 3f, top + 164f, pnt)
                pnt.color = pal.accent; ac.drawRect(bx - 3f, minOf(cy, cy - v), bx + 3f, maxOf(cy, cy - v), pnt)
                pnt.color = pal.ink; ac.drawCircle(bx, cy - v, 6f, pnt)
            }
            pnt.color = pal.muted; pnt.textSize = 11f
            ac.drawText("31   63  125  250  500   1k   2k   4k   8k  16k", x + 18f, top + 180f, pnt)
        }
        File(out, "tema_app.png").outputStream().use { app.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Muestrario de paletas.
        val pals = SpectrumPalette.entries
        val sw = Bitmap.createBitmap(w, pals.size * 44 + 10, Bitmap.Config.ARGB_8888)
        val pc = Canvas(sw)
        pc.drawColor(0xFF0F1115.toInt())
        pals.forEachIndexed { k, pal ->
            for (x in 0 until 420) {
                val paint = Paint().apply { color = pal.color(x / 419f) }
                pc.drawRect(280f + x, 10f + k * 44, 281f + x, 44f + k * 44, paint)
            }
            pc.drawText(pal.label, 12f, 34f + k * 44, text)
        }
        File(out, "paletas.png").outputStream().use { sw.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Maqueta del fondo animado sobre una foto (si existe fondo_usuario.png).
        val photo = File(out, "fondo_usuario.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } ?: return
        val wall = photo.copy(Bitmap.Config.ARGB_8888, true)
        val wc = Canvas(wall)
        wc.drawColor(0x40000000)
        val s = SpectrumSettings(style = SpectrumStyle.BARS, palette = SpectrumPalette.VIOLET, grid = false)
        val (values, peaks) = analyzed(s.bars)
        val h = wall.height * 0.25f
        wc.save()
        wc.translate(16f, wall.height - h - wall.height * 0.14f)
        r.draw(wc, wall.width - 32f, h, values, peaks, s, 1.5f)
        wc.restore()
        File(out, "fondo_animado.png").outputStream().use { wall.compress(Bitmap.CompressFormat.PNG, 95, it) }
    }
}

/** El estilo dibujado antes no debe cambiar cómo se ve el siguiente (pinceles compartidos). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererStateTest {
    @Test
    fun previousStyleDoesNotLeak() {
        val values = FloatArray(32) { 0.8f }
        val mirror = SpectrumSettings(style = SpectrumStyle.MIRROR, palette = SpectrumPalette.SUNSET, bars = 32, grid = false)
        fun render(first: SpectrumSettings?): IntArray {
            val r = SpectrumRenderer()
            val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
            first?.let { r.draw(Canvas(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)), 200f, 100f, values, null, it) }
            r.draw(Canvas(bmp), 200f, 100f, values, null, mirror)
            return IntArray(200 * 100).also { bmp.getPixels(it, 0, 200, 0, 0, 200, 100) }
        }
        val clean = render(null)
        SpectrumStyle.entries.forEach { st ->
            val after = render(SpectrumSettings(style = st, bars = 32))
            org.junit.Assert.assertArrayEquals("el estilo ${st.label} cambió el dibujo siguiente", clean, after)
        }
    }

    /** Con la ventana del skin, la esquina es la cara del skin y el centro sigue siendo el analizador. */
    @Test
    fun skinFrameSurroundsAnalyzer() {
        val skin = WinampSkins.byName("Denon Antique")
        val values = FloatArray(19) { 1f }
        fun render(frame: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888)
            SpectrumRenderer().draw(
                Canvas(bmp), 300f, 120f, values, null,
                SpectrumSettings(style = SpectrumStyle.WINAMP_CLASSIC, winampSkin = skin.name, winampFrame = frame),
            )
            return bmp
        }
        val framed = render(true)
        val plain = render(false)
        org.junit.Assert.assertEquals(skin.chrome[WinampChrome.FACE], framed.getPixel(2, 60))
        org.junit.Assert.assertNotEquals(skin.chrome[WinampChrome.FACE], plain.getPixel(2, 60))
        // El centro (barras llenas) sigue mostrando colores del analizador del skin.
        org.junit.Assert.assertTrue(framed.getPixel(150, 100) in skin.colors.toList())
    }
}
