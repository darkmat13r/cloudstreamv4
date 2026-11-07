package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState

import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.IconCell
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_open_play
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.downloaded
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.no_subtitles
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_buffering
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_failed_detail
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_offline_copy
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_previous_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_seek_back
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_seek_forward
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_speed
import com.lagradost.cloudstream4.core.strings.generated.resources.player_hud_up_next
import com.lagradost.cloudstream4.core.strings.generated.resources.player_subtitles_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.strings.generated.resources.video_source
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.HudBanner
import com.lagradost.cloudstream4.feature.player.HudTime
import com.lagradost.cloudstream4.feature.player.PlaybackPanelState
import com.lagradost.cloudstream4.feature.player.PlayerHudAction
import com.lagradost.cloudstream4.feature.player.PlayerHudState
import com.lagradost.cloudstream4.feature.player.ScrubReadout
import org.jetbrains.compose.resources.stringResource

/**
 * Artboard **7d** of `design/TV Detail Player.dc.html`: the player HUD over full-bleed video.
 *
 * The stack solves upward from the focus band's floor, as the board's note says: selectors, transport, the seek cell,
 * then the times. Buffered and played are bands inside one track. The three selectors read out what is playing, value
 * over label, because a generic icon is what made the old HUD unanswerable.
 *
 * **Hidden**, only two things draw: the scrub readout and its hairline while Left or Right scrub (`ScrubReadout`), and
 * `Skip intro`, the one control allowed on screen while hidden. What the keys do while hidden is the
 * [com.lagradost.cloudstream4.feature.player.PlayerHudController]'s, which the host consults before any view sees a key.
 *
 * **Focus.** Entry on reveal is play/pause. Down from the chrome row lands on the seek cell, which is wide enough to catch
 * it from anywhere; Up from AUDIO and SPEED goes to the seek cell, which spans them both. Left and Right on the seek cell
 * move by [shownSeekMs], the old player's `android_tv_interface_on_seek_key`.
 *
 * @param clock the wall clock, already formatted by the host as the old HUD's `TextClock` did.
 */
@Composable
fun PlayerHudTv(
    state: PlayerHudState,
    shown: Boolean,
    readout: ScrubReadout?,
    clock: String,
    shownSeekMs: Long,
    onAction: (PlayerHudAction) -> Unit,
    modifier: Modifier = Modifier,
    panel: PlaybackPanelState? = null,
) {
    // 7e over 7d: the HUD stays mounted under the panel, and closing it gives focus back to the selector that opened it.
    val lastPanel = rememberLastPanel(panel)
    SidePanelHost(
        expanded = panel != null,
        onDismissRequest = { onAction(PlayerHudAction.PanelBack) },
        modifier = modifier,
        panel = { lastPanel?.let { PlaybackPanelTv(it, onAction) } },
    ) {
        Hud(state, shown, readout, clock, shownSeekMs, onAction)
    }
}

/** The panel last shown, so one sliding out keeps drawing what it closed on. */
@Composable
private fun rememberLastPanel(panel: PlaybackPanelState?): PlaybackPanelState? {
    val last = remember { mutableStateOf(panel) }
    if (panel != null) last.value = panel
    return last.value
}

@Composable
private fun Hud(
    state: PlayerHudState,
    shown: Boolean,
    readout: ScrubReadout?,
    clock: String,
    shownSeekMs: Long,
    onAction: (PlayerHudAction) -> Unit,
) {
    val playPause = remember { FocusRequester() }
    val seekCell = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        if (shown) {
            Scrims()
            ChromeRow(state, clock, onAction)
            state.banner?.let { Banner(it, Modifier.offset(y = PlayerHudDefaults.BannerTop)) }
            LowerStack(state, shownSeekMs, onAction, playPause, seekCell)
        } else {
            readout?.let { Readout(it) }
        }
        state.skip?.let { skip ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = PlayerHudDefaults.EndInset)
                    .offset(y = PlayerHudDefaults.SkipTop),
            ) {
                GhostTextButton(
                    label = skip.label,
                    onClick = { onAction(PlayerHudAction.Skip) },
                    modifier = Modifier.width(PlayerHudDefaults.SkipWidth).focusProperties { down = seekCell },
                )
            }
        }
    }
    // 7d: entry focus on reveal is play/pause.
    LaunchedEffect(shown) { if (shown) runCatching { playPause.requestFocus() } }
}

/** `.scrimtop` and `.scrimbot`: the `bg` token at the board's 0.88 and 0.92, fading to nothing. */
@Composable
private fun Scrims() {
    val ground = CloudStreamTheme.colors.bg
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PlayerHudDefaults.TopScrimHeight)
                .background(Brush.verticalGradient(listOf(ground.copy(alpha = TOP_SCRIM_ALPHA), ground.copy(alpha = 0f)))),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(PlayerHudDefaults.BottomScrimHeight)
                .background(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground.copy(alpha = BOTTOM_SCRIM_ALPHA)))),
        )
    }
}

/** Back, the title over its meta line, the `DOWNLOADED` badge and the clock, on the chrome row. */
@Composable
private fun ChromeRow(state: PlayerHudState, clock: String, onAction: (PlayerHudAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CloudStreamDimens.ContentOriginX, end = PlayerHudDefaults.EndInset, top = CloudStreamDimens.ChromeRowTop),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.Top,
    ) {
        HudCell(CloudStreamIcons.Back, stringResource(Res.string.go_back), { onAction(PlayerHudAction.Back) })
        Column(Modifier.weight(1f)) {
            Text(state.title, style = typography.railTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.meta.isNotEmpty()) {
                Text(state.meta, style = typography.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.downloaded) {
            Badge(text = stringResource(Res.string.downloaded).uppercase(), modifier = Modifier.padding(top = PlayerHudDefaults.ChromeTextTop))
        }
        Text(
            clock,
            style = typography.labelMono,
            color = colors.ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(PlayerHudDefaults.TotalWidth).padding(top = PlayerHudDefaults.ChromeTextTop),
        )
    }
}

/** 7d's error and offline banner: a strip over the mounted HUD. Its action is the SOURCE selector, not a button here. */
@Composable
private fun Banner(banner: HudBanner, modifier: Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.medium
    Row(
        modifier = modifier
            .padding(start = CloudStreamDimens.ContentOriginX, end = PlayerHudDefaults.EndInset)
            .fillMaxWidth()
            .height(PlayerHudDefaults.BannerHeight)
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape)
            .padding(horizontal = PlayerHudDefaults.BannerPadding),
        horizontalArrangement = Arrangement.spacedBy(PlayerHudDefaults.BannerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state = if (banner == HudBanner.Failed) StatusDotState.Failed else StatusDotState.Neutral)
        when (banner) {
            HudBanner.Failed -> {
                Text(stringResource(Res.string.source_error), style = typography.label, color = colors.ink, maxLines = 1)
                Text(stringResource(Res.string.player_hud_failed_detail), style = typography.label, color = colors.ink2, maxLines = 1)
            }
            HudBanner.OfflineCopy ->
                Text(stringResource(Res.string.player_hud_offline_copy), style = typography.label, color = colors.ink, maxLines = 1)
        }
    }
}

/** Everything from the times down: the stack 7d solves upward from the focus band's floor. */
@Composable
private fun LowerStack(
    state: PlayerHudState,
    shownSeekMs: Long,
    onAction: (PlayerHudAction) -> Unit,
    playPause: FocusRequester,
    seekCell: FocusRequester,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = CloudStreamDimens.ContentOriginX, end = PlayerHudDefaults.EndInset, bottom = CloudStreamDimens.FocusBandInset),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Times(state)
        Spacer(Modifier.height(CloudStreamDimens.MinFocusGap))
        // 7d: Down from the seek cell is play/pause, not whichever transport cell is nearest.
        SeekCell(state, shownSeekMs, onAction, Modifier.focusRequester(seekCell).focusProperties { down = playPause })
        Spacer(Modifier.height(CloudStreamDimens.MinFocusGap))
        Transport(state, onAction, playPause)
        Spacer(Modifier.height(CloudStreamDimens.MinFocusGap))
        Selectors(state, onAction, seekCell)
    }
}

/** `42:13`, the `BUFFERING` badge while it is, `-19:31` and `1:01:44`. */
@Composable
private fun Times(state: PlayerHudState) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(HudTime.clock(state.positionMs), style = typography.labelMono, color = colors.ink, modifier = Modifier.width(PlayerHudDefaults.ElapsedWidth))
        Spacer(Modifier.width(CloudStreamDimens.MinFocusGap))
        if (state.buffering) Badge(text = stringResource(Res.string.player_hud_buffering))
        Spacer(Modifier.weight(1f))
        if (state.durationMs > 0) {
            Text(
                HudTime.remaining(state.positionMs, state.durationMs),
                style = typography.labelMono,
                color = colors.muted,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(CloudStreamDimens.MinFocusGap))
            Text(
                HudTime.clock(state.durationMs),
                style = typography.labelMono,
                color = colors.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.width(PlayerHudDefaults.TotalWidth),
            )
        }
    }
}

/**
 * `.seekcell`: one focus target as wide as the content, holding the track. The track is one 8dp band with the buffered
 * and played bands inside it, never a second bar, the skip stamps as ticks, and the knob at the playhead.
 */
@Composable
private fun SeekCell(state: PlayerHudState, shownSeekMs: Long, onAction: (PlayerHudAction) -> Unit, modifier: Modifier) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val description = HudTime.clock(state.positionMs) + if (state.durationMs > 0) " / " + HudTime.clock(state.durationMs) else ""
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(CloudStreamDimens.IconTargetCell)
            .tvFocusRing(focused = focused, shape = shape)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAction(PlayerHudAction.SeekBy(-shownSeekMs)); true }
                    Key.DirectionRight -> { onAction(PlayerHudAction.SeekBy(shownSeekMs)); true }
                    else -> false
                }
            }
            .semantics { contentDescription = description }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        fun at(ms: Long): Dp = if (state.durationMs <= 0) 0.dp else width * (ms.coerceIn(0, state.durationMs).toFloat() / state.durationMs)
        Box(Modifier.fillMaxWidth().height(PlayerHudDefaults.TrackHeight).background(colors.line, shape))
        Box(Modifier.width(at(state.bufferedMs)).height(PlayerHudDefaults.TrackHeight).background(colors.muted, shape))
        Box(Modifier.width(at(state.positionMs)).height(PlayerHudDefaults.TrackHeight).background(colors.live, shape))
        for (mark in state.marks) {
            Box(
                Modifier
                    .offset(x = at(mark))
                    .width(PlayerHudDefaults.TickWidth)
                    .height(PlayerHudDefaults.TrackHeight)
                    .background(colors.ink2.copy(alpha = TICK_ALPHA)),
            )
        }
        if (state.durationMs > 0) {
            Box(
                Modifier
                    .offset(x = at(state.positionMs) - PlayerHudDefaults.KnobSize / 2)
                    .size(PlayerHudDefaults.KnobSize)
                    .background(colors.ink, CloudStreamTheme.shapes.pill),
            )
        }
    }
}

/** Play/pause, then back, forward, the previous and the next episode, and `Up next · …` at the row's end. */
@Composable
private fun Transport(state: PlayerHudState, onAction: (PlayerHudAction) -> Unit, playPause: FocusRequester) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudCell(
            icon = if (state.playing) CloudStreamIcons.Pause else CloudStreamIcons.Play,
            description = stringResource(if (state.playing) Res.string.pause else Res.string.action_open_play),
            onClick = { onAction(PlayerHudAction.PlayPause) },
            modifier = Modifier.focusRequester(playPause).focusProperties { left = FocusRequester.Cancel },
        )
        HudCell(CloudStreamIcons.SeekBackward, stringResource(Res.string.player_hud_seek_back), { onAction(PlayerHudAction.SeekBackward) })
        HudCell(CloudStreamIcons.SeekForward, stringResource(Res.string.player_hud_seek_forward), { onAction(PlayerHudAction.SeekForward) })
        HudCell(
            CloudStreamIcons.PreviousEpisode,
            stringResource(Res.string.player_hud_previous_episode),
            { onAction(PlayerHudAction.PreviousEpisode) },
            enabled = state.hasPrevious,
        )
        // The row ends at the last cell: Right from it finds nothing, as 7d has nothing there to reach.
        HudCell(
            CloudStreamIcons.NextEpisode,
            stringResource(Res.string.next_episode),
            { onAction(PlayerHudAction.NextEpisode) },
            enabled = state.hasNext,
            modifier = Modifier.focusProperties { right = FocusRequester.Cancel },
        )
        Spacer(Modifier.weight(1f))
        state.upNext?.let { next ->
            Text(
                UiString.Resource(Res.string.player_hud_up_next, listOf(next)).resolve(),
                style = typography.label,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = PlayerHudDefaults.UpNextMaxWidth),
            )
        }
    }
}

/**
 * A transport or chrome cell, icon-only with its content description and no label on focus: the design system's rule
 * for 7d's transport controls and the Back cell (the review of #159, `docs/tasks/design-system.md`). A disabled cell draws muted and is not a stop.
 */
@Composable
private fun HudCell(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = CloudStreamTheme.colors
    IconCell(onClick = onClick, modifier = modifier, enabled = enabled) { focused ->
        CloudStreamIcon(
            icon = icon,
            contentDescription = description,
            tint = when {
                !enabled -> colors.muted
                focused -> colors.ink
                else -> colors.ink2
            },
        )
    }
}

/** SOURCE, SUBTITLES, AUDIO and SPEED: what is playing, value over label. Each opens the player's existing picker. */
@Composable
private fun Selectors(state: PlayerHudState, onAction: (PlayerHudAction) -> Unit, seekCell: FocusRequester) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
        Selector(
            value = state.source ?: NONE,
            label = stringResource(Res.string.video_source),
            onClick = { onAction(PlayerHudAction.OpenSource) },
            modifier = Modifier.width(PlayerHudDefaults.SourceWidth),
        )
        Selector(
            value = state.subtitles ?: stringResource(Res.string.no_subtitles),
            label = stringResource(Res.string.player_subtitles_settings),
            onClick = { onAction(PlayerHudAction.OpenSubtitles) },
            modifier = Modifier.width(PlayerHudDefaults.SubtitlesWidth),
        )
        // Up from AUDIO and SPEED is the seek cell, which spans them: no transport cell sits above them.
        Selector(
            value = state.audio ?: NONE,
            label = stringResource(Res.string.audio_singular),
            onClick = { onAction(PlayerHudAction.OpenAudio) },
            modifier = Modifier.width(PlayerHudDefaults.AudioWidth).focusProperties { up = seekCell },
        )
        Spacer(Modifier.weight(1f))
        Selector(
            value = HudTime.speed(state.speed),
            label = stringResource(Res.string.player_hud_speed),
            onClick = { onAction(PlayerHudAction.OpenSpeed) },
            modifier = Modifier.width(PlayerHudDefaults.SpeedWidth).focusProperties { up = seekCell },
        )
    }
}

/** `.cbtn`: the value in `label` ink over the caption in mono `muted`, on the raised fill. */
@Composable
private fun Selector(value: String, label: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = modifier
            .height(CloudStreamDimens.IconTargetCell)
            .tvFocusRing(focused = focused, shape = shape)
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = PlayerHudDefaults.SelectorPadding),
        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap, Alignment.CenterVertically),
    ) {
        Text(value, style = typography.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label.uppercase(), style = typography.captionMono, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 7d's `HIDDEN` readout, `+00:30 → 42:43`, centred over an ink rule, neither revealing the HUD. */
@Composable
private fun Readout(readout: ScrubReadout) {
    val colors = CloudStreamTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = CloudStreamDimens.ContentOriginX, end = PlayerHudDefaults.EndInset, bottom = PlayerHudDefaults.ReadoutBottom),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            HudTime.delta(readout.deltaMs) + " → " + HudTime.clock(readout.targetMs),
            style = CloudStreamTheme.typography.railTitle,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PlayerHudDefaults.ReadoutRuleGap))
        Box(Modifier.fillMaxWidth().height(PlayerHudDefaults.ReadoutRuleHeight).background(colors.ink))
    }
}

private const val NONE = "—"
private const val TOP_SCRIM_ALPHA = 0.88f
private const val BOTTOM_SCRIM_ALPHA = 0.92f
private const val TICK_ALPHA = 0.8f

/** Literals the HUD owns. Artboard px / 2 = dp, measured from `7d`. */
internal object PlayerHudDefaults {
    /** 1920 − 1864 = 56px from the right edge. Mirrors the chrome row's end, `ContentEndX` on a 960dp screen. */
    val EndInset: Dp = 28.dp

    /** `.scrimtop`, 320px. */
    val TopScrimHeight: Dp = 160.dp

    /** `.scrimbot`, from y480 to the floor: 600px. */
    val BottomScrimHeight: Dp = 300.dp

    /** The badge and the clock sit at y64 in a row whose top is y56. */
    val ChromeTextTop: Dp = 4.dp

    /** The banner slot at y176, 120px tall. */
    val BannerTop: Dp = 88.dp
    val BannerHeight: Dp = 60.dp
    val BannerPadding: Dp = 16.dp
    val BannerGap: Dp = 12.dp

    /** `Skip intro` at y528, 280px wide, right-aligned on the content's end. */
    val SkipTop: Dp = 264.dp
    val SkipWidth: Dp = 140.dp

    /** The elapsed time's box, 200px, and the total's, 160px. */
    val ElapsedWidth: Dp = 100.dp
    val TotalWidth: Dp = 80.dp

    /** `.track`, 16px, inside the 96px seek cell. */
    val TrackHeight: Dp = 8.dp

    /** `.tick`, 4px: a stroke weight, exempt from the step in its own axis (`dimension-scale.md`). */
    val TickWidth: Dp = 2.dp

    /** `.knob`, 32px. */
    val KnobSize: Dp = 16.dp

    /** `Up next · …`, 800px at most. */
    val UpNextMaxWidth: Dp = 400.dp

    /** `.cbtn` widths: 440, 424, 440 and 240px, and its `padding: 0 24px`. */
    val SourceWidth: Dp = 220.dp
    val SubtitlesWidth: Dp = 212.dp
    val AudioWidth: Dp = 220.dp
    val SpeedWidth: Dp = 120.dp
    val SelectorPadding: Dp = 12.dp

    /** The readout's rule at y1008, 8px of ink, 72px from the floor; the text above it ends 16px higher. */
    val ReadoutBottom: Dp = 64.dp
    val ReadoutRuleHeight: Dp = 4.dp
    val ReadoutRuleGap: Dp = 8.dp
}
