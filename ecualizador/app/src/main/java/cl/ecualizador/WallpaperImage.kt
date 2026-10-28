package cl.ecualizador

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Prepara la foto del fondo animado sin pixelarla. La foto se guarda tal cual (sin recomprimir ni
 * reducir) y solo al dibujarla se lleva a la resolución de la pantalla. Reducir mucho una imagen en
 * un solo paso con filtro bilineal solo mira 2×2 píxeles de origen por cada píxel de destino y
 * produce bordes dentados; por eso se decodifica cerca del tamaño final y se reduce a la mitad por pasos.
 */
object WallpaperImage {
    /** inSampleSize de BitmapFactory: la mayor potencia de 2 que deja la imagen igual o más grande que el destino. */
    fun sampleSizeFor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        // Para cubrir la pantalla manda el lado que menos sobra.
        val needed = maxOf(targetW / srcW.toFloat(), targetH / srcH.toFloat())
        var s = 1
        while (needed * s * 2 <= 1f) s *= 2
        return s
    }

    /** Recorte centrado que cubre [w]×[h], reduciendo por mitades mientras falte más de la mitad. */
    fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (maxOf(w / cur.width.toFloat(), h / cur.height.toFloat()) < 0.5f) {
            val next = Bitmap.createScaledBitmap(cur, (cur.width / 2).coerceAtLeast(1), (cur.height / 2).coerceAtLeast(1), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val scale = maxOf(w / cur.width.toFloat(), h / cur.height.toFloat())
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - cur.width * scale) / 2, (h - cur.height * scale) / 2)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(cur, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG))
        if (cur !== src) cur.recycle()
        return out
    }

    /**
     * Tamaño que conviene pedir para la superficie del fondo cuando el sistema la entrega más chica
     * que la pantalla (algunas capas de fabricante lo hacen y luego la amplían, lo que pixela la
     * foto). Mantiene la forma de la superficie y la agranda hasta cubrir la pantalla real; `null`
     * si ya tiene resolución completa.
     */
    fun fullResolutionSize(surfaceW: Int, surfaceH: Int, displayW: Int, displayH: Int): Pair<Int, Int>? {
        if (surfaceW <= 0 || surfaceH <= 0 || displayW <= 0 || displayH <= 0) return null
        // Se comparan lado largo con lado largo: la superficie puede estar girada respecto de la pantalla.
        val sLong = maxOf(surfaceW, surfaceH); val sShort = minOf(surfaceW, surfaceH)
        val dLong = maxOf(displayW, displayH); val dShort = minOf(displayW, displayH)
        if (sLong >= dLong || sShort >= dShort) return null
        val f = maxOf(dLong / sLong.toFloat(), dShort / sShort.toFloat())
        return Math.round(surfaceW * f) to Math.round(surfaceH * f)
    }

    /** Grados que hay que girar la foto según su orientación EXIF (0 si no tiene o no se puede leer). */
    fun exifDegrees(path: String): Int = runCatching {
        when (android.media.ExifInterface(path).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Ancho y alto de la foto ya enderezada (sin decodificarla). */
    fun uprightSize(path: String): Pair<Int, Int>? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return if (exifDegrees(path) % 180 == 90) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    /** Endereza según la orientación EXIF de la cámara (90, 180 o 270 grados). */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }
}
