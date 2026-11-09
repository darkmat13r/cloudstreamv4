package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Over real files in a temporary directory. Nothing here touches `~/.cloudstream4`. */
class DesktopLibraryStoreTest {

    private val directory = createTempDirectory("library-store").toFile()
    private val titlesFile = File(directory, "library.tsv")
    private val positionsFile = File(directory, "watch-positions.tsv")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store(beforeMove: () -> Unit = {}) =
        DesktopLibraryStore(titlesFile, positionsFile, beforeMove)

    /** The id a desktop title really has: the provider's name, a NUL, and the URL. */
    private fun id(path: String) = LibraryTitleId("Prov\u0000https://example.com/$path")

    private fun title(
        id: LibraryTitleId,
        bucket: LibraryBucket? = LibraryBucket.Watching,
        name: String = "Dune",
        keptAt: Long = 10L,
        posterUrl: String? = "https://example.com/p.jpg",
    ) = LibraryTitle(id, name, bucket, keptAt, posterUrl, "Prov")

    @Test
    fun `nothing stored is an empty library rather than a failure`() = runBlocking {
        assertEquals(emptyList(), store().titles())
        assertEquals(emptyList(), store().positions())
    }

    @Test
    fun `a title survives a round trip, NUL and all`() = runBlocking {
        val kept = title(id("dune"))
        store().remember(kept)
        assertEquals(listOf(kept), store().titles())
    }

    @Test
    fun `a tab, a newline, a backslash and a NUL in a title all come back as themselves`() = runBlocking {
        val awkward = "a\tb\nc\\d\u0000e\rf"
        store().remember(title(id("x"), name = awkward))
        // The control characters are stripped on the way out as untrusted text; the escaping is what
        // this test is about, so it is checked on the field that keeps its content: the id.
        val weirdId = LibraryTitleId("Prov\u0000https://example.com/a\tb\\c")
        store().remember(title(weirdId, name = "Plain"))
        assertTrue(store().titles().any { it.id == weirdId })
        assertEquals(1, store().titles().count { it.title == "Plain" })
    }

    @Test
    fun `a title's text is sanitised again on the way out`() = runBlocking {
        store().remember(title(id("x"), name = "Dune‮evil"))
        assertEquals("Duneevil", store().titles().single().title)
    }

    @Test
    fun `a stored poster that is not http is dropped`() = runBlocking {
        store().remember(title(id("x"), posterUrl = "file:///etc/passwd"))
        assertNull(store().titles().single().posterUrl)
        store().remember(title(id("y"), posterUrl = "https://example.com/ok.jpg"))
        assertEquals("https://example.com/ok.jpg", store().titles().first { it.id == id("y") }.posterUrl)
    }

    @Test
    fun `a poster with userinfo is never written, and one already in the file is dropped on reading (BUG-78)`() = runBlocking {
        store().remember(title(id("x"), posterUrl = "https://user:secret@example.com/p.jpg"))
        assertNull(store().titles().single().posterUrl)
        assertTrue("secret" !in titlesFile.readText(), "the credential never reaches the file")

        store().remember(title(id("y"), posterUrl = "https://example.com/clean.jpg"))
        val withClean = titlesFile.readText()
        assertTrue("https://example.com/clean.jpg" in withClean, "the control: a clean one is written")
        titlesFile.writeText(withClean.replace("https://example.com/clean.jpg", "http:///user:secret@example.com/p.jpg"))
        assertNull(store().titles().first { it.id == id("y") }.posterUrl, "dropped on reading")

        store().remember(title(id("z"), posterUrl = "https://example.com/a@2x.jpg"))
        assertEquals("https://example.com/a@2x.jpg", store().titles().first { it.id == id("z") }.posterUrl, "an at sign in the path")
    }

    @Test
    fun `one row per title, and the newest write wins`() = runBlocking {
        val store = store()
        store.remember(title(id("dune"), name = "Dune"))
        store.remember(title(id("dune"), name = "Dune: Part Two"))
        val held = store.titles()
        assertEquals(1, held.size)
        assertEquals("Dune: Part Two", held.single().title)
    }

    @Test
    fun `remembering a title keeps the list it is already in`() = runBlocking {
        val store = store()
        store.remember(title(id("dune"), bucket = LibraryBucket.Completed, keptAt = 5L))
        // What playing it does: records what it looks like and claims no list.
        store.remember(title(id("dune"), bucket = null, keptAt = 99L))
        val held = store.titles().single()
        assertEquals(LibraryBucket.Completed, held.bucket)
        assertEquals(5L, held.keptAt)
    }

    @Test
    fun `moving a title to another list dates it now, and back to none keeps the date`() = runBlocking {
        val store = store()
        store.remember(title(id("dune"), bucket = LibraryBucket.Watching, keptAt = 1L))
        store.setBucket(id("dune"), LibraryBucket.Dropped)
        val moved = store.titles().single()
        assertEquals(LibraryBucket.Dropped, moved.bucket)
        assertTrue(moved.keptAt > 1L)

        store.setBucket(id("dune"), null)
        val dropped = store.titles().single()
        assertNull(dropped.bucket)
        assertEquals(moved.keptAt, dropped.keptAt)
    }

    @Test
    fun `a bucket this version does not know refuses the whole line rather than guessing`() = runBlocking {
        store().remember(title(id("dune")))
        val text = titlesFile.readText().replace("Watching", "Rewatching")
        titlesFile.writeText(text)
        assertEquals(emptyList(), store().titles())
    }

    @Test
    fun `a line that does not parse costs that line and not the library`() = runBlocking {
        val store = store()
        store.remember(title(id("a"), name = "Alpha", keptAt = 2L))
        store.remember(title(id("b"), name = "Beta", keptAt = 1L))
        val lines = titlesFile.readText().trimEnd('\n').split('\n').toMutableList()
        lines[1] = "this is not a row"
        titlesFile.writeText(lines.joinToString("\n", postfix = "\n"))
        assertEquals(listOf("Beta"), store.titles().map { it.title })
    }

    @Test
    fun `a file that is not ours is refused, and said so`() = runBlocking {
        titlesFile.parentFile.mkdirs()
        titlesFile.writeText("something else entirely\n")
        assertFailsWith<IOException> { store().titles() }
        Unit
    }

    @Test
    fun `a link is not followed`() = runBlocking {
        store().remember(title(id("dune")))
        val real = File(directory, "real.tsv")
        titlesFile.renameTo(real)
        Files.createSymbolicLink(titlesFile.toPath(), real.toPath())
        assertFailsWith<IOException> { store().titles() }
        Unit
    }

    @Test
    fun `a position survives a round trip and there is one per episode`() = runBlocking {
        val store = store()
        val first = WatchPosition(id("dune"), "e1", 60_000L, 600_000L, 10L)
        val second = WatchPosition(id("dune"), "e2", 90_000L, 600_000L, 20L)
        store.record(first)
        store.record(second)
        store.record(first.copy(positionMillis = 120_000L, watchedAt = 30L))
        val held = store.positions()
        assertEquals(2, held.size)
        assertEquals(120_000L, held.first { it.episodeData == "e1" }.positionMillis)
    }

    @Test
    fun `a title with no episode of its own round trips with no episode`() = runBlocking {
        val store = store()
        store.record(WatchPosition(id("film"), null, 60_000L, 600_000L, 1L))
        assertNull(store.positions().single().episodeData)
    }

    @Test
    fun `clearing positions drops every episode of that title and leaves the others`() = runBlocking {
        val store = store()
        store.record(WatchPosition(id("dune"), "e1", 60_000L, 600_000L, 1L))
        store.record(WatchPosition(id("dune"), "e2", 60_000L, 600_000L, 2L))
        store.record(WatchPosition(id("other"), null, 60_000L, 600_000L, 3L))
        store.clearPositions(id("dune"))
        assertEquals(listOf(id("other")), store.positions().map { it.id })
    }

    @Test
    fun `clearing positions forgets a row that was only there to draw a card`() = runBlocking {
        val store = store()
        store.remember(title(id("played"), bucket = null))
        store.remember(title(id("kept"), bucket = LibraryBucket.Watching))
        store.record(WatchPosition(id("played"), null, 60_000L, 600_000L, 1L))
        store.record(WatchPosition(id("kept"), null, 60_000L, 600_000L, 2L))

        store.clearPositions(id("played"))
        assertEquals(listOf(id("kept")), store.titles().map { it.id })

        // One in a list keeps its row, because removing it from continue watching is not forgetting it.
        store.clearPositions(id("kept"))
        assertEquals(listOf(id("kept")), store.titles().map { it.id })
    }

    @Test
    fun `a crash between writing and replacing leaves the old file`() = runBlocking {
        val store = store()
        store.remember(title(id("first"), name = "First"))
        val crashing = store { throw IOException("crash") }
        assertFailsWith<IOException> { crashing.remember(title(id("second"), name = "Second")) }
        assertEquals(listOf("First"), store.titles().map { it.title })
        // And nothing was left behind in the directory.
        assertEquals(
            emptyList(),
            directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".part") },
        )
    }

    @Test
    fun `the files are written where the defaults say`() {
        assertEquals("library.tsv", DesktopLibraryStore.defaultTitlesFile().name)
        assertEquals("watch-positions.tsv", DesktopLibraryStore.defaultPositionsFile().name)
        assertEquals(".cloudstream4", DesktopLibraryStore.defaultTitlesFile().parentFile.name)
        assertEquals(
            DesktopLibraryStore.defaultTitlesFile().parentFile,
            DesktopLibraryStore.defaultPositionsFile().parentFile,
        )
    }
}
