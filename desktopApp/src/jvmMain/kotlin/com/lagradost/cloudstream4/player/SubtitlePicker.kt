package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.no_subtitles
import com.lagradost.cloudstream4.core.strings.generated.resources.player_subtitles_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.subtitles_file_empty
import com.lagradost.cloudstream4.core.strings.generated.resources.subtitles_format_unsupported
import com.lagradost.cloudstream4.core.strings.generated.resources.subtitles_load_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.player.RowVerb
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
    PlayerListPanel(stringResource(Res.string.player_subtitles_settings), onDismiss) {
        if (tracks.isEmpty()) {
            BasicText(
                text = stringResource(Res.string.no_subtitles),
                style = CloudStreamTheme.typography.body.copy(color = CloudStreamTheme.colors.ink),
                modifier = Modifier.padding(horizontal = PlayerScreenDefaults.PanelWordsInset, vertical = DesktopDimens.RelatedGap),
            )
        } else {
            TrackList(tracks, selection, failures, onSelect)
        }
    }
}

/**
 * `DS-23` M4 to M7: a panel from the right, the window's height and 360 wide (`DesktopDimens.SidePanelWidth`), with no
 * scrim: the picture keeps playing beside it, and the controls move into the room it leaves. Its title is a heading,
 * an optional line under it, then the rows, then Done. A click on the picture closes it, as Done and Escape do.
 */
@Composable
internal fun PlayerListPanel(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .width(DesktopDimens.SidePanelWidth)
                .fillMaxHeight()
                .background(colors.surface.copy(alpha = PlayerScreenDefaults.PickerAlpha))
                .leftRule(colors.line)
                // A click on the panel but between its rows is not a click outside it.
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                // The rows run nearer the panel's edges than its words do (`KMP-93`).
                .padding(vertical = DesktopDimens.WindowMargin, horizontal = PlayerScreenDefaults.PanelListEdge),
        ) {
            val words = Modifier.padding(horizontal = PlayerScreenDefaults.PanelWordsInset)
            BasicText(text = title, style = typography.railTitle.copy(color = colors.ink), maxLines = 1, modifier = words)
            if (subtitle != null) {
                BasicText(text = subtitle, style = typography.caption.copy(color = colors.muted), modifier = words)
            }
            Column(Modifier.weight(1f).fillMaxWidth()) { content() }
            Box(words.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                DesktopButton(label = stringResource(Res.string.setup_done), onClick = onDismiss, primary = true)
            }
        }
    }
}

/** A hairline down the panel's left edge, where it meets the picture. A stroke, so it is exempt from the 4 dp step. */
private fun Modifier.leftRule(color: Color): Modifier = drawBehind {
    drawRect(color, size = Size(CloudStreamDimens.Hairline.toPx(), size.height))
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
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(all = PlayerScreenDefaults.PickerRingRoom),
        verticalArrangement = Arrangement.spacedBy(PlayerScreenDefaults.PanelRowGap),
    ) {
        item {
            PlayerPanelRow(
                title = stringResource(Res.string.no_subtitles),
                detail = null,
                verb = useVerb(selected == null),
                selected = selected == null,
                onClick = { onSelect(null) },
                modifier = if (selectedRow == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
        itemsIndexed(tracks) { index, track ->
            val loading = selection is SubtitleSelection.Loading && selection.track == track
            val failure = failures[track.id].takeUnless { loading }
            PlayerPanelRow(
                title = track.label,
                detail = (if (loading) Res.string.loading else failure?.let(::failureWords))?.let { stringResource(it) },
                // Pressing a failed track loads it again, as a failed source's row retries it.
                verb = if (failure != null) RowVerb.Retry else useVerb(selected == track),
                selected = selected == track,
                failed = failure != null,
                onClick = { onSelect(track) },
                modifier = if (selectedRow == index + 1) Modifier.focusRequester(focus) else Modifier,
            )
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
