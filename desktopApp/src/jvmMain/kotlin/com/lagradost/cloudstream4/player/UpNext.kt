package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.dismiss
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.player_play_now
import com.lagradost.cloudstream4.core.strings.generated.resources.player_up_next_in
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The episode after this one, as `DS-23`'s Up next card shows it (`KMP-94`).
 *
 * @param line `S1:E5 · Its name`, in the player's own letters.
 * @param artUrl the title's poster: an episode carries no still of its own. Null draws the card's art box empty.
 */
internal data class UpNext(val line: String, val artUrl: String?)

/**
 * One second of the countdown. Injected so a test lets each second pass when it says so, as [HudIdle] is, instead of
 * sleeping through ten of them.
 */
internal fun interface UpNextTick {
    suspend fun second()

    companion object {
        val Real = UpNextTick { delay(UpNextDefaults.SecondMillis) }
    }
}

/** Whether the card is due: at the credits, which without credit stamps on the Mac is the last [UpNextDefaults.LeadMillis], or once the stream has ended. */
internal fun upNextDue(status: PlayerStatus): Boolean = status.finished || status.remainsAtMost(UpNextDefaults.LeadMillis)

/**
 * Whether its count may run: only in the episode's last [UpNextDefaults.CountdownSeconds], or once it has ended, so
 * autoplay never cuts the end of an episode (the PM's ruling on `KMP-94`). Until `KMP-86`'s credit stamps reach the Mac,
 * the end of the stream is the only "credits" the player knows.
 */
internal fun upNextCounts(status: PlayerStatus): Boolean =
    status.finished || status.remainsAtMost(UpNextDefaults.CountdownSeconds * UpNextDefaults.SecondMillis)

private fun PlayerStatus.remainsAtMost(millis: Long): Boolean = lengthMillis > 0 && lengthMillis - positionMillis <= millis

/**
 * The card's count, for one appearance (`DS-23` M13 and M16).
 *
 * [left] is the seconds to go, or null when nothing is counting: autoplay is off, or Cancel was chosen. Cancel keeps the
 * card; [dismiss] puts it away for the rest of the credits. [starting] once the next episode has been asked for, by the
 * count reaching nothing or by Play now; it is asked for once.
 */
internal class UpNextCountdown(autoplay: Boolean, private val seconds: Int = UpNextDefaults.CountdownSeconds) {
    var left by mutableStateOf(if (autoplay) seconds else null)
        private set
    var cancelled by mutableStateOf(false)
        private set
    var dismissed by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set

    /** A second has passed. True when that was the last one, and the next episode is to start now. */
    fun tick(): Boolean {
        val now = left ?: return false
        if (starting) return false
        if (now > 1) {
            left = now - 1
            return false
        }
        left = 0
        starting = true
        return true
    }

    /** Play now: true unless the next episode has already been asked for. */
    fun playNow(): Boolean {
        if (starting) return false
        starting = true
        left = null
        return true
    }

    /** Cancel, or Esc: the card stays, without a count. */
    fun cancel() {
        if (starting) return
        cancelled = true
        left = null
    }

    fun dismiss() {
        if (starting) return
        dismissed = true
        left = null
    }

    /** The Settings window changed "Play the next episode" while the card is up. Turning it on does not undo a Cancel. */
    fun autoplayChanged(on: Boolean) {
        if (starting || cancelled) return
        left = if (on) left ?: seconds else null
    }
}

/**
 * `DS-23` M13 and M16. The next episode with its art, and its count as a line and a rule along the card's foot that
 * empties; Play now and Cancel under it, or Play now and × once nothing counts.
 *
 * @param counting whether the count is running yet, which is only in the episode's last seconds ([upNextCounts]).
 *   Until then the card shows no count, and Cancel already stops the one to come.
 * @param onPointerOnCard told when the pointer comes onto the card and leaves it, because the count holds meanwhile.
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent: Enter and Exit, for the hold.
@Composable
internal fun UpNextCard(
    upNext: UpNext,
    countdown: UpNextCountdown,
    counting: Boolean,
    onPlayNow: () -> Unit,
    onPointerOnCard: (Boolean) -> Unit,
    artwork: @Composable (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val left = countdown.left
    val shownLeft = left?.takeIf { counting }
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier
            .width(DesktopDimens.SidePanelWidth)
            .onPointerEvent(PointerEventType.Enter) { onPointerOnCard(true) }
            .onPointerEvent(PointerEventType.Exit) { onPointerOnCard(false) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CloudStreamTheme.shapes.large)
                .background(colors.surface.copy(alpha = PlayerScreenDefaults.ControlsAlpha)),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
                modifier = Modifier.padding(DesktopDimens.RowPadding),
            ) {
                Box(
                    Modifier
                        .size(UpNextDefaults.ArtWidth, UpNextDefaults.ArtHeight)
                        .clip(CloudStreamTheme.shapes.medium)
                        .background(colors.bg),
                ) {
                    upNext.artUrl?.let { artwork(it) }
                }
                Column {
                    BasicText(
                        text = upNext.line,
                        style = typography.label.copy(color = colors.ink),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        text = when {
                            countdown.starting -> stringResource(Res.string.loading)
                            shownLeft != null -> UiString.Plural(Res.plurals.player_up_next_in, shownLeft).resolve()
                            else -> stringResource(Res.string.next_episode)
                        },
                        style = typography.caption.copy(color = colors.ink2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (shownLeft != null) {
                ProgressRule(
                    progress = shownLeft.toFloat() / UpNextDefaults.CountdownSeconds,
                    color = colors.ink,
                    trackColor = colors.line,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!countdown.starting) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DesktopButton(
                    label = stringResource(Res.string.player_play_now),
                    onClick = onPlayNow,
                    primary = true,
                    icon = CloudStreamIcons.Play,
                    modifier = Modifier.weight(1f),
                )
                if (left != null) {
                    DesktopButton(
                        label = stringResource(Res.string.cancel),
                        onClick = countdown::cancel,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    DesktopIconButton(
                        icon = CloudStreamIcons.Close,
                        contentDescription = stringResource(Res.string.dismiss),
                        onClick = countdown::dismiss,
                        tone = DesktopButtonTone.Secondary,
                    )
                }
            }
        }
    }
}

internal object UpNextDefaults {
    /** The owner's ruling (`DS-23`): ten, because the card has a two-line title and art to read. */
    const val CountdownSeconds = 10
    const val SecondMillis = 1_000L
    /** How long before the end the card comes, while the Mac has no credit stamps (`KMP-86`). */
    const val LeadMillis = 30_000L
    /** `DS-23` M13's art, 136 by 76. */
    val ArtWidth: Dp = 136.dp
    val ArtHeight: Dp = 76.dp
}
