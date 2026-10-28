package cl.ecualizador

/** Colores de la interfaz de la app (ARGB). [light] indica fondo claro con texto oscuro. */
data class AppPalette(
    val bg: Int,
    val card: Int,
    val track: Int,
    val accent: Int,
    val ink: Int,
    val muted: Int,
    val warn: Int,
    val light: Boolean,
)

/**
 * Tema de la app a partir de un skin de Winamp, como hacían los temas de color de Winamp: la cara
 * de la ventana da el fondo y las tarjetas, y el acento sale del propio skin. Se ajustan los tonos
 * hasta que el texto se lea bien (contraste WCAG ≥ 7 en tarjetas y ≥ 3 para el acento).
 */
object AppTheme {
    val DEFAULT = AppPalette(
        bg = 0xFF0F1115.toInt(), card = 0xFF1A1D24.toInt(), track = 0xFF2A2F3A.toInt(), accent = 0xFFB388FF.toInt(),
        ink = 0xFFFFFFFF.toInt(), muted = 0xFF888888.toInt(), warn = 0xFFFFCA28.toInt(), light = false,
    )

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Luminancia relativa WCAG (0 negro … 1 blanco). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Lleva [c] hacia [target] lo justo para cumplir [ok]. */
    private fun toward(c: Int, target: Int, ok: (Int) -> Boolean): Int {
        var t = 0f
        while (t <= 1f) {
            val m = WinampColors.mix(c, target, t)
            if (ok(m)) return m
            t += 0.05f
        }
        return target
    }

    fun from(skin: WinampSkin): AppPalette {
        val ch = skin.chrome
        val face = ch[WinampChrome.FACE]
        val light = luminance(face) > 0.4
        val ink = if (light) 0xFF15161A.toInt() else 0xFFF2F3F5.toInt()
        val card = toward(face, if (light) WHITE else BLACK) { contrast(ink, it) >= 7.0 }
        val bg = WinampColors.mix(card, BLACK, if (light) 0.07f else 0.35f)
        val track = WinampColors.mix(card, ink, 0.16f)
        val candidates = listOf(
            ch[WinampChrome.ACCENT], ch[WinampChrome.LIST_CURRENT], ch[WinampChrome.TEXT], skin.colors[2],
            skin.colors[WinampColors.PEAK], ch[WinampChrome.LIST_TEXT], skin.colors[17],
        )
        // Primero un color con tono propio (no gris) que contraste; si no hay, el más cercano ajustado.
        fun colorful(c: Int): Boolean {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return maxOf(r, g, b) - minOf(r, g, b) > 40
        }
        val accent = candidates.firstOrNull { colorful(it) && contrast(it, card) >= 3.0 }
            ?: candidates.firstOrNull { colorful(it) }?.let { c -> toward(c, ink) { contrast(it, card) >= 3.0 } }
            ?: candidates.firstOrNull { contrast(it, card) >= 3.0 }
            ?: toward(ch[WinampChrome.ACCENT], ink) { contrast(it, card) >= 3.0 }
        val warn = toward(if (light) 0xFFB26A00.toInt() else 0xFFFFCA28.toInt(), ink) { contrast(it, card) >= 4.5 }
        return AppPalette(
            bg = bg, card = card, track = track, accent = accent or (0xFF shl 24), ink = ink,
            muted = WinampColors.mix(ink, card, 0.42f), warn = warn, light = light,
        )
    }
}
