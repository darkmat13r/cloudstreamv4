package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** `KMP-94`: which episode is up next, and asking the title's page to play it. */
class NextEpisodeTest {
    private val one = DetailEpisode("Quiet Harbor", "e1", 1, 1)
    private val two = DetailEpisode("Low Tide", "e2", 1, 2)
    private val three = DetailEpisode("North Shore", "e3", 2, 1)

    @Test
    fun theNextEpisodeIsTheOneAfterInThePagesOrderAcrossSeasons() {
        assertEquals(two, nextEpisode(listOf(one, two, three), "e1"))
        assertEquals(three, nextEpisode(listOf(one, two, three), "e2"), "the next season's first")
    }

    @Test
    fun theOrderIsThePagesNotTheProvidersWhenItListsALaterSeasonFirst() {
        val s1e1 = DetailEpisode("Quiet Harbor", "a", 1, 1)
        val s1e2 = DetailEpisode("Low Tide", "b", 1, 2)
        val s2e1 = DetailEpisode("North Shore", "c", 2, 1)
        val listed = listOf(s2e1, s1e1, s1e2)
        assertEquals(s2e1, nextEpisode(listed, "b"), "the end of season 1 leads to season 2, though it is listed first")
        assertNull(nextEpisode(listed, "c"), "and season 2 is the last")
    }

    @Test
    fun specialsAmongTheNumbersAreSkippedOverAndComeAfterTheLastSeason() {
        val s1e1 = DetailEpisode("Quiet Harbor", "a", 1, 1)
        val special = DetailEpisode("Making Of", "sp", 0, 1)
        val s2e1 = DetailEpisode("North Shore", "c", 2, 1)
        val listed = listOf(s1e1, special, s2e1)
        assertEquals(s2e1, nextEpisode(listed, "a"), "season 1 goes on to season 2, not to the special between them")
        assertEquals(special, nextEpisode(listed, "c"), "Specials come after the numbered seasons, as the page lists them")
    }

    @Test
    fun aSeasonListedCountingDownIsWatchedCountingUp() {
        val listed = (3 downTo 1).map { DetailEpisode("Episode $it", "e$it", 1, it) }
        assertEquals("e2", nextEpisode(listed, "e1")?.data)
        assertEquals("e3", nextEpisode(listed, "e2")?.data)
        assertNull(nextEpisode(listed, "e3"))
    }

    @Test
    fun anUnnumberedEpisodeComesLastInItsSeasonAndTwoWithOneDataAreTakenAsTheFirst() {
        val unnumbered = DetailEpisode("Bonus", "x", 1, null)
        val listed = listOf(unnumbered, one, two)
        assertEquals(unnumbered, nextEpisode(listed, "e2"), "the unnumbered one after the numbered")
        // The player knows only `data`. With two episodes sharing it, the first in play order is the one playing.
        val twin = DetailEpisode("Low Tide, again", "e1", 1, 3)
        assertEquals(two, nextEpisode(listOf(one, two, twin), "e1"))
    }

    @Test
    fun thereIsNoneAfterTheLastForAFilmOrForAnEpisodeTheListDoesNotHold() {
        assertNull(nextEpisode(listOf(one, two, three), "e3"))
        assertNull(nextEpisode(listOf(one, two), null))
        assertNull(nextEpisode(listOf(one, two), "elsewhere"))
        assertNull(nextEpisode(emptyList(), "e1"))
    }

    private val sd = StreamLink("Source One", "720p", "https://example.invalid/sd", null, null, null, quality = 720)
    private val uhd = StreamLink("Source Three", "2160p", "https://example.invalid/uhd", null, null, null, quality = 2160)
    private val hd = StreamLink("Source Two", "1080p", "https://example.invalid/hd", null, null, null, quality = 1080)

    private class Source(private val links: (String) -> Flow<List<StreamLink>>) : DetailSource {
        val asked = mutableListOf<String>()
        override suspend fun details(id: String) =
            TitleDetail("Quiet Harbor", "Source One", "TvSeries", 2024, null, null, "e1", emptyList())

        override fun links(id: String, data: String): Flow<List<StreamLink>> {
            asked += data
            return links(data)
        }
    }

    private fun model(source: DetailSource) =
        DetailViewModel("id", source, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), playBestAfter = 8.seconds)

    @Test
    fun askingPlaysTheBestOfWhatTheSearchFoundForThatEpisode() = runBlocking {
        val source = Source { flowOf(listOf(sd), listOf(sd, hd)) }
        val played = requestPlay(model(source), "e2")
        assertEquals(listOf("e2"), source.asked)
        assertEquals(hd, played?.link, "the best, as the page's Play would")
    }

    @Test
    fun aPlayerClosedWhileItWaitsStopsTheSearchAndNothingItFindsPlaysLater() = runBlocking {
        val arriving = MutableSharedFlow<List<StreamLink>>()
        val page = model(Source { arriving })
        val waiting = CoroutineScope(Dispatchers.Unconfined).launch { requestPlay(page, "e2") }
        assertEquals(1, arriving.subscriptionCount.value, "the search is under way")
        // The player closes during Loading…, which cancels the scope this ran in.
        waiting.cancel()
        assertEquals(0, arriving.subscriptionCount.value, "the search stopped with it")
        assertNull(page.state.value.links, "and the page's list was dismissed")
        // Back on the page, its effects have a reader again; a 2160p link, which would play at once, finds no search.
        val played = mutableListOf<DetailEffect>()
        val reader = CoroutineScope(Dispatchers.Unconfined).launch { page.effect.collect { played += it } }
        arriving.emit(listOf(uhd))
        assertEquals(emptyList(), played, "no Play for the episode that was backed out of")
        reader.cancel()
    }

    @Test
    fun aSearchThatEndsWithNothingOrFailsPlaysNothing() = runBlocking {
        assertNull(requestPlay(model(Source { flowOf(emptyList()) }), "e2"))
        assertNull(requestPlay(model(Source { flow { throw IllegalStateException("offline") } }), "e2"))
    }
}
