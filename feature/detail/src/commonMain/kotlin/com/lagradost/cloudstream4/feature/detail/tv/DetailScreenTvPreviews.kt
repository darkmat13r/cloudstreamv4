package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import androidx.compose.ui.tooling.preview.Preview

/**
 * The load-bearing states, pinned to the artboard. The screenshot plugin cannot see a KMP module and
 * renders nothing on this toolchain (`DEC-10`), so these are not yet guarded by a build.
 */
private val PreviewCopy = DetailTvCopy(
    back = "Go Back",
    playMovie = "Play Movie",
    playEpisode = "Play Episode",
    episodes = "Episodes",
    noEpisodes = "No Episodes found",
    sources = "Sources",
    seasonShort = "S",
    episodeShort = "E",
    typeLabel = { it },
)

private val PreviewMovie = TitleDetail(
    title = "The Last Signal",
    providerName = "Provider",
    typeName = "Movie",
    year = 2019,
    plot = "Six months after the broadcast went dark, a salvage crew picks up a repeating signal.",
    posterUrl = null,
    playData = "data",
    episodes = emptyList(),
)

private val PreviewPrompt = StatePrompt("Source error", "Error Loading Links", StateAction("Try again") {})

@Composable
private fun Screen(state: DetailState) {
    CloudStreamTheme {
        DetailScreenTv(
            state = state,
            copy = PreviewCopy,
            onAction = {},
            onBack = {},
            artwork = {},
            modifier = Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg),
        )
    }
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun MoviePreview() = Screen(DetailState(UiState.Content(PreviewMovie)))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun SeriesPreview() = Screen(
    DetailState(
        UiState.Content(
            PreviewMovie.copy(
                playData = null,
                typeName = "TvSeries",
                episodes = List(6) { DetailEpisode("Episode ${it + 1}", "e$it", 1, it + 1) },
            )
        )
    )
)

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun NothingPlayablePreview() = Screen(DetailState(UiState.Content(PreviewMovie.copy(playData = null))))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun LoadFailedPreview() = Screen(DetailState(UiState.Error(PreviewPrompt, "NOT_LOADED")))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun SourcesPreview() = Screen(
    DetailState(
        UiState.Content(PreviewMovie),
        UiState.Content(List(4) { StreamLink("Source $it", "1080p", "https://example.invalid/$it", null, null, null) }),
    )
)

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun NoSourcesPreview() = Screen(DetailState(UiState.Content(PreviewMovie), UiState.Empty(PreviewPrompt)))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun SourcesFailedPreview() =
    Screen(DetailState(UiState.Content(PreviewMovie), UiState.Error(PreviewPrompt, "extractor down")))
