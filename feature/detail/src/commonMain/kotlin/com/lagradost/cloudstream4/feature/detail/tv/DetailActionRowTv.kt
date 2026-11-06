package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.play_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.play_movie_button
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.season
import com.lagradost.cloudstream4.core.strings.generated.resources.synopsis
import com.lagradost.cloudstream4.core.strings.generated.resources.trailer
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.META_SEPARATOR
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.namedSeason
import org.jetbrains.compose.resources.stringResource

/**
 * The action row, y256 to y288, with every gap exactly [CloudStreamDimens.MinFocusGap].
 *
 * One row at a fixed y and a fixed left origin is the whole point of the board: the nine independently
 * hidden rail buttons of the old TV result page are what make its controls reshuffle under the user's
 * thumb. So the primary is always first and always [DetailScreenTvDefaults.ResumeWidth] wide, and a
 * control that has nothing behind it is absent rather than dead.
 *
 * Order follows the boards, and so does the **count**: 7a draws Resume, Trailer, Season, Episodes and
 * Full synopsis, and 7b draws Resume, Start over, Trailer, Download and Full synopsis. Neither draws
 * six, and six would not fit — the band leaves 833dp here and `ResumeWidth` plus five ghosts at
 * [CloudStreamDimens.TextButtonMinWidth] and five gaps is 880, which a `Row` resolves by squeezing its
 * last child under its own minimum. Every ghost sits at that 120dp floor at 12sp, so a longer
 * translation only makes it worse. The most this row ever holds is five.
 *
 * The reductions the strings force: "start over" is `play_movie_button` / `play_episode`, because
 * nothing says "Start over" and "Resume" beside "Play Movie" reads correctly as
 * resume-versus-from-the-start. 7b's Download is absent: `DetailAction` has no download action to send.
 *
 * @param takeFocus whether the primary takes focus now — on arrival, which both boards state, or on
 *   coming back from the player.
 * @param onScrollToEpisodes what Season and Episodes do, in place of the episode panel they are drawn
 *   for, which is board 6a and a later task.
 */
@Composable
internal fun DetailActionRowTv(
    detail: TitleDetail,
    onAction: (DetailAction) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onScrollToEpisodes: () -> Unit,
) {
    val fromStart = if (detail.playData != null) {
        stringResource(Res.string.play_movie_button)
    } else {
        stringResource(Res.string.play_episode)
    }
    val resuming = detail.resume?.fraction
    Row(
        modifier = Modifier.height(CloudStreamDimens.TextButtonHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
    ) {
        val primaryData = detail.primaryData
        if (primaryData != null) {
            ResumeButton(
                label = if (resuming != null) stringResource(Res.string.resume) else fromStart,
                progress = resuming,
                onClick = { onAction(DetailAction.PlaybackRequested(primaryData, fromStart = false)) },
                takeFocus = takeFocus,
                onFocusTaken = onFocusTaken,
            )
        }
        // Not Resume with another label: a player that is given no position reads the stored one and
        // carries on from it, so starting over has to say so.
        //
        // Only for a playable title, which is 7b's case and the only board that draws this control. A
        // series has nothing to start over: the best it could offer is its **first episode**, so the
        // button would sit beside a Resume of S3:E4 and play S1:E1 — a different action, not the same
        // one from the beginning. It returns when the episode panel (board 6a) can say which episode
        // is meant.
        val restartData = detail.playData
        if (resuming != null && restartData != null) {
            GhostTextButton(
                label = fromStart,
                onClick = { onAction(DetailAction.PlaybackRequested(restartData, fromStart = true)) },
            )
        }
        if (detail.hasTrailer) {
            GhostTextButton(
                label = stringResource(Res.string.trailer),
                onClick = { onAction(DetailAction.TrailerRequested) },
            )
        }
        // Both are dropped where there is no one season to name — see `namedSeason`. A button of fixed
        // width holding a bare word, or a count that belongs to a different season from the one beside
        // it, is not the control the board drew.
        val season = detail.namedSeason()
        if (season != null && detail.episodes.isNotEmpty()) {
            GhostTextButton(
                label = "${stringResource(Res.string.season)} ${season.number}",
                onClick = onScrollToEpisodes,
            )
            GhostTextButton(
                label = UiString.Plural(Res.plurals.episodes, season.episodeCount).resolve() +
                    META_SEPARATOR + season.episodeCount,
                onClick = onScrollToEpisodes,
            )
        }
        if (!detail.plot.isNullOrBlank()) {
            GhostTextButton(
                label = stringResource(Res.string.synopsis),
                onClick = { onAction(DetailAction.SynopsisToggled) },
            )
        }
    }
}

/**
 * The primary, with the board's progress bar flush inside its bottom edge.
 *
 * ## Why the bar is drawn here and not in the design system
 *
 * `PrimaryTextButton` has no slot for it, and it must not gain one: it is shared, and the bar is a
 * detail of this one button on these two boards. It is **black on 24% black**, not `live` — the
 * contract forbids `live` on a button, and `live` is spent zero times on 7a, 7b and 7a-ii. Progress
 * reads as an ink inversion on the white fill instead.
 *
 * The bar is an overlay at [Modifier.matchParentSize], so it changes nothing the button measured, and
 * it is clipped to the button's own shape so its bottom corners follow the fill. The clip is on the
 * overlay alone: clipping the wrapper would cut the button's focus ring, which bleeds outside it.
 *
 * @param progress `0f..1f`, or null for a button with no stored position and therefore no bar.
 */
@Composable
private fun ResumeButton(
    label: String,
    progress: Float?,
    onClick: () -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // After a frame: a button composed in this one has not been placed.
        withFrameNanos { }
        focusRequester.requestFocusIfAttached()
        onFocusTaken()
    }
    Box(Modifier.width(DetailScreenTvDefaults.ResumeWidth)) {
        PrimaryTextButton(
            label = label,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        if (progress != null) {
            Box(Modifier.matchParentSize().clip(shape)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(DetailScreenTvDefaults.ResumeProgressHeight)
                        .background(colors.bg.copy(alpha = DetailScreenTvDefaults.ResumeTrackAlpha)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.bg))
                }
            }
        }
    }
}
