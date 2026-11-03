package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.no_subtitles
import com.lagradost.cloudstream4.core.strings.generated.resources.player_subtitles_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.subtitles_file_empty
import com.lagradost.cloudstream4.core.strings.generated.resources.subtitles_format_unsupported
import com.lagradost.cloudstream4.core.strings.generated.resources.subtitles_load_failed
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The tracks a stream offers and Off, floating above the controls it opened from, in the same column.
 *
 * Closes on a choice, on a click anywhere else, and on Escape, which the player routes here while it is open.
 * With no tracks it says so and has nothing to choose, rather than being a control that is not there.
 *
 * @param tracks what the plugin offered; [failures] and [selection] say what became of each.
 * @param onSelect null is Off.
 */
@Composable
internal fun SubtitlePicker(
    tracks: List<SubtitleTrack>,
    selection: SubtitleSelection,
    failures: Map<String, SubtitleFailure>,
    onSelect: (SubtitleTrack?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(onDismiss) {
                awaitEachGesture {
                    awaitFirstDown()
                    onDismiss()
                }
            },
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val spare = maxHeight - PlayerScreenDefaults.HudTopInset - PlayerScreenDefaults.HudBottomInset
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier
                .padding(start = DesktopDimens.WindowMargin, end = DesktopDimens.WindowMargin, bottom = PlayerScreenDefaults.HudBottomInset)
                .widthIn(max = PlayerScreenDefaults.ControlsMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .width(PlayerScreenDefaults.PickerWidth)
                    .heightIn(max = minOf(spare, PlayerScreenDefaults.PickerMaxHeight))
                    .background(colors.surface.copy(alpha = PlayerScreenDefaults.PickerAlpha), CloudStreamTheme.shapes.large)
                    // A click on the panel but between its rows is not a click outside it.
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                    .padding(DesktopDimens.RelatedGap),
            ) {
                BasicText(
                    text = stringResource(Res.string.player_subtitles_settings),
                    style = typography.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
                )
                if (tracks.isEmpty()) {
                    BasicText(
                        text = stringResource(Res.string.no_subtitles),
                        style = typography.body.copy(color = colors.ink),
                        modifier = Modifier.padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
                    )
                } else {
                    TrackList(tracks, selection, failures, onSelect)
                }
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<SubtitleTrack>,
    selection: SubtitleSelection,
    failures: Map<String, SubtitleFailure>,
    onSelect: (SubtitleTrack?) -> Unit,
) {
    val selected = selection.track()
    // The first row is Off, so a track's row is its index plus one.
    val selectedRow = remember(tracks) { tracks.indexOf(selected) + 1 }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedRow)
    val focus = remember { FocusRequester() }
    // After a frame: a lazy list composes its rows during layout, so the row is not attached before that.
    LaunchedEffect(focus) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    // Room for the focus ring, which draws outside its row and a lazy list clips.
    LazyColumn(state = list, contentPadding = PaddingValues(all = PlayerScreenDefaults.PickerRingRoom)) {
        item {
            TrackRow(
                title = stringResource(Res.string.no_subtitles),
                detail = null,
                selected = selected == null,
                onClick = { onSelect(null) },
                modifier = if (selectedRow == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
        itemsIndexed(tracks) { index, track ->
            val detail = when {
                selection is SubtitleSelection.Loading && selection.track == track -> Res.string.loading
                else -> failures[track.id]?.let(::failureWords)
            }
            TrackRow(
                title = track.label,
                detail = detail?.let { stringResource(it) },
                selected = selected == track,
                onClick = { onSelect(track) },
                modifier = if (selectedRow == index + 1) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

/** `DesktopRow`, with room for the mark that says which one is on. */
@Composable
private fun TrackRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier
            .fillMaxWidth()
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (hovered) colors.raised else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = DesktopDimens.RowMinHeight)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
    ) {
        Box(Modifier.size(PlayerScreenDefaults.PickerMarkSize), contentAlignment = Alignment.Center) {
            // Decoration: a row's state is also in the selection the screen reads back.
            if (selected) CloudStreamIcon(CloudStreamIcons.Done, null, size = CloudStreamIconSize.Small, tint = colors.ink)
        }
        Column {
            BasicText(
                text = title,
                style = typography.label.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                BasicText(
                    text = detail,
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun SubtitleSelection.track(): SubtitleTrack? = when (this) {
    SubtitleSelection.Off -> null
    is SubtitleSelection.Loading -> track
    is SubtitleSelection.Showing -> track
    is SubtitleSelection.Failed -> track
}

/** One sentence for each way a track can fail, chosen here because state holds no words (`I18N-08`). */
internal fun failureWords(reason: SubtitleFailure): StringResource = when (reason) {
    SubtitleFailure.Fetch -> Res.string.subtitles_load_failed
    SubtitleFailure.UnsupportedFormat -> Res.string.subtitles_format_unsupported
    SubtitleFailure.NoCues -> Res.string.subtitles_file_empty
}
