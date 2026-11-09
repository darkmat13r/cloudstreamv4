@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.ui.library.LibraryOpener
import com.lagradost.cloudstream3.ui.library.LibraryOpenerType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.library.ProviderLibraryData
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibrarySourceId
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.search.encodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `KMP-49`'s source over `SyncRepo`, with a fake `SyncAPI`: no real account and no network. */
class AndroidLibrarySourcesTest {

    /** A tracking service that answers with what it is given, and counts how often it is asked. */
    private class FakeSyncApi(
        override val name: String,
        var answer: suspend () -> SyncAPI.LibraryMetadata?,
        val local: Boolean = false,
    ) : SyncAPI() {
        override val idPrefix = name.lowercase()
        override val requiresLogin = false
        override val syncIdName = SyncIdName.Anilist
        var asked = 0

        override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
            asked++
            return answer()
        }
    }

    private class Records : LibrarySourceRecords {
        var chosen: String? = null
        val times = mutableMapOf<String, Long>()
        override fun chosen() = chosen
        override fun choose(name: String) { chosen = name }
        override fun syncedAt(name: String) = times[name]
        override fun synced(name: String, at: Long) { times[name] = at }
        var mode: Int? = null
        override fun sortingMode() = mode
        override fun sortBy(mode: Int) { this.mode = mode }
    }

    private fun item(
        name: String,
        url: String = "https://anilist.co/anime/$name",
        watched: Int? = null,
        total: Int? = null,
        rating: Score? = null,
        updated: Long? = null,
    ) = SyncAPI.LibraryItem(
        name = name,
        url = url,
        syncId = "sync-$name",
        episodesCompleted = watched,
        episodesTotal = total,
        personalRating = rating,
        lastUpdatedUnixTime = updated,
        apiName = "AniList",
        type = TvType.Anime,
        posterUrl = "https://img.example/$name.jpg",
        posterHeaders = null,
        quality = null,
        releaseDate = null,
    )

    private val watching = SyncAPI.LibraryList(
        txt(R.string.type_watching),
        listOf(item("Frieren", watched = 12, total = 28, rating = Score.from(9.1, 10), updated = 5)),
    )
    private val rewatching = SyncAPI.LibraryList(txt(R.string.type_re_watching), listOf(item("Vinland")))
    private val metadata = SyncAPI.LibraryMetadata(listOf(watching, rewatching), emptySet())

    private val words: (UiText) -> String = { text ->
        when (text) {
            is UiText.StringResource -> when (text.resId) {
                R.string.type_watching -> "Watching"
                R.string.type_re_watching -> "Rewatching"
                // I18N-17: the local list's name comes from its key, here a word no source is called.
                R.string.library_source_local -> "Lokal"
                else -> "?"
            }
            is UiText.DynamicString -> text.value
        }
    }

    private val aniListApi = FakeSyncApi("AniList", { metadata })
    private val aniList = SyncLibrarySource(SyncRepo(aniListApi), connected = { true }, account = { "lagradost\u0000" })
    private val mal = SyncLibrarySource(SyncRepo(FakeSyncApi("MAL", { null })), connected = { false }, account = { null })
    private val localApi = object : SyncAPI() {
        override val name = "Local"
        override val idPrefix = "local"
    }
    private val records = Records()
    private var now = 1_000L

    private fun sources(vararg all: SyncLibrarySource) =
        AndroidLibrarySources(
            all = { all.toList() },
            records = records,
            labels = { words },
            typeLabels = { { type -> type.name } },
            localCount = { 7 },
            clock = { now },
            io = Dispatchers.Unconfined,
        )

    private val local = SyncLibrarySource(SyncRepo(localApi), local = true)

    @Test
    fun `signed-in sources are listed first, each group in the app's order`() = runBlocking {
        val listed = sources(mal, aniList, local).sources()
        assertEquals(listOf("AniList", "Lokal", "MAL"), listed.map { it.name })
        val (ani, loc, m) = listed
        assertEquals("lagradost", ani.account)
        assertTrue(loc.local && loc.connected)
        assertEquals(7, loc.titleCount)
        assertEquals(false, m.connected)
        assertNull("no read was ever recorded", ani.syncedAt)
    }

    @Test
    fun `it opens on the last choice where that is still signed in, else the first that is`() = runBlocking {
        val host = sources(mal, aniList, local)
        assertEquals("nothing chosen yet: the first signed in", LibrarySourceId("AniList"), host.current())
        records.chosen = "Local"
        assertEquals(LibrarySourceId("Local"), host.current())
        records.chosen = "MAL"
        assertEquals("MAL is not signed in any more", LibrarySourceId("AniList"), host.current())
        host.choose(LibrarySourceId("Local"))
        assertEquals("Local", records.chosen)
    }

    @Test
    fun `a service's lists are its own, in its order and words, sanitised`() = runBlocking {
        val lists = sources(aniList, local).lists(LibrarySourceId("AniList"))
        assertEquals(listOf("Watching", "Rewatching"), lists.map { it.label })
        assertEquals("res:${R.string.type_watching}", lists[0].key)
        val frieren = lists[0].titles.single()
        assertEquals(LibraryTitleId(encodeId("AniList", "https://anilist.co/anime/Frieren").value), frieren.id)
        assertEquals(12, frieren.episodesWatched)
        assertEquals(28, frieren.episodesTotal)
        assertEquals("9.1", frieren.rating)
        assertEquals("ANIME", frieren.typeLabel)
        assertEquals(5L, frieren.keptAt)
        assertNull(lists[1].titles.single().rating)
    }

    @Test
    fun `a rating of 0 is no rating, and a title with no name or address is left out`() = runBlocking {
        aniListApi.answer = {
            SyncAPI.LibraryMetadata(
                listOf(
                    SyncAPI.LibraryList(
                        txt(R.string.type_watching),
                        listOf(item("Zero", rating = Score.from(0.0, 10)), item(" "), item("NoUrl", url = "")),
                    ),
                ),
                emptySet(),
            )
        }
        val titles = sources(aniList, local).lists(LibrarySourceId("AniList")).single().titles
        assertEquals(listOf("Zero"), titles.map { it.title })
        assertNull(titles.single().rating)
    }

    @Test
    fun `a read records its time, and a service's own cache is shown again without a new time`() = runBlocking {
        val host = sources(aniList, local)
        now = 5_000L
        host.lists(LibrarySourceId("AniList"))
        assertEquals(5_000L, records.times["AniList"])
        assertEquals(5_000L, host.sources().first { it.name == "AniList" }.syncedAt)

        now = 9_000L
        host.lists(LibrarySourceId("AniList"))
        assertEquals("requireLibraryRefresh is false after a read", 1, aniListApi.asked)
        assertEquals(5_000L, records.times["AniList"])

        aniList.repo.requireLibraryRefresh = true
        host.lists(LibrarySourceId("AniList"))
        assertEquals(2, aniListApi.asked)
        assertEquals(9_000L, records.times["AniList"])
    }

    @Test
    fun `a service that answers nothing fails in the old screen's words, and records no time`() {
        aniListApi.answer = { null }
        val host = sources(aniList, local)
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { host.lists(LibrarySourceId("AniList")) }
        }
        assertEquals("Unable to fetch library", failure.message)
        assertNull(records.times["AniList"])
    }

    @Test
    fun `a service that throws fails with its own exception`() {
        aniListApi.answer = { throw java.io.IOException("unreachable") }
        val failure = assertThrows(java.io.IOException::class.java) {
            runBlocking { sources(aniList, local).lists(LibrarySourceId("AniList")) }
        }
        assertEquals("unreachable", failure.message)
    }

    @Test
    fun `what was read can be opened as it was read, and only the shown source's`() = runBlocking {
        val host = sources(aniList, local)
        host.lists(LibrarySourceId("AniList"))
        val id = LibraryTitleId(encodeId("AniList", "https://anilist.co/anime/Frieren").value)
        val synced = host.synced(id)
        assertEquals("sync-Frieren", synced?.item?.syncId)
        assertEquals(SyncIdName.Anilist, synced?.syncName)
        assertNull(host.synced(LibraryTitleId("Prov\u0000/dune")))
    }

    /** The reviewer's case on `#175`: A, then B, then A again from the cache must open A's titles, not B's. */
    @Test
    fun `switching back to a service shown from its cache opens that service's titles`() = runBlocking {
        val simklApi = FakeSyncApi("Simkl", {
            SyncAPI.LibraryMetadata(
                listOf(SyncAPI.LibraryList(txt(R.string.type_watching), listOf(item("Mushishi", url = "https://simkl.com/anime/1")))),
                emptySet(),
            )
        })
        val simkl = SyncLibrarySource(SyncRepo(simklApi), connected = { true }, account = { "lagradost" })
        val host = sources(aniList, simkl, local)
        val frierenId = LibraryTitleId(encodeId("AniList", "https://anilist.co/anime/Frieren").value)
        val mushishiId = LibraryTitleId(encodeId("AniList", "https://simkl.com/anime/1").value)

        host.lists(LibrarySourceId("AniList"))
        host.lists(LibrarySourceId("Simkl"))
        assertEquals("sync-Mushishi", host.synced(mushishiId)?.item?.syncId)
        assertNull("AniList is not shown", host.synced(frierenId))

        host.lists(LibrarySourceId("AniList"))
        assertEquals("AniList came from its cache", 1, aniListApi.asked)
        assertEquals("sync-Frieren", host.synced(frierenId)?.item?.syncId)
        assertNull("Simkl is not shown any more", host.synced(mushishiId))
    }

    /** The reviewer's second case: a slow read for B that returns after switching back to A does not take A's place. */
    @Test
    fun `a late read for another service does not replace the shown one's titles`() = runBlocking {
        val late = CompletableDeferred<Unit>()
        val simklApi = FakeSyncApi("Simkl", {
            late.await()
            SyncAPI.LibraryMetadata(
                listOf(SyncAPI.LibraryList(txt(R.string.type_watching), listOf(item("Mushishi", url = "https://simkl.com/anime/1")))),
                emptySet(),
            )
        })
        val simkl = SyncLibrarySource(SyncRepo(simklApi), connected = { true }, account = { "lagradost" })
        val host = sources(aniList, simkl, local)
        val frierenId = LibraryTitleId(encodeId("AniList", "https://anilist.co/anime/Frieren").value)

        host.lists(LibrarySourceId("AniList"))
        // Started at once, so the read for Simkl is under way and waiting when AniList is asked for again.
        val slow = launch(start = CoroutineStart.UNDISPATCHED) { host.lists(LibrarySourceId("Simkl")) }
        host.lists(LibrarySourceId("AniList"))
        late.complete(Unit)
        slow.join()

        assertEquals(1, simklApi.asked)
        assertEquals("AniList is still the one shown", "sync-Frieren", host.synced(frierenId)?.item?.syncId)
    }

    // ---- `KMP-50`: sort ------------------------------------------------------------------------------------------

    /**
     * The choice is stored as the old screen's `ListSorting` ordinal, so the two screens agree only while the ordinals
     * do. Pinned, all nine: reordering the enum would change what every stored choice means.
     */
    @Test
    fun `the old screen's sort ordinals are pinned`() {
        assertEquals(
            listOf(
                "Query" to 0, "RatingHigh" to 1, "RatingLow" to 2, "UpdatedNew" to 3, "UpdatedOld" to 4,
                "AlphabeticalA" to 5, "AlphabeticalZ" to 6, "ReleaseDateNew" to 7, "ReleaseDateOld" to 8,
            ),
            ListSorting.entries.map { it.name to it.ordinal },
        )
    }

    @Test
    fun `each of the eight is one criterion one way round, and Query is none`() {
        with(AndroidLibrarySources) {
            assertNull(ListSorting.Query.toLibrarySort())
            val sorts = ListSorting.entries.mapNotNull { it.toLibrarySort() }
            assertEquals(8, sorts.toSet().size)
            sorts.forEach { assertEquals(it, it.toListSorting().toLibrarySort()) }
            assertEquals(LibrarySort.Default, ListSorting.UpdatedNew.toLibrarySort())
            assertEquals(LibrarySort(LibrarySortCriterion.Title, descending = false), ListSorting.AlphabeticalA.toLibrarySort())
        }
    }

    @Test
    fun `the sort is kept under the old screen's ordinal, and none kept is the default`() = runBlocking {
        val host = sources(aniList, local)
        assertNull(host.sort())
        host.sortBy(LibrarySort(LibrarySortCriterion.Released, descending = false))
        assertEquals(ListSorting.ReleaseDateOld.ordinal, records.mode)
        assertEquals(LibrarySort(LibrarySortCriterion.Released, descending = false), host.sort())
        records.mode = ListSorting.Query.ordinal
        assertNull("the old screen's no sort", host.sort())
        records.mode = 99
        assertNull("an ordinal from a later enum", host.sort())
    }

    @Test
    fun `a service sorts by what its read says, and the local list by all but a rating`() = runBlocking {
        aniListApi.answer = {
            SyncAPI.LibraryMetadata(listOf(watching), setOf(ListSorting.AlphabeticalA, ListSorting.UpdatedOld))
        }
        val host = sources(aniList, local)
        assertEquals(
            "before a read nothing is ruled out",
            LibrarySortCriterion.entries.toSet(),
            host.sources().first { it.name == "AniList" }.sorts,
        )
        host.lists(LibrarySourceId("AniList"))
        assertEquals(
            setOf(LibrarySortCriterion.Title, LibrarySortCriterion.Updated),
            host.sources().first { it.name == "AniList" }.sorts,
        )
        assertEquals(
            setOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Title, LibrarySortCriterion.Released),
            host.sources().first { it.local }.sorts,
        )
    }

    @Test
    fun `a service's title carries its year and its rating out of 100, and no rating is none`() = runBlocking {
        val released = java.util.Calendar.getInstance().apply { set(2023, 8, 29) }.time
        aniListApi.answer = {
            SyncAPI.LibraryMetadata(
                listOf(
                    SyncAPI.LibraryList(
                        txt(R.string.type_watching),
                        listOf(
                            item("Frieren", rating = Score.from(9.1, 10)).copy(releaseDate = released),
                            item("Zero", rating = Score.from(0.0, 10)),
                        ),
                    ),
                ),
                emptySet(),
            )
        }
        val (frieren, zero) = sources(aniList, local).lists(LibrarySourceId("AniList")).single().titles
        assertEquals(2023, frieren.releaseYear)
        assertEquals(91, frieren.ratingValue)
        assertNull(zero.releaseYear)
        assertNull("0 is not rated, and sorts last", zero.ratingValue)
    }

    // The opener: `LibraryFragment.loadLibraryItem`'s rule.

    private val frieren = SyncedTitle(item("Frieren"), SyncIdName.Anilist)

    @Test
    fun `with nothing saved a service's title is a search by its name`() {
        assertEquals(SyncedOpen.Search("Frieren"), syncedOpen(frieren, { null }, { false }))
    }

    @Test
    fun `with nothing saved a title from a loaded provider opens its page`() {
        assertEquals(SyncedOpen.Page(frieren.item), syncedOpen(frieren, { null }, { it == "AniList" }))
    }

    @Test
    fun `the title's own opener wins, unless it is the default, then the service's`() {
        val browser = LibraryOpener(LibraryOpenerType.Browser, null)
        val provider = LibraryOpener(LibraryOpenerType.Provider, ProviderLibraryData("SomeProvider"))
        val default = LibraryOpener(LibraryOpenerType.Default, null)
        val saved = mapOf("sync-Frieren" to browser, SyncIdName.Anilist.name to provider)
        assertEquals(SyncedOpen.Browser(frieren.item.url), syncedOpen(frieren, { saved[it] }, { false }))

        val fallBack = mapOf("sync-Frieren" to default, SyncIdName.Anilist.name to provider)
        assertEquals(
            SyncedOpen.Provider(frieren.item.url, "SomeProvider", "Frieren"),
            syncedOpen(frieren, { fallBack[it] }, { false }),
        )
        val none = mapOf(SyncIdName.Anilist.name to LibraryOpener(LibraryOpenerType.None, null))
        assertEquals(SyncedOpen.Nothing, syncedOpen(frieren, { none[it] }, { true }))
    }
}
