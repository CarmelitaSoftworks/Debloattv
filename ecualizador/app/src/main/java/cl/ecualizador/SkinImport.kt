package cl.ecualizador

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Imagen decodificada: [argb] fila por fila. */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]
}

/** Archivos de un skin: rutas en minúsculas con «/», sin la carpeta raíz del zip si la hay. */
class SkinArchive(val files: Map<String, ByteArray>) {
    /** Primer archivo con ese nombre, en cualquier carpeta (los .wsz a veces traen una carpeta dentro). */
    fun byName(name: String): ByteArray? =
        files.entries.filter { it.key.substringAfterLast('/') == name }.minByOrNull { it.key.length }?.value

    companion object {
        const val MAX_TOTAL = 48L shl 20
        const val MAX_FILES = 4000

        /**
         * Lee un .wsz/.wal/.zip. Los zip dentro del zip (skins empaquetados dos veces) se abren también.
         * Corta en [MAX_TOTAL] bytes descomprimidos o [MAX_FILES] archivos para no agotar la memoria.
         */
        fun read(input: InputStream): SkinArchive {
            val files = LinkedHashMap<String, ByteArray>()
            var total = 0L
            fun walk(stream: InputStream, depth: Int) {
                val zip = ZipInputStream(stream)
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 shl 10)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL) throw IllegalArgumentException("El skin es demasiado grande")
                        buf.write(chunk, 0, n)
                    }
                    val name = e.name.replace('\\', '/').lowercase()
                    val bytes = buf.toByteArray()
                    if ((name.endsWith(".wsz") || name.endsWith(".wal") || name.endsWith(".zip")) && depth < 2) {
                        runCatching { walk(bytes.inputStream(), depth + 1) }
                    } else if (files.size < MAX_FILES) files.putIfAbsent(name, bytes)
                }
            }
            walk(input, 0)
            if (files.isEmpty()) throw IllegalArgumentException("No es un archivo zip de skin")
            return SkinArchive(files)
        }
    }
}

/**
 * Convierte un skin clásico de Winamp (.wsz) en lo que usa el visor: los colores (VISCOLOR.TXT,
 * MAIN.BMP, TEXT.BMP y PLEDIT.TXT) y su ventana real (ver [WinampWindow]).
 */
object SkinImport {
    const val GROUP_IMPORTED = "Importados"

    fun interface Decoder { fun decode(bytes: ByteArray): Pixels? }

    /** Decodifica BMP y PNG con Android; descarta imágenes enormes (los skins usan imágenes chicas). */
    val ANDROID_DECODER = Decoder { bytes ->
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000) return@Decoder null
        val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { bmp ->
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Pixels(bmp.width, bmp.height, px).also { bmp.recycle() }
        }
    }

    /**
     * El skin clásico que sale del archivo (vacío si no lo es). Los skins modernos (.wal, con
     * skin.xml) no se aceptan: su ventana depende de un motor de diseño completo.
     */
    fun import(archive: SkinArchive, fallbackName: String, decoder: Decoder, group: String = GROUP_IMPORTED): List<WinampSkin> {
        if (isModern(archive)) return emptyList()
        return listOfNotNull(classic(archive, fallbackName, decoder, group))
    }

    fun isModern(archive: SkinArchive) = archive.files.keys.any { it.substringAfterLast('/') == "skin.xml" }

    // ------------------------------------------------------------------ colores

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun distance(a: Int, b: Int) = kotlin.math.abs(r(a) - r(b)) + kotlin.math.abs(g(a) - g(b)) + kotlin.math.abs(b(a) - b(b))

    /** Color más frecuente (agrupando tonos parecidos de a 24 niveles) y su promedio real. */
    fun dominant(pixels: List<Int>): Int? {
        if (pixels.isEmpty()) return null
        val groups = LinkedHashMap<Int, MutableList<Int>>()  // en empates gana el primero visto
        for (p in pixels) groups.getOrPut((r(p) / 24 shl 16) or (g(p) / 24 shl 8) or (b(p) / 24)) { ArrayList() }.add(p)
        val best = groups.values.maxBy { it.size }
        return rgb(best.sumOf { r(it) } / best.size, best.sumOf { g(it) } / best.size, best.sumOf { b(it) } / best.size)
    }

    /** Color más llamativo con presencia (saturación × luminosidad media × área^¼). */
    fun accent(pixels: List<Int>): Int? {
        if (pixels.isEmpty()) return null
        val counts = LinkedHashMap<Int, Int>()
        for (p in pixels) counts.merge((r(p) / 32 shl 16) or (g(p) / 32 shl 8) or (b(p) / 32), 1, Int::plus)
        var best: Int? = null
        var bestScore = 0.0
        for ((k, n) in counts) {
            val share = n.toDouble() / pixels.size
            if (share < 0.004) continue
            val c = rgb((k shr 16) * 32 + 16, ((k shr 8) and 0xFF) * 32 + 16, (k and 0xFF) * 32 + 16)
            val mx = maxOf(r(c), g(c), b(c)) / 255.0
            val mn = minOf(r(c), g(c), b(c)) / 255.0
            val l = (mx + mn) / 2
            val s = if (mx == mn) 0.0 else if (l <= 0.5) (mx - mn) / (mx + mn) else (mx - mn) / (2 - mx - mn)
            val score = s * minOf(l, 1 - l) * 2 * Math.pow(share, 0.25)
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    /**
     * Ajusta lo extraído para que se pueda leer: lista y letra con contraste sobre su fondo (si no,
     * el primer color propio del skin que contraste).
     */
    fun finishChrome(vis: IntArray, face: Int, frame: Int, accent: Int, text: Int?, textBg: Int?, listText: Int?, listCurrent: Int?, listBg: Int?): IntArray {
        val tb = textBg ?: vis[0]
        var pn = listText ?: vis[12]
        var pc = listCurrent ?: vis[23]
        var pb = listBg ?: vis[0]
        if (distance(pn, pb) < 90) { pn = vis[12]; pb = vis[0] }
        if (distance(pc, pb) < 90) pc = vis[23]
        val t = listOf(text ?: vis[12], pn, pc, accent, vis[23], vis[2], vis[12]).firstOrNull { distance(it, tb) >= 220 }
            ?: if (r(tb) + g(tb) + b(tb) < 384) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        return intArrayOf(face, frame, accent, t, tb, pn, pc, pb).map { it or (0xFF shl 24) }.toIntArray()
    }

    // ------------------------------------------------------------------ clásicos (.wsz)

    /** VISCOLOR.TXT: 24 líneas «r,g,b» (con comentarios); las que falten salen del Winamp original. */
    fun parseVisColors(text: String): IntArray {
        val found = Regex("""^\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""", RegexOption.MULTILINE)
            .findAll(text).map { m -> rgb(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            .take(24).toList()
        return IntArray(24) { found.getOrNull(it) ?: WinampColors.VIS[it] }
    }

    private fun plEditColor(text: String, key: String): Int? =
        Regex("""^\s*$key\s*=\s*#?([0-9a-fA-F]{6})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1)?.toInt(16)?.let { it or (0xFF shl 24) }

    fun classic(archive: SkinArchive, name: String, decoder: Decoder, group: String): WinampSkin? {
        // Muchos skins no traen VISCOLOR.TXT: Winamp usa entonces los colores originales del visor
        val visText = archive.byName("viscolor.txt")
        if (visText == null && archive.byName("main.bmp") == null) return null
        val vis = visText?.let { parseVisColors(String(it, Charsets.ISO_8859_1)) } ?: WinampColors.VIS.copyOf()
        var face = vis[0]; var frame = vis[1]; var accent = vis[2]
        archive.byName("main.bmp")?.let { decoder.decode(it) }?.takeIf { it.width >= 275 && it.height >= 116 }?.let { m ->
            val edge = ArrayList<Int>()
            for (x in (4 until 15) + (260 until 271)) for (y in 18 until 110) edge += m[x, y]
            for (x in 4 until 271) for (y in 104 until 112) edge += m[x, y]
            face = dominant(edge) ?: face
            val visBg = m[30, 50]
            var ring = ArrayList<Int>()
            for (d in 1..3) {
                for (x in 24 - d until 101 + d) { ring += m[x, 43 - d]; ring += m[x, 59 + d] }
                for (y in 43 - d until 60 + d) { ring += m[24 - d, y]; ring += m[100 + d, y] }
            }
            ring.filter { distance(it, visBg) > 40 }.takeIf { it.isNotEmpty() }?.let { ring = ArrayList(it) }
            frame = dominant(ring) ?: frame
            val all = ArrayList<Int>()
            for (x in 0 until 275) for (y in 14 until 116) all += m[x, y]
            accent = accent(all) ?: face
        }
        var text: Int? = null
        var textBg: Int? = null
        archive.byName("text.bmp")?.let { decoder.decode(it) }?.let { t ->
            val bg = t.argb[0]
            textBg = bg
            t.argb.filter { distance(it, bg) > 90 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { text = it.key }
        }
        val pl = archive.byName("pledit.txt")?.let { String(it, Charsets.ISO_8859_1) }
        val chrome = finishChrome(
            vis, face, frame, accent, text, textBg,
            pl?.let { plEditColor(it, "normal") }, pl?.let { plEditColor(it, "current") }, pl?.let { plEditColor(it, "normalbg") },
        )
        return WinampSkin(name, "importado", vis, chrome, group, windowPixels = WinampWindow.compose(archive, decoder, name))
    }
}
