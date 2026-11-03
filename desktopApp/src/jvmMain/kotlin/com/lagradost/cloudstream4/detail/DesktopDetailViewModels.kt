package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Keeps a title's ViewModel while the player is over it, so backing out of the player lands on the
 * same source list. There is no navigation library yet to scope it.
 */
internal object DesktopDetailViewModels {
    private val live = mutableMapOf<String, DetailViewModel>()
    private val source: DetailSource = DesktopDetailSource()

    fun get(id: String, scope: CoroutineScope): DetailViewModel =
        live.getOrPut(id) { DetailViewModel(id, source, scope) }

    /** What a title's search has found so far, for the player that is over its page. */
    fun subtitles(id: String): Flow<List<SubtitleTrack>> =
        live[id]?.state?.map(DetailState::subtitlesSoFar)?.distinctUntilChanged() ?: emptyFlow()

    /** Whatever it was still asking a plugin for stops here, because the scope it runs in outlives it. */
    fun forget(id: String) {
        live.remove(id)?.onAction(DetailAction.LinksDismissed)
    }
}

/** A provider's subtitles belong to the title, so every link of an answer carries the same list. */
internal fun DetailState.subtitlesSoFar(): List<SubtitleTrack> =
    (links as? DetailLoad.Loaded)?.value?.firstOrNull()?.subtitles.orEmpty()
