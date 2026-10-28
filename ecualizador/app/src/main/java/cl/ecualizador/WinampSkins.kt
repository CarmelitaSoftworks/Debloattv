package cl.ecualizador

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Colores de un skin de Winamp para el visor. [colors] son los 24 de VISCOLOR.TXT (fondo, grilla, 16
 * filas del analizador de arriba hacia abajo, 5 tonos del osciloscopio y el pico; ver [WinampColors])
 * y [chrome] los de la ventana, en el orden de [WinampChrome].
 */
data class WinampSkin(
    val name: String,
    val archiveId: String,
    val colors: IntArray,
    val chrome: IntArray = WinampChrome.DEFAULT,
    val group: String = WinampSkins.GROUP_CLASSIC,
    /** Ventana del skin importado (PNG de 275×116 en los archivos de la app); ver [WinampWindows]. */
    val windowFile: String? = null,
    /** Ventana recién armada al importar, antes de guardarla en [windowFile]. */
    val windowPixels: IntArray? = null,
    /** El .wsz original del skin importado, para rearmar su ventana si cambia el formato. */
    val sourceFile: String? = null,
)

/** Todos los skins: los clásicos incluidos y los que importa el usuario (.wsz). */
object WinampSkins {
    const val GROUP_CLASSIC = "Clásicos"

    val DEFAULT = WinampSkin("Winamp 2 (original)", "base-2.91", WinampColors.VIS)

    val BUILT_IN: List<WinampSkin> = listOf(DEFAULT) + WinampClassicSkins.ALL

    /** Skins importados desde archivos .wsz (se guardan en la app). */
    val imported = MutableStateFlow<List<WinampSkin>>(emptyList())

    val ALL: List<WinampSkin> get() = BUILT_IN + imported.value

    @Volatile private var last: WinampSkin = DEFAULT

    /** Skin por nombre (el visor lo pide en cada cuadro: se recuerda el último). */
    fun byName(name: String?): WinampSkin {
        val l = last
        if (l.name == name) return l
        val found = BUILT_IN.firstOrNull { it.name == name } ?: imported.value.firstOrNull { it.name == name } ?: DEFAULT
        last = found
        return found
    }

    // ------------------------------------------------------------------ importados

    private var loaded = false
    private fun file(ctx: Context) = File(ctx.filesDir, "winamp-skins.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        imported.value = runCatching { fromJson(file(ctx).readText()) }.getOrDefault(emptyList())
        // Ventanas de un formato anterior: se rearman del .wsz guardado, sin bloquear el inicio
        if (imported.value.any { it.sourceFile != null && WinampWindows.isOutdated(it) }) {
            Thread { upgradeWindows(ctx.applicationContext) }.start()
        }
    }

    /** Vuelve a armar las ventanas desactualizadas de los skins que guardan su .wsz. */
    @Synchronized
    internal fun upgradeWindows(ctx: Context) {
        var changed = false
        val list = imported.value.map { s ->
            val src = s.sourceFile?.let(::File)?.takeIf { it.exists() && WinampWindows.isOutdated(s) } ?: return@map s
            val pixels = runCatching { WinampWindow.compose(SkinArchive.read(src.inputStream()), SkinImport.ANDROID_DECODER) }.getOrNull()
                ?: return@map s
            val window = saveWindow(ctx, pixels) ?: return@map s
            s.windowFile?.let { File(it).delete() }
            changed = true
            s.copy(windowFile = window)
        }
        if (changed) {
            save(ctx, list)
            WinampWindows.clear()
        }
    }

    /**
     * Agrega skins importados; los nombres repetidos llevan un número. [source] es el archivo
     * original, que se guarda para poder rearmar la ventana más adelante. Devuelve los agregados.
     */
    fun add(ctx: Context, skins: List<WinampSkin>, source: ByteArray? = null): List<WinampSkin> {
        val sourceFile = source?.let { bytes ->
            runCatching {
                File(File(ctx.filesDir, "skin-windows").apply { mkdirs() }, "${System.nanoTime()}.wsz").also { it.writeBytes(bytes) }.path
            }.getOrNull()
        }
        load(ctx)
        val names = ALL.map { it.name }.toMutableSet()
        val added = skins.map { s ->
            var n = s.name.take(60)
            var k = 2
            while (n in names) n = "${s.name.take(55)} ($k)".also { k++ }
            names += n
            s.copy(
                name = n, group = SkinImport.GROUP_IMPORTED, windowFile = s.windowPixels?.let { saveWindow(ctx, it) },
                windowPixels = null, sourceFile = sourceFile,
            )
        }
        save(ctx, imported.value + added)
        return added
    }

    /** Guarda la ventana armada como PNG en los archivos de la app y devuelve su ruta. */
    private fun saveWindow(ctx: Context, pixels: IntArray): String? = runCatching {
        val dir = File(ctx.filesDir, "skin-windows").apply { mkdirs() }
        val f = File(dir, "${System.nanoTime()}.png")
        val bmp = android.graphics.Bitmap.createBitmap(pixels, WinampWindow.WIDTH, pixels.size / WinampWindow.WIDTH, android.graphics.Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        f.path
    }.getOrNull()

    fun remove(ctx: Context, name: String) {
        load(ctx)
        imported.value.firstOrNull { it.name == name }?.let { s ->
            s.windowFile?.let { File(it).delete() }
            s.sourceFile?.let { File(it).delete() }
        }
        save(ctx, imported.value.filterNot { it.name == name })
        last = DEFAULT
    }

    private fun save(ctx: Context, list: List<WinampSkin>) {
        imported.value = list
        runCatching { file(ctx).writeText(toJson(list)) }
    }

    fun toJson(list: List<WinampSkin>): String = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject().put("name", s.name).put("colors", JSONArray(s.colors.toList())).put("chrome", JSONArray(s.chrome.toList()))
                    .apply { s.windowFile?.let { put("window", it) }; s.sourceFile?.let { put("source", it) } }
            )
        }
    }.toString()

    fun fromJson(text: String): List<WinampSkin> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val colors = o.getJSONArray("colors").let { c -> IntArray(c.length()) { c.getInt(it) } }
            val chrome = o.getJSONArray("chrome").let { c -> IntArray(c.length()) { c.getInt(it) } }
            if (colors.size != 24 || chrome.size != 8) null
            else WinampSkin(
                o.getString("name"), "importado", colors, chrome, SkinImport.GROUP_IMPORTED,
                windowFile = o.optString("window").ifEmpty { null }, sourceFile = o.optString("source").ifEmpty { null },
            )
        }
    }
}
