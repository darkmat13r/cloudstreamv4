package com.lagradost.cloudstream4.feature.library

import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** `KMP-51`: a service that cannot be read (5b), the device offline, coming back, and `BUG-33`. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryOfflineViewModelTest {

    private val local = LibrarySourceId("Local")
    private val aniList = LibrarySourceId("AniList")

    private class Network(start: Connectivity) : ConnectivityFlow {
        override val status = MutableStateFlow(start)
    }

    private class Store(var fail: Exception? = null) : LibraryStore {
        override suspend fun titles(): List<LibraryTitle> {
            fail?.let { throw it }
            return listOf(
                LibraryTitle(LibraryTitleId("Prov\u0000/tide"), "Tide", LibraryBucket.Watching, keptAt = 1),
            )
        }

        override suspend fun positions() = emptyList<WatchPosition>()
        override suspend fun remember(title: LibraryTitle) = Unit
        override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = Unit
        override suspend fun record(position: WatchPosition) = Unit
        override suspend fun clearPositions(id: LibraryTitleId) = Unit
    }

    private inner class Sources(var current: LibrarySourceId = aniList) : LibrarySources {
        var reads = 0
        var fail: Exception? = null

        override suspend fun sources() = listOf(
            LibrarySource(aniList, "AniList", account = "tidewatch", syncedAt = 1_000L),
            LibrarySource(local, "Local", local = true),
        )

        override suspend fun current() = current
        override suspend fun choose(id: LibrarySourceId) {
            current = id
        }

        override suspend fun lists(id: LibrarySourceId): List<SyncedList> {
            reads++
            fail?.let { throw it }
            return listOf(
                SyncedList(
                    "watching",
                    "Watching",
                    listOf(LibraryTitle(LibraryTitleId("AniList\u0000/fernwood"), "Fernwood", null, keptAt = 2)),
                ),
            )
        }
    }

    private fun TestScope.model(
        host: LibrarySources?,
        network: ConnectivityFlow,
        store: LibraryStore = Store(),
    ) = LibraryViewModel(
        store,
        TestScope(UnconfinedTestDispatcher(testScheduler)),
        initialTab = LibraryTab.Bucket(LibraryBucket.Watching),
        sources = host,
        connectivity = network,
    )

    private fun LibraryViewModel.titles() = assertIs<LibraryStatus.Showing>(state.value.status).cards.map { it.title }

    @Test
    fun `a service that fails after a good read shows that read, marked unreachable`() = runTest {
        val host = Sources()
        val vm = model(host, Network(Connectivity.ONLINE))
        assertEquals(listOf("Fernwood"), vm.titles())
        assertNull(vm.state.value.stale)

        host.fail = java.io.IOException("unreachable")
        vm.onAction(LibraryAction.Retry)
        assertEquals(listOf("Fernwood"), vm.titles())
        assertEquals(LibraryStale.Unreachable, vm.state.value.stale)
        assertEquals(listOf("Watching"), vm.state.value.syncedTabs?.map { it.label }, "its tabs stay")

        host.fail = null
        vm.onAction(LibraryAction.Retry)
        assertNull(vm.state.value.stale, "a good read is live again")
    }

    @Test
    fun `a service that fails with nothing held is the failure page`() = runTest {
        val host = Sources().apply { fail = java.io.IOException("unreachable") }
        val vm = model(host, Network(Connectivity.ONLINE))
        assertIs<LibraryStatus.Failed>(vm.state.value.status)
        assertNull(vm.state.value.stale)
    }

    @Test
    fun `offline with nothing held, the service is not asked and says it needs a connection`() = runTest {
        val host = Sources()
        val vm = model(host, Network(Connectivity.OFFLINE))
        assertEquals(LibraryStatus.Offline, vm.state.value.status)
        assertEquals(0, host.reads)
    }

    @Test
    fun `offline after a good read, the held read is shown and the service is not asked`() = runTest {
        val host = Sources()
        val network = Network(Connectivity.ONLINE)
        val vm = model(host, network)
        network.status.value = Connectivity.OFFLINE
        runCurrent()
        vm.onAction(LibraryAction.Retry)
        assertEquals(listOf("Fernwood"), vm.titles())
        assertEquals(LibraryStale.Offline, vm.state.value.stale)
        assertEquals(1, host.reads)
    }

    @Test
    fun `unknown connectivity is never offline`() = runTest {
        val host = Sources()
        val vm = model(host, Network(Connectivity.UNKNOWN))
        assertEquals(listOf("Fernwood"), vm.titles())
        assertEquals(1, host.reads)
    }

    @Test
    fun `back online, the shown service is read once, after the connection has held`() = runTest {
        val host = Sources()
        val network = Network(Connectivity.OFFLINE)
        val vm = model(host, network)
        assertEquals(LibraryStatus.Offline, vm.state.value.status)

        network.status.value = Connectivity.ONLINE
        runCurrent()
        advanceTimeBy(RECONNECT_SETTLE_MS - 1)
        runCurrent()
        assertEquals(0, host.reads, "not before it has held")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, host.reads)
        assertEquals(listOf("Fernwood"), vm.titles())
        assertNull(vm.state.value.stale)
    }

    @Test
    fun `a network that flaps is one read when it settles, not one per flap`() = runTest {
        val host = Sources()
        val network = Network(Connectivity.OFFLINE)
        model(host, network)
        repeat(5) {
            network.status.value = Connectivity.ONLINE
            runCurrent()
            advanceTimeBy(RECONNECT_SETTLE_MS / 4)
            network.status.value = Connectivity.OFFLINE
            runCurrent()
            advanceTimeBy(RECONNECT_SETTLE_MS / 4)
        }
        assertEquals(0, host.reads, "it never held")
        network.status.value = Connectivity.ONLINE
        runCurrent()
        advanceTimeBy(RECONNECT_SETTLE_MS * 3)
        runCurrent()
        assertEquals(1, host.reads)
    }

    @Test
    fun `back online, a service shown live, or the local list, is not read again`() = runTest {
        val host = Sources()
        val network = Network(Connectivity.ONLINE)
        model(host, network)
        network.status.value = Connectivity.OFFLINE
        runCurrent()
        network.status.value = Connectivity.ONLINE
        runCurrent()
        advanceTimeBy(RECONNECT_SETTLE_MS * 2)
        runCurrent()
        assertEquals(1, host.reads, "it was live, so nothing is asked")

        val onLocal = Sources(current = local)
        val localNetwork = Network(Connectivity.OFFLINE)
        val vm = model(onLocal, localNetwork)
        assertEquals(listOf("Tide"), vm.titles(), "the local list does not need a connection")
        localNetwork.status.value = Connectivity.ONLINE
        runCurrent()
        advanceTimeBy(RECONNECT_SETTLE_MS * 2)
        runCurrent()
        assertEquals(0, onLocal.reads)
    }

    @Test
    fun `BUG-33 - after a failed read, another tab or a filter keeps the failure, not an empty list`() = runTest {
        val vm = model(host = null, network = Network(Connectivity.UNKNOWN), store = Store(fail = IllegalStateException("x")))
        assertIs<LibraryStatus.Failed>(vm.state.value.status)
        vm.onAction(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Completed)))
        assertIs<LibraryStatus.Failed>(vm.state.value.status)
        assertEquals(LibraryTab.Bucket(LibraryBucket.Completed), vm.state.value.tab)
        vm.onAction(LibraryAction.FilterChanged("du"))
        assertIs<LibraryStatus.Failed>(vm.state.value.status)
    }

    private companion object {
        /** `LibraryViewModel`'s own, restated: it is private there. */
        const val RECONNECT_SETTLE_MS = 2_000L
    }
}
