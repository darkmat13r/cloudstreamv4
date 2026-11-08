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
        assertEquals("autoplay_next_key=true\ndownload_parallel_key=4\n", file.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertEquals(4, SettingsRepository(FileSettingsStorage(file)).downloadsAtOnce.value)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") }, "no temporary file is left behind")
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
