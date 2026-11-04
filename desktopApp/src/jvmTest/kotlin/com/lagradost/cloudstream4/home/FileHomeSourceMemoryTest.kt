package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream4.feature.home.HomeSourceId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileHomeSourceMemoryTest {
    private val directory: File = Files.createTempDirectory("home-source").toFile()
    private val file = File(directory, "nested/home-source")

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    private fun waitForFile(expected: String) {
        repeat(200) {
            if (file.exists() && file.readText() == expected) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `nothing until someone chooses, and reading writes nothing`() {
        assertNull(FileHomeSourceMemory(file).last())
        assertFalse(file.exists())
    }

    @Test
    fun `a choice is there at the next launch, in whatever script its name is`() {
        FileHomeSourceMemory(file).remember(HomeSourceId("CineTmdb हिंदी"))
        waitForFile("CineTmdb हिंदी")
        assertEquals(HomeSourceId("CineTmdb हिंदी"), FileHomeSourceMemory(file).last())
        assertEquals(listOf("home-source"), file.parentFile.list()?.toList(), "no temporary file left")
    }

    @Test
    fun `the latest choice counts at once, before the file is written`() {
        val memory = FileHomeSourceMemory(file)
        memory.remember(HomeSourceId("A"))
        memory.remember(HomeSourceId("B"))
        assertEquals(HomeSourceId("B"), memory.last())
        waitForFile("B")
    }

    @Test
    fun `a name that could not be one is not kept, and neither is one found in the file`() {
        val memory = FileHomeSourceMemory(file)
        memory.remember(HomeSourceId("a\nb"))
        memory.remember(HomeSourceId("x".repeat(257)))
        assertNull(memory.last())
        file.parentFile.mkdirs()
        file.writeText("a\u0000b")
        assertNull(FileHomeSourceMemory(file).last())
        file.writeText("x".repeat(4097))
        assertNull(FileHomeSourceMemory(file).last())
    }
}
