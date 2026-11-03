package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import androidx.compose.ui.tooling.preview.Preview

/**
 * The load-bearing states, pinned to the artboard. The screenshot plugin cannot see a KMP module and
 * renders nothing on this toolchain (`DEC-10`), so these are not yet guarded by a build.
 */
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

@Composable
private fun Screen(state: DetailState) {
    CloudStreamTheme {
        DetailScreenTv(
            state = state,
            onAction = {},
            onBack = {},
            artwork = {},
            modifier = Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg),
        )
    }
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun MoviePreview() = Screen(DetailState(DetailLoad.Loaded(PreviewMovie)))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun SeriesPreview() = Screen(
    DetailState(
        DetailLoad.Loaded(
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
private fun NothingPlayablePreview() = Screen(DetailState(DetailLoad.Loaded(PreviewMovie.copy(playData = null))))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun LoadFailedPreview() = Screen(DetailState(DetailLoad.Failed("NOT_LOADED")))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun SourcesPreview() = Screen(
    DetailState(
        DetailLoad.Loaded(PreviewMovie),
        DetailLoad.Loaded(List(4) { StreamLink("Source $it", "1080p", "https://example.invalid/$it", null, null, null) }),
    )
)

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun NoSourcesPreview() = Screen(DetailState(DetailLoad.Loaded(PreviewMovie), DetailLoad.Empty))

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun SourcesFailedPreview() =
    Screen(DetailState(DetailLoad.Loaded(PreviewMovie), DetailLoad.Failed("extractor down")))
