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
