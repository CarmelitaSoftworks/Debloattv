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
    fun gammaMatchesWinampFormula() {
        val white = c(255, 255, 255)
        assertEquals(c(127, 255, 255), SkinImport.Gamma(-2048, 0, 0, 0, 0).apply(white))
        // Gris por promedio y luego el tinte.
        assertEquals(c(100, 100, 100), SkinImport.Gamma(0, 0, 0, 2, 0).apply(c(50, 100, 150)))
        assertEquals(c(150, 150, 150), SkinImport.Gamma(0, 0, 0, 1, 0).apply(c(50, 100, 150)))
        // Realce 1: suma 128 antes de multiplicar.
        assertEquals(c(255, 255, 255), SkinImport.Gamma(4095, 4095, 4095, 0, 1).apply(c(0, 0, 0)))
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

    @Test
    fun zipWithoutSkinFilesGivesNothing() {
        val archive = SkinArchive.read(zip("leeme.txt" to "hola").inputStream())
        assertTrue(SkinImport.import(archive, "x", SkinImport.ANDROID_DECODER).isEmpty())
    }

    @Test
    fun unclosedColorThemeIsKept() {
        val archive = SkinArchive.read(
            zip(
                "skin.xml" to "<include file=\"c.xml\"/>",
                "c.xml" to """<gammaset id="Uno"><gammagroup id="V" value="0,0,0"/>
                    <gammaset id="Dos"><gammagroup id="V" value="-4096,0,0"/>
                    <vis gammagroup="V" colorband16="200,200,200"/>""",
            ).inputStream()
        )
        val skins = SkinImport.import(archive, "Roto", SkinImport.ANDROID_DECODER)
        assertEquals(listOf("Roto · Uno", "Roto · Dos"), skins.map { it.name })
        assertEquals(c(0, 200, 200), skins[1].colors[2])
    }

    @Test
    fun shortVisColorIsCompletedWithOriginal() {
        val v = SkinImport.parseVisColors("1,2,3\n4,5,6\n")
        assertEquals(c(1, 2, 3), v[0])
        assertEquals(WinampColors.VIS[23], v[23])
    }

    @Test
    fun importsEveryColorThemeOfModernSkin() {
        val archive = SkinArchive.read(
            zip(
                "skin.xml" to "<WinampAbstractionLayer><skininfo><name>Prueba</name></skininfo><include file=\"xml/colors.xml\"/>" +
                    "<include file=\"xml/player.xml\"/></WinampAbstractionLayer>",
                "xml/colors.xml" to """
                    <gammaset id="*Default"><gammagroup id="DisplayVis" value="0,0,0"/></gammaset>
                    <gammaset id="Rojo | Fuerte"><gammagroup id="DisplayVis" value="0,-4096,-4096"/><gammagroup id="ListText" value="0,-4096,-4096"/></gammaset>
                    <color id="color.text" value="200,200,200"/>
                    <color id="wasabi.list.text" value="color.text" gammagroup="ListText"/>
                    <color id="wasabi.list.background" value="10,10,30"/>
                """.trimIndent(),
                "xml/player.xml" to """<!-- <vis colorband1="1,1,1"/> comentario --><vis id="v" gammagroup="DisplayVis" ColorBand1="100,100,100" ColorBand16="255,255,255" colorbandpeak="255,255,255"/>""",
            ).inputStream()
        )
        val skins = SkinImport.import(archive, "archivo", SkinImport.ANDROID_DECODER)
        assertEquals(listOf("Prueba · Default", "Prueba · Rojo · Fuerte"), skins.map { it.name })
        val (normal, red) = skins
        assertEquals(c(255, 255, 255), normal.colors[2])
        assertEquals(c(100, 100, 100), normal.colors[17])
        assertEquals(c(255, 0, 0), red.colors[2])
        assertEquals(c(255, 0, 0), red.colors[23])
        assertEquals(c(200, 0, 0), red.chrome[WinampChrome.LIST_TEXT])
        assertEquals(c(200, 200, 200), normal.chrome[WinampChrome.LIST_TEXT])
        assertEquals(c(10, 10, 30), normal.colors[0])
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

    /** Con -DwinampModernDir y -DmodernSkinsOut: genera WinampModernSkins.kt con los temas de Winamp 5.9. */
    @Test
    fun generateModernThemes() {
        val dir = File(System.getProperty("winampModernDir")?.takeIf { it.isNotEmpty() } ?: return)
        val out = File(System.getProperty("modernSkinsOut")?.takeIf { it.isNotEmpty() } ?: return)
        fun folder(d: File) = SkinArchive(d.walkTopDown().filter { it.isFile }.associate { it.relativeTo(d).path.replace('\\', '/').lowercase() to it.readBytes() })
        val skins = listOf("Winamp Modern" to "Winamp Modern", "Big Bento" to "Bento").flatMap { (folderName, group) ->
            SkinImport.import(folder(File(dir, folderName)), group, SkinImport.ANDROID_DECODER, group)
                .map { it.copy(name = it.name.replace("Big Bento", "Bento").replace("Winamp5 Base Skin", "Winamp Modern"), archiveId = "winamp-5.9.2/$folderName") }
        }
        fun hex(a: IntArray) = a.joinToString(", ") { "0x%06X".format(it and 0xFFFFFF) }
        val sb = StringBuilder()
        sb.append("package cl.ecualizador\n\n")
        sb.append("/**\n * Temas de color de los skins modernos que trae Winamp 5.9.2 (Winamp Modern y Bento), convertidos\n")
        sb.append(" * con [SkinImport] desde el instalador oficial: solo colores, sin imágenes del skin. Generado por\n")
        sb.append(" * SkinImportTest.generateModernThemes; no editar a mano.\n */\n")
        sb.append("object WinampModernSkins {\n    private fun rgb(vararg c: Int) = IntArray(c.size) { 0xFF000000.toInt() or c[it] }\n\n")
        sb.append("    val ALL: List<WinampSkin> = listOf(\n")
        skins.forEach { s ->
            sb.append("        WinampSkin(\n            \"${s.name}\", \"${s.archiveId}\",\n")
            sb.append("            rgb(${hex(s.colors)}),\n            rgb(${hex(s.chrome)}),\n            \"${s.group}\",\n        ),\n")
        }
        sb.append("    )\n}\n")
        out.writeText(sb.toString())
        println("Temas generados: ${skins.size}")
    }
}
