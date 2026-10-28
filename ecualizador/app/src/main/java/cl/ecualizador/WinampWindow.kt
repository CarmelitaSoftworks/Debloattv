package cl.ecualizador

/**
 * Arma la ventana principal de un skin clásico (275×116) como la dibuja Winamp 2: MAIN.BMP de
 * fondo y encima la barra de título activa, los botones, la barra de posición, volumen, balance,
 * estéreo y los kbps/kHz con la fuente del skin (TEXT.BMP). Las posiciones son las del Winamp
 * original (las mismas que usa Webamp). Lo que falte en el skin se deja como está en MAIN.BMP.
 *
 * Lo que cambia con la música (título, reloj, reproducir/pausa y el indicador de posición) no va
 * en la ventana: debajo de ella, en el mismo «atlas» de 275×[ATLAS_HEIGHT], van esas piezas del
 * skin para que el visor las dibuje en cada cuadro (ver SpectrumRenderer). El visor va en
 * [VIS_X], [VIS_Y] de [VIS_W]×[VIS_H].
 */
object WinampWindow {
    const val WIDTH = 275
    const val HEIGHT = 116
    const val VIS_X = 24
    const val VIS_Y = 43
    const val VIS_W = 76
    const val VIS_H = 16

    // Piezas debajo de la ventana: fuente (TEXT.BMP), dígitos (NUMBERS.BMP o NUMS_EX.BMP),
    // reproducir/pausa/detener (PLAYPAUS.BMP), indicador de posición (POSBAR.BMP) y los botones
    // presionados (segunda fila de CBUTTONS.BMP)
    const val TEXT_Y = 116
    const val NUMS_Y = TEXT_Y + 18
    const val PLAYPAUS_Y = NUMS_Y + 13
    const val THUMB_Y = PLAYPAUS_Y + 9
    const val PRESSED_Y = THUMB_Y + 10
    /** Los 28 fondos del volumen (68×13 cada uno, de 0 a 100 %), de a 4 por fila. */
    const val VOLUME_Y = PRESSED_Y + 18
    const val VOLUME_FRAMES = 28
    const val VOLUME_THUMB_Y = VOLUME_Y + 13 * 7
    const val ATLAS_HEIGHT = VOLUME_THUMB_Y + 11

    // Volumen en la ventana
    const val VOLUME_X = 107
    const val VOLUME_WY = 57
    const val VOLUME_W = 68
    const val VOLUME_THUMB_W = 14

    // Dónde van en la ventana
    const val TITLE_X = 111
    const val TITLE_Y = 27
    const val TITLE_W = 154
    val CLOCK_X = intArrayOf(48, 60, 78, 90)
    const val CLOCK_Y = 26
    const val PLAYPAUS_X = 26
    const val PLAYPAUS_WY = 28
    const val POSBAR_X = 16
    const val POSBAR_WY = 72
    const val POSBAR_W = 248
    const val THUMB_W = 29

    /**
     * Botones de la ventana: posición en la ventana y del botón presionado en CBUTTONS.BMP (misma
     * columna, segunda fila).
     */
    enum class Button(val x: Int, val y: Int, val w: Int, val h: Int, val sx: Int) {
        PREVIOUS(16, 88, 23, 18, 0),
        PLAY(39, 88, 23, 18, 23),
        PAUSE(62, 88, 23, 18, 46),
        STOP(85, 88, 23, 18, 69),
        NEXT(108, 88, 22, 18, 92),
    }

    /** Lo que se toca en el skin. */
    sealed interface Hit {
        data class Press(val button: Button) : Hit
        /** Barra de posición: ir a esa fracción de la canción. */
        data class Seek(val fraction: Float) : Hit
        /** La pantalla (reloj o visor): reproducir/pausa. */
        data object TogglePlay : Hit
        /** Volumen de la música a esa fracción. */
        data class Volume(val fraction: Float) : Hit
        /** Expulsar o PL: abre la app de música que suena. */
        data object OpenPlayer : Hit
        /** EQ: abre el ecualizador. */
        data object OpenEqualizer : Hit
    }

    /** Qué hay en el punto ([x], [y]) de la ventana (en píxeles del skin), dentro de la parte visible. */
    fun hit(x: Float, y: Float, part: Part): Hit? {
        if (x < part.x || y < part.y || x >= part.x + part.w || y >= part.y + part.h) return null
        fun inside(rx: Int, ry: Int, rw: Int, rh: Int) = x >= rx && x < rx + rw && y >= ry && y < ry + rh
        Button.entries.firstOrNull { inside(it.x, it.y, it.w, it.h) }?.let { return Hit.Press(it) }
        if (inside(136, 89, 22, 16) || inside(242, 58, 23, 12)) return Hit.OpenPlayer
        if (inside(219, 58, 23, 12)) return Hit.OpenEqualizer
        if (inside(VOLUME_X, VOLUME_WY - 2, VOLUME_W, 17)) {
            return Hit.Volume(((x - VOLUME_X - VOLUME_THUMB_W / 2f) / (VOLUME_W - VOLUME_THUMB_W)).coerceIn(0f, 1f))
        }
        // Barra de posición, con un poco de margen arriba y abajo para el dedo
        if (x >= POSBAR_X && x < POSBAR_X + POSBAR_W && y >= POSBAR_WY - 3 && y < POSBAR_WY + 13) {
            return Hit.Seek(((x - POSBAR_X - THUMB_W / 2f) / (POSBAR_W - THUMB_W)).coerceIn(0f, 1f))
        }
        // La pantalla del skin (reloj y visor)
        if (x >= 9 && x < 107 && y >= 22 && y < 64) return Hit.TogglePlay
        return null
    }

    /** Qué parte del skin se muestra. */
    enum class Part(val x: Int, val y: Int, val w: Int, val h: Int) {
        /** La ventana entera del reproductor. */
        FULL(0, 0, WIDTH, HEIGHT),
        /** Solo la pantalla: reloj, reproducir/pausa y el visor, con el marco del skin alrededor. */
        DISPLAY(5, 17, 104, 48),
    }

    fun compose(archive: SkinArchive, decoder: SkinImport.Decoder): IntArray? {
        fun img(name: String) = archive.byName(name)?.let { runCatching { decoder.decode(it) }.getOrNull() }
        val main = img("main.bmp")?.takeIf { it.width >= WIDTH && it.height >= HEIGHT } ?: return null
        // Ventana opaca arriba; las piezas de abajo quedan transparentes donde el skin no las trae
        val out = IntArray(WIDTH * ATLAS_HEIGHT) { i -> if (i < WIDTH * HEIGHT) main[i % WIDTH, i / WIDTH] or (0xFF shl 24) else 0 }

        /** Copia un recorte de [src] a la ventana; si el recorte no existe en esa imagen, no hace nada. */
        fun blit(src: Pixels?, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
            src ?: return
            if (sx < 0 || sy < 0 || sx + w > src.width || sy + h > src.height) return
            for (y in 0 until h) for (x in 0 until w) {
                val tx = dx + x; val ty = dy + y
                if (tx in 0 until WIDTH && ty in 0 until ATLAS_HEIGHT) out[ty * WIDTH + tx] = src[sx + x, sy + y] or (0xFF shl 24)
            }
        }

        // Barra de título activa
        blit(img("titlebar.bmp"), 27, 0, 275, 14, 0, 0)
        // Botones: anterior, reproducir, pausa, detener, siguiente y expulsar
        img("cbuttons.bmp").let { b ->
            for (i in 0 until 4) blit(b, 23 * i, 0, 23, 18, 16 + 23 * i, 88)
            blit(b, 92, 0, 22, 18, 108, 88)
            blit(b, 114, 0, 22, 16, 136, 89)
        }
        // Barra de posición (el indicador va con las piezas, se mueve con la canción)
        img("posbar.bmp").let { p ->
            blit(p, 0, 0, POSBAR_W, 10, POSBAR_X, POSBAR_WY)
            blit(p, 248, 0, THUMB_W, 10, 0, THUMB_Y)
        }
        // Volumen (fondo al 75 %, por si no se dibuja en vivo) y sus 28 cuadros con el indicador como
        // piezas; balance centrado
        img("volume.bmp").let { v ->
            blit(v, 0, 15 * 20, 68, 13, VOLUME_X, VOLUME_WY)
            for (f in 0 until VOLUME_FRAMES) blit(v, 0, 15 * f, 68, 13, (f % 4) * 68, VOLUME_Y + (f / 4) * 13)
            blit(v, 15, 422, VOLUME_THUMB_W, 11, 0, VOLUME_THUMB_Y)
        }
        img("balance.bmp").let { b ->
            blit(b, 9, 0, 38, 13, 177, 57)
            blit(b, 15, 422, 14, 11, 177 + 12, 58)
        }
        // Repetir, aleatorio, EQ y lista
        img("shufrep.bmp").let { s ->
            blit(s, 0, 0, 28, 15, 210, 89)
            blit(s, 28, 0, 47, 15, 164, 89)
            blit(s, 0, 61, 23, 12, 219, 58)
            blit(s, 23, 61, 23, 12, 242, 58)
        }
        // Estéreo encendido, mono apagado
        img("monoster.bmp").let { m ->
            blit(m, 0, 0, 29, 12, 239, 41)
            blit(m, 29, 12, 27, 12, 212, 41)
        }
        // Piezas: botones presionados
        img("cbuttons.bmp").let { b -> blit(b, 0, 18, 114, 18, 0, PRESSED_Y) }
        // Piezas: reproducir, pausa y detener (9×9 cada una)
        blit(img("playpaus.bmp"), 0, 0, 27, 9, 0, PLAYPAUS_Y)
        // Dígitos 0–9 (NUMS_EX.BMP si lo trae, si no NUMBERS.BMP)
        (img("nums_ex.bmp") ?: img("numbers.bmp")).let { n -> blit(n, 0, 0, 90, 13, 0, NUMS_Y) }
        // Fuente completa, y los kbps/kHz fijos en la ventana
        img("text.bmp")?.let { t ->
            blit(t, 0, 0, minOf(t.width, 155), minOf(t.height, 18), 0, TEXT_Y)
            fun write(text: String, x: Int, y: Int) = text.forEachIndexed { i, c ->
                val (col, row) = glyph(c)
                blit(t, col * 5, row * 6, 5, 6, x + i * 5, y)
            }
            write("128", 111, 43)
            write("44", 156, 43)
        }
        return out
    }

    /** Columna y fila de [c] en TEXT.BMP (celdas de 5×6); lo que no está, como espacio. */
    fun glyph(c: Char): Pair<Int, Int> {
        val l = c.lowercaseChar()
        return when (l) {
            in 'a'..'z' -> (l - 'a') to 0
            in '0'..'9' -> (l - '0') to 1
            '"' -> 26 to 0
            '@' -> 27 to 0
            '.' -> 11 to 1
            ':' -> 12 to 1
            '(' -> 13 to 1
            ')' -> 14 to 1
            '-', '·' -> 15 to 1
            '\'' -> 16 to 1
            '!' -> 17 to 1
            '_' -> 18 to 1
            '+' -> 19 to 1
            '/' -> 21 to 1
            '&' -> 25 to 1
            '%' -> 26 to 1
            ',' -> 27 to 1
            '=' -> 28 to 1
            '?' -> 3 to 2
            '*' -> 4 to 2
            else -> 30 to 0
        }
    }
}
