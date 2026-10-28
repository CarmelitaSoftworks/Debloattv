package cl.ecualizador

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Prepara la foto del fondo animado sin pixelarla. Reducir mucho una imagen en un solo paso con
 * filtro bilineal solo mira 2×2 píxeles de origen por cada píxel de destino y produce bordes
 * dentados; por eso se decodifica cerca del tamaño final y se reduce a la mitad por pasos.
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

    /** Endereza según la orientación EXIF de la cámara (90, 180 o 270 grados). */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }
}
