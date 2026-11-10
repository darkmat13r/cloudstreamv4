package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailRecommendation
import com.lagradost.cloudstream4.feature.detail.DetailResume
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail

/**
 * The states the device tests drive, pinned to the artboard's 960x540.
 *
 * Development previews only: the screenshot plugin cannot see a KMP module and renders nothing on this
 * toolchain (`DEC-10`, `.claude/rules/screenshot-testing.md`), so nothing here is guarded by a build.
 * The real coverage is the Android device tests, and the curated screenshot set lives in
 * `:core:screenshot`.
 */
private val PreviewCast = listOf(
    DetailCastMember("Ines Marlowe", "https://example.invalid/1", "Cdr. Rhea Voss"),
    DetailCastMember("Tobias Kerr", "https://example.invalid/2", "Idris Pell"),
    DetailCastMember("Amara Osei", "https://example.invalid/3", "Dr. Lune Hart"),
    DetailCastMember("Jonah Wilde", "https://example.invalid/4", "Casimir"),
    // No image: the board's initials variant, cast item 5.
    DetailCastMember("Ren Takeda", null, "Main", voiceActorName = "Hana Sato"),
    DetailCastMember("Saoirse Doyle", "https://example.invalid/6", null),
    DetailCastMember("Maya Lindqvist", "https://example.invalid/7", "Kestrel"),
)

private val PreviewRecommendations = listOf(
    DetailRecommendation("1", "Ashen Field", null, 2021),
    DetailRecommendation("2", "Deep Signal", null, 2019),
    DetailRecommendation("3", "Quiet Harbor", null, 2023),
    DetailRecommendation("4", "Nine Lives", null, 2020),
    DetailRecommendation("5", "Salt Road", null, 2022),
    DetailRecommendation("6", "Hollow Crown", null, 2018),
    DetailRecommendation("7", "Drift", null, 2024),
)

/** 7b: a film, with a stored position two thirds of the way through. */
private val PreviewMovie = TitleDetail(
    title = "The Last Expedition",
    providerName = "Source A",
    typeName = "Movie",
    year = 2024,
    plot = "A salvage crew answers a distress call from a ship that vanished forty years ago, with one " +
        "more name on its manifest than there should be.",
    posterUrl = null,
    playData = "movie",
    episodes = emptyList(),
    score = 8.4f,
    tags = listOf("Science Fiction", "Thriller"),
    contentRating = "PG-13",
    durationMinutes = 136,
    cast = PreviewCast,
    recommendations = PreviewRecommendations,
    hasTrailer = true,
    resume = DetailResume("movie", positionMs = 5_047_000L, durationMs = 8_160_000L, episode = null),
    watchStatus = DetailWatchStatus.Watching,
    isFavourite = false,
)

/** 7a: a series, ten episodes in one season. */
private val PreviewShow = PreviewMovie.copy(
    title = "The Last Signal",
    typeName = "TvSeries",
    year = 2019,
    plot = "Six months after the broadcast went dark, a salvage crew picks up a repeating signal from a " +
        "station decommissioned forty years ago.",
    playData = null,
    episodes = episodes(10),
    tags = listOf("Drama", "Thriller", "Mystery"),
    contentRating = "TV-MA",
    statusName = "Ongoing",
    durationMinutes = null,
    seasons = listOf(DetailSeason(number = 1, episodeCount = 10)),
    resume = DetailResume("e3", positionMs = 1_000_000L, durationMs = 2_620_000L, episode = episodes(4)[3]),
)

private fun episodes(count: Int): List<DetailEpisode> =
    List(count) { DetailEpisode("Episode ${it + 1}", "e$it", 1, it + 1) }

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

@Preview(name = "Show", widthDp = 960, heightDp = 540)
@Composable
private fun DetailShow() = Screen(DetailState(DetailLoad.Loaded(PreviewShow)))

@Preview(name = "Movie", widthDp = 960, heightDp = 540)
@Composable
private fun DetailMovie() = Screen(DetailState(DetailLoad.Loaded(PreviewMovie)))

@Preview(name = "Loading", widthDp = 960, heightDp = 540)
@Composable
private fun DetailLoading() = Screen(DetailState(DetailLoad.Loading))

@Preview(name = "Failed", widthDp = 960, heightDp = 540)
@Composable
private fun DetailFailed() = Screen(DetailState(DetailLoad.Failed("NOT_LOADED")))

/** Nothing playable: no primary, and the caption slot carries `no_episodes_found` instead. */
@Preview(name = "No episodes", widthDp = 960, heightDp = 540)
@Composable
private fun DetailNoEpisodes() = Screen(
    DetailState(DetailLoad.Loaded(PreviewShow.copy(episodes = emptyList(), seasons = emptyList(), resume = null))),
)

@Preview(name = "One episode", widthDp = 960, heightDp = 540)
@Composable
private fun DetailOneEpisode() = Screen(
    DetailState(
        DetailLoad.Loaded(
            PreviewShow.copy(episodes = episodes(1), seasons = listOf(DetailSeason(1, 1)), resume = null),
        ),
    ),
)

@Preview(name = "Forty episodes", widthDp = 960, heightDp = 540)
@Composable
private fun DetailFortyEpisodes() = Screen(
    DetailState(
        DetailLoad.Loaded(PreviewShow.copy(episodes = episodes(40), seasons = listOf(DetailSeason(1, 40)))),
    ),
)

/**
 * Two seasons, so the row names neither: `Season 1` beside a count from one of them would contradict
 * an episode list that holds both. The list carries the seasons itself, in each row's `S1:E4`.
 */
@Preview(name = "Two seasons", widthDp = 960, heightDp = 540)
@Composable
private fun DetailTwoSeasons() = Screen(
    DetailState(
        DetailLoad.Loaded(
            PreviewShow.copy(
                episodes = episodes(10) + List(30) { DetailEpisode("Episode ${it + 1}", "s2e$it", 2, it + 1) },
                seasons = listOf(DetailSeason(number = 1, episodeCount = 10), DetailSeason(number = 2, episodeCount = 30)),
            ),
        ),
    ),
)

/** Every text box on the page is fixed, so all of these have to ellipsise rather than wrap or grow. */
@Preview(name = "Long text", widthDp = 960, heightDp = 540)
@Composable
private fun DetailLongText() = Screen(
    DetailState(
        DetailLoad.Loaded(
            PreviewShow.copy(
                title = "The Unreasonably Long Title Of A Station Decommissioned Forty Years Ago",
                providerName = "A Provider With A Very Long Name Indeed",
                tags = List(8) { "Genre $it" },
                plot = PreviewShow.plot.orEmpty().repeat(4),
                cast = PreviewCast.map { it.copy(name = "${it.name} Of The Long Surname") },
            ),
        ),
    ),
)

/** A stored position of zero is not a resume: no bar, no caption, and the primary says Play. */
@Preview(name = "Resume at zero", widthDp = 960, heightDp = 540)
@Composable
private fun DetailResumeAtZero() = Screen(
    DetailState(
        DetailLoad.Loaded(
            PreviewShow.copy(
                resume = DetailResume("e0", positionMs = 0L, durationMs = 2_620_000L, episode = null),
            ),
        ),
    ),
)

/** No watch lists and no favourites: the two chrome cells are absent rather than dead. */
@Preview(name = "No title state", widthDp = 960, heightDp = 540)
@Composable
private fun DetailNoTitleState() = Screen(
    DetailState(DetailLoad.Loaded(PreviewShow.copy(watchStatus = null, isFavourite = null))),
)

/** 7c, reduced: the source list, which only a host that sends `PlayRequested` can reach. */
@Preview(name = "Sources", widthDp = 960, heightDp = 540)
@Composable
private fun DetailSources() = Screen(
    DetailState(
        DetailLoad.Loaded(PreviewShow),
        DetailLoad.Loaded(List(4) { StreamLink("Source $it", "1080p", "https://example.invalid/$it", null, null, null) }),
    ),
)

@Preview(name = "Sources failed", widthDp = 960, heightDp = 540)
@Composable
private fun DetailSourcesFailed() = Screen(DetailState(DetailLoad.Loaded(PreviewShow), DetailLoad.Failed("extractor down")))
