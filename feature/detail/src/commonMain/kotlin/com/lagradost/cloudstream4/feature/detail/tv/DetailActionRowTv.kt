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
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.IconOnlyButton
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
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
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
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
 * ## The secondaries are icon-only (`DEC-17`, boards 7f and 7g)
 *
 * Rule 4 of `ui-components.md` carries **one ruled exception** — this row — so Start over, Trailer and
 * the watchlist are [IconOnlyButton] cells that show their label on focus, and the primary keeps its
 * own [CloudStreamIcons.Play] glyph. That also buys back the width the old five-ghost row had no room
 * for: a 48dp cell against a 120dp ghost.
 *
 * Order is the board's, as a prefix: Resume, Start over, Trailer, watchlist. Season, Episodes and Full
 * synopsis follow as text buttons and are **not** on 7f or 7g — they stay only until their
 * replacements exist (the inline episode list of 7i, and the synopsis stop of 7j/7k). Removing them
 * first would leave no way to pick an episode or read a clamped plot, so when those land the row
 * becomes 7f exactly, with nothing to reorder.
 *
 * Still absent, and deliberately: **Download** and **Mark watched**, which 7g draws for films —
 * `DetailAction` has neither action, and a control with nothing behind it is worse than none. **More**
 * has no board yet. Both are later tasks.
 *
 * The reductions the strings force: "start over" is `play_movie_button` / `play_episode`, because
 * nothing says "Start over" and "Resume" beside "Play Movie" reads correctly as
 * resume-versus-from-the-start.
 *
 * @param takeFocus whether the primary takes focus now — on arrival, which both boards state, or on
 *   coming back from the player.
 * @param onScrollToEpisodes what Season and Episodes do, in place of the episode panel they are drawn
 *   for, which is board 6a and a later task.
 * @param onWatchStatusRequested opens the watch-status panel. The cell is a button, not a toggle: five
 *   values cannot be cycled on a remote.
 */
@Composable
internal fun DetailActionRowTv(
    detail: TitleDetail,
    onAction: (DetailAction) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onScrollToEpisodes: () -> Unit,
    onWatchStatusRequested: () -> Unit,
) {
    val fromStart = if (detail.playData != null) {
        stringResource(Res.string.play_movie_button)
    } else {
        stringResource(Res.string.play_episode)
    }
    val resuming = detail.resume?.fraction
    Row(
        // The cell, not the text button: board 7f draws the 48dp icon cells y248..296 with the 32dp
        // text buttons centred on them at y256..288. Everything in the row is still on one baseline.
        modifier = Modifier.height(CloudStreamDimens.IconTargetCell),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
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
            IconOnlyButton(
                icon = CloudStreamIcons.StartOver,
                label = fromStart,
                onClick = { onAction(DetailAction.PlaybackRequested(restartData, fromStart = true)) },
            )
        }
        if (detail.hasTrailer) {
            IconOnlyButton(
                icon = CloudStreamIcons.Trailer,
                label = stringResource(Res.string.trailer),
                onClick = { onAction(DetailAction.TrailerRequested) },
            )
        }
        // The watchlist, moved out of the chrome row because 7f and 7g put it here. Not an
        // `IconOnlyToggle`: OK opens the five-value status panel rather than flipping a flag, so this
        // is a button, and `IconOnlyToggle`'s checked `raised` fill would be wrong twice over — rule 4
        // now says a toggle whose glyph pair is named draws no fill, and this is not a toggle at all.
        // The pair is the rule's own: `Add` (Plus) off, `Done` (Check) on, state carried by shape.
        val watchStatus = detail.watchStatus
        if (watchStatus != null) {
            IconOnlyButton(
                icon = if (watchStatus != DetailWatchStatus.None) {
                    CloudStreamIcons.Done
                } else {
                    CloudStreamIcons.Add
                },
                label = stringResource(Res.string.action_add_to_bookmarks),
                onClick = onWatchStatusRequested,
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
            icon = CloudStreamIcons.Play,
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
