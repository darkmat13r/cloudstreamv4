package com.lagradost.cloudstream4.player

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerVolumeStoreTest {
    private val directory: File = Files.createTempDirectory("player-volume").toFile()
    private val file = File(directory, "nested/player-volume")
    private val writes = AtomicInteger()
    private val store = FilePlayerVolumeStore(file, writeDelayMillis = 40L, onWritten = { writes.incrementAndGet() })

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    private fun waitForFile(expected: String) {
        repeat(300) {
            if (file.exists() && file.readText() == expected) return
            Thread.sleep(10)
        }
    }

    private fun garbage(text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `full and unmuted until someone chooses, and reading writes nothing`() {
        assertEquals(SavedVolume(100, false), store.read())
        assertFalse(file.exists())
    }

    @Test
    fun `a level is there at the next launch`() {
        store.remember(SavedVolume(35, false))
        waitForFile("35")
        assertEquals(SavedVolume(35, false), FilePlayerVolumeStore(file).read())
    }

    @Test
    fun `mute is there at the next launch, with the level it will come back to`() {
        store.remember(SavedVolume(35, true))
        waitForFile("35 muted")
        val volume = PlayerVolume(FilePlayerVolumeStore(file))
        assertEquals(0, volume.audible)
        volume.toggleMute()
        assertEquals(35, volume.audible)
    }

    @Test
    fun `anything else in the file reads as never having chosen`() {
        listOf("<script>", "101", "999", "-1", "50 loud", "muted", "", "9".repeat(17)).forEach { text ->
            garbage(text)
            assertEquals(SavedVolume(), store.read(), "for '$text'")
        }
    }

    @Test
    fun `a drag writes the last level and not every one`() {
        val volume = PlayerVolume(100, false, store)
        (100 downTo 1).forEach(volume::set)
        waitForFile("1")
        assertEquals("1", file.readText())
        assertTrue(writes.get() <= 2, "wrote ${writes.get()} times")
    }

    @Test
    fun `a change after the write is written too`() {
        store.remember(SavedVolume(10, false))
        waitForFile("10")
        store.remember(SavedVolume(20, false))
        waitForFile("20")
        assertEquals("20", file.readText())
    }

    @Test
    fun `a level that does not change writes nothing`() {
        val volume = PlayerVolume(50, false, store)
        volume.set(50)
        Thread.sleep(120)
        assertFalse(file.exists())
    }

    @Test
    fun `no temporary file is left behind`() {
        store.remember(SavedVolume(5, false))
        waitForFile("5")
        Thread.sleep(40)
        assertEquals(listOf("player-volume"), file.parentFile.list()?.toList())
    }
}
