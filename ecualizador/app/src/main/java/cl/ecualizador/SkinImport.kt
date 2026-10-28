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
 * Convierte skins de Winamp en colores para el visor: clásicos (.wsz: VISCOLOR.TXT, MAIN.BMP,
 * TEXT.BMP y PLEDIT.TXT) y modernos (.wal: el `<vis>` del XML, los colores de sistema de Wasabi y
 * cada tema de color, aplicando sus «gamma groups» como lo hace Winamp).
 */
object SkinImport {
    const val GROUP_IMPORTED = "Importados"
    private const val MAX_CACHED_PIXELS = 6_000_000L

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

    /** Todos los skins que salen del archivo: uno si es clásico, uno por tema de color si es moderno. */
    fun import(archive: SkinArchive, fallbackName: String, decoder: Decoder, group: String = GROUP_IMPORTED): List<WinampSkin> {
        val skinXml = archive.files.keys.filter { it.substringAfterLast('/') == "skin.xml" }.minByOrNull { it.length }
        if (skinXml != null) {
            val modern = modern(archive, skinXml, fallbackName, decoder, group)
            if (modern.isNotEmpty()) return modern
        }
        return listOfNotNull(classic(archive, fallbackName, decoder, group))
    }

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
        return WinampSkin(name, "importado", vis, chrome, group)
    }

    // ------------------------------------------------------------------ modernos (.wal)

    /** Un «gamma group» de un tema: valor −4096..4096 por canal, gris (1 máx., 2 promedio) y realce. */
    class Gamma(val r: Int, val g: Int, val b: Int, val gray: Int, val boost: Int) {
        /** Misma fórmula que Winamp (y Webamp): canal × (1 + valor/4096), con gris y realce antes. */
        fun apply(c: Int): Int {
            var cr = r(c).toDouble(); var cg = g(c).toDouble(); var cb = b(c).toDouble()
            if (gray != 0) {
                val v = if (gray == 2) (cr + cg + cb) / 3 else maxOf(cr, cg, cb)
                cr = v; cg = v; cb = v
            }
            val mult = if (boost == 2) 4 else 1
            val bright = when (boost) { 1 -> 128; 2 -> 32; else -> 0 }
            fun ch(v: Double, k: Int) = ((v + bright) * mult * (k / 4096.0 + 1.0)).toInt().coerceIn(0, 255)
            return rgb(ch(cr, r), ch(cg, g), ch(cb, b))
        }
    }

    private class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean)

    private val tagRegex = Regex("""<(/?)([A-Za-z][\w.:-]*)([^<>]*?)/?>""")
    private val attrRegex = Regex("""([\w.:-]+)\s*=\s*"([^"]*)"""")

    /** Etiquetas de [path] y de sus `<include>` (rutas relativas al archivo que las incluye), en orden. */
    private fun tags(archive: SkinArchive, path: String, seen: MutableSet<String> = HashSet(), out: MutableList<Tag> = ArrayList()): List<Tag> {
        if (!seen.add(path) || seen.size > 300) return out
        val bytes = archive.files[path] ?: return out
        val text = String(bytes, Charsets.UTF_8).replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val dir = path.substringBeforeLast('/', "")
        for (m in tagRegex.findAll(text)) {
            val attrs = attrRegex.findAll(m.groupValues[3]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val tag = Tag(m.groupValues[2].lowercase(), attrs, m.groupValues[1] == "/")
            if (tag.name == "include" && !tag.closing) attrs["file"]?.let { tags(archive, resolve(dir, it), seen, out) }
            else out += tag
        }
        return out
    }

    private fun resolve(dir: String, file: String): String {
        val parts = ArrayList<String>()
        for (p in ("$dir/" + file.replace('\\', '/')).lowercase().split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    private fun parseRgb(v: String?): Int? {
        val n = v?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return if (n.size >= 3) rgb(n[0], n[1], n[2]) else null
    }

    fun modern(archive: SkinArchive, skinXml: String, fallbackName: String, decoder: Decoder, group: String): List<WinampSkin> {
        val root = skinXml.substringBeforeLast('/', "")
        val all = tags(archive, skinXml)
        val nameText = archive.files[skinXml]?.let { Regex("<name>([^<]{1,60})</name>").find(String(it, Charsets.UTF_8))?.groupValues?.get(1)?.trim() }
        val skinName = nameText?.takeIf { it.isNotEmpty() } ?: fallbackName

        // Temas de color.
        val themes = LinkedHashMap<String, Map<String, Gamma>>()
        var current: String? = null
        var groups = HashMap<String, Gamma>()
        for (t in all) when {
            t.name == "gammaset" && !t.closing -> {
                // Un tema sin «</gammaset>» se guarda igual al empezar el siguiente
                current?.let { themes.putIfAbsent(it, groups) }
                current = t.attrs["id"] ?: "Tema ${themes.size + 1}"
                groups = HashMap()
            }
            t.name == "gammaset" && t.closing -> { current?.let { themes.putIfAbsent(it, groups) }; current = null }
            t.name == "gammagroup" && current != null -> {
                val v = t.attrs["value"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
                val id = t.attrs["id"]?.lowercase()
                if (id != null && v.size >= 3) groups[id] = Gamma(v[0], v[1], v[2], t.attrs["gray"]?.toIntOrNull() ?: 0, t.attrs["boost"]?.toIntOrNull() ?: 0)
            }
        }
        current?.let { themes.putIfAbsent(it, groups) }
        if (themes.isEmpty()) themes[""] = emptyMap()

        // Colores con nombre (los valores pueden nombrar otro color).
        val colors = HashMap<String, Pair<String, String?>>()
        for (t in all) if (t.name == "color" && !t.closing) {
            val id = t.attrs["id"]?.lowercase() ?: continue
            colors.putIfAbsent(id, (t.attrs["value"] ?: "") to t.attrs["gammagroup"]?.lowercase())
        }
        fun rawColor(id: String, depth: Int = 0): Pair<Int, String?>? {
            val (v, gg) = colors[id.lowercase()] ?: return null
            parseRgb(v)?.let { return it to gg }
            return if (depth < 5) rawColor(v, depth + 1)?.let { it.first to (gg ?: it.second) } else null
        }

        // Imágenes por gamma group: color dominante (y llamativo) de sus píxeles opacos. Aparte se
        // guarda la imagen más grande de cada grupo (de preferencia de la ventana del reproductor,
        // «player…»/«main…»): el mismo grupo suele teñir también botones y ventanas secundarias, y
        // la cara de la ventana es la imagen grande, no la suma de los botones.
        // Las hojas de sprites se usan en muchas etiquetas: se guardan decodificadas, pero solo hasta
        // [MAX_CACHED_PIXELS] en total para que un .wal con imágenes grandes no agote la memoria.
        val decoded = HashMap<String, Pixels?>()
        var cachedPixels = 0L
        fun image(path: String): Pixels? {
            if (path in decoded) return decoded[path]
            val img = archive.files[path]?.let { runCatching { decoder.decode(it) }.getOrNull() }
            val size = img?.let { it.width.toLong() * it.height } ?: 0L
            if (cachedPixels + size <= MAX_CACHED_PIXELS) { decoded[path] = img; cachedPixels += size }
            return img
        }
        val samples = HashMap<String, MutableList<Int>>()
        val largest = HashMap<String, Pair<Long, List<Int>>>()
        fun offerLargest(group: String, player: Boolean, size: Long, pixels: List<Int>) {
            val score = size + if (player) 1L shl 40 else 0L
            if (pixels.isNotEmpty() && score > (largest[group]?.first ?: -1L)) largest[group] = score to pixels
        }
        val area = HashMap<String, Long>()
        for (t in all) if (t.name == "bitmap" && !t.closing) {
            val gg = t.attrs["gammagroup"]?.lowercase() ?: continue
            val file = t.attrs["file"] ?: continue
            val list = samples.getOrPut(gg) { ArrayList() }
            val id = t.attrs["id"]?.lowercase().orEmpty()
            val player = id.startsWith("player") || id.startsWith("main")
            if (file.startsWith("$")) {
                parseRgb(t.attrs["color"])?.let { c ->
                    val n = ((t.attrs["w"]?.toIntOrNull() ?: 8) * (t.attrs["h"]?.toIntOrNull() ?: 8)).coerceIn(1, 4096)
                    area.merge(gg, n.toLong(), Long::plus)
                    repeat(minOf(n, 256)) { list += c }
                    offerLargest(gg, player, n.toLong(), listOf(c))
                }
                continue
            }
            val path = resolve(root, file)
            val img = image(path) ?: continue
            val x0 = (t.attrs["x"]?.toIntOrNull() ?: 0).coerceIn(0, img.width - 1)
            val y0 = (t.attrs["y"]?.toIntOrNull() ?: 0).coerceIn(0, img.height - 1)
            val w = (t.attrs["w"]?.toIntOrNull() ?: img.width).coerceIn(1, img.width - x0)
            val h = (t.attrs["h"]?.toIntOrNull() ?: img.height).coerceIn(1, img.height - y0)
            area.merge(gg, w.toLong() * h, Long::plus)
            // Misma densidad de muestreo en todas: las imágenes grandes pesan más.
            val own = ArrayList<Int>()
            for (y in y0 until y0 + h step 3) for (x in x0 until x0 + w step 3) {
                val p = img[x, y]
                if ((p ushr 24) > 200) own += p
            }
            list += own
            offerLargest(gg, player, w.toLong() * h, own)
        }
        fun biggest(match: (String) -> Boolean) = area.keys.filter(match).maxByOrNull { area[it] ?: 0 }
        val faceGroup = biggest { "background" in it && "list" !in it && "sel" !in it && "tooltip" !in it }
        val displayGroup = biggest { "display" in it && listOf("text", "song", "element", "vis", "beat", "gradient").none { k -> k in it } }
        val accentGroup = biggest { "bolt" in it } ?: biggest { "glow" in it }
        fun dominantOf(group: String) = dominant(largest[group]?.second.orEmpty()) ?: dominant(samples[group].orEmpty())
        val faceRaw = faceGroup?.let(::dominantOf)
        val displayRaw = displayGroup?.let(::dominantOf)
        val accentRaw = accentGroup?.let { accent(samples[it].orEmpty()) ?: dominant(samples[it].orEmpty()) }

        // El visor: 16 bandas (1 = abajo), pico y 5 tonos de osciloscopio.
        val visTag = all.firstOrNull { it.name == "vis" && !it.closing && it.attrs.keys.any { k -> k.startsWith("colorband") } }
        val visGroup = visTag?.attrs?.get("gammagroup")?.lowercase()

        val out = ArrayList<WinampSkin>()
        for ((themeId, gammas) in themes) {
            fun tone(c: Int, group: String?) = group?.let { gammas[it] }?.apply(c) ?: c
            fun named(id: String) = rawColor(id)?.let { (c, gg) -> tone(c, gg) }
            val listText = named("wasabi.list.text")
            val listBg = named("wasabi.list.background")
            val listCurrent = named("wasabi.list.text.current")
            val display = displayRaw?.let { tone(it, displayGroup) } ?: listBg ?: 0xFF000000.toInt()
            val face = faceRaw?.let { tone(it, faceGroup) } ?: WinampColors.mix(display, 0xFF808080.toInt(), 0.3f)
            val text = named("font.display") ?: listText
            val vis = IntArray(24)
            vis[0] = display
            val bands = (1..16).map { i -> visTag?.attrs?.get("colorband$i")?.let { parseRgb(it) }?.let { tone(it, visGroup) } }
            val top = text ?: 0xFFFFFFFF.toInt()
            for (row in 0 until 16) {
                // VISCOLOR: índice 2 arriba … 17 abajo; colorband16 arriba … colorband1 abajo.
                vis[2 + row] = bands[15 - row] ?: WinampColors.mix(top, display, row / 24f)
            }
            vis[1] = WinampColors.mix(display, vis[9], 0.18f)
            for (k in 0 until 5) vis[18 + k] = visTag?.attrs?.get("colorosc${k + 1}")?.let { parseRgb(it) }?.let { tone(it, visGroup) } ?: vis[2 + k * 3]
            vis[23] = visTag?.attrs?.get("colorbandpeak")?.let { parseRgb(it) }?.let { tone(it, visGroup) } ?: top
            for (i in vis.indices) vis[i] = vis[i] or (0xFF shl 24)
            // El tinte del tema se ve sobre todo en el visor: si sus barras tienen tono propio, ese es el
            // acento; si son grises o blancas, el rayo (o el brillo de los botones) del skin.
            fun colorful(c: Int) = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c)) > 60
            val accent = vis.slice(2..9).firstOrNull { colorful(it) && distance(it, face) > 60 }
                ?: accentRaw?.let { tone(it, accentGroup) }?.takeIf { distance(it, face) > 60 } ?: vis[23]
            val frame = WinampColors.mix(face, display, 0.5f)
            val chrome = finishChrome(vis, face, frame, accent, text, display, listText, listCurrent, listBg)
            val theme = themeId.trim().trimStart('*').replace(" | ", " · ").trim()
            out += WinampSkin(if (theme.isEmpty() || themes.size == 1) skinName else "$skinName · $theme", "importado", vis, chrome, group)
        }
        // Temas que dan exactamente los mismos colores aparecen una sola vez.
        return out.distinctBy { it.colors.toList() + it.chrome.toList() }
    }
}
