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
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val movie = TitleDetail("Film", "P", "Movie", 1968, "plot", null, "data", emptyList())
    private val link = StreamLink("H264", "1080p", "https://a/b", null, null, null)

    private class FakeSource(
        var detail: suspend () -> TitleDetail,
        var links: suspend () -> List<StreamLink> = { emptyList() },
        var stream: (() -> Flow<List<StreamLink>>)? = null,
        var write: suspend () -> Unit = {},
    ) : DetailSource {
        var reads = 0
        val statuses = mutableListOf<DetailWatchStatus>()
        val favourites = mutableListOf<Boolean>()

        override suspend fun details(id: String): TitleDetail {
            reads++
            return detail()
        }

        override fun links(id: String, data: String): Flow<List<StreamLink>> =
            stream?.invoke() ?: flow { links().takeIf { it.isNotEmpty() }?.let { emit(it) } }

        override suspend fun setWatchStatus(id: String, status: DetailWatchStatus) {
            statuses += status
            write()
        }

        override suspend fun setFavourite(id: String, favourite: Boolean) {
            favourites += favourite
            write()
        }
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

    @Test
    fun `the primary button plays the title, else the first episode`() {
        assertEquals("data", movie.primaryData)
        val series = movie.copy(playData = null, episodes = listOf(DetailEpisode("One", "e1", 1, 1)))
        assertEquals("e1", series.primaryData)
        assertNull(movie.copy(playData = null).primaryData)
    }

    /** Collects one effect, having started collecting before the action: the channel is a rendezvous. */
    private suspend fun TestScope.effectOf(vm: DetailViewModel, action: DetailAction): DetailEffect {
        val received = CompletableDeferred<DetailEffect>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { received.complete(vm.effect.first()) }
        vm.onAction(action)
        val effect = received.await()
        collector.cancel()
        return effect
    }

    @Test
    fun `asking the host to play hands it the data and the position it was given`() = runTest {
        val vm = model(FakeSource({ movie }))
        assertEquals(
            DetailEffect.OpenPlayer("data", fromStart = false),
            effectOf(vm, DetailAction.PlaybackRequested("data", fromStart = false)),
        )
        assertEquals(
            DetailEffect.OpenPlayer("data", fromStart = true),
            effectOf(vm, DetailAction.PlaybackRequested("data", fromStart = true)),
        )
        assertNull(vm.state.value.links, "the host's player is not the source list")
    }

    @Test
    fun `playing the primary comes back to the primary, and playing an episode to the list`() = runTest {
        val series = movie.copy(playData = null, episodes = listOf(DetailEpisode("One", "e1", 1, 1), DetailEpisode("Two", "e2", 1, 2)))
        val vm = model(FakeSource({ series }))

        effectOf(vm, DetailAction.PlaybackRequested("e1", fromStart = false))
        assertEquals(DetailReturn(DetailFocus.Primary), vm.state.value.returnTo, "e1 is primaryData")

        effectOf(vm, DetailAction.PlaybackRequested("e2", fromStart = false))
        assertEquals(DetailReturn(DetailFocus.Episodes), vm.state.value.returnTo)
    }

    @Test
    fun `the trailer and a recommendation go straight out, and name where focus comes back to`() = runTest {
        val vm = model(FakeSource({ movie }))
        assertEquals(DetailEffect.PlayTrailer, effectOf(vm, DetailAction.TrailerRequested))
        assertEquals(DetailReturn(DetailFocus.Primary), vm.state.value.returnTo)

        assertEquals(
            DetailEffect.OpenTitle("other"),
            effectOf(vm, DetailAction.RecommendationActivated("other")),
        )
        assertEquals(DetailReturn(DetailFocus.Recommendations), vm.state.value.returnTo)
    }

    @Test
    fun `coming back re-reads the title and frees focus, and handling it clears the target`() = runTest {
        val updated = movie.copy(watchStatus = DetailWatchStatus.Completed)
        val source = FakeSource({ movie })
        val vm = model(source)
        effectOf(vm, DetailAction.PlaybackRequested("data", fromStart = false))
        assertEquals(false, vm.state.value.returnTo?.ready, "the host has not come back yet")

        source.detail = { updated }
        vm.onAction(DetailAction.Reappeared)
        assertEquals(DetailReturn(DetailFocus.Primary, ready = true), vm.state.value.returnTo)
        assertEquals(DetailLoad.Loaded(updated), vm.state.value.content, "a bookmark set elsewhere shows up")

        vm.onAction(DetailAction.ReturnHandled)
        assertNull(vm.state.value.returnTo)
    }

    @Test
    fun `a failed re-read leaves the page that is already on screen alone`() = runTest {
        val source = FakeSource({ movie })
        val vm = model(source)
        source.detail = { error("provider exploded") }
        vm.onAction(DetailAction.Reappeared)
        assertEquals(DetailLoad.Loaded(movie), vm.state.value.content)
    }

    @Test
    fun `picking a watch list shows it at once and writes it`() = runTest {
        val listed = movie.copy(watchStatus = DetailWatchStatus.None)
        val source = FakeSource({ listed })
        val vm = model(source)

        vm.onAction(DetailAction.WatchStatusPicked(DetailWatchStatus.OnHold))
        assertEquals(DetailWatchStatus.OnHold, vm.state.value.detail?.watchStatus)
        assertEquals(listOf(DetailWatchStatus.OnHold), source.statuses)
    }

    @Test
    fun `the favourite toggles from what is loaded, and writes what it now shows`() = runTest {
        val source = FakeSource({ movie.copy(isFavourite = false) })
        val vm = model(source)

        vm.onAction(DetailAction.FavouriteToggled)
        assertEquals(true, vm.state.value.detail?.isFavourite)
        vm.onAction(DetailAction.FavouriteToggled)
        assertEquals(false, vm.state.value.detail?.isFavourite)
        assertEquals(listOf(true, false), source.favourites)
    }

    @Test
    fun `a host with no watch lists has nothing to toggle`() = runTest {
        val source = FakeSource({ movie })
        val vm = model(source)
        vm.onAction(DetailAction.FavouriteToggled)
        assertEquals(DetailLoad.Loaded(movie), vm.state.value.content)
        assertTrue(source.favourites.isEmpty())
    }

    @Test
    fun `a write that throws does not take the page down`() = runTest {
        val source = FakeSource({ movie.copy(isFavourite = false) }, write = { error("the store is gone") })
        val vm = model(source)

        vm.onAction(DetailAction.FavouriteToggled)
        vm.onAction(DetailAction.WatchStatusPicked(DetailWatchStatus.Dropped))
        assertEquals(true, vm.state.value.detail?.isFavourite)
        assertEquals(DetailWatchStatus.Dropped, vm.state.value.detail?.watchStatus)
    }

    @Test
    fun `the synopsis opens and closes, and nothing is re-read for it`() = runTest {
        val source = FakeSource({ movie })
        val vm = model(source)
        val reads = source.reads

        vm.onAction(DetailAction.SynopsisToggled)
        assertTrue(vm.state.value.synopsisOpen)
        vm.onAction(DetailAction.SynopsisToggled)
        assertFalse(vm.state.value.synopsisOpen)
        assertEquals(reads, source.reads)
    }

    @Test
    fun `a dismiss closes whichever panel is open, and is not a toggle`() = runTest {
        val source = FakeSource({ movie })
        val vm = model(source)

        // The host's back callback fires without knowing which panel is up, so a toggle would open the
        // synopsis from a closed page. This is why PanelDismissed is its own action.
        vm.onAction(DetailAction.PanelDismissed)
        assertFalse(vm.state.value.synopsisOpen, "dismiss on a closed page opens nothing")
        assertFalse(vm.state.value.watchStatusPicking)
        assertFalse(vm.state.value.panelOpen)

        vm.onAction(DetailAction.SynopsisToggled)
        assertTrue(vm.state.value.panelOpen)
        vm.onAction(DetailAction.PanelDismissed)
        assertFalse(vm.state.value.panelOpen, "dismiss closes the synopsis")

        vm.onAction(DetailAction.WatchStatusPickerOpened)
        assertTrue(vm.state.value.watchStatusPicking)
        assertTrue(vm.state.value.panelOpen, "panelOpen is what a host gates its back callback on")
        vm.onAction(DetailAction.PanelDismissed)
        assertFalse(vm.state.value.watchStatusPicking, "dismiss closes the picker")
    }

    @Test
    fun `picking a watch status commits it and closes the picker`() = runTest {
        val source = FakeSource({ movie.copy(watchStatus = DetailWatchStatus.None) })
        val vm = model(source)

        vm.onAction(DetailAction.WatchStatusPickerOpened)
        assertTrue(vm.state.value.watchStatusPicking)

        // DEC-17: a single-choice panel commits on the press, so the press is also the dismiss.
        vm.onAction(DetailAction.WatchStatusPicked(DetailWatchStatus.Completed))
        assertFalse(vm.state.value.watchStatusPicking, "the pick closes the panel")
        assertEquals(DetailWatchStatus.Completed, vm.state.value.detail?.watchStatus)
        assertEquals(listOf(DetailWatchStatus.Completed), source.statuses)
    }

    @Test
    fun `the row names a season only when there is exactly one, and counts that one season`() {
        assertNull(movie.namedSeason(), "no season named, no button")

        val forty = List(40) { DetailEpisode("E$it", "e$it", 1, it + 1) }
        val one = movie.copy(seasons = listOf(DetailSeason(number = 1, episodeCount = 40)), episodes = forty)
        assertEquals(DetailSeason(1, 40), one.namedSeason())

        // The count is the season's, not the title's whole list: on a single-season show the two agree,
        // which is exactly why reading the wrong one survives review.
        val short = movie.copy(seasons = listOf(DetailSeason(number = 2, episodeCount = 10)), episodes = forty)
        assertEquals(10, short.namedSeason()?.episodeCount)
        assertEquals(2, short.namedSeason()?.number)

        val several = movie.copy(
            seasons = listOf(DetailSeason(1, 10), DetailSeason(2, 30)),
            episodes = forty,
        )
        assertNull(several.namedSeason(), "two seasons and no selector: the row names neither")
    }

    @Test
    fun `a cast position swaps its role line and swaps back, independently of its neighbours`() = runTest {
        val vm = model(FakeSource({ movie }))
        vm.onAction(DetailAction.CastMemberActivated(4))
        assertEquals(setOf(4), vm.state.value.swappedRoles)

        vm.onAction(DetailAction.CastMemberActivated(0))
        assertEquals(setOf(0, 4), vm.state.value.swappedRoles)

        vm.onAction(DetailAction.CastMemberActivated(4))
        assertEquals(setOf(0), vm.state.value.swappedRoles)
    }
}
