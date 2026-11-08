package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The episode after [playing] in the title's list, the order its page shows (`KMP-94`). Null for a film, for an episode
 * the list does not hold, and for the last one.
 */
internal fun nextEpisode(episodes: List<DetailEpisode>, playing: String?): DetailEpisode? {
    if (playing == null) return null
    val at = episodes.indexOfFirst { it.data == playing }
    return if (at < 0) null else episodes.getOrNull(at + 1)
}

/**
 * Asks the title's page for [data]'s streams, as its Play does, and waits for the one it plays: the best found, after
 * `DesktopDetailViewModels.BEST_LINK_WAIT` at most (`KMP-39`). Null when the search ends with nothing to play; the page
 * under the player then shows why.
 *
 * Only while the page is not on screen, as under the player: it reads the page's effects, which have one reader.
 */
internal suspend fun requestPlay(model: DetailViewModel, data: String): DetailEffect.Play? {
    model.onAction(DetailAction.PlayRequested(data))
    val nothing = model.state
        .filter { !it.linksArriving && (it.links is DetailLoad.Empty || it.links is DetailLoad.Failed) }
        .map { null }
    return merge(model.effect.filterIsInstance<DetailEffect.Play>(), nothing).first()
}
