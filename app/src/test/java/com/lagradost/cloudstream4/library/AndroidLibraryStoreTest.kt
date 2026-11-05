package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from what `:app` stores to what the screen reads. Plain JUnit: the keys themselves bottom out
 * in `SharedPreferences`, so [DataStoreLocalLibrary] is the one thin layer not covered here, and the
 * device test `LibraryTvNavigationTest` covers it through real keys.
 */
class AndroidLibraryStoreTest {

    private class Records(
        val states: Map<Int, WatchType> = emptyMap(),
        val bookmarks: Map<Int, DataStoreHelper.BookmarkedData> = emptyMap(),
        val favourites: List<DataStoreHelper.FavoritesData> = emptyList(),
        val resume: Map<Int, DownloadObjects.ResumeWatching> = emptyMap(),
        val positions: Map<Int, DataStoreHelper.PosDur> = emptyMap(),
    ) : LocalLibraryRecords {
        override fun watchStates() = states.toList()
        override fun bookmark(id: Int) = bookmarks[id]
        override fun favourites() = favourites
        override fun lastWatched(id: Int) = resume[id]
        override fun viewPosition(id: Int) = positions[id]
    }

    private fun store(records: Records) = AndroidLibraryStore(
        records = records,
        labels = { { type -> type.name } },
        io = Dispatchers.Unconfined,
    )

    private fun bookmark(
        id: Int,
        name: String = "Title $id",
        updated: Long = 0L,
        poster: String? = null,
        api: String = "Filmxy",
        url: String = "https://example.com/$id",
        type: TvType? = null,
        headers: Map<String, String>? = null,
    ) = DataStoreHelper.BookmarkedData(
        bookmarkedTime = 0L,
        id = id,
        latestUpdatedTime = updated,
        name = name,
        url = url,
        apiName = api,
        type = type,
        posterUrl = poster,
        year = null,
        posterHeaders = headers,
    )

    private fun favourite(id: Int?, url: String = "https://example.com/$id") = DataStoreHelper.FavoritesData(
        favoritesTime = 0L,
        id = id,
        latestUpdatedTime = 0L,
        name = "Title $id",
        url = url,
        apiName = "Filmxy",
        type = null,
        posterUrl = null,
        year = null,
    )

    private fun resume(parent: Int, episode: Int?, at: Long = 5L) =
        DownloadObjects.ResumeWatching(parent, episode, null, null, at, false)

    @Test
    fun everyWatchStatusLandsInItsOwnList() = runBlocking {
        val types = listOf(
            WatchType.WATCHING, WatchType.COMPLETED, WatchType.ONHOLD, WatchType.DROPPED, WatchType.PLANTOWATCH,
        )
        val records = Records(
            states = types.withIndex().associate { (id, type) -> id to type },
            bookmarks = types.indices.associateWith { bookmark(it) },
        )
        assertEquals(
            listOf(
                LibraryBucket.Watching, LibraryBucket.Completed, LibraryBucket.OnHold,
                LibraryBucket.Dropped, LibraryBucket.PlanToWatch,
            ).toSet(),
            store(records).titles().map { it.bucket }.toSet(),
        )
    }

    @Test
    fun aRowWithNoStatusOrNoStoredFieldsIsNotAListEntry() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.NONE, 2 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1)),
        )
        assertEquals(emptyList<Any>(), store(records).titles())
    }

    /** Favourites are a separate store on Android, so one title can be in both lists. */
    @Test
    fun aFavouriteIsAlsoItsWatchStatusAndCountsInBothLists() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1)),
            favourites = listOf(favourite(1), favourite(null, "https://example.com/noid")),
        )
        val titles = store(records).titles()
        assertEquals(listOf(LibraryBucket.Watching, LibraryBucket.Favourites), titles.map { it.bucket })
        assertEquals(1, titles.map { it.id }.distinct().size)
    }

    /** Two stored ids can hold one title, and a lazy grid crashes on a repeated key. */
    @Test
    fun oneTitleUnderTwoStoredIdsIsOneCardAndTheNewerRowWins() = runBlocking {
        val url = "https://example.com/same"
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING, 2 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1, updated = 1L, url = url), 2 to bookmark(2, updated = 9L, url = url)),
        )
        val titles = store(records).titles()
        assertEquals(1, titles.size)
        assertEquals(9L, titles.single().keptAt)
    }

    @Test
    fun anIdReopensTheSameProviderAndUrl() = runBlocking {
        val url = "https://example.com/a:b|c\u0000d"
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1, url = url, api = "My:Api")),
        )
        val target = store(records).titles().single().id.target()
        assertEquals("My:Api", target?.apiName)
        assertEquals(url, target?.url)
    }

    @Test
    fun providerTextIsSanitisedOnTheWayOut() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING, 2 to WatchType.WATCHING),
            bookmarks = mapOf(
                1 to bookmark(
                    1,
                    name = " Dune\u0007‮\n",
                    api = "Film\u0000xy",
                    poster = "https://example.com/p.jpg",
                    headers = mapOf("Referer" to "https://example.com", "X-Bad" to "a\r\nb"),
                ),
                2 to bookmark(2, name = "x".repeat(500), poster = "file:///data/secret"),
            ),
        )
        val (dune, long) = store(records).titles().sortedBy { it.title.length }
        assertEquals("Dune", dune.title)
        assertEquals("Filmxy", dune.providerName)
        assertEquals("https://example.com/p.jpg", dune.posterUrl)
        assertEquals(mapOf("Referer" to "https://example.com"), dune.posterHeaders)
        assertEquals(200, long.title.length)
        assertNull(long.posterUrl)
    }

    @Test
    fun aPosterWithALineBreakOrAnOtherSchemeIsDropped() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING, 2 to WatchType.WATCHING),
            bookmarks = mapOf(
                1 to bookmark(1, poster = "https://example.com/a\nb"),
                2 to bookmark(2, poster = "javascript:alert(1)"),
            ),
        )
        assertTrue(store(records).titles().all { it.posterUrl == null })
    }

    @Test
    fun theKindOfTitleIsInUpperCaseAndAbsentWhereUnknown() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING, 2 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1, type = TvType.Anime), 2 to bookmark(2)),
        )
        assertEquals(setOf("ANIME", null), store(records).titles().map { it.typeLabel }.toSet())
    }

    /** A series' position is under the episode the resume record names, not under the series. */
    @Test
    fun aPositionIsTheResumedEpisodesOwn() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1)),
            resume = mapOf(1 to resume(1, 77, at = 42L)),
            positions = mapOf(77 to DataStoreHelper.PosDur(60_000L, 600_000L)),
        )
        val position = store(records).positions().single()
        assertEquals(LibraryTitleId(store(records).titles().single().id.value), position.id)
        assertEquals("77", position.episodeData)
        assertEquals(60_000L, position.positionMillis)
        assertEquals(600_000L, position.durationMillis)
        assertEquals(42L, position.watchedAt)
    }

    /** A film finished is off the resume list, and its position stays under its own id. */
    @Test
    fun aTitleWithNoResumeRecordUsesAPositionUnderItsOwnId() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1)),
            positions = mapOf(1 to DataStoreHelper.PosDur(600_000L, 600_000L)),
        )
        val position = store(records).positions().single()
        assertNull(position.episodeData)
        assertEquals(600_000L, position.positionMillis)
    }

    @Test
    fun aTitleNeverPlayedHasNoPosition() = runBlocking {
        val records = Records(states = mapOf(1 to WatchType.WATCHING), bookmarks = mapOf(1 to bookmark(1)))
        assertEquals(emptyList<Any>(), store(records).positions())
    }

    @Test
    fun aTitleInTwoListsHasOnePosition() = runBlocking {
        val records = Records(
            states = mapOf(1 to WatchType.WATCHING),
            bookmarks = mapOf(1 to bookmark(1)),
            favourites = listOf(favourite(1)),
            positions = mapOf(1 to DataStoreHelper.PosDur(60_000L, 600_000L)),
        )
        assertEquals(1, store(records).positions().size)
    }

    @Test
    fun writesRefuseRatherThanPretend() {
        val store = store(Records())
        val id = LibraryTitleId("x")
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { store.setBucket(id, LibraryBucket.Watching) }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { store.clearPositions(id) }
        }
    }
}
