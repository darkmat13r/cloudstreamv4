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
    fun thereIsNoneAfterTheLastForAFilmOrForAnEpisodeTheListDoesNotHold() {
        assertNull(nextEpisode(listOf(one, two, three), "e3"))
        assertNull(nextEpisode(listOf(one, two), null))
        assertNull(nextEpisode(listOf(one, two), "elsewhere"))
        assertNull(nextEpisode(emptyList(), "e1"))
    }

    private val sd = StreamLink("Source One", "720p", "https://example.invalid/sd", null, null, null, quality = 720)
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
    fun aSearchThatEndsWithNothingOrFailsPlaysNothing() = runBlocking {
        assertNull(requestPlay(model(Source { flowOf(emptyList()) }), "e2"))
        assertNull(requestPlay(model(Source { flow { throw IllegalStateException("offline") } }), "e2"))
    }
}
