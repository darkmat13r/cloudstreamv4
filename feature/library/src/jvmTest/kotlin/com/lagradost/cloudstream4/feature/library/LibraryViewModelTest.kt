package com.lagradost.cloudstream4.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dune = LibraryTitleId("Prov\u0000/dune")
    private val arrival = LibraryTitleId("Prov\u0000/arrival")

    /** The store as a host would behave: a write changes what the next read returns. */
    private class Fake(
        titles: List<LibraryTitle> = emptyList(),
        positions: List<WatchPosition> = emptyList(),
    ) : LibraryStore {
        var titles = titles.toMutableList()
        var positions = positions.toMutableList()
        var failReads: Exception? = null
        var failWrites: Exception? = null
        var reads = 0

        override suspend fun titles(): List<LibraryTitle> {
            reads++
            failReads?.let { throw it }
            return titles.toList()
        }

        override suspend fun remember(title: LibraryTitle) {
            failWrites?.let { throw it }
            titles.removeAll { it.id == title.id }
            titles += title
        }

        override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) {
            failWrites?.let { throw it }
            titles.replaceAll { if (it.id == id) it.copy(bucket = bucket) else it }
        }

        override suspend fun positions(): List<WatchPosition> {
            failReads?.let { throw it }
            return positions.toList()
        }

        override suspend fun record(position: WatchPosition) {
            failWrites?.let { throw it }
            positions.removeAll { it.id == position.id && it.episodeData == position.episodeData }
            positions += position
        }

        override suspend fun clearPositions(id: LibraryTitleId) {
            failWrites?.let { throw it }
            positions.removeAll { it.id == id }
        }
    }

    private fun title(id: LibraryTitleId, bucket: LibraryBucket?, keptAt: Long = 0L) =
        LibraryTitle(id = id, title = id.value, bucket = bucket, keptAt = keptAt)

    private fun position(id: LibraryTitleId, watchedAt: Long = 0L) =
        WatchPosition(id, null, positionMillis = 60_000L, durationMillis = 600_000L, watchedAt = watchedAt)

    private fun TestScope.model(store: LibraryStore) =
        LibraryViewModel(store, TestScope(UnconfinedTestDispatcher(testScheduler)))

    /** Collects into [into] until cancelled, so a test can assert that nothing was posted. */
    private fun TestScope.collecting(vm: LibraryViewModel, into: MutableList<LibraryEffect>): Job =
        launch { vm.effect.collect { into += it } }

    @Test
    fun `the window opens on continue watching`() = runTest {
        val vm = model(Fake())
        assertEquals(LibraryTab.ContinueWatching, vm.state.value.tab)
    }

    @Test
    fun `an empty store is empty rather than failed`() = runTest {
        val vm = model(Fake())
        assertEquals(LibraryStatus.Empty, vm.state.value.status)
    }

    @Test
    fun `a store that cannot be read is a failure that names its reason`() = runTest {
        val store = Fake()
        store.failReads = IllegalStateException("permission denied")
        val vm = model(store)
        assertEquals(LibraryStatus.Failed("permission denied"), vm.state.value.status)
    }

    @Test
    fun `a failure with no message still says something`() = runTest {
        val store = Fake()
        store.failReads = IllegalStateException()
        val vm = model(store)
        assertEquals(LibraryStatus.Failed("IllegalStateException"), vm.state.value.status)
    }

    @Test
    fun `retry reads again and recovers`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Watching)))
        store.failReads = IllegalStateException("broken")
        val vm = model(store)
        assertIs<LibraryStatus.Failed>(vm.state.value.status)

        store.failReads = null
        vm.onAction(LibraryAction.Retry)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Watching)))
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `changing list does not touch the store`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Dropped)))
        val vm = model(store)
        val afterFirstRead = store.reads
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Dropped)))
        assertEquals(afterFirstRead, store.reads)
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `a list with nothing in it is empty while another still has content`() = runTest {
        val vm = model(Fake(listOf(title(dune, LibraryBucket.Completed))))
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Completed)))
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Dropped)))
        assertEquals(LibraryStatus.Empty, vm.state.value.status)
    }

    @Test
    fun `opening a title asks the host to open it and leaves the store alone`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Watching)))
        val vm = model(store)
        val effects = mutableListOf<LibraryEffect>()
        val collector = collecting(vm, effects)
        vm.onAction(LibraryAction.TitleActivated(dune))
        runCurrent()
        assertEquals(listOf<LibraryEffect>(LibraryEffect.OpenTitle(dune)), effects)
        assertEquals(1, store.titles.size)
        collector.cancel()
    }

    @Test
    fun `resume carries where to start from`() = runTest {
        val store = Fake(listOf(title(dune, null)), listOf(position(dune)))
        val vm = model(store)
        val effects = mutableListOf<LibraryEffect>()
        val collector = collecting(vm, effects)
        vm.onAction(LibraryAction.ResumeActivated(dune))
        runCurrent()
        assertEquals(
            listOf<LibraryEffect>(LibraryEffect.ResumeTitle(dune, LibraryResume(null, 60_000L))),
            effects,
        )
        collector.cancel()
    }

    @Test
    fun `resume on a card with nowhere to come back to does nothing at all`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.PlanToWatch)))
        val vm = model(store)
        val effects = mutableListOf<LibraryEffect>()
        val collector = collecting(vm, effects)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.PlanToWatch)))
        vm.onAction(LibraryAction.ResumeActivated(dune))
        runCurrent()
        assertEquals(emptyList(), effects)
        collector.cancel()
    }

    @Test
    fun `remove on a bucket takes the title out of every list and keeps its position`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Watching)), listOf(position(dune)))
        val vm = model(store)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Watching)))
        vm.onAction(LibraryAction.RemoveRequested(dune))

        assertEquals(LibraryStatus.Empty, vm.state.value.status)
        assertNull(store.titles.single().bucket)
        assertEquals(1, store.positions.size)
        // Still resumable, so it is still on continue watching — removing a bookmark is not forgetting.
        vm.onAction(LibraryAction.TabSelected(LibraryTab.ContinueWatching))
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `remove on continue watching clears the position and leaves the bookmark`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Watching)), listOf(position(dune)))
        val vm = model(store)
        vm.onAction(LibraryAction.RemoveRequested(dune))

        assertEquals(LibraryStatus.Empty, vm.state.value.status)
        assertEquals(emptyList(), store.positions)
        assertEquals(LibraryBucket.Watching, store.titles.single().bucket)
    }

    @Test
    fun `a write that fails shows what the store still holds instead of pretending`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Watching)))
        val vm = model(store)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Watching)))
        store.failWrites = IllegalStateException("read only")
        vm.onAction(LibraryAction.RemoveRequested(dune))

        val status = assertIs<LibraryStatus.Showing>(vm.state.value.status)
        assertEquals(listOf(dune), status.cards.map { it.id })
    }

    @Test
    fun `refresh picks up a bookmark made on another screen`() = runTest {
        val store = Fake()
        val vm = model(store)
        assertEquals(LibraryStatus.Empty, vm.state.value.status)

        store.titles += title(arrival, LibraryBucket.Favourites)
        vm.onAction(LibraryAction.Refresh)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Favourites)))
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `an empty library points at search`() = runTest {
        val vm = model(Fake())
        val effects = mutableListOf<LibraryEffect>()
        val collector = collecting(vm, effects)
        vm.onAction(LibraryAction.SearchRequested)
        runCurrent()
        assertEquals(listOf<LibraryEffect>(LibraryEffect.OpenSearch), effects)
        collector.cancel()
    }

    @Test
    fun `cards carry what the store knows about a title`() = runTest {
        val kept = LibraryTitle(
            id = dune,
            title = "Dune",
            bucket = LibraryBucket.Watching,
            keptAt = 5L,
            posterUrl = "https://example.com/p.jpg",
            providerName = "Prov",
        )
        val vm = model(Fake(listOf(kept)))
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Watching)))
        val card = assertIs<LibraryStatus.Showing>(vm.state.value.status).cards.single()
        assertEquals("Dune", card.title)
        assertEquals("https://example.com/p.jpg", card.posterUrl)
        assertEquals("Prov", card.providerName)
        assertTrue(card.progress == null)
    }

    private fun TestScope.tvModel(store: LibraryStore) = LibraryViewModel(
        store,
        TestScope(UnconfinedTestDispatcher(testScheduler)),
        initialTab = LibraryTab.Bucket(LibraryBucket.Watching),
    )

    @Test
    fun `a screen can open on a list of its own`() = runTest {
        val vm = tvModel(Fake(listOf(title(dune, LibraryBucket.Watching))))
        assertEquals(LibraryTab.Bucket(LibraryBucket.Watching), vm.state.value.tab)
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
    }

    @Test
    fun `tab counts are what was read and a title in two lists is one title`() = runTest {
        val vm = tvModel(
            Fake(
                listOf(
                    title(dune, LibraryBucket.Watching),
                    title(dune, LibraryBucket.Favourites),
                    title(arrival, LibraryBucket.Completed),
                    title(LibraryTitleId("played only"), null),
                ),
            ),
        )
        assertEquals(
            mapOf(LibraryBucket.Watching to 1, LibraryBucket.Favourites to 1, LibraryBucket.Completed to 1),
            vm.state.value.counts,
        )
        assertEquals(2, vm.state.value.totalTitles)
    }

    @Test
    fun `no counts exist before a read or after a failed one`() = runTest {
        val store = Fake(listOf(title(dune, LibraryBucket.Watching)))
        store.failReads = IllegalStateException("no")
        val vm = tvModel(store)
        assertEquals(emptyMap(), vm.state.value.counts)
        assertNull(vm.state.value.totalTitles)

        store.failReads = null
        vm.onAction(LibraryAction.Retry)
        assertEquals(1, vm.state.value.totalTitles)

        store.failReads = IllegalStateException("no")
        vm.onAction(LibraryAction.Retry)
        assertEquals(emptyMap(), vm.state.value.counts)
        assertNull(vm.state.value.totalTitles)
    }

    @Test
    fun `a filter narrows the cards and leaves the counts alone`() = runTest {
        val vm = tvModel(
            Fake(listOf(title(dune, LibraryBucket.Watching), title(arrival, LibraryBucket.Watching))),
        )
        vm.onAction(LibraryAction.FilterChanged("ARRIV"))
        val cards = assertIs<LibraryStatus.Showing>(vm.state.value.status).cards
        assertEquals(listOf(arrival), cards.map { it.id })
        assertEquals(2, vm.state.value.counts[LibraryBucket.Watching])

        vm.onAction(LibraryAction.FilterChanged("nothing has this"))
        assertEquals(LibraryStatus.Empty, vm.state.value.status)

        vm.onAction(LibraryAction.FilterChanged(""))
        assertEquals(2, assertIs<LibraryStatus.Showing>(vm.state.value.status).cards.size)
    }

    @Test
    fun `a filter set over a failure leaves the failure showing`() = runTest {
        val store = Fake()
        store.failReads = IllegalStateException("no")
        val vm = tvModel(store)
        vm.onAction(LibraryAction.FilterChanged("x"))
        assertIs<LibraryStatus.Failed>(vm.state.value.status)
    }

    @Test
    fun `opening from a grid keeps a return point until it is handled`() = runTest {
        val vm = tvModel(Fake(listOf(title(dune, LibraryBucket.Watching))))
        vm.onAction(LibraryAction.TitleActivated(dune, firstVisibleIndex = 14))
        assertEquals(LibraryReturn(dune, 14), vm.state.value.returnTo)
        vm.onAction(LibraryAction.Reappeared)
        assertEquals(LibraryReturn(dune, 14, ready = true), vm.state.value.returnTo)
        vm.onAction(LibraryAction.ReturnHandled)
        assertNull(vm.state.value.returnTo)
    }

    @Test
    fun `opening without an index keeps no return point, which is what desktop sends`() = runTest {
        val vm = model(Fake(listOf(title(dune, LibraryBucket.Watching))))
        vm.onAction(LibraryAction.TitleActivated(dune))
        assertNull(vm.state.value.returnTo)
    }

    /** Loading would take the focused action off the screen every time the page came back. */
    @Test
    fun `coming back to an empty list shows no skeleton, and still reads`() = runTest {
        val store = Fake()
        val seen = mutableListOf<LibraryStatus>()
        val vm = tvModel(store)
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it.status } }
        seen.clear()
        val reads = store.reads

        vm.onAction(LibraryAction.Reappeared)
        assertEquals(reads + 1, store.reads)
        assertTrue(seen.none { it is LibraryStatus.Loading }, "$seen")

        store.titles += title(dune, LibraryBucket.Watching)
        vm.onAction(LibraryAction.Reappeared)
        assertIs<LibraryStatus.Showing>(vm.state.value.status)
        watcher.cancel()
    }
}
