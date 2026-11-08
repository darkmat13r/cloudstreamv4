package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.playOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The episode after [playing] (`KMP-94`), in the order a viewer goes through them ([playOrder]), not the provider's:
 * a provider can list S2 before S1, put specials among the numbers, or count a season down. Null for a film, for an
 * episode the list does not hold, and for the last one.
 *
 * Two episodes with the same `data` cannot be told apart by it, which is all the player knows of what it plays: the
 * first of them in that order is taken as the one playing.
 */
internal fun nextEpisode(episodes: List<DetailEpisode>, playing: String?): DetailEpisode? {
    if (playing == null) return null
    val order = episodes.playOrder()
    val at = order.indexOfFirst { it.data == playing }
    return if (at < 0) null else order.getOrNull(at + 1)
}

/**
 * Asks the title's page for [data]'s streams, as its Play does, and waits for the one it plays: the best found, after
 * `DesktopDetailViewModels.BEST_LINK_WAIT` at most (`KMP-39`). Null when the search ends with nothing to play; the page
 * under the player then shows why.
 *
 * Only while the page is not on screen, as under the player: it reads the page's effects, which have one reader.
 * Cancelled, it dismisses the search it started, so nothing it found plays later.
 */
internal suspend fun requestPlay(model: DetailViewModel, data: String): DetailEffect.Play? {
    model.onAction(DetailAction.PlayRequested(data))
    val nothing = model.state
        .filter { !it.linksArriving && (it.links is DetailLoad.Empty || it.links is DetailLoad.Failed) }
        .map { null }
    try {
        return merge(model.effect.filterIsInstance<DetailEffect.Play>(), nothing).first()
    } catch (cancelled: CancellationException) {
        // The player closed while this waited. Stop the search too, and with it a Play it was about to send, or the
        // page would later play the episode that was backed out of (review of #276).
        model.onAction(DetailAction.LinksDismissed)
        throw cancelled
    }
}
