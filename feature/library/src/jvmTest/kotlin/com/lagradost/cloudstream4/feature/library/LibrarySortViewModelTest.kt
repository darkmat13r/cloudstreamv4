package com.lagradost.cloudstream4.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** `KMP-50`: one sort for every source, applied at once, with a fallback for a source that cannot sort by it. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySortViewModelTest {

    private val local = LibrarySourceId("Local")
    private val aniList = LibrarySourceId("AniList")
    private val localSorts = setOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Title, LibrarySortCriterion.Released)

    private class Store(private val titles: List<LibraryTitle>) : LibraryStore {
        override suspend fun titles() = titles
        override suspend fun positions() = emptyList<WatchPosition>()
        override suspend fun remember(title: LibraryTitle) = Unit
        override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = Unit
        override suspend fun record(position: WatchPosition) = Unit
        override suspend fun clearPositions(id: LibraryTitleId) = Unit
    }

    private inner class Sources(var current: LibrarySourceId, var saved: LibrarySort? = null) : LibrarySources {
        val kept = mutableListOf<LibrarySort>()

        override suspend fun sources() = listOf(
            LibrarySource(aniList, "AniList", account = "tidewatch"),
            LibrarySource(local, "Local", local = true, sorts = localSorts),
        )

        override suspend fun current() = current
        override suspend fun choose(id: LibrarySourceId) {
            current = id
        }

        override suspend fun lists(id: LibrarySourceId) = listOf(SyncedList("watching", "Watching", serviceTitles))
        override suspend fun sort() = saved
        override suspend fun sortBy(sort: LibrarySort) {
            kept += sort
            saved = sort
        }
    }

    private fun title(name: String, keptAt: Long, rating: Int? = null) = LibraryTitle(
        id = LibraryTitleId("Prov\u0000/$name"), title = name, bucket = LibraryBucket.Watching, keptAt = keptAt,
        ratingValue = rating,
    )

    private val localTitles = listOf(title("Breakwater", 1), title("Anchor", 3), title("Cove", 2))
    private val serviceTitles = listOf(title("Fernwood", 1, rating = 91), title("Still Water", 2, rating = 88))

    private fun TestScope.model(host: LibrarySources?) = LibraryViewModel(
        Store(localTitles),
        TestScope(UnconfinedTestDispatcher(testScheduler)),
        initialTab = LibraryTab.Bucket(LibraryBucket.Watching),
        sources = host,
    )

    private fun LibraryViewModel.titles() = assertIs<LibraryStatus.Showing>(state.value.status).cards.map { it.title }

    @Test
    fun `nothing saved is last updated, newest first`() = runTest {
        val vm = model(Sources(current = local))
        assertEquals(LibrarySort.Default, vm.state.value.sort)
        assertEquals(listOf("Anchor", "Cove", "Breakwater"), vm.titles())
    }

    @Test
    fun `the saved sort is read and applied`() = runTest {
        val vm = model(Sources(current = local, saved = LibrarySort(LibrarySortCriterion.Title, descending = false)))
        assertEquals(listOf("Anchor", "Breakwater", "Cove"), vm.titles())
    }

    @Test
    fun `a row applies at once, keeps the panel open, and is remembered for every source`() = runTest {
        val host = Sources(current = local)
        val vm = model(host)
        vm.onAction(LibraryAction.SortOpened)
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Title))
        assertEquals(listOf("Anchor", "Breakwater", "Cove"), vm.titles())
        assertTrue(vm.state.value.sortOpen)
        assertEquals(listOf(LibrarySort(LibrarySortCriterion.Title, descending = false)), host.kept)
    }

    @Test
    fun `pressing the applied row again turns it the other way round`() = runTest {
        val host = Sources(current = local)
        val vm = model(host)
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Title))
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Title))
        assertEquals(listOf("Cove", "Breakwater", "Anchor"), vm.titles())
        // The default is applied already, so its own row turns it oldest first.
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Updated))
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Updated))
        assertEquals(listOf("Breakwater", "Cove", "Anchor"), vm.titles())
    }

    @Test
    fun `a criterion the shown source keeps nothing for is refused`() = runTest {
        val host = Sources(current = local)
        val vm = model(host)
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Rating))
        assertEquals(LibrarySort.Default, vm.state.value.sort)
        assertTrue(host.kept.isEmpty())
    }

    @Test
    fun `a choice the source cannot sort by falls back, and comes back on a source that can`() = runTest {
        val rating = LibrarySort(LibrarySortCriterion.Rating, descending = true)
        val host = Sources(current = aniList, saved = rating)
        val vm = model(host)
        assertEquals(rating, vm.state.value.appliedSort)
        assertEquals(listOf("Fernwood", "Still Water"), vm.titles())

        vm.onAction(LibraryAction.SourceChosen(local))
        assertEquals(LibrarySort.Default, vm.state.value.appliedSort, "Local keeps no rating")
        assertEquals(rating, vm.state.value.sort, "the choice is kept")
        assertEquals(listOf("Anchor", "Cove", "Breakwater"), vm.titles())
        assertTrue(host.kept.isEmpty(), "falling back writes nothing")

        vm.onAction(LibraryAction.SourceChosen(aniList))
        assertEquals(rating, vm.state.value.appliedSort)
    }

    @Test
    fun `Done and Back close the panel with the choice already applied`() = runTest {
        val vm = model(Sources(current = local))
        vm.onAction(LibraryAction.SortOpened)
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Title))
        vm.onAction(LibraryAction.SortDismissed)
        assertFalse(vm.state.value.sortOpen)
        assertEquals(listOf("Anchor", "Breakwater", "Cove"), vm.titles())
    }

    @Test
    fun `without sources a sort still applies, and nothing keeps it`() = runTest {
        val vm = model(host = null)
        vm.onAction(LibraryAction.SortChosen(LibrarySortCriterion.Title))
        assertEquals(listOf("Anchor", "Breakwater", "Cove"), vm.titles())
    }
}
