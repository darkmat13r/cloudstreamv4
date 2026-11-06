@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [episodeSlots] against the arithmetic it was lifted out of.
 *
 * The id is a **storage key**: the stored position, the watched marker and the Continue Watching
 * entry are all filed under it, so an id that moves by one does not render slightly differently — it
 * files the viewer's progress where nothing reads it. The lift exists so that the TV detail page and
 * the old result page cannot drift apart; these tests are what says they have not.
 *
 * ## What "the old path" is here, exactly
 *
 * `ResultViewModel2.postEpisodes` cannot run in a JVM test: it is private, it posts to `LiveData`,
 * and `buildResultEpisode` reads `SharedPreferences` through `DataStoreHelper`. So [oldIds] is the
 * pre-lift expression **transcribed from `git show 8c0961248:…/ResultViewModel2.kt`**, lines 2228,
 * 2285 and 2344, with its walk order and its de-duplication. Transcribed and not executed, which is
 * the weakness of this file and the reason for the second half of every test: the literal ids below
 * are worked out by hand from the formula, so a mistake copied into [oldIds] cannot make a test pass.
 */
class EpisodeSlotsTest {

    /**
     * `postEpisodes`' three id expressions as they stood before the lift, with nothing else.
     *
     * Deliberately a near-verbatim copy, warts included: the `10_000` sort stride, `kotlin.run`-free
     * but otherwise the same control flow, and `HashSet` de-duplication shared across an anime's dub
     * lists.
     */
    private fun oldIds(loadResponse: LoadResponse, mainId: Int): List<Int> = when (loadResponse) {
        is AnimeLoadResponse -> {
            val existingEpisodes = HashSet<Int>()
            val ids = mutableListOf<Int>()
            loadResponse.episodes.map { ep ->
                val idIndex = ep.key.id
                for ((index, i) in ep.value.withIndex()) {
                    val episode = i.episode ?: (index + 1)
                    val id =
                        mainId + episode + idIndex * 1_000_000 + (i.season?.times(10_000) ?: 0)
                    if (!existingEpisodes.contains(id)) {
                        existingEpisodes.add(id)
                        ids += id
                    }
                }
            }
            ids
        }

        is TvSeriesLoadResponse -> {
            val existingEpisodes = HashSet<Int>()
            val ids = mutableListOf<Int>()
            for ((index, episode) in loadResponse.episodes.sortedBy {
                (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
            }.withIndex()) {
                val episodeIndex = episode.episode ?: (index + 1)
                val id = mainId + (episode.season?.times(100_000) ?: 0) + episodeIndex + 1
                if (!existingEpisodes.contains(id)) {
                    existingEpisodes.add(id)
                    ids += id
                }
            }
            ids
        }

        is MovieLoadResponse, is LiveStreamLoadResponse, is TorrentLoadResponse -> listOf(mainId)
        else -> emptyList()
    }

    private fun LoadResponse.slotIds(mainId: Int = MAIN_ID) = episodeSlots(mainId).map { it.id }

    /** Both paths, so neither test is only the function agreeing with itself. */
    private fun LoadResponse.assertIdsAre(expected: List<Int>) {
        assertEquals("the lifted function", expected, slotIds())
        assertEquals("the expression it was lifted from", expected, oldIds(this, MAIN_ID))
    }

    @Test
    fun `a movie is kept under the title's own id`() {
        movie().assertIdsAre(listOf(MAIN_ID))
    }

    @Test
    fun `a live stream and a torrent are too`() {
        liveStream().assertIdsAre(listOf(MAIN_ID))
        torrent().assertIdsAre(listOf(MAIN_ID))
    }

    @Test
    fun `a series is season times 100 000 plus episode plus one`() {
        val response = series(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2),
            episode(season = 2, number = 1),
        )
        response.assertIdsAre(
            listOf(
                MAIN_ID + 100_000 + 1 + 1,
                MAIN_ID + 100_000 + 2 + 1,
                MAIN_ID + 200_000 + 1 + 1,
            )
        )
        // Spelled out once, so that the formula is pinned and not only the shape of it.
        assertEquals(listOf(1_100_002, 1_100_003, 1_200_002), response.slotIds())
    }

    @Test
    fun `a season with one episode gets that season's own id and no index fallback`() {
        series(episode(season = 4, number = 7)).assertIdsAre(listOf(MAIN_ID + 400_000 + 7 + 1))
        // The `+ 1` is not cosmetic: without it a season's episode 1 would collide with the title id
        // of whatever hashes to `mainId + season * 100_000`, and for season 0 with the title itself.
        series(episode(season = null, number = 1)).assertIdsAre(listOf(MAIN_ID + 2))
    }

    @Test
    fun `an anime's dub and sub lists are a million apart`() {
        val response = anime(
            DubStatus.Dubbed to listOf(episode(season = 1, number = 1), episode(season = 1, number = 2)),
            DubStatus.Subbed to listOf(episode(season = 1, number = 1)),
        )
        // `DubStatus.Dubbed.id` is 1 and `Subbed.id` is 0, so subbed is the one with no stride.
        response.assertIdsAre(
            listOf(
                MAIN_ID + 1 + 1_000_000 + 10_000,
                MAIN_ID + 2 + 1_000_000 + 10_000,
                MAIN_ID + 1 + 0 + 10_000,
            )
        )
        assertEquals(listOf(2_010_001, 2_010_002, 1_010_001), response.slotIds())
    }

    @Test
    fun `the same episode dubbed and subbed are different ids`() {
        val ids = anime(
            DubStatus.Dubbed to listOf(episode(season = 1, number = 1)),
            DubStatus.Subbed to listOf(episode(season = 1, number = 1)),
        ).slotIds()
        assertEquals(2, ids.size)
        assertNotEquals("one stored position for both languages", ids[0], ids[1])
    }

    @Test
    fun `an unnumbered episode is numbered by where it sits, in the walked order`() {
        // A series is walked sorted, so a null-numbered episode sorts to the front of its season and
        // takes index 0 → number 1, which is why it collides with a real episode 1 below.
        val response = series(
            episode(season = 1, number = 2),
            episode(season = 1, number = null),
        )
        response.assertIdsAre(listOf(MAIN_ID + 100_000 + 1 + 1, MAIN_ID + 100_000 + 2 + 1))
        // Anime is walked in insertion order instead, so the unnumbered one sits at index 1 and is
        // numbered 2 — the number the episode before it already has. One id, and the second part is
        // dropped: the same input loses an episode on one response type and not on the other.
        anime(DubStatus.Subbed to listOf(episode(season = 1, number = 2), episode(season = 1, number = null)))
            .assertIdsAre(listOf(MAIN_ID + 2 + 10_000))
    }

    @Test
    fun `a repeated id drops the second part rather than renumbering it`() {
        val response = series(
            episode(season = 1, number = 1, data = "first"),
            episode(season = 1, number = 1, data = "second"),
        )
        response.assertIdsAre(listOf(MAIN_ID + 100_001 + 1))
        assertEquals(listOf("first"), response.episodeSlots(MAIN_ID).map { it.data })
    }

    /**
     * `loadSmall` opens a title from a library item with a response that is none of the five playable
     * types, which is the `else` arm: no parts until the full load replaces it.
     */
    @Test
    fun `a response that is none of the playable types has no parts`() {
        ResultViewModel2.LoadResponseFromSearch(
            name = "From the library",
            url = URL,
            apiName = API,
            type = TvType.TvSeries,
            posterUrl = null,
            id = MAIN_ID,
        ).assertIdsAre(emptyList())
    }

    /**
     * The index each slot carries, which is what `buildResultEpisode` is given.
     *
     * Counted before de-duplication, so it is not the slot's own position in the list — the old page
     * passes the walk index and this has to keep doing so.
     */
    @Test
    fun `the index is the walk index, not the surviving position`() {
        val slots = series(
            episode(season = 1, number = 1, data = "first"),
            episode(season = 1, number = 1, data = "duplicate"),
            episode(season = 1, number = 2, data = "third"),
        ).episodeSlots(MAIN_ID)
        assertEquals(listOf("first", "third"), slots.map { it.data })
        assertEquals("the duplicate consumed index 1", listOf(0, 2), slots.map { it.index })
    }

    private fun episode(season: Int?, number: Int?, data: String = "s$season-e$number") =
        Episode(data = data, season = season, episode = number)

    private fun series(vararg episodes: Episode) = TvSeriesLoadResponse(
        name = "Andor",
        url = URL,
        apiName = API,
        type = TvType.TvSeries,
        episodes = episodes.toList(),
    )

    private fun anime(vararg lists: Pair<DubStatus, List<Episode>>) = AnimeLoadResponse(
        name = "Frieren",
        url = URL,
        apiName = API,
        type = TvType.Anime,
        // Insertion-ordered, because the walk order is part of the formula.
        episodes = LinkedHashMap<DubStatus, List<Episode>>().apply { putAll(lists) },
    )

    private fun movie() = MovieLoadResponse(
        name = "Arrival",
        url = URL,
        apiName = API,
        type = TvType.Movie,
        dataUrl = "movie-payload",
    )

    private fun liveStream() = LiveStreamLoadResponse(
        name = "Channel",
        url = URL,
        apiName = API,
        dataUrl = "stream-payload",
    )

    private fun torrent() = TorrentLoadResponse(
        name = "Release",
        url = URL,
        apiName = API,
        magnet = "magnet:?xt=x",
        torrent = "https://example.test/x.torrent",
        plot = null,
    )

    private companion object {
        const val URL = "https://example.test/title"
        const val API = "Example"

        /** A round number so the arithmetic in every expectation can be read at a glance. */
        const val MAIN_ID = 1_000_000
    }
}
