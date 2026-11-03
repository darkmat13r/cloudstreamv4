package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable

/**
 * @param links null while nobody has asked to play anything. Otherwise the source list replaces the
 *   page, and going back returns to the page without reloading it.
 */
@Immutable
data class DetailState(
    val content: DetailLoad<TitleDetail> = DetailLoad.Loading,
    val links: DetailLoad<List<StreamLink>>? = null,
)

/** How far a request got, with no words in it. The screens add them: see `toPageUiState`, `toLinksUiState`. */
sealed interface DetailLoad<out T> {
    data object Loading : DetailLoad<Nothing>
    data class Loaded<T>(val value: T) : DetailLoad<T>

    /** The source answered, with nothing playable. */
    data object Empty : DetailLoad<Nothing>

    /** [detail] is the machine-readable line under the words. */
    data class Failed(val detail: String) : DetailLoad<Nothing>
}

sealed interface DetailAction {
    data object Retry : DetailAction
    data class PlayRequested(val data: String) : DetailAction

    /** Asks for the sources the last [PlayRequested] asked for. */
    data object RetryLinks : DetailAction
    data class LinkActivated(val link: StreamLink) : DetailAction
    data object LinksDismissed : DetailAction
}

sealed interface DetailEffect {
    data class Play(val title: String, val link: StreamLink) : DetailEffect
}
