package cl.ecualizador

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Ventanas de los skins clásicos (275×116, ver [WinampWindow]): las de los skins incluidos vienen en
 * assets/skins/<archiveId>.png y las de los importados en los archivos de la app. Se guardan unas
 * pocas decodificadas, porque el visor las pide en cada cuadro.
 */
object WinampWindows {
    private var app: Context? = null
    private var assetNames: Set<String>? = null
    private val cache = object : LinkedHashMap<String, Bitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 6
    }

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    /** Ruta de la ventana del skin (archivo o asset), o null si no tiene. */
    fun sourceOf(skin: WinampSkin): String? {
        skin.windowFile?.let { return it }
        if (skin.group != WinampSkins.GROUP_CLASSIC) return null
        val names = assetNames ?: app?.let { c ->
            runCatching { c.assets.list("skins")?.toSet() }.getOrNull().orEmpty().also { assetNames = it }
        } ?: return null
        val file = "${skin.archiveId}.png"
        return if (file in names) "asset:skins/$file" else null
    }

    @Synchronized
    fun bitmap(skin: WinampSkin): Bitmap? {
        val src = sourceOf(skin) ?: return null
        if (cache.containsKey(src)) return cache[src]
        val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = runCatching {
            if (src.startsWith("asset:")) app?.assets?.open(src.removePrefix("asset:"))?.use { BitmapFactory.decodeStream(it, null, opts) }
            else BitmapFactory.decodeFile(src, opts)
        // Atlas con las piezas en vivo, o la ventana sola de los importados antes de la 2.8
        }.getOrNull()?.takeIf { it.width == WinampWindow.WIDTH && (it.height == WinampWindow.ATLAS_HEIGHT || it.height == WinampWindow.HEIGHT) }
        cache[src] = bmp
        return bmp
    }
}
