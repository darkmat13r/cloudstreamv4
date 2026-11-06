package com.lagradost.cloudstream4.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** `KMP-49`: the library read from a source, the local list or a tracking service's. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySourcesViewModelTest {

    private val local = LibrarySourceId("Local")
    private val aniList = LibrarySourceId("AniList")
    private val mal = LibrarySourceId("MAL")

    private val dune = LibraryTitleId("Prov\u0000/dune")
    private val frieren = LibraryTitleId("AniList\u0000/frieren")
    private val severance = LibraryTitleId("AniList\u0000/severance")

    private class Store(var titles: List<LibraryTitle> = emptyList()) : LibraryStore {
        override suspend fun titles() = titles
        override suspend fun positions() = emptyList<WatchPosition>()
        override suspend fun remember(title: LibraryTitle) = Unit
        override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = Unit
        override suspend fun record(position: WatchPosition) = Unit
        override suspend fun clearPositions(id: LibraryTitleId) = Unit
    }

    /** Sources as a host keeps them: a choice is remembered, and a read records its time. */
    private class Sources(
        var all: List<LibrarySource>,
        var current: LibrarySourceId,
        var lists: Map<LibrarySourceId, List<SyncedList>> = emptyMap(),
    ) : LibrarySources {
        val chosen = mutableListOf<LibrarySourceId>()
        val read = mutableListOf<LibrarySourceId>()
        var failLists: Exception? = null
        var failSources: Exception? = null
        var clock = 1_000L

        override suspend fun sources(): List<LibrarySource> {
            failSources?.let { throw it }
            return all
        }

        override suspend fun current() = current

        override suspend fun choose(id: LibrarySourceId) {
            chosen += id
            current = id
        }

        override suspend fun lists(id: LibrarySourceId): List<SyncedList> {
            read += id
            failLists?.let { throw it }
            all = all.map { if (it.id == id) it.copy(syncedAt = clock) else it }
            return lists.getValue(id)
        }
    }

    private fun sources(current: LibrarySourceId = local) = Sources(
        all = listOf(
            LibrarySource(aniList, "AniList", account = "lagradost"),
            LibrarySource(local, "Local", local = true, titleCount = 1),
            LibrarySource(mal, "MyAnimeList", connected = false),
        ),
        current = current,
        lists = mapOf(
            aniList to listOf(
                SyncedList(
                    "watching",
                    "Watching",
                    listOf(
                        synced(frieren, "Frieren", keptAt = 2, watched = 12, total = 28, rating = "9.1"),
                        synced(severance, "Severance", keptAt = 5, watched = 9, total = 10),
                    ),
                ),
                SyncedList("rewatching", "Rewatching", listOf(synced(frieren, "Frieren", keptAt = 2))),
                SyncedList("dropped", "Dropped", emptyList()),
            ),
        ),
    )

    private fun synced(
        id: LibraryTitleId,
        name: String,
        keptAt: Long,
        watched: Int? = null,
        total: Int? = null,
        rating: String? = null,
    ) = LibraryTitle(
        id = id, title = name, bucket = null, keptAt = keptAt,
        episodesWatched = watched, episodesTotal = total, rating = rating,
    )

    private val store = Store(listOf(LibraryTitle(dune, "Dune", LibraryBucket.Watching, keptAt = 1)))

    private fun TestScope.model(host: LibrarySources?) = LibraryViewModel(
        store,
        TestScope(UnconfinedTestDispatcher(testScheduler)),
        initialTab = LibraryTab.Bucket(LibraryBucket.Watching),
        sources = host,
    )

    @Test
    fun `the local list shows the store's lists, with every source beside it`() = runTest {
        val vm = model(sources(current = local))
        val state = vm.state.value
        assertEquals(listOf(aniList, local, mal), state.sources.map { it.id })
        assertEquals(local, state.source)
        assertNull(state.syncedTabs)
        assertEquals(LibraryTab.Bucket(LibraryBucket.Watching), state.tab)
        assertEquals(listOf(dune), assertIs<LibraryStatus.Showing>(state.status).cards.map { it.id })
    }

    @Test
    fun `a service shows its own lists, in its order, with what each holds`() = runTest {
        val host = sources(current = aniList)
        val vm = model(host)
        val state = vm.state.value
        assertEquals(listOf("Watching", "Rewatching", "Dropped"), state.syncedTabs?.map { it.label })
        assertEquals(LibraryTab.Synced("watching", "Watching"), state.tab)
        assertEquals(mapOf("watching" to 2, "rewatching" to 1, "dropped" to 0), state.syncedCounts)
        // Frieren is in two lists and is one title.
        assertEquals(2, state.totalTitles)
        val cards = assertIs<LibraryStatus.Showing>(state.status).cards
        assertEquals(listOf(severance, frieren), cards.map { it.id }, "newest kept first")
        assertEquals(listOf(aniList), host.read)
    }

    @Test
    fun `a service's card carries its count, its bar and the user's own rating`() = runTest {
        val vm = model(sources(current = aniList))
        val card = assertIs<LibraryStatus.Showing>(vm.state.value.status).cards.first { it.id == frieren }
        assertEquals(12, card.episodesWatched)
        assertEquals(28, card.episodesTotal)
        assertEquals("9.1", card.rating)
        assertEquals(12f / 28f, card.progress)
    }

    @Test
    fun `the time a read recorded is on the source once it is read`() = runTest {
        val host = sources(current = aniList)
        host.clock = 42_000L
        val vm = model(host)
        assertEquals(42_000L, vm.state.value.shownSource?.syncedAt)
    }

    @Test
    fun `choosing a service closes the panel, remembers it and shows its lists`() = runTest {
        val host = sources(current = local)
        val vm = model(host)
        vm.onAction(LibraryAction.SourcesOpened)
        assertTrue(vm.state.value.sourcesOpen)
        val seen = mutableListOf<LibraryStatus>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it.status } }

        vm.onAction(LibraryAction.SourceChosen(aniList))

        assertFalse(vm.state.value.sourcesOpen)
        assertEquals(listOf(aniList), host.chosen)
        assertEquals(aniList, vm.state.value.source)
        assertTrue(seen.any { it is LibraryStatus.Loading }, "switching reloads, as the old screen does: $seen")
        assertEquals(LibraryTab.Synced("watching", "Watching"), vm.state.value.tab)
        watcher.cancel()
    }

    @Test
    fun `back on the local list the store's tabs return`() = runTest {
        val vm = model(sources(current = aniList))
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Synced("dropped", "Dropped")))
        vm.onAction(LibraryAction.SourceChosen(local))
        val state = vm.state.value
        assertNull(state.syncedTabs)
        assertEquals(LibraryTab.Bucket(LibraryBucket.Watching), state.tab)
        assertEquals(1, state.counts[LibraryBucket.Watching])
        assertEquals(listOf(dune), assertIs<LibraryStatus.Showing>(state.status).cards.map { it.id })
    }

    @Test
    fun `a source that is not signed in is refused, and the panel closes`() = runTest {
        val host = sources(current = local)
        val vm = model(host)
        vm.onAction(LibraryAction.SourcesOpened)
        vm.onAction(LibraryAction.SourceChosen(mal))
        assertFalse(vm.state.value.sourcesOpen)
        assertEquals(local, vm.state.value.source)
        assertTrue(host.chosen.isEmpty())
    }

    @Test
    fun `choosing the source already shown only closes the panel`() = runTest {
        val host = sources(current = aniList)
        val vm = model(host)
        vm.onAction(LibraryAction.SourcesOpened)
        vm.onAction(LibraryAction.SourceChosen(aniList))
        assertFalse(vm.state.value.sourcesOpen)
        assertEquals(listOf(aniList), host.read, "no second read")
        assertTrue(host.chosen.isEmpty())
    }

    @Test
    fun `a service that cannot be read is a failure with no counts, and retry recovers`() = runTest {
        val host = sources(current = aniList)
        host.failLists = IllegalStateException("Unable to fetch library")
        val vm = model(host)
        val state = vm.state.value
        assertEquals(LibraryStatus.Failed("Unable to fetch library"), state.status)
        assertNull(state.syncedTabs)
        assertNull(state.totalTitles)
        assertEquals(aniList, state.source)

        host.failLists = null
        vm.onAction(LibraryAction.Retry)
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `without the sources the store still shows`() = runTest {
        val host = sources(current = aniList)
        host.failSources = IllegalStateException("no")
        val vm = model(host)
        assertTrue(vm.state.value.sources.isEmpty())
        assertEquals(listOf(dune), assertIs<LibraryStatus.Showing>(vm.state.value.status).cards.map { it.id })
    }

    @Test
    fun `a filter narrows a service's list and leaves its counts`() = runTest {
        val vm = model(sources(current = aniList))
        vm.onAction(LibraryAction.FilterChanged("frie"))
        assertEquals(listOf(frieren), assertIs<LibraryStatus.Showing>(vm.state.value.status).cards.map { it.id })
        assertEquals(2, vm.state.value.syncedCounts["watching"])
    }

    @Test
    fun `an empty list of a service is empty, not failed`() = runTest {
        val vm = model(sources(current = aniList))
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Synced("dropped", "Dropped")))
        assertEquals(LibraryStatus.Empty, vm.state.value.status)
    }

    @Test
    fun `a screen without sources has no panel to open`() = runTest {
        val vm = model(host = null)
        vm.onAction(LibraryAction.SourcesOpened)
        assertFalse(vm.state.value.sourcesOpen)
        assertNull(vm.state.value.source)
    }
}
