package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultViewModel2.EpisodeIndexer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The season the legacy result page opens on (`KMP-92`'s phone follow-up). Before, with no remembered season, it
 * aimed for season 1, so a show with no season 1 and its specials listed first opened on the specials. Season 0 is
 * also where this page files episodes with no season.
 */
class OpeningSeasonTest {

    private fun episode(seasonIndex: Int?, number: Int) = ResultEpisode(
        headerName = "Show", name = "E$number", poster = null, episode = number, seasonIndex = seasonIndex,
        season = seasonIndex, data = "s$seasonIndex-e$number", apiName = "Api", id = number, index = number,
        position = 0L, duration = 0L, score = null, description = null, isFiller = null, tvType = TvType.TvSeries,
        parentId = 1, videoWatchState = VideoWatchState.None,
    )

    /** The indexers in the order the episodes first name them, as `updateEpisodes` builds its map. */
    private fun indexers(episodes: List<ResultEpisode>) =
        episodes.map { EpisodeIndexer(DubStatus.None, it.seasonIndex ?: 0) }.distinct()

    private fun opening(episodes: List<ResultEpisode>, remembered: Int? = null) =
        openingIndexer(indexers(episodes), episodes, remembered, DubStatus.None)?.season

    @Test
    fun `no season 1 and the specials listed first, it opens on the lowest numbered season`() {
        val episodes = listOf(episode(0, 1), episode(2, 1), episode(3, 1))
        assertEquals(2, opening(episodes))
    }

    @Test
    fun `specials listed first with a season 1, it opens on season 1, as it did`() {
        val episodes = listOf(episode(0, 1), episode(1, 1), episode(2, 1))
        assertEquals(1, opening(episodes))
    }

    @Test
    fun `a remembered season still wins, specials included`() {
        val episodes = listOf(episode(0, 1), episode(2, 1), episode(3, 1))
        assertEquals(0, opening(episodes, remembered = 0))
        assertEquals(3, opening(episodes, remembered = 3))
    }

    @Test
    fun `only specials, or no seasons at all, it opens on the one group there is`() {
        assertEquals(0, opening(listOf(episode(0, 2), episode(0, 1))))
        assertEquals(0, opening(listOf(episode(null, 1), episode(null, 2))))
    }

    @Test
    fun `the start season is the season of the episode the Compose pages would start`() {
        assertEquals(2, startSeason(listOf(episode(0, 1), episode(3, 1), episode(2, 4), episode(2, 1))))
        assertEquals(0, startSeason(listOf(episode(0, 1), episode(null, 1))))
        assertEquals(1, startSeason(emptyList()))
    }
}
