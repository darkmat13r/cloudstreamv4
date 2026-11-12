package com.lagradost.cloudstream4.feature.downloads.series.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.download
import com.lagradost.cloudstream4.core.strings.generated.resources.download_not_available_here
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import org.jetbrains.compose.resources.stringResource

/**
 * `KMP-109`: one episode's download on the Mac title page, at the right of its row. D4's words for its state, then
 * the verbs for that state, drawn while the row is hovered or focused:
 *
 * | State | Verbs |
 * |---|---|
 * | none yet | Download |
 * | waiting | Cancel |
 * | downloading | Pause, Cancel |
 * | paused | Resume |
 * | failed | Retry |
 * | downloaded | none |
 *
 * The verbs keep their width while hidden, so the row's words never move under the pointer. They are the title
 * page's 36 cells, as its Play is, not D4's 24.
 *
 * @param download the episode's download, or null when it has none.
 * @param shown the row is hovered or focused: the verbs are drawn.
 * @param unavailable this episode cannot be downloaded from here: the host's enqueue was refused (part 2b). With no
 *   [download], the cell says so, muted, and offers no verb. A refusal is not drawn as an error.
 * @param onResume a paused episode's Resume and a failed one's Retry: one call, as the downloads service has.
 */
@Composable
fun EpisodeDownloadDesktop(
    download: DownloadEpisode?,
    shown: Boolean,
    formatBytes: (Long) -> String,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    unavailable: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        modifier = modifier,
    ) {
        if (download != null) {
            EpisodeStatus(download, formatBytes)
        } else if (unavailable) {
            BasicText(
                text = stringResource(Res.string.download_not_available_here),
                style = CloudStreamTheme.typography.body.copy(color = CloudStreamTheme.colors.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(SeriesScreenDesktopDefaults.StatusWidth),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(EpisodeDownloadDesktopDefaults.VerbsWidth),
        ) {
            if (shown && !(download == null && unavailable)) {
                when (download?.state) {
                    null -> Verb(CloudStreamIcons.Download, stringResource(Res.string.download), onDownload)
                    DownloadEpisodeState.Queued -> Verb(CloudStreamIcons.Close, stringResource(Res.string.cancel), onCancel)
                    DownloadEpisodeState.Running -> {
                        Verb(CloudStreamIcons.Pause, stringResource(Res.string.pause), onPause)
                        Verb(CloudStreamIcons.Close, stringResource(Res.string.cancel), onCancel)
                    }

                    DownloadEpisodeState.Paused -> Verb(CloudStreamIcons.Play, stringResource(Res.string.resume), onResume)
                    DownloadEpisodeState.Failed ->
                        Verb(CloudStreamIcons.Retry, stringResource(Res.string.player_source_retry), onResume)

                    DownloadEpisodeState.Done -> Unit
                }
            }
        }
    }
}

@Composable
private fun Verb(icon: ImageVector, label: String, onClick: () -> Unit) {
    DesktopIconButton(icon = icon, contentDescription = label, onClick = onClick, tone = DesktopButtonTone.Quiet)
}

/** Values the title page's download cell needs that `DesktopDimens` does not name. */
object EpisodeDownloadDesktopDefaults {
    /** Two verbs, Pause and Cancel: two `DesktopDimens.ButtonHeight` cells and a `DesktopDimens.RelatedGap`. */
    val VerbsWidth: Dp = 80.dp
}
