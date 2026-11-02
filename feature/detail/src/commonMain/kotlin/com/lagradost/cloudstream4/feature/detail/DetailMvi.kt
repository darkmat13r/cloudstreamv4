package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream4.core.designsystem.state.UiState

/**
 * @param links null while nobody has asked to play anything. Otherwise the source list replaces the
 *   page, and going back returns to the page without reloading it.
 */
@Immutable
data class DetailState(
    val content: UiState<TitleDetail> = UiState.Loading,
    val links: UiState<List<StreamLink>>? = null,
)

sealed interface DetailAction {
    data object Retry : DetailAction
    data class PlayRequested(val data: String) : DetailAction
    data class LinkActivated(val link: StreamLink) : DetailAction
    data object LinksDismissed : DetailAction
}

sealed interface DetailEffect {
    data class Play(val title: String, val link: StreamLink) : DetailEffect
}

/** The words for each state that carries a prompt, resolved by the host. */
interface DetailPrompts {
    fun failed(detail: String, onRetry: () -> Unit): UiState.Error
    fun linksFailed(detail: String, onRetry: () -> Unit): UiState.Error
    fun noLinks(onRetry: () -> Unit): UiState.Empty
}
