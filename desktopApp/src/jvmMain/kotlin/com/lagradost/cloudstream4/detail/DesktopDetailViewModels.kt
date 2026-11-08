package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps a title's ViewModel while the player is over it, so backing out of the player lands on the
 * same source list. There is no navigation library yet to scope it.
 */
internal object DesktopDetailViewModels {
    private val live = mutableMapOf<String, DetailViewModel>()
    private val plugins: DetailSource = DesktopDetailSource()

    /**
     * @param library what this computer remembers about the title (`KMP-58`): where it was left and which list it is
     *   on. Null opens the page with the provider's word alone.
     */
    fun get(id: String, scope: CoroutineScope, library: LibraryStore? = null): DetailViewModel =
        live.getOrPut(id) {
            val source = library?.let { LibraryDetailSource(plugins, it) } ?: plugins
            DetailViewModel(id, source, scope, playBestAfter = BEST_LINK_WAIT)
        }

    /**
     * How long after a title's first playable link the best one found is played, unless a 2160p one arrives first
     * (owner, 2026-11-05: "highest resolution first"). CineStream's CineTmdb, five titles in a copy of the owner's
     * plugins: its best link came 3.8 to 7.1 s after its first, and 7.2 s was the latest in all 15 runs of its three
     * providers. See `KMP-39`.
     */
    val BEST_LINK_WAIT = 8.seconds

    /** What a title's search has found so far, for the player that is over its page. */
    fun subtitles(id: String): Flow<List<SubtitleTrack>> =
        live[id]?.state?.map(DetailState::subtitlesSoFar)?.distinctUntilChanged() ?: emptyFlow()

    /** The streams it has found so far, for the player over its page to switch between. */
    fun links(id: String): Flow<List<StreamLink>> =
        live[id]?.state
            ?.map { (it.links as? DetailLoad.Loaded)?.value.orEmpty() }
            ?.distinctUntilChanged()
            ?: emptyFlow()

    /** Whatever it was still asking a plugin for stops here, because the scope it runs in outlives it. */
    fun forget(id: String) {
        live.remove(id)?.onAction(DetailAction.LinksDismissed)
    }
}

/** A provider's subtitles belong to the title, so every link of an answer carries the same list. */
internal fun DetailState.subtitlesSoFar(): List<SubtitleTrack> =
    (links as? DetailLoad.Loaded)?.value?.firstOrNull()?.subtitles.orEmpty()
