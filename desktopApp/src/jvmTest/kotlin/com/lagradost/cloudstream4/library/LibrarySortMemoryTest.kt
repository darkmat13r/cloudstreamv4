package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `KMP-57`: what the desktop says its one source can sort by, and how the choice is kept between launches. */
class LibrarySortMemoryTest {

    private val directory = createTempDirectory("library-sort").toFile()
    private val file = File(directory, "library-sort")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `the one source is the local list, sorting by what the store keeps`() = runBlocking {
        val store = InMemoryLibraryStore(
            listOf(
                LibraryTitle(LibraryTitleId("a"), "A", LibraryBucket.Watching, keptAt = 1L),
                LibraryTitle(LibraryTitleId("b"), "B", null, keptAt = 2L),
            ),
            emptyList(),
        )
        val sources = DesktopLibrarySources(store)
        val only = sources.sources().single()
        assertTrue(only.local)
        assertEquals(only.id, sources.current())
        // Only what is in a list counts: a title known because it was played is not kept.
        assertEquals(1, only.titleCount)
        // No rating, no year: the store holds neither, so those two are greyed with the reason.
        assertEquals(setOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Title), only.sorts)
    }

    @Test
    fun `a sort written is read back, by the same and by a new reader`() {
        val sort = LibrarySort(LibrarySortCriterion.Title, descending = true)
        val memory = FileLibrarySortMemory(file)
        memory.remember(sort)
        assertEquals(sort, memory.last())
        waitFor { file.exists() }
        assertEquals(sort, FileLibrarySortMemory(file).last())
        assertEquals("Title desc", file.readText())
    }

    @Test
    fun `the file is the owner's alone`() {
        FileLibrarySortMemory(file).remember(LibrarySort.Default)
        waitFor { file.exists() }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }

    @Test
    fun `anything but a criterion's name and a direction reads as never having chosen`() {
        listOf("", "Title", "title asc", "Title up", "Title asc extra", "Rating\u0000 desc", "Popularity desc")
            .forEach { text ->
                file.writeText(text)
                assertNull(FileLibrarySortMemory(file).last(), "read \"$text\"")
            }
        file.writeText(" Updated asc \n")
        assertEquals(LibrarySort(LibrarySortCriterion.Updated, descending = false), FileLibrarySortMemory(file).last())
    }

    @Test
    fun `a file too large to be one is not read`() {
        file.writeText("Title asc" + " ".repeat(1024))
        assertNull(FileLibrarySortMemory(file).last())
    }

    @Test
    fun `the default keeps nothing and writes nothing`() {
        LibrarySortMemory.None.remember(LibrarySort.Default)
        assertNull(LibrarySortMemory.None.last())
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(WAIT_ROUNDS) {
            if (condition()) return
            Thread.sleep(WAIT_MILLIS)
        }
        error("timed out")
    }

    private companion object {
        const val WAIT_ROUNDS = 50
        const val WAIT_MILLIS = 20L
    }
}
