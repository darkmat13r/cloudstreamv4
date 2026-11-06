package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.library.AndroidLibraryStore
import com.lagradost.cloudstream4.library.LocalLibraryRecords
import com.lagradost.cloudstream4.search.encodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's Continue watching and progress, read through the library's store from what `:app` keeps. Plain JUnit over the
 * records seam; `TvHomeNavigationTest` reads the real keys.
 */
class LibraryHomeHistoryTest {

    private class Records(
        val states: Map<Int, WatchType> = emptyMap(),
        val bookmarks: Map<Int, DataStoreHelper.BookmarkedData> = emptyMap(),
        val resume: Map<Int, DownloadObjects.ResumeWatching> = emptyMap(),
        val headers: Map<Int, DownloadObjects.DownloadHeaderCached> = emptyMap(),
        val positions: Map<Int, DataStoreHelper.PosDur> = emptyMap(),
    ) : LocalLibraryRecords {
        override fun watchStates() = states.toList()
        override fun bookmark(id: Int) = bookmarks[id]
        override fun favourites() = emptyList<DataStoreHelper.FavoritesData>()
        override fun lastWatched(id: Int) = resume[id]
        override fun viewPosition(id: Int) = positions[id]
        override fun resumeIds() = resume.keys.toList()
        override fun resumeHeader(parentId: Int) = headers[parentId]
    }

    private fun store(records: Records) = AndroidLibraryStore(records, { { it.name } }, Dispatchers.Unconfined)

    private fun history(records: Records) = runBlocking { LibraryHomeHistory(store(records)).read() }

    private fun header(id: Int, name: String = "Title $id", poster: String? = null, api: String = "Filmxy") =
        DownloadObjects.DownloadHeaderCached(api, "https://example.com/$id", TvType.TvSeries, name, poster, 0L, id)

    private fun resume(parent: Int, episode: Int?, at: Long, season: Int? = null, number: Int? = null) =
        DownloadObjects.ResumeWatching(parent, episode, number, season, at, false)

    private fun id(parent: Int, api: String = "Filmxy") = encodeId(api, "https://example.com/$parent").value

    @Test
    fun everyResumeRecordWithATitleIsListedNewestFirstWithItsPartAndSource() {
        val records = Records(
            resume = mapOf(1 to resume(1, 11, at = 10L, season = 2, number = 4), 2 to resume(2, 21, at = 30L)),
            headers = mapOf(1 to header(1, poster = "https://img.example/1.jpg"), 2 to header(2)),
            positions = mapOf(11 to DataStoreHelper.PosDur(30_000L, 120_000L)),
        )
        assertEquals(
            listOf(
                HomeResume(id(2), "Title 2", sourceName = "Filmxy"),
                HomeResume(
                    id(1), "Title 1", posterUrl = "https://img.example/1.jpg", progress = 0.25f, season = 2, episode = 4,
                    sourceName = "Filmxy",
                ),
            ),
            history(records).resume,
        )
    }

    /** The next episode, once the last was nearly finished: a record with nothing played. The old home lists it. */
    @Test
    fun aRecordWhosePartHasNeverPlayedIsListedAtZero() {
        val records = Records(resume = mapOf(1 to resume(1, 12, at = 1L)), headers = mapOf(1 to header(1)))
        val entry = history(records).resume.single()
        assertEquals(0f, entry.progress)
        assertTrue("zero is not card progress", history(records).progress.isEmpty())
    }

    @Test
    fun aRecordWithNoTitleOrAnUnsafeOneIsDropped() {
        val records = Records(
            resume = mapOf(1 to resume(1, 11, at = 1L), 2 to resume(2, 21, at = 2L), 3 to resume(3, 31, at = 3L)),
            headers = mapOf(2 to header(2, name = "‮\u0000"), 3 to header(3, name = "Fine‮", poster = "file:///x")),
        )
        val entry = history(records).resume.single()
        assertEquals("Fine", entry.title)
        assertEquals(null, entry.posterUrl)
    }

    @Test
    fun progressIsByTheCardsOwnIdForBookmarksAndResumeRecordsAlike() {
        val bookmark = DataStoreHelper.BookmarkedData(
            bookmarkedTime = 0L, id = 5, latestUpdatedTime = 0L, name = "Kept", url = "https://example.com/5",
            apiName = "Filmxy", type = null, posterUrl = null, year = null,
        )
        val records = Records(
            states = mapOf(5 to WatchType.WATCHING),
            bookmarks = mapOf(5 to bookmark),
            resume = mapOf(1 to resume(1, 11, at = 1L)),
            headers = mapOf(1 to header(1)),
            positions = mapOf(5 to DataStoreHelper.PosDur(60_000L, 120_000L), 11 to DataStoreHelper.PosDur(90_000L, 120_000L)),
        )
        assertEquals(mapOf(id(5) to 0.5f, id(1) to 0.75f), history(records).progress)
    }

    @Test
    fun resumeRecordsLeaveTheLibrarysListsAsTheyWere() = runBlocking {
        val records = Records(resume = mapOf(1 to resume(1, 11, at = 1L)), headers = mapOf(1 to header(1)))
        assertEquals(emptyList<Any>(), store(records).titles())
    }

    @Test
    fun oneTitleResumedFromTwoRecordsIsOneEntryTheNewest() {
        val records = Records(
            resume = mapOf(1 to resume(1, 11, at = 1L), 2 to resume(2, 22, at = 9L)),
            // Two ids for one title, as when a provider's id scheme changed.
            headers = mapOf(1 to header(1), 2 to header(1).copy(id = 2)),
        )
        val entry = history(records).resume.single()
        assertEquals(id(1), entry.id)
    }
}
