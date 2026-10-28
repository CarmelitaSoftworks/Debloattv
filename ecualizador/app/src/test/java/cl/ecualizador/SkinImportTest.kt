package cl.ecualizador

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinImportTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private fun c(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun zipBytes(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t); z.closeEntry() } }
    }.toByteArray()

    /** BMP de 24 bits sin compresión, de un solo color. */
    private fun bmp(w: Int, h: Int, color: Int): ByteArray {
        val row = (w * 3 + 3) / 4 * 4
        val size = 54 + row * h
        val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        b.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(row * h).putInt(2835).putInt(2835).putInt(0).putInt(0)
        repeat(h) {
            repeat(w) { b.put((color and 0xFF).toByte()).put(((color shr 8) and 0xFF).toByte()).put(((color shr 16) and 0xFF).toByte()) }
            repeat(row - w * 3) { b.put(0) }
        }
        return b.array()
    }


    @Test
    fun importsClassicSkinFromVisColorAndPlaylist() {
        val vis = (0 until 24).joinToString("\n") { "$it,${it * 2},${it * 3}, // fila $it" }
        val archive = SkinArchive.read(
            zip("MiSkin/VISCOLOR.TXT" to vis, "MiSkin/pledit.txt" to "[Text]\nNormal=#00FF00\nCurrent=#FFFFFF\nNormalBG=#000000\n").inputStream()
        )
        val skins = SkinImport.import(archive, "Mi skin", SkinImport.ANDROID_DECODER)
        assertEquals(1, skins.size)
        val s = skins[0]
        assertEquals("Mi skin", s.name)
        assertEquals(c(5, 10, 15), s.colors[5])
        assertEquals(c(0, 255, 0), s.chrome[WinampChrome.LIST_TEXT])
        assertEquals(c(255, 255, 255), s.chrome[WinampChrome.LIST_CURRENT])
    }

    /** Muchos .wsz no traen VISCOLOR.TXT: se importan con los colores originales del visor. */
    @Test
    fun classicWithoutVisColorUsesOriginalColors() {
        val face = c(200, 120, 40)
        val archive = SkinArchive.read(zipBytes("Main.bmp" to bmp(275, 116, face)).inputStream())
        val s = SkinImport.import(archive, "Sin vis", SkinImport.ANDROID_DECODER).single()
        assertArrayEquals(WinampColors.VIS, s.colors)
        assertEquals(face, s.chrome[WinampChrome.FACE])
    }

    /** La ventana se arma como en Winamp: MAIN.BMP de fondo y cada pieza en su posición original. */
    @Test
    fun windowIsComposedFromSkinParts() {
        val face = c(10, 20, 30); val buttons = c(200, 0, 0); val bar = c(0, 0, 200)
        val archive = SkinArchive.read(
            zipBytes(
                "main.bmp" to bmp(275, 116, face),
                "cbuttons.bmp" to bmp(136, 36, buttons),
                "titlebar.bmp" to bmp(344, 87, bar),
                "viscolor.txt" to "0,0,0".toByteArray(),
            ).inputStream()
        )
        val skin = SkinImport.import(archive, "Prueba", SkinImport.ANDROID_DECODER).single()
        val px = skin.windowPixels!!
        fun at(x: Int, y: Int) = px[y * WinampWindow.WIDTH + x]
        assertEquals(WinampWindow.WIDTH * WinampWindow.ATLAS_HEIGHT, px.size)
        assertEquals(bar, at(100, 5))          // barra de título
        assertEquals(buttons, at(20, 95))      // botón «anterior» en (16, 88)
        assertEquals(face, at(30, 50))         // MAIN.BMP donde no hay piezas (el visor)
        assertEquals(face, at(190, 95))        // sin SHUFREP.BMP («aleatorio» en 164..210) queda MAIN.BMP
        assertEquals(face, at(60, 30))         // el reloj no va en la ventana: se dibuja en vivo
        assertEquals(0, at(5, WinampWindow.NUMS_Y + 3)) // sin NUMBERS.BMP, la pieza queda transparente
    }

    @Test
    fun skinWithoutMainBitmapHasNoWindow() {
        val archive = SkinArchive.read(zip("viscolor.txt" to "1,2,3").inputStream())
        assertEquals(null, SkinImport.import(archive, "x", SkinImport.ANDROID_DECODER).single().windowPixels)
    }

    @Test
    fun textGlyphsFollowWinampFontLayout() {
        assertEquals(0 to 0, WinampWindow.glyph('A'))
        assertEquals(25 to 0, WinampWindow.glyph('z'))
        assertEquals(7 to 1, WinampWindow.glyph('7'))
        assertEquals(12 to 1, WinampWindow.glyph(':'))
        assertEquals(30 to 0, WinampWindow.glyph(' '))
    }

    /** Los modernos (.wal, con skin.xml) no se aceptan. */
    @Test
    fun modernSkinsAreRejected() {
        val archive = SkinArchive.read(zip("skin.xml" to "<WinampAbstractionLayer/>", "viscolor.txt" to "1,2,3").inputStream())
        assertTrue(SkinImport.isModern(archive))
        assertTrue(SkinImport.import(archive, "x", SkinImport.ANDROID_DECODER).isEmpty())
    }

    @Test
    fun zipWithoutSkinFilesGivesNothing() {
        val archive = SkinArchive.read(zip("leeme.txt" to "hola").inputStream())
        assertTrue(SkinImport.import(archive, "x", SkinImport.ANDROID_DECODER).isEmpty())
    }


    @Test
    fun shortVisColorIsCompletedWithOriginal() {
        val v = SkinImport.parseVisColors("1,2,3\n4,5,6\n")
        assertEquals(c(1, 2, 3), v[0])
        assertEquals(WinampColors.VIS[23], v[23])
    }


    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilesThatAreNotZip() {
        SkinArchive.read("esto no es un zip".byteInputStream())
    }

    @Test
    fun importedSkinsSurviveJson() {
        val s = WinampSkin("X", "importado", WinampColors.VIS, WinampChrome.DEFAULT, SkinImport.GROUP_IMPORTED)
        val back = WinampSkins.fromJson(WinampSkins.toJson(listOf(s))).single()
        assertEquals("X", back.name)
        assertArrayEquals(s.colors, back.colors)
        assertArrayEquals(s.chrome, back.chrome)
    }

    /** Con -DclassicWszDir (carpetas separadas por «:»): el importador da los mismos colores que el script que generó los clásicos. */
    @Test
    fun classicImportMatchesBuiltInCatalog() {
        val dirs = (System.getProperty("classicWszDir")?.takeIf { it.isNotEmpty() } ?: return).split(File.pathSeparator).map(::File)
        val files = dirs.flatMap { it.listFiles().orEmpty().toList() }
        var checked = 0
        WinampClassicSkins.ALL.forEach { skin ->
            val f = files.firstOrNull { it.nameWithoutExtension == skin.archiveId } ?: return@forEach
            val got = SkinImport.import(SkinArchive.read(f.inputStream()), skin.name, SkinImport.ANDROID_DECODER).single()
            assertArrayEquals("${skin.name}: analizador", skin.colors, got.colors)
            assertArrayEquals("${skin.name}: chasis", skin.chrome, got.chrome)
            checked++
        }
        assertEquals(WinampClassicSkins.ALL.size, checked)
    }


    /**
     * Con -DclassicWszDir, -DwinampExeBitmaps (BITMAP de winamp.exe extraídos) y -DwindowsOut: arma la
     * ventana de cada skin clásico incluido y la guarda como assets/skins/<archiveId>.png.
     */
    @Test
    fun generateClassicWindows() {
        val out = File(System.getProperty("windowsOut")?.takeIf { it.isNotEmpty() } ?: return).apply { mkdirs() }
        val dirs = (System.getProperty("classicWszDir")?.takeIf { it.isNotEmpty() } ?: return).split(File.pathSeparator).map(::File)
        val files = dirs.flatMap { it.listFiles().orEmpty().toList() }
        fun save(id: String, px: IntArray) {
            val bmp = android.graphics.Bitmap.createBitmap(px, WinampWindow.WIDTH, WinampWindow.ATLAS_HEIGHT, android.graphics.Bitmap.Config.ARGB_8888)
            File(out, "$id.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        var n = 0
        WinampClassicSkins.ALL.forEach { skin ->
            val f = files.firstOrNull { it.nameWithoutExtension == skin.archiveId } ?: return@forEach
            WinampWindow.compose(SkinArchive.read(f.inputStream()), SkinImport.ANDROID_DECODER)?.let { save(skin.archiveId, it); n++ }
        }
        // Skin base: los BITMAP de winamp.exe con su número de recurso
        System.getProperty("winampExeBitmaps")?.takeIf { it.isNotEmpty() }?.let { dir ->
            val ids = mapOf(
                126 to "main.bmp", 127 to "cbuttons.bmp", 128 to "monoster.bmp", 129 to "playpaus.bmp", 130 to "shufrep.bmp",
                132 to "numbers.bmp", 133 to "volume.bmp", 134 to "text.bmp", 135 to "posbar.bmp", 150 to "titlebar.bmp", 197 to "balance.bmp",
            )
            // «<id>.png» reemplaza al .bmp si el recurso venía dañado (p. ej. el RLE de la fuente, 134)
            val archive = SkinArchive(ids.mapNotNull { (id, name) ->
                (File(dir, "$id.png").takeIf { it.exists() } ?: File(dir, "$id.bmp").takeIf { it.exists() })?.let { name to it.readBytes() }
            }.toMap())
            WinampWindow.compose(archive, SkinImport.ANDROID_DECODER)?.let { save(WinampSkins.DEFAULT.archiveId, it); n++ }
        }
        println("Ventanas generadas: $n")
        assertTrue(n > 0)
    }
}
