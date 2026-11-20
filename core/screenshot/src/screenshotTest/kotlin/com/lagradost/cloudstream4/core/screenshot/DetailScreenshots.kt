package com.lagradost.cloudstream4.core.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailNextAiring
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop

/**
 * The Mac and iPad title page with the next episode's countdown under its facts. Like the rest of this suite it renders
 * nothing until the plugin works on this toolchain (`DS-04`); `DetailScreenTest` in `:desktopApp` holds the state today.
 * The page's now is fixed, so the countdown reads the same on every run.
 */
private const val PreviewNow = 1_794_833_696_789L
private const val Minute = 60_000L

private val Series = TitleDetail(
    title = "The Last Signal",
    providerName = "CineTmdb",
    typeName = "TvSeries",
    year = 2019,
    plot = "Six months after the broadcast went dark, a salvage crew picks up a repeating signal.",
    posterUrl = null,
    playData = null,
    episodes = listOf(DetailEpisode("Pilot", "s1e1", 1, 1, runTimeMinutes = 48)),
    nextAiring = DetailNextAiring(season = 3, episode = 1, airDateMs = PreviewNow + (2 * 24 * 60 + 5 * 60 + 30) * Minute),
)

/** Season 3 Episode 1 in 2d 5h 30m: the line sits between the facts and the synopsis, in the facts' colour. */
@Preview(name = "Detail - next episode countdown", widthDp = 1280, heightDp = 800)
@Composable
fun DetailNextAiringCountdown() {
    CloudStreamTheme {
        CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                DetailScreenDesktop(
                    state = DetailState(DetailLoad.Loaded(Series)),
                    onAction = {},
                    onBack = {},
                    artwork = {},
                    now = { PreviewNow },
                )
            }
        }
    }
}

/** 2008-01-20 and 2008-01-27, each its day's UTC midnight, as `DetailEpisode.airDateMs` carries it (`KMP-120`). */
private const val Jan20 = 1_200_787_200_000L
private const val Jan27 = 1_201_392_000_000L

private val Dated = Series.copy(
    nextAiring = null,
    episodes = listOf(
        DetailEpisode("Pilot", "s1e1", 1, 1, runTimeMinutes = 48, airDateMs = Jan20),
        DetailEpisode("Cat's in the Bag", "s1e2", 1, 2, airDateMs = Jan27),
        DetailEpisode("And the Bag's in the River", "s1e3", 1, 3, runTimeMinutes = 48),
    ),
)

/**
 * Each state of an episode's second line with its air date (`KMP-120`): the runtime then the day (`S1:E1 · 48 min ·
 * 20 Jan 2008`), the day alone where there is no runtime, and no day, which reads as before.
 */
@Preview(name = "Detail - episode air dates", widthDp = 1280, heightDp = 800)
@Composable
fun DetailEpisodeAirDates() {
    CloudStreamTheme {
        CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                DetailScreenDesktop(
                    state = DetailState(DetailLoad.Loaded(Dated)),
                    onAction = {},
                    onBack = {},
                    artwork = {},
                    now = { PreviewNow },
                )
            }
        }
    }
}
