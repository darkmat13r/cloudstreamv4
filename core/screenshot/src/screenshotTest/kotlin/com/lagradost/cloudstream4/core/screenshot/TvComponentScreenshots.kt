package com.lagradost.cloudstream4.core.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.card.ContinueCard
import com.lagradost.cloudstream4.core.designsystem.card.EpisodeCard
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardArtPlaceholder
import com.lagradost.cloudstream4.core.designsystem.primitive.Avatar
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPill
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPillState
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageMeter
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageUsage
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRow
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRowState
import com.lagradost.cloudstream4.core.designsystem.target.SegmentedControl
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The load-bearing states of the components `DS-08` added. Like the rest of this suite these render nothing until
 * the plugin works on this toolchain (`DS-04`); the cases are written so they guard as soon as it does.
 */
@Composable
private fun TvSurface(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Column(
            modifier = Modifier.background(CloudStreamTheme.colors.bg).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** A failure must read from its shape and its words, with no `live` (`DEC-17`). */
@Preview(name = "StatusPill - every state", widthDp = 480, heightDp = 80)
@Composable
fun StatusPills() {
    TvSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusPill(StatusPillState.Probing, "PROBING")
            StatusPill(StatusPillState.Ready, "READY")
            StatusPill(StatusPillState.Failed, "FAILED")
            StatusPill(StatusPillState.Playing, "PLAYING")
        }
    }
}

@Preview(name = "SegmentedControl - first chosen", widthDp = 320, heightDp = 104)
@Composable
fun SegmentedChosen() {
    TvSurface { SegmentedControl(listOf("Merged", "By source"), 0, {}) }
}

/** The track is drawn at 0 so titles stay on one baseline across the rail. */
@Preview(name = "EpisodeCard - unwatched, in progress, watched", widthDp = 480, heightDp = 168)
@Composable
fun EpisodeCardProgress() {
    TvSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EpisodeCard("Cold Start", "44M", number = "E5") { MediaCardArtPlaceholder() }
            EpisodeCard("The Relay", "48M · 28M LEFT", number = "E4", progress = 0.6f) { MediaCardArtPlaceholder() }
            EpisodeCard("Deck Four", "46M · WATCHED", number = "E3", progress = 1f) { MediaCardArtPlaceholder() }
        }
    }
}

@Preview(name = "ContinueCard - focused", widthDp = 256, heightDp = 208)
@Composable
fun ContinueCardFocused() {
    TvSurface {
        ContinueCard("The Long Dark", "S2 E4 · 28M LEFT", 0.6f, focused = true) { MediaCardArtPlaceholder() }
    }
}

/** A provider often sends no image. */
@Preview(name = "Avatar - initials", widthDp = 136, heightDp = 136)
@Composable
fun AvatarInitials() {
    TvSurface { Avatar("BC") }
}

@Preview(name = "StorageMeter - divided and loading", widthDp = 480, heightDp = 116)
@Composable
fun StorageMeters() {
    TvSurface {
        StorageMeter(StorageUsage(0.5f, 0.25f), "384 GB OF 512 GB USED", Modifier.fillMaxWidth())
        StorageMeter(null, null, Modifier.fillMaxWidth())
    }
}

/** The stalled bar and the hollow marker are all a failed row has, with no `live` (`DEC-17`). */
@Preview(name = "DownloadProgressRow - transferring and failed", widthDp = 480, heightDp = 164)
@Composable
fun DownloadRows() {
    TvSurface {
        DownloadProgressRow(
            DownloadProgressRowState("The Long Night", "128 MB OF 512 MB", "25%", progress = 0.25f),
            {},
            Modifier.fillMaxWidth(),
        )
        DownloadProgressRow(
            DownloadProgressRowState("Bloodline", "STORAGE FULL · 1.2 GB SHORT", "62%", progress = 0.62f, failed = true),
            {},
            Modifier.fillMaxWidth(),
        )
    }
}
