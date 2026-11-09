package com.lagradost.cloudstream4.settings

import com.lagradost.cloudstream4.feature.settings.SettingsRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSettingsStorageTest {
    private val directory: File = Files.createTempDirectory("settings-test").toFile()
    private val file = File(directory, FileSettingsStorage.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun aChangeIsWrittenOwnerOnlyAndReadBackAfterARestart() {
        SettingsRepository(FileSettingsStorage(file)).setDownloadsAtOnce(4)
        val written = "autoplay_next_key=true\ndownload_parallel_key=4\nposter_size_desktop_key=medium\nrandom_button_key=true\n"
        assertEquals(written, file.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertEquals(4, SettingsRepository(FileSettingsStorage(file)).downloadsAtOnce.value)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") }, "no temporary file is left behind")
    }

    @Test
    fun aDirectoryItCreatesIsOwnerOnlyAndOneThatExistsIsLeftAsItIs() {
        val made = File(File(directory, "made"), FileSettingsStorage.FILE_NAME)
        FileSettingsStorage(made).save(mapOf("autoplay_next_key" to "true"))
        val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(made.parentFile.toPath()))
        assertEquals("rwx------", mode)

        val before = Files.getPosixFilePermissions(directory.toPath())
        FileSettingsStorage(file).save(mapOf("autoplay_next_key" to "true"))
        assertEquals(before, Files.getPosixFilePermissions(directory.toPath()))
    }

    @Test
    fun aMissingFileReadsAsEveryDefault() {
        assertEquals(emptyMap(), FileSettingsStorage(file).load())
    }

    @Test
    fun aFileTooLargeToBeOursReadsAsEmpty() {
        file.writeText("autoplay_next_key=false\n" + "x".repeat(FileSettingsStorage.MAX_FILE_BYTES.toInt()))
        assertEquals(emptyMap(), FileSettingsStorage(file).load())
    }

    @Test
    fun aFileAtExactlyTheCapIsReadAndBytesThatAreNotTextSpoilOnlyTheirLine() {
        val notText = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), '\n'.code.toByte())
        val head = "autoplay_next_key=false\n".toByteArray() + notText
        val tail = "download_parallel_key=2\n".toByteArray()
        val pad = ByteArray(FileSettingsStorage.MAX_FILE_BYTES.toInt() - head.size - tail.size) { '\n'.code.toByte() }
        file.writeBytes(head + pad + tail)
        assertEquals(FileSettingsStorage.MAX_FILE_BYTES, file.length())
        val read = FileSettingsStorage(file).load()
        assertEquals(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "2"), read)
    }

    @Test
    fun linesThatAreNotSettingsAreSkipped() {
        val read = FileSettingsStorage.parse(
            "# a comment\nautoplay_next_key = false\nnot a line\n=x\nBAD_KEY=1\n" +
                "download_parallel_key=2\ndownload_parallel_key=5\n${"k".repeat(65)}=1\n",
        )
        assertEquals(mapOf("autoplay_next_key" to "false", "download_parallel_key" to "5"), read)
    }

    @Test
    fun aValueThatWouldBreakTheFileIsNeverWritten() {
        assertFailsWith<IllegalArgumentException> { FileSettingsStorage.format(mapOf("autoplay_next_key" to "true\nx=1")) }
        assertFailsWith<IllegalArgumentException> { FileSettingsStorage.format(mapOf("Key With Space" to "1")) }
        assertTrue(FileSettingsStorage.format(mapOf("b" to "2", "a" to "1")) == "a=1\nb=2\n", "sorted, one per line")
    }
}
