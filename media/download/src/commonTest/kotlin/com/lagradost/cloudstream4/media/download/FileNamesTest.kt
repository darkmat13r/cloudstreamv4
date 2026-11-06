package com.lagradost.cloudstream4.media.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNamesTest {
    private fun assertWellFormed(name: String) {
        assertTrue(name.isNotEmpty())
        assertTrue(name.none { it == '/' || it == '\\' || it == '\u0000' || it < ' ' })
        assertTrue(name != "." && name != "..")
        assertFalse(name.startsWith(".") || name.endsWith(".") || name.endsWith(" "))
        val bytes = name.encodeToByteArray()
        assertTrue(bytes.size <= 200, "too long: ${bytes.size}")
        assertEquals(name, bytes.decodeToString(throwOnInvalidSequence = true))
    }

    @Test
    fun `ordinary names are untouched`() {
        assertEquals("Movie (2024) [1080p].mkv", sanitizeFileName("Movie (2024) [1080p].mkv"))
        assertEquals("\u65e5\u672c\u8a9e\u306e\u30d5\u30a1\u30a4\u30eb.mp4", sanitizeFileName("\u65e5\u672c\u8a9e\u306e\u30d5\u30a1\u30a4\u30eb.mp4"))
        assertEquals("a.tar.gz", sanitizeFileName("a.tar.gz"))
    }

    @Test
    fun `path components are dropped for both separators`() {
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("x", sanitizeFileName("..\\..\\x"))
        assertEquals("x.mp4", sanitizeFileName("/abs/olute\\mixed/x.mp4"))
        assertEquals("download", sanitizeFileName("../"))
        assertEquals("download", sanitizeFileName("a/.."))
        assertEquals("b", sanitizeFileName("a/b/"))
    }

    @Test
    fun `dots and empty become the fallback`() {
        listOf("", ".", "..", "...", " ", " . ", "/", "\\", "\u0000").forEach {
            assertEquals("download", sanitizeFileName(it), it)
        }
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("ab.mp4", sanitizeFileName("a\u0000b.mp4"))
        assertEquals("evil.mp4", sanitizeFileName("ev\r\nil.mp4"))
        assertEquals("tabbed.mp4", sanitizeFileName("tab\tbed.mp4"))
        assertEquals("ab", sanitizeFileName("a\u007fb"))
        assertEquals("ab", sanitizeFileName("a\u0085b"))
    }

    @Test
    fun `bidi overrides and invisible characters are removed`() {
        assertEquals("fdp.exe", sanitizeFileName("\u202efdp.exe"))
        assertEquals("ab", sanitizeFileName("a\u200bb"))
        assertEquals("ab", sanitizeFileName("a\ufeffb"))
        assertEquals("ab", sanitizeFileName("a\u2066b\u2069"))
        assertEquals("ab", sanitizeFileName("a\u2028b"))
    }

    @Test
    fun `lone surrogates are dropped and pairs kept`() {
        assertEquals("ab", sanitizeFileName("a\uD800b"))
        assertEquals("ab", sanitizeFileName("a\uDC00b"))
        assertEquals("a\ud83c\udfacb", sanitizeFileName("a\ud83c\udfacb"))
    }

    @Test
    fun `characters that fat and windows refuse are replaced`() {
        assertEquals("a_b_c_d_e_f_g.mp4", sanitizeFileName("a<b>c:d\"e|f?g.mp4"))
        assertEquals("a_b.mp4", sanitizeFileName("a:b.mp4"))
        assertEquals("what_.mp4", sanitizeFileName("what?.mp4"))
    }

    @Test
    fun `leading dots and trailing dots and spaces are trimmed`() {
        assertEquals("hidden", sanitizeFileName(".hidden"))
        assertEquals("a.mp4", sanitizeFileName("...a.mp4"))
        assertEquals("a.mp4", sanitizeFileName("a.mp4. . "))
        assertEquals("a", sanitizeFileName("  a  "))
    }

    @Test
    fun `windows reserved stems are prefixed whatever the case or extension`() {
        assertEquals("_CON.mp4", sanitizeFileName("CON.mp4"))
        assertEquals("_con.mp4", sanitizeFileName("con.mp4"))
        assertEquals("_Nul", sanitizeFileName("Nul"))
        assertEquals("_COM1.tar.gz", sanitizeFileName("COM1.tar.gz"))
        assertEquals("_lpt9.txt", sanitizeFileName("lpt9.txt"))
        assertEquals("_AUX .mp4", sanitizeFileName("AUX .mp4"))
        assertEquals("CONSOLE.mp4", sanitizeFileName("CONSOLE.mp4"))
        assertEquals("COM10.mp4", sanitizeFileName("COM10.mp4"))
    }

    @Test
    fun `long ascii names keep the extension`() {
        val name = sanitizeFileName("a".repeat(500) + ".mp4")
        assertWellFormed(name)
        assertTrue(name.endsWith(".mp4"))
        assertEquals(200, name.encodeToByteArray().size)
    }

    @Test
    fun `long multibyte names stay valid utf8 and keep the extension`() {
        for (unit in listOf("\u00e9", "\u65e5", "\ud83c\udfac", "a\u65e5")) {
            val name = sanitizeFileName(unit.repeat(500) + ".mkv")
            assertWellFormed(name)
            assertTrue(name.endsWith(".mkv"), unit)
        }
    }

    @Test
    fun `a huge extension does not survive but the name is still bounded and valid`() {
        val name = sanitizeFileName("movie." + "x".repeat(400))
        assertWellFormed(name)
    }

    @Test
    fun `a reserved stem that grows past the cap is still capped`() {
        val name = sanitizeFileName("CON" + ".mp4".repeat(100))
        assertWellFormed(name)
    }

    @Test
    fun `sanitising is idempotent`() {
        listOf("../../etc/passwd", "CON.mp4", "a".repeat(500) + ".mp4", "\u65e5".repeat(300) + ".mkv", "\u202eevil.exe", ".hidden.")
            .forEach { val once = sanitizeFileName(it); assertEquals(once, sanitizeFileName(once), it) }
    }

    @Test
    fun `temp directory name is hidden and tagged`() {
        assertEquals(".movie.mp4.csdl", tempDirectoryName("movie.mp4"))
        assertTrue(tempDirectoryName(sanitizeFileName("a".repeat(500))).encodeToByteArray().size < 255)
    }
}
