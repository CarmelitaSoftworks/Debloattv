package cl.ecualizador

/**
 * Arma la ventana principal de un skin clásico (275×116) como la dibuja Winamp 2: MAIN.BMP de
 * fondo y encima la barra de título activa, los botones, la barra de posición, volumen, balance,
 * estéreo, el reloj (NUMBERS.BMP) y el título y los kbps/kHz con la fuente del skin (TEXT.BMP).
 * Las posiciones son las del Winamp original (las mismas que usa Webamp). Lo que falte en el skin
 * se deja como está en MAIN.BMP. El visor se dibuja aparte, en [VIS_X], [VIS_Y] de [VIS_W]×[VIS_H].
 */
object WinampWindow {
    const val WIDTH = 275
    const val HEIGHT = 116
    const val VIS_X = 24
    const val VIS_Y = 43
    const val VIS_W = 76
    const val VIS_H = 16

    fun compose(archive: SkinArchive, decoder: SkinImport.Decoder, title: String): IntArray? {
        fun img(name: String) = archive.byName(name)?.let { runCatching { decoder.decode(it) }.getOrNull() }
        val main = img("main.bmp")?.takeIf { it.width >= WIDTH && it.height >= HEIGHT } ?: return null
        val out = IntArray(WIDTH * HEIGHT) { main[it % WIDTH, it / WIDTH] or (0xFF shl 24) }

        /** Copia un recorte de [src] a la ventana; si el recorte no existe en esa imagen, no hace nada. */
        fun blit(src: Pixels?, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
            src ?: return
            if (sx < 0 || sy < 0 || sx + w > src.width || sy + h > src.height) return
            for (y in 0 until h) for (x in 0 until w) {
                val tx = dx + x; val ty = dy + y
                if (tx in 0 until WIDTH && ty in 0 until HEIGHT) out[ty * WIDTH + tx] = src[sx + x, sy + y] or (0xFF shl 24)
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
        // Barra de posición con el indicador al inicio
        img("posbar.bmp").let { p ->
            blit(p, 0, 0, 248, 10, 16, 72)
            blit(p, 248, 0, 29, 10, 16, 72)
        }
        // Volumen (fondo al 75 %) y balance (centrado), con sus indicadores
        img("volume.bmp").let { v ->
            blit(v, 0, 15 * 20, 68, 13, 107, 57)
            blit(v, 15, 422, 14, 11, 107 + 40, 58)
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
        // Indicador de reproducción
        blit(img("playpaus.bmp"), 0, 0, 9, 9, 26, 28)
        // Reloj 00:00 (NUMS_EX.BMP si lo trae, si no NUMBERS.BMP)
        (img("nums_ex.bmp") ?: img("numbers.bmp")).let { n ->
            for ((x, d) in listOf(48 to 0, 60 to 0, 78 to 0, 90 to 0)) blit(n, 9 * d, 0, 9, 13, x, 26)
        }
        // Título, kbps y kHz con la fuente del skin
        img("text.bmp")?.let { t ->
            fun write(text: String, x: Int, y: Int, maxChars: Int) {
                text.take(maxChars).forEachIndexed { i, c ->
                    val (col, row) = glyph(c)
                    blit(t, col * 5, row * 6, 5, 6, x + i * 5, y)
                }
            }
            write(("1. $title").padEnd(31), 111, 27, 31)
            write("128", 111, 43, 3)
            write("44", 156, 43, 2)
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
