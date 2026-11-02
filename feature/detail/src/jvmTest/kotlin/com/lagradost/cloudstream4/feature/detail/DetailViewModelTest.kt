package com.lagradost.cloudstream4.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val movie = TitleDetail("Film", "P", "Movie", 1968, "plot", null, "data", emptyList())
    private val link = StreamLink("H264", "1080p", "https://a/b", null, null, null)

    private class FakeSource(
        var detail: suspend () -> TitleDetail,
        var links: suspend () -> List<StreamLink> = { emptyList() },
    ) : DetailSource {
        override suspend fun details(id: String) = detail()
        override suspend fun links(id: String, data: String) = links()
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

    @Test
    fun `activating a link asks the host to play it, under the title`() = runTest {
        val vm = model(FakeSource({ movie }, { listOf(link) }))
        val received = CompletableDeferred<DetailEffect>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { received.complete(vm.effect.first()) }
        vm.onAction(DetailAction.LinkActivated(link))
        assertEquals(DetailEffect.Play("Film", link), received.await())
        collector.cancel()
    }

    @Test
    fun `the primary button plays the title, else the first episode`() {
        assertEquals("data", movie.primaryData)
        val series = movie.copy(playData = null, episodes = listOf(DetailEpisode("One", "e1", 1, 1)))
        assertEquals("e1", series.primaryData)
        assertNull(movie.copy(playData = null).primaryData)
    }
}
