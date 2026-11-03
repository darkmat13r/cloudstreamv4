package com.lagradost.cloudstream4.player

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SubtitlePreferenceTest {
    private val directory: File = Files.createTempDirectory("subtitle-language").toFile()
    private val file = File(directory, "nested/subtitle-language")

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    /** Written on its own thread, so a read of the file has to wait for it. */
    private fun waitForFile(expected: String) {
        repeat(200) {
            if (file.exists() && file.readText() == expected) return
            Thread.sleep(10)
        }
    }

    private fun garbage(text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `English until someone chooses`() {
        assertEquals("en", FileSubtitlePreference(file).language())
        assertFalse(file.exists(), "reading writes nothing")
    }

    @Test
    fun `a choice is there at the next launch`() {
        FileSubtitlePreference(file).remember("pt-BR")
        waitForFile("pt-BR")
        assertEquals("pt-BR", FileSubtitlePreference(file).language())
    }

    @Test
    fun `Off is a choice too, and not the same as never having chosen`() {
        FileSubtitlePreference(file).remember(SubtitlePreference.NONE)
        waitForFile("")
        assertEquals(SubtitlePreference.NONE, FileSubtitlePreference(file).language())
    }

    @Test
    fun `the choice counts at once, before the file is written`() {
        val preference = FileSubtitlePreference(file)
        preference.remember("de")
        assertEquals("de", preference.language())
        waitForFile("de")
    }

    @Test
    fun `a tag that is not one is not kept`() {
        val preference = FileSubtitlePreference(file)
        preference.remember("../../etc\npasswd")
        preference.remember("x".repeat(36))
        assertEquals("en", preference.language())
    }

    @Test
    fun `anything else in the file reads as never having chosen`() {
        garbage("<script>")
        assertEquals("en", FileSubtitlePreference(file).language())
        garbage("e".repeat(65))
        assertEquals("en", FileSubtitlePreference(file).language())
    }

    @Test
    fun `no temporary file is left behind`() {
        FileSubtitlePreference(file).remember("fr")
        waitForFile("fr")
        assertEquals(listOf("subtitle-language"), file.parentFile.list()?.toList())
    }
}
