package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** Home's Continue watching and Bookmarks read from the Mac's library store (`KMP-55`). */
class DesktopHomeLibraryTest {

    private fun title(id: String, bucket: LibraryBucket? = null, keptAt: Long = 0L) =
        LibraryTitle(LibraryTitleId(id), "Title $id", bucket, keptAt, posterUrl = "https://example.org/$id.jpg", providerName = "Archive")

    private fun position(id: String, at: Long, minutes: Long, of: Long = 60) =
        WatchPosition(LibraryTitleId(id), episodeData = null, positionMillis = minutes * 60_000, durationMillis = of * 60_000, watchedAt = at)

    @Test
    fun `continue watching is what can be resumed, newest first, from each title's newest position`() = runBlocking {
        val store = InMemoryLibraryStore(
            titles = listOf(title("a"), title("b"), title("c"), title("d")),
            positions = listOf(
                position("a", at = 10, minutes = 20),
                position("a", at = 30, minutes = 40),
                position("b", at = 20, minutes = 59),
                position("c", at = 40, minutes = 0),
                position("d", at = 50, minutes = 15),
                position("unknown", at = 60, minutes = 15),
            ),
        )
        val history = DesktopHomeHistory(store).read()
        assertEquals(listOf("d", "a"), history.resume.map { it.id })
        assertEquals(40f / 60f, history.resume[1].progress)
        assertEquals("Archive", history.resume[1].sourceName)
        assertEquals(setOf("a", "b", "d", "unknown"), history.progress.keys)
    }

    @Test
    fun `bookmarks are the titles in a status's bucket, and favourites are not a status`() = runBlocking {
        val store = InMemoryLibraryStore(
            titles = listOf(
                title("w", LibraryBucket.Watching, keptAt = 5),
                title("p", LibraryBucket.PlanToWatch, keptAt = 4),
                title("f", LibraryBucket.Favourites, keptAt = 3),
                title("none", null),
            ),
        )
        val bookmarks = DesktopHomeBookmarks(store)
        val read = bookmarks.read()
        assertEquals(listOf("w" to HomeWatchStatus.Watching, "p" to HomeWatchStatus.PlanToWatch), read.map { it.item.id to it.status })
        assertEquals(5L, read.first().updatedAt)

        assertEquals(null, bookmarks.chosen())
        bookmarks.choose(HomeWatchStatus.PlanToWatch)
        assertEquals(HomeWatchStatus.PlanToWatch, bookmarks.chosen())
    }
}
