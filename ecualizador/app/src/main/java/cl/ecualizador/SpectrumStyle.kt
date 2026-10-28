package cl.ecualizador

/** Forma de dibujar el analizador. */
enum class SpectrumStyle(val label: String, val winamp: Boolean = false) {
    BARS("Barras"),
    BLOCKS("Bloques LED"),
    LINE("Línea"),
    MIRROR("Espejo"),
    DOTS("Puntos"),
    CIRCLE("Circular"),
    WAVE("Onda"),
    WINAMP_CLASSIC("Winamp clásico", true),
    WINAMP_FIRE("Winamp fuego", true),
    WINAMP_LINES("Winamp líneas", true),
    WINAMP_SCOPE("Winamp osciloscopio", true),
}

/**
 * Colores del analizador de Winamp 2 (VISCOLOR.TXT por defecto): índice 0 fondo, 1 puntos de la
 * grilla, 2 a 17 las filas del analizador de arriba (rojo) hacia abajo (verde), 18 a 22 el
 * osciloscopio y 23 los picos.
 */
object WinampColors {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    val VIS = intArrayOf(
        rgb(0, 0, 0), rgb(24, 33, 41),
        rgb(239, 49, 16), rgb(206, 41, 16), rgb(214, 90, 0), rgb(214, 102, 0), rgb(214, 115, 0), rgb(198, 123, 8),
        rgb(222, 165, 24), rgb(214, 181, 33), rgb(189, 222, 41), rgb(148, 222, 33), rgb(41, 206, 16), rgb(50, 190, 16),
        rgb(57, 181, 16), rgb(49, 156, 8), rgb(41, 148, 0), rgb(24, 132, 8),
        rgb(255, 255, 255), rgb(214, 214, 222), rgb(181, 189, 189), rgb(160, 170, 175), rgb(148, 156, 165),
        rgb(150, 150, 150),
    )
    const val HEIGHT = 16
    const val PEAK = 23

    /**
     * Color de la fila [row] (0 = abajo) de una barra de [barHeight] píxeles.
     * Normal: el color depende de la altura absoluta. Fuego: de la distancia a la punta.
     */
    fun barColorIndex(row: Int, barHeight: Int, fire: Boolean): Int =
        if (fire) (2 + (barHeight - 1 - row)).coerceIn(2, 17) else (17 - row).coerceIn(2, 17)

    /** Color del osciloscopio según la distancia al centro (más lejos, más tenue). */
    fun scopeColorIndex(distanceFromCenter: Int): Int = (18 + distanceFromCenter / 2).coerceIn(18, 22)

    /** Mezcla [a] con [b] en proporción [t] (0 = a, 1 = b). Resultado opaco. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}

/**
 * Colores de la ventana de un skin ([WinampSkin.chrome]): cara y marco de la ventana principal, color
 * de acento, letra y fondo de la pantalla, y los colores de la lista de reproducción.
 */
object WinampChrome {
    const val FACE = 0
    const val FRAME = 1
    const val ACCENT = 2
    const val TEXT = 3
    const val TEXT_BG = 4
    const val LIST_TEXT = 5
    const val LIST_CURRENT = 6
    const val LIST_BG = 7

    /**
     * Skin base de Winamp (MAIN.BMP dentro de winamp.exe 5.9.2): ventana azul noche, marco lavanda,
     * rayo dorado, letras verdes y lista verde sobre negro.
     */
    val DEFAULT = intArrayOf(
        0xFF252439.toInt(), 0xFF6F6F83.toInt(), 0xFFA78E56.toInt(), 0xFF00E200.toInt(),
        0xFF000000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
    )
}

/** Cómo se pintan las barras de los estilos Winamp con los colores del skin. */
enum class WinampPattern(val label: String) {
    /** Como el original: el color depende de la altura (o de la punta, en «fuego»). */
    ROWS("Filas"),
    /** Opción «Line» de Winamp: toda la barra del color de su punta. */
    SOLID("Línea"),
    /** Cada banda con una fila de la paleta: graves abajo de la escala, agudos arriba. */
    BANDS("Bandas"),
    /** Filas separadas, como una pantalla LCD. */
    LCD("LCD"),
    /** Píxeles alternados con el fondo, como los gráficos de 16 colores. */
    DITHER("Trama"),
    /** Degradado de la cara de la ventana al color de acento del skin. */
    CHROME("Chasis"),
    /** Colores de la lista de reproducción del skin. */
    PLAYLIST("Lista");

    /**
     * Algunos skins alternan filas del color del fondo (un efecto de rayas que solo funciona en
     * «Filas»): si la fila [index] no se distingue del fondo, usa la más cercana que sí.
     */
    private fun visible(vis: IntArray, index: Int): Int {
        fun far(i: Int): Boolean {
            val a = vis[i]; val b = vis[0]
            fun d(sh: Int) = kotlin.math.abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF))
            return d(16) + d(8) + d(0) >= 60
        }
        for (k in 0..15) {
            for (i in intArrayOf(index - k, index + k)) if (i in 2..17 && far(i)) return i
        }
        return index
    }

    /** Fondo del analizador (`null`: el fondo y la grilla del skin). */
    fun background(chrome: IntArray): Int? = if (this == PLAYLIST) chrome[WinampChrome.LIST_BG] else null

    /** Color del pico. */
    fun peak(vis: IntArray, chrome: IntArray): Int = when (this) {
        PLAYLIST -> chrome[WinampChrome.LIST_CURRENT]
        CHROME -> chrome[WinampChrome.TEXT]
        else -> vis[WinampColors.PEAK]
    }

    /**
     * Color del píxel en la fila [row] (0 = abajo) y columna [x] de la banda [band] de [bands], con
     * la barra de [barHeight] píxeles. 0 deja ver el fondo.
     */
    fun pixel(vis: IntArray, chrome: IntArray, band: Int, bands: Int, row: Int, barHeight: Int, fire: Boolean, x: Int): Int {
        val rows = vis[WinampColors.barColorIndex(row, barHeight, fire)]
        return when (this) {
            ROWS -> rows
            SOLID -> vis[visible(vis, WinampColors.barColorIndex(barHeight - 1, barHeight, fire))]
            BANDS -> vis[visible(vis, (17 - band * 16 / bands.coerceAtLeast(1)).coerceIn(2, 17))]
            LCD -> if (row % 2 == 1) 0 else rows
            DITHER -> if ((x + row) % 2 == 1) WinampColors.mix(rows, vis[0], 0.55f) else rows
            CHROME -> WinampColors.mix(
                chrome[WinampChrome.FACE], chrome[WinampChrome.ACCENT],
                0.35f + 0.65f * row / (WinampColors.HEIGHT - 1)
            )
            PLAYLIST -> if (row == barHeight - 1) chrome[WinampChrome.LIST_CURRENT] else chrome[WinampChrome.LIST_TEXT]
        }
    }
}

/** Paleta de colores: paradas de 0 (silencio) a 1 (máximo), en ARGB. */
enum class SpectrumPalette(val label: String, val stops: List<Pair<Float, Int>>) {
    VIOLET("Violeta", listOf(0f to 0xFF1E3A8A.toInt(), 0.5f to 0xFFB388FF.toInt(), 0.8f to 0xFFFF7043.toInt(), 1f to 0xFFFFEB3B.toInt())),
    FIRE("Fuego", listOf(0f to 0xFF3E0A00.toInt(), 0.4f to 0xFFD84315.toInt(), 0.75f to 0xFFFFA000.toInt(), 1f to 0xFFFFF59D.toInt())),
    OCEAN("Océano", listOf(0f to 0xFF002B36.toInt(), 0.45f to 0xFF0288D1.toInt(), 0.8f to 0xFF26C6DA.toInt(), 1f to 0xFFE0F7FA.toInt())),
    NEON("Neón", listOf(0f to 0xFF00E5FF.toInt(), 0.35f to 0xFF00E676.toInt(), 0.7f to 0xFFFFEA00.toInt(), 1f to 0xFFFF1744.toInt())),
    MATRIX("Verde clásico", listOf(0f to 0xFF003300.toInt(), 0.6f to 0xFF00C853.toInt(), 1f to 0xFFB9F6CA.toInt())),
    MONO("Blanco", listOf(0f to 0xFF424242.toInt(), 1f to 0xFFFFFFFF.toInt())),
    WINAMP("Winamp", listOf(0f to 0xFF188408.toInt(), 0.45f to 0xFF29CE10.toInt(), 0.6f to 0xFFBDDE29.toInt(), 0.75f to 0xFFDEA518.toInt(), 0.88f to 0xFFD65A00.toInt(), 1f to 0xFFEF3110.toInt())),
    RAINBOW("Arcoíris", listOf(0f to 0xFF7C4DFF.toInt(), 0.2f to 0xFF2979FF.toInt(), 0.4f to 0xFF00E676.toInt(), 0.6f to 0xFFFFEA00.toInt(), 0.8f to 0xFFFF9100.toInt(), 1f to 0xFFFF1744.toInt())),
    SUNSET("Atardecer", listOf(0f to 0xFF2D1B69.toInt(), 0.4f to 0xFFC2185B.toInt(), 0.75f to 0xFFFF7043.toInt(), 1f to 0xFFFFD54F.toInt())),
    SYNTHWAVE("Synthwave", listOf(0f to 0xFF2B0B4A.toInt(), 0.35f to 0xFF7B1FA2.toInt(), 0.7f to 0xFFFF2E97.toInt(), 1f to 0xFF00E5FF.toInt())),
    AURORA("Aurora", listOf(0f to 0xFF0B1E3B.toInt(), 0.4f to 0xFF00897B.toInt(), 0.75f to 0xFF69F0AE.toInt(), 1f to 0xFFE040FB.toInt())),
    ICE("Hielo", listOf(0f to 0xFF0D2137.toInt(), 0.5f to 0xFF4FC3F7.toInt(), 1f to 0xFFFFFFFF.toInt())),
    GOLD("Oro", listOf(0f to 0xFF3E2723.toInt(), 0.5f to 0xFFB8860B.toInt(), 0.85f to 0xFFFFD54F.toInt(), 1f to 0xFFFFF8E1.toInt())),
    LAVA("Lava", listOf(0f to 0xFF1A0000.toInt(), 0.45f to 0xFFB71C1C.toInt(), 0.8f to 0xFFFF6F00.toInt(), 1f to 0xFFFFE082.toInt())),
    PINK("Rosa", listOf(0f to 0xFF4A148C.toInt(), 0.5f to 0xFFEC407A.toInt(), 1f to 0xFFFCE4EC.toInt())),
    CYBERPUNK("Cyberpunk", listOf(0f to 0xFF00E5FF.toInt(), 0.5f to 0xFFFF00E5.toInt(), 1f to 0xFFFFF000.toInt())),
    ;

    /** Color interpolado para un nivel 0..1. */
    fun color(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        val hi = stops.indexOfFirst { it.first >= x }.let { if (it <= 0) 1 else it }
        val (p0, c0) = stops[hi - 1]
        val (p1, c1) = stops[hi]
        val t = if (p1 == p0) 0f else ((x - p0) / (p1 - p0)).coerceIn(0f, 1f)
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/** Velocidad con que bajan las barras. */
enum class FallSpeed(val label: String, val perSec: Float) { SLOW("Lenta", 0.8f), NORMAL("Normal", 1.6f), FAST("Rápida", 3.2f) }

/** Opciones del visor (app y widget). */
data class SpectrumSettings(
    val style: SpectrumStyle = SpectrumStyle.BARS,
    val palette: SpectrumPalette = SpectrumPalette.VIOLET,
    val bars: Int = 48,
    val sensitivity: Float = 1f,
    val fall: FallSpeed = FallSpeed.NORMAL,
    val peaks: Boolean = true,
    val grid: Boolean = true,
    val widgetFps: Int = 8,
    /** Skin de Winamp cuyos colores usan los estilos Winamp (ver [WinampSkins]). */
    val winampSkin: String = WinampSkins.DEFAULT.name,
    /** Cómo se pintan las barras de los estilos Winamp. */
    val winampPattern: WinampPattern = WinampPattern.ROWS,
    /** Dibuja alrededor del analizador la ventana del skin (cara, marco, acento y nombre). */
    val winampFrame: Boolean = true,
    /** Tiñe toda la app con los colores del skin de Winamp elegido (ver [AppTheme]). */
    val appTheme: Boolean = false,
    /** Bandas gruesas de «Winamp clásico» y «Winamp fuego» (el original tiene 19). */
    val winampBars: Int = WINAMP_BARS_ORIGINAL,
    /** Líneas finas de «Winamp líneas» (el original tiene 75). */
    val winampLines: Int = WINAMP_LINES_ORIGINAL,
) {
    /** Barras que se dibujan con el estilo elegido. */
    val effectiveBars: Int
        get() = when (style) {
            SpectrumStyle.WINAMP_CLASSIC, SpectrumStyle.WINAMP_FIRE -> winampBars
            SpectrumStyle.WINAMP_LINES -> winampLines
            else -> bars
        }

    /** Opciones de cantidad de barras para el estilo elegido (vacío si no se elige, como el osciloscopio). */
    val barOptions: List<Int>
        get() = when (style) {
            SpectrumStyle.WINAMP_CLASSIC, SpectrumStyle.WINAMP_FIRE -> WINAMP_BAR_OPTIONS
            SpectrumStyle.WINAMP_LINES -> WINAMP_LINE_OPTIONS
            SpectrumStyle.WINAMP_SCOPE, SpectrumStyle.WAVE -> emptyList()
            else -> BAR_OPTIONS
        }

    /** Cambia la cantidad de barras del estilo elegido. */
    fun withBars(n: Int): SpectrumSettings = when (style) {
        SpectrumStyle.WINAMP_CLASSIC, SpectrumStyle.WINAMP_FIRE -> copy(winampBars = n)
        SpectrumStyle.WINAMP_LINES -> copy(winampLines = n)
        else -> copy(bars = n)
    }

    companion object {
        const val WINAMP_BARS_ORIGINAL = 19
        const val WINAMP_LINES_ORIGINAL = 75
        // Hasta 96: es lo que entrega el analizador; con más, las barras se repetirían
        val WINAMP_BAR_OPTIONS = listOf(10, WINAMP_BARS_ORIGINAL, 28, 38, 48)
        val WINAMP_LINE_OPTIONS = listOf(38, WINAMP_LINES_ORIGINAL, 96)
        val BAR_OPTIONS = listOf(24, 32, 48, 64, 96)
        val FPS_OPTIONS = listOf(4, 8, 12)
        const val SOURCE_BARS = 96
    }
}

object SpectrumShape {
    /** Reparte [src] (p. ej. 96 barras analizadas) en [n] barras tomando el máximo de cada grupo. */
    fun resample(src: FloatArray, n: Int): FloatArray {
        if (n == src.size) return src.copyOf()
        return FloatArray(n) { i ->
            val a = i * src.size / n
            val b = maxOf(a + 1, (i + 1) * src.size / n)
            var m = 0f
            for (k in a until b) m = maxOf(m, src[k])
            m
        }
    }

    /** Sensibilidad: curva de ganancia que sube los niveles bajos sin saturar los altos. */
    fun applySensitivity(v: Float, sensitivity: Float): Float =
        (1f - Math.pow((1.0 - v.coerceIn(0f, 1f)), sensitivity.toDouble().coerceIn(0.3, 3.0)).toFloat()).coerceIn(0f, 1f)
}
