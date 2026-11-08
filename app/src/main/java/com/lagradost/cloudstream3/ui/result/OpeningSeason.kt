package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ui.result.ResultViewModel2.EpisodeIndexer
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.firstEpisodeToPlay

/** What this page files an episode with no season under, specials included. */
private const val NO_SEASON = 0

/** The season to aim for when there are no episodes to ask. */
private const val FIRST_SEASON = 1

/**
 * Which season and dub the legacy result page opens on: the one nearest the season the user last viewed here, in the
 * dub they prefer.
 *
 * With no remembered season it aims for the season an unstarted show starts in, the owner's S1E1 rule (`KMP-92`),
 * taken from `:feature:detail`'s [firstEpisodeToPlay] so the phone and the Compose pages share one rule. Before, it
 * aimed for season 1, so a show with no season 1 and its specials listed first opened on the specials.
 */
internal fun openingIndexer(
    indexers: Collection<EpisodeIndexer>,
    episodes: Collection<ResultEpisode>,
    rememberedSeason: Int?,
    dub: DubStatus?,
): EpisodeIndexer? {
    val target = rememberedSeason ?: startSeason(episodes)
    return indexers.minByOrNull { index ->
        kotlin.math.abs(index.season - target) + if (index.dubStatus == dub) 0 else 100000
    }
}

/** The season of the episode an unstarted show starts on, as this page numbers seasons. */
internal fun startSeason(episodes: Collection<ResultEpisode>): Int =
    episodes.map { DetailEpisode(it.name, it.data, it.seasonIndex, it.episode) }
        .firstEpisodeToPlay()
        ?.let { it.season ?: NO_SEASON }
        ?: FIRST_SEASON
