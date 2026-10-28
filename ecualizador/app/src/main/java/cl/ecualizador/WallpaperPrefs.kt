package cl.ecualizador

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

enum class WallpaperPosition(val label: String) { BOTTOM("Abajo"), CENTER("Centro"), TOP("Arriba") }

/** Qué se ve en el fondo animado. */
enum class WallpaperView(val label: String) {
    /** La ventana del skin de Winamp a todo el ancho, con el espectro en su visor. */
    PLAYER("Reproductor completo"),
    /** Solo el espectro, con el estilo y el alto elegidos. */
    SPECTRUM("Solo el espectro");

    /**
     * Ajustes del visor para este modo. El reproductor siempre usa un estilo de Winamp y la ventana
     * entera; «Solo el espectro» con un estilo de Winamp muestra la pantalla del skin (reloj y visor
     * con su marco) a todo el ancho.
     */
    fun spectrumSettings(s: SpectrumSettings): SpectrumSettings = when (this) {
        PLAYER -> s.copy(
            style = if (s.style.winamp) s.style else SpectrumStyle.WINAMP_CLASSIC, winampFrame = true, grid = false,
            winampPart = WinampWindow.Part.FULL,
        )
        SPECTRUM -> s.copy(winampFrame = true, grid = false, winampPart = WinampWindow.Part.DISPLAY)
    }

    /**
     * Alto del visor en el fondo: con un estilo de Winamp, el de la parte del skin a ese ancho (su
     * proporción); con los demás estilos, la fracción [heightFraction] de la pantalla.
     */
    fun height(s: SpectrumSettings, width: Float, screenHeight: Float, heightFraction: Float): Float {
        val e = spectrumSettings(s)
        return if (e.style.winamp) width * e.winampPart.h / e.winampPart.w else screenHeight * heightFraction.coerceIn(0.1f, 0.6f)
    }
}

data class WallpaperSettings(
    val position: WallpaperPosition = WallpaperPosition.BOTTOM,
    /** Alto del espectro como fracción de la pantalla. */
    val height: Float = 0.25f,
    val view: WallpaperView = WallpaperView.PLAYER,
    /** Oscurecer la foto para que el espectro resalte (0..0,7). */
    val dim: Float = 0.25f,
    val hasImage: Boolean = false,
    /** Cambia cada vez que se elige otra foto, para recargarla. */
    val imageVersion: Long = 0,
)

/** Resolución con que se dibujó el fondo: superficie que dio el sistema, pantalla real y foto. */
data class WallpaperRender(
    val surfaceW: Int, val surfaceH: Int,
    val displayW: Int, val displayH: Int,
    /** Si el sistema dio una superficie más chica y se pidió una del tamaño de la pantalla. */
    val enlarged: Boolean,
) {
    fun encode() = "$surfaceW,$surfaceH,$displayW,$displayH,$enlarged"

    companion object {
        fun decode(s: String?): WallpaperRender? = runCatching {
            val p = s!!.split(',')
            WallpaperRender(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toBoolean())
        }.getOrNull()
    }
}

/** Opciones del fondo animado y la foto elegida (copiada a la app para no depender de la galería). */
object WallpaperPrefs {
    val settings = MutableStateFlow(WallpaperSettings())
    private var loaded = false

    /** La foto original tal como se eligió (JPEG, PNG, WebP o HEIF). */
    fun imageFile(ctx: Context) = File(ctx.filesDir, "wallpaper-original")
    /** Versiones anteriores guardaban aquí una copia reducida y recomprimida en JPEG. */
    private fun legacyFile(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")
    private const val MAX_IMAGE_BYTES = 80L shl 20

    /** La foto que hay que dibujar: la original, o la copia de versiones anteriores si es lo único que hay. */
    fun currentImage(ctx: Context): File? = imageFile(ctx).takeIf { it.exists() } ?: legacyFile(ctx).takeIf { it.exists() }

    fun load(ctx: Context): WallpaperSettings {
        if (!loaded) {
            val p = ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
            settings.value = WallpaperSettings(
                position = runCatching { WallpaperPosition.valueOf(p.getString("position", "BOTTOM")!!) }.getOrDefault(WallpaperPosition.BOTTOM),
                view = runCatching { WallpaperView.valueOf(p.getString("view", "PLAYER")!!) }.getOrDefault(WallpaperView.PLAYER),
                height = p.getFloat("height", 0.25f),
                dim = p.getFloat("dim", 0.25f),
                hasImage = currentImage(ctx) != null,
                imageVersion = p.getLong("imageVersion", 0),
            )
            loaded = true
        }
        return settings.value
    }

    fun update(ctx: Context, change: (WallpaperSettings) -> WallpaperSettings) {
        val s = change(load(ctx))
        settings.value = s
        ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit()
            .putString("position", s.position.name).putString("view", s.view.name).putFloat("height", s.height).putFloat("dim", s.dim)
            .putLong("imageVersion", s.imageVersion).apply()
    }

    /**
     * Copia la foto elegida tal cual, byte a byte: sin recomprimir ni reducir, para no perder
     * resolución. El giro de la cámara (EXIF) y el ajuste a la pantalla se hacen al dibujarla.
     */
    fun saveImage(ctx: Context, uri: Uri): Boolean = runCatching {
        val tmp = File(ctx.filesDir, "wallpaper.tmp")
        val copied = ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 shl 10)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMAGE_BYTES) return@use false
                    out.write(buf, 0, n)
                }
                true
            }
        } ?: false
        // Solo se acepta si Android la puede abrir (p. ej. HEIC no se lee antes de Android 9)
        if (!copied || WallpaperImage.uprightSize(tmp.path) == null) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(imageFile(ctx))) {
            tmp.delete()
            return false
        }
        legacyFile(ctx).delete()
        update(ctx) { it.copy(hasImage = true, imageVersion = System.currentTimeMillis()) }
        true
    }.getOrDefault(false)

    /** Cómo dibujó el fondo el teléfono la última vez (para mostrarlo en la app). */
    val render = MutableStateFlow<WallpaperRender?>(null)

    fun reportRender(ctx: Context, r: WallpaperRender) {
        if (render.value == r) return
        render.value = r
        ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit().putString("render", r.encode()).apply()
    }

    fun loadRender(ctx: Context): WallpaperRender? {
        if (render.value == null) {
            render.value = WallpaperRender.decode(ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("render", null))
        }
        return render.value
    }

    fun removeImage(ctx: Context) {
        imageFile(ctx).delete()
        legacyFile(ctx).delete()
        update(ctx) { it.copy(hasImage = false, imageVersion = System.currentTimeMillis()) }
    }
}
