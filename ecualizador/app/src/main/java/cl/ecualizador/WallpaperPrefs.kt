package cl.ecualizador

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

enum class WallpaperPosition(val label: String) { BOTTOM("Abajo"), CENTER("Centro"), TOP("Arriba") }

data class WallpaperSettings(
    val position: WallpaperPosition = WallpaperPosition.BOTTOM,
    /** Alto del espectro como fracción de la pantalla. */
    val height: Float = 0.25f,
    /** Oscurecer la foto para que el espectro resalte (0..0,7). */
    val dim: Float = 0.25f,
    val hasImage: Boolean = false,
    /** Cambia cada vez que se elige otra foto, para recargarla. */
    val imageVersion: Long = 0,
)

/** Opciones del fondo animado y la foto elegida (copiada a la app para no depender de la galería). */
object WallpaperPrefs {
    val settings = MutableStateFlow(WallpaperSettings())
    private var loaded = false

    fun imageFile(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    fun load(ctx: Context): WallpaperSettings {
        if (!loaded) {
            val p = ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
            settings.value = WallpaperSettings(
                position = runCatching { WallpaperPosition.valueOf(p.getString("position", "BOTTOM")!!) }.getOrDefault(WallpaperPosition.BOTTOM),
                height = p.getFloat("height", 0.25f),
                dim = p.getFloat("dim", 0.25f),
                hasImage = imageFile(ctx).exists(),
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
            .putString("position", s.position.name).putFloat("height", s.height).putFloat("dim", s.dim)
            .putLong("imageVersion", s.imageVersion).apply()
    }

    /** Copia la foto elegida reduciéndola a un tamaño razonable de pantalla. */
    fun saveImage(ctx: Context, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        // Las fotos de cámara guardan el giro aparte (EXIF): se aplica para que no queden de lado.
        val degrees = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0
        val upright = WallpaperImage.rotate(bmp, degrees)
        imageFile(ctx).outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (upright !== bmp) upright.recycle()
        bmp.recycle()
        update(ctx) { it.copy(hasImage = true, imageVersion = System.currentTimeMillis()) }
        true
    }.getOrDefault(false)

    fun removeImage(ctx: Context) {
        imageFile(ctx).delete()
        update(ctx) { it.copy(hasImage = false, imageVersion = System.currentTimeMillis()) }
    }
}
