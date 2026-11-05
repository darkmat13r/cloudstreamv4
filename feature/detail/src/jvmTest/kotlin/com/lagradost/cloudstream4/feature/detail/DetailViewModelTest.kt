package com.lagradost.cloudstream4.feature.detail

import kotlin.test.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val movie = TitleDetail("Film", "P", "Movie", 1968, "plot", null, "data", emptyList())
    private val link = StreamLink("H264", "1080p", "https://a/b", null, null, null)

    private class FakeSource(
        var detail: suspend () -> TitleDetail,
        var links: suspend () -> List<StreamLink> = { emptyList() },
        var stream: (() -> Flow<List<StreamLink>>)? = null,
    ) : DetailSource {
        override suspend fun details(id: String) = detail()
        override fun links(id: String, data: String): Flow<List<StreamLink>> =
            stream?.invoke() ?: flow { links().takeIf { it.isNotEmpty() }?.let { emit(it) } }
    }

    /** Streams one value per [send], for as long as it stays open. */
    private class Arriving {
        val updates = Channel<List<StreamLink>>(Channel.UNLIMITED)
        var cancelled = false
        val flow: Flow<List<StreamLink>> = flow {
            try {
                for (list in updates) emit(list)
            } catch (gone: CancellationException) {
                cancelled = true
                throw gone
            }
        }

        fun send(vararg links: StreamLink) = updates.trySend(links.toList())
    }

    private fun TestScope.model(source: DetailSource) =
        DetailViewModel("id", source, TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `a title loads`() = runTest {
        val vm = model(FakeSource({ movie }))
        assertEquals(DetailLoad.Loaded(movie), vm.state.value.content)
        assertNull(vm.state.value.links)
    }

    @Test
    fun `a failed load names the reason, and retry recovers`() = runTest {
        val source = FakeSource({ error("provider exploded") })
        val vm = model(source)
        assertEquals("provider exploded", assertIs<DetailLoad.Failed>(vm.state.value.content).detail)

        source.detail = { movie }
        vm.onAction(DetailAction.Retry)
        assertEquals(DetailLoad.Loaded(movie), vm.state.value.content)
    }

    @Test
    fun `asking to play shows the sources`() = runTest {
        val vm = model(FakeSource({ movie }, { listOf(link) }))
        vm.onAction(DetailAction.PlayRequested("data"))
        assertEquals(DetailLoad.Loaded(listOf(link)), vm.state.value.links)
    }

    @Test
    fun `no links is empty, not an error`() = runTest {
        val vm = model(FakeSource({ movie }))
        vm.onAction(DetailAction.PlayRequested("data"))
        assertIs<DetailLoad.Empty>(vm.state.value.links)
    }

    @Test
    fun `failed links stay on the source list, and retry asks again`() = runTest {
        val source = FakeSource({ movie }, { error("extractor down") })
        val vm = model(source)
        vm.onAction(DetailAction.PlayRequested("data"))
        val failed = assertIs<DetailLoad.Failed>(vm.state.value.links)
        assertEquals("extractor down", failed.detail)

        source.links = { listOf(link) }
        vm.onAction(DetailAction.RetryLinks)
        assertEquals(DetailLoad.Loaded(listOf(link)), vm.state.value.links)
    }

    @Test
    fun `a slow answer cannot reopen a dismissed list`() = runTest {
        val gate = CompletableDeferred<List<StreamLink>>()
        val vm = model(FakeSource({ movie }, { gate.await() }))
        vm.onAction(DetailAction.PlayRequested("data"))
        assertEquals(DetailLoad.Loading, vm.state.value.links)

        vm.onAction(DetailAction.LinksDismissed)
        gate.complete(listOf(link))
        assertNull(vm.state.value.links)
        assertEquals(DetailLoad.Loaded(movie), vm.state.value.content, "the page does not reload")
    }

    @Test
    fun `an older request cannot overwrite a newer one, even if it ignores cancellation`() = runTest {
        val slow = CompletableDeferred<List<StreamLink>>()
        val newer = link.copy(label = "newer")
        val answers = ArrayDeque(listOf<suspend () -> List<StreamLink>>(
            { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { slow.await() } },
            { listOf(newer) },
        ))
        val vm = model(FakeSource({ movie }, { answers.removeFirst()() }))
        vm.onAction(DetailAction.PlayRequested("episode 1"))
        vm.onAction(DetailAction.PlayRequested("episode 2"))
        slow.complete(listOf(link))
        assertEquals(DetailLoad.Loaded(listOf(newer)), vm.state.value.links)
    }

    private val second = link.copy(label = "second", url = "https://a/c")

    @Test
    fun `the first link shows at once, and the list grows until the source has finished`() = runTest {
        val arriving = Arriving()
        val vm = model(FakeSource({ movie }, stream = { arriving.flow }))
        vm.onAction(DetailAction.PlayRequested("data"))
        assertEquals(DetailLoad.Loading, vm.state.value.links)
        assertTrue(vm.state.value.linksArriving)

        arriving.send(link)
        assertEquals(DetailLoad.Loaded(listOf(link)), vm.state.value.links)
        assertTrue(vm.state.value.linksArriving, "the first link is not the last")

        arriving.send(link, second)
        assertEquals(DetailLoad.Loaded(listOf(link, second)), vm.state.value.links)
        assertTrue(vm.state.value.linksArriving)

        arriving.updates.close()
        assertEquals(DetailLoad.Loaded(listOf(link, second)), vm.state.value.links)
        assertFalse(vm.state.value.linksArriving)
    }

    @Test
    fun `a source failing keeps the links it already gave`() = runTest {
        val arriving = Arriving()
        val vm = model(FakeSource({ movie }, stream = { arriving.flow }))
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(link)
        arriving.updates.close(java.io.IOException("the plugin host exited"))
        assertEquals(DetailLoad.Loaded(listOf(link)), vm.state.value.links)
        assertFalse(vm.state.value.linksArriving)
    }

    @Test
    fun `a source failing with nothing is still the failure`() = runTest {
        val arriving = Arriving()
        val vm = model(FakeSource({ movie }, stream = { arriving.flow }))
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.updates.close(java.io.IOException("CineStream did not answer within 90000ms"))
        assertEquals("CineStream did not answer within 90000ms", assertIs<DetailLoad.Failed>(vm.state.value.links).detail)
        assertFalse(vm.state.value.linksArriving)
    }

    @Test
    fun `finishing with nothing is empty, and only then`() = runTest {
        val arriving = Arriving()
        val vm = model(FakeSource({ movie }, stream = { arriving.flow }))
        vm.onAction(DetailAction.PlayRequested("data"))
        assertEquals(DetailLoad.Loading, vm.state.value.links)
        arriving.updates.close()
        assertEquals(DetailLoad.Empty, vm.state.value.links)
        assertFalse(vm.state.value.linksArriving)
    }

    @Test
    fun `picking a link leaves the search running, so what it finds is there to come back to`() = runTest {
        val arriving = Arriving()
        val vm = model(FakeSource({ movie }, stream = { arriving.flow }))
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(link)

        vm.onAction(DetailAction.LinkActivated(link))
        assertFalse(arriving.cancelled, "the source was left alone")
        assertTrue(vm.state.value.linksArriving)

        arriving.send(link, second)
        assertEquals(DetailLoad.Loaded(listOf(link, second)), vm.state.value.links)
    }

    @Test
    fun `dismissing stops the search`() = runTest {
        val arriving = Arriving()
        val vm = model(FakeSource({ movie }, stream = { arriving.flow }))
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(link)

        vm.onAction(DetailAction.LinksDismissed)
        assertTrue(arriving.cancelled, "the source was told")
        assertNull(vm.state.value.links)
        assertFalse(vm.state.value.linksArriving)
    }

    @Test
    fun `asking again starts from nothing and stops the last search`() = runTest {
        val first = Arriving()
        val again = Arriving()
        val answers = ArrayDeque(listOf(first, again))
        val vm = model(FakeSource({ movie }, stream = { answers.removeFirst().flow }))
        vm.onAction(DetailAction.PlayRequested("episode 1"))
        first.send(link)
        vm.onAction(DetailAction.PlayRequested("episode 2"))
        assertTrue(first.cancelled)
        assertEquals(DetailLoad.Loading, vm.state.value.links)
        assertTrue(vm.state.value.linksArriving)
    }

    @Test
    fun `activating a link asks the host to play it, under the title`() = runTest {
        val vm = model(FakeSource({ movie }, { listOf(link) }))
        val received = CompletableDeferred<DetailEffect>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { received.complete(vm.effect.first()) }
        vm.onAction(DetailAction.LinkActivated(link))
        assertEquals(DetailEffect.Play("Film", link), received.await())
        collector.cancel()
    }

    private fun TestScope.waiting(source: DetailSource) =
        DetailViewModel("id", source, TestScope(UnconfinedTestDispatcher(testScheduler)), playBestAfter = WAIT)

    /** Every [DetailEffect.Play], as it is posted. */
    private fun TestScope.plays(vm: DetailViewModel): MutableList<StreamLink> {
        val played = mutableListOf<StreamLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effect.collect { if (it is DetailEffect.Play) played += it.link }
        }
        return played
    }

    private val sd = link.copy(label = "sd", url = "https://a/sd", qualityLabel = "480p", quality = 480)
    private val uhd = link.copy(label = "uhd", url = "https://a/uhd", qualityLabel = "2160p", quality = 2160)
    private val fhd = link.copy(label = "fhd", url = "https://a/fhd", qualityLabel = "1080p", quality = 1080)

    @Test
    fun `asking to play plays the best link found by the end of the wait, not the first`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(sd)
        advanceTimeBy(WAIT / 2)
        arriving.send(sd, fhd)
        assertEquals(emptyList(), played, "nothing plays inside the wait")
        advanceTimeBy(WAIT / 2 + 1.milliseconds)
        assertEquals(listOf(fhd), played)
        // Better, but after the start: it is on the list and in the player's, and does not interrupt.
        arriving.send(sd, fhd, uhd)
        advanceTimeBy(WAIT * 2)
        arriving.updates.close()
        assertEquals(listOf(fhd), played, "it played once")
    }

    @Test
    fun `the wait counts from the first link, not from the request`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        advanceTimeBy(WAIT * 3)
        assertEquals(emptyList(), played)
        arriving.send(sd)
        advanceTimeBy(WAIT - 1.milliseconds)
        assertEquals(emptyList(), played)
        advanceTimeBy(2.milliseconds)
        assertEquals(listOf(sd), played)
    }

    @Test
    fun `a search that ends inside the wait plays at once`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(sd, uhd)
        arriving.updates.close()
        assertEquals(listOf(uhd), played)
        advanceTimeBy(WAIT * 2)
        assertEquals(listOf(uhd), played, "and the wait does not play it again")
    }

    @Test
    fun `a search that fails after some links still plays the best of them`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(sd, fhd)
        arriving.updates.close(java.io.IOException("the plugin host exited"))
        assertEquals(listOf(fhd), played)
    }

    @Test
    fun `choosing a row inside the wait is the choice, and nothing plays over it`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(sd, uhd)
        vm.onAction(DetailAction.LinkActivated(sd))
        advanceTimeBy(WAIT * 2)
        arriving.updates.close()
        assertEquals(listOf(sd), played)
    }

    @Test
    fun `dismissing the list inside the wait plays nothing`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.send(uhd)
        vm.onAction(DetailAction.LinksDismissed)
        advanceTimeBy(WAIT * 2)
        assertEquals(emptyList(), played)
    }

    @Test
    fun `nothing found plays nothing`() = runTest {
        val arriving = Arriving()
        val vm = waiting(FakeSource({ movie }, stream = { arriving.flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        arriving.updates.close()
        advanceTimeBy(WAIT * 2)
        assertEquals(emptyList(), played)
        assertEquals(DetailLoad.Empty, vm.state.value.links)
    }

    @Test
    fun `each request plays once, and the next episode plays its own best`() = runTest {
        val first = Arriving()
        val again = Arriving()
        val answers = ArrayDeque(listOf(first, again))
        val vm = waiting(FakeSource({ movie }, stream = { answers.removeFirst().flow }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("episode 1"))
        first.send(sd)
        advanceTimeBy(WAIT * 2)
        vm.onAction(DetailAction.PlayRequested("episode 2"))
        again.send(fhd, sd)
        advanceTimeBy(WAIT * 2)
        assertEquals(listOf(sd, fhd), played)
    }

    @Test
    fun `without a wait nothing plays by itself, as before`() = runTest {
        val vm = model(FakeSource({ movie }, { listOf(uhd) }))
        val played = plays(vm)
        vm.onAction(DetailAction.PlayRequested("data"))
        advanceTimeBy(WAIT * 2)
        assertEquals(emptyList(), played)
    }

    @Test
    fun `the primary button plays the title, else the first episode`() {
        assertEquals("data", movie.primaryData)
        val series = movie.copy(playData = null, episodes = listOf(DetailEpisode("One", "e1", 1, 1)))
        assertEquals("e1", series.primaryData)
        assertNull(movie.copy(playData = null).primaryData)
    }

    private companion object {
        val WAIT = 4.seconds
    }
}
