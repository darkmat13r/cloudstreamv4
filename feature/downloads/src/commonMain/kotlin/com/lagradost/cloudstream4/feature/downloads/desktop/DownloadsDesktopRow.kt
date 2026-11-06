package com.lagradost.cloudstream4.feature.downloads.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.delete
import com.lagradost.cloudstream4.core.strings.generated.resources.download_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.download_paused
import com.lagradost.cloudstream4.core.strings.generated.resources.download_size_format
import com.lagradost.cloudstream4.core.strings.generated.resources.downloaded
import com.lagradost.cloudstream4.core.strings.generated.resources.downloading
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_episode_count
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_film_subtitle
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_series_subtitle
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_show_in_finder
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.movies_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.popup_resume_download
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series_singular
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.DownloadTitle
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One press on a row, with what the pointer and the keyboard were doing. [at] is in the row's own coordinates. */
internal data class DownloadsPress(
    val secondary: Boolean,
    val double: Boolean,
    val shift: Boolean,
    val command: Boolean,
    val at: Offset,
)

/**
 * One title on the Mac, board D1: art, the name over what it is, its state, its size, and its verbs.
 *
 * It selects on the press, not the release (Apple's rule that feedback comes on the way down), and reports a second
 * press within the double-click time as opening. The verbs appear while the pointer is over the row, in a column kept
 * for them, so nothing moves when they do. They are glyph-only 24 cells, each naming itself in a tooltip (rule 4 of
 * `ui-components.md`, extended to the Mac by the owner, `DEC-19`). The context menu and the menu bar name the same verbs
 * in words.
 *
 * @param wide false below `WideMinWidth`, where the size folds into the second line (board D6).
 * @param menuAt where this row's context menu is open, or null.
 */
@Composable
internal fun DownloadsDesktopRow(
    title: DownloadTitle,
    selected: Boolean,
    wide: Boolean,
    formatBytes: (Long) -> String,
    canReveal: Boolean,
    artwork: @Composable () -> Unit,
    onPress: (DownloadsPress) -> Unit,
    onResume: () -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
    menuAt: Offset? = null,
    onMenuDismiss: () -> Unit = {},
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = CloudStreamTheme.shapes.medium
    val press by rememberUpdatedState(onPress)
    val fill = when {
        selected -> colors.raised
        hovered -> colors.surface
        else -> Color.Transparent
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(DownloadsScreenDesktopDefaults.RowHeight)
                .hoverable(source)
                .background(fill, shape)
                .then(if (hovered && !selected) Modifier.border(CloudStreamDimens.Hairline, colors.line, shape) else Modifier)
                .pointerInput(title.id) {
                    awaitPointerEventScope {
                        var lastPrimary = Long.MIN_VALUE / 2
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type != PointerEventType.Press) continue
                            val change = event.changes.firstOrNull() ?: continue
                            // Control-click is a right-click on a Mac.
                            val secondary = event.buttons.isSecondaryPressed ||
                                (event.buttons.isPrimaryPressed && event.keyboardModifiers.isCtrlPressed)
                            val double = !secondary &&
                                change.uptimeMillis - lastPrimary <= viewConfiguration.doubleTapTimeoutMillis
                            if (!secondary) lastPrimary = if (double) Long.MIN_VALUE / 2 else change.uptimeMillis
                            change.consume()
                            press(
                                DownloadsPress(
                                    secondary = secondary,
                                    double = double,
                                    shift = event.keyboardModifiers.isShiftPressed,
                                    command = event.keyboardModifiers.isMetaPressed,
                                    at = change.position,
                                ),
                            )
                        }
                    }
                }
                .padding(horizontal = DesktopDimens.RowPadding),
        ) {
            Box(
                Modifier
                    .size(DownloadsScreenDesktopDefaults.ArtWidth, DownloadsScreenDesktopDefaults.ArtHeight)
                    .clip(CloudStreamTheme.shapes.small)
                    .background(colors.surface),
            ) { artwork() }
            Spacer(Modifier.width(DesktopDimens.GroupGap))
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title.name,
                    style = typography.label.copy(color = colors.ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = subtitle(title, wide, formatBytes),
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DesktopDimens.GroupGap))
            StatusCell(title)
            if (wide) {
                Spacer(Modifier.width(DesktopDimens.GroupGap))
                BasicText(
                    text = size(title, formatBytes),
                    style = typography.captionMono.copy(color = colors.ink2, textAlign = TextAlign.End),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(DownloadsScreenDesktopDefaults.SizeWidth),
                )
            }
            Spacer(Modifier.width(DesktopDimens.RelatedGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(DownloadsScreenDesktopDefaults.VerbsWidth),
            ) {
                if (hovered) Verbs(title, canReveal, onResume, onReveal, onDelete)
            }
        }
        if (menuAt != null) {
            DownloadsContextMenu(
                at = menuAt,
                canReveal = canReveal,
                onDismiss = onMenuDismiss,
                onReveal = onReveal,
                onDelete = onDelete,
            )
        }
    }
}

/** The status column: words, and the rule under them while there is something left to fetch. */
@Composable
private fun StatusCell(title: DownloadTitle) {
    val colors = CloudStreamTheme.colors
    val percent = title.progress?.let { "${(it * PERCENT).toInt()}%" }
    fun joined(word: String) =
        percent?.let { UiString.Resource(Res.string.downloads_joined, listOf(word, it)) }
    val (text, color) = when (title.state) {
        DownloadTitleState.Done -> stringResource(Res.string.downloaded) to colors.muted
        DownloadTitleState.Failed -> stringResource(Res.string.download_failed) to colors.ink
        DownloadTitleState.Paused -> stringResource(Res.string.download_paused).let { joined(it)?.resolve() ?: it } to colors.ink2
        DownloadTitleState.Running -> stringResource(Res.string.downloading).let { joined(it)?.resolve() ?: it } to colors.ink
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.width(DownloadsScreenDesktopDefaults.StatusWidth),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
            // A failure's mark is a glyph and a word, never `live` (`DEC-17`). Decoration: the word says it.
            if (title.state == DownloadTitleState.Failed) {
                CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = color)
            }
            BasicText(
                text = text,
                style = CloudStreamTheme.typography.body.copy(color = color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val progress = title.progress
        // A failed row draws no rule: it is not moving, and its mark is the glyph and the word.
        if (progress != null && (title.state == DownloadTitleState.Running || title.state == DownloadTitleState.Paused)) {
            ProgressRule(
                progress = progress,
                color = colors.ink,
                trackColor = colors.line,
                modifier = Modifier.width(DownloadsScreenDesktopDefaults.RuleWidth),
            )
        }
    }
}

/** Resume or retry, Show in Finder, Delete: board D1's three, each only when it can act. */
@Composable
private fun Verbs(title: DownloadTitle, canReveal: Boolean, onResume: () -> Unit, onReveal: () -> Unit, onDelete: () -> Unit) {
    when (title.state) {
        DownloadTitleState.Failed -> DesktopVerb(CloudStreamIcons.Retry, stringResource(Res.string.player_source_retry), onResume)
        DownloadTitleState.Paused -> DesktopVerb(CloudStreamIcons.Play, stringResource(Res.string.popup_resume_download), onResume)
        DownloadTitleState.Running, DownloadTitleState.Done -> Unit
    }
    if (canReveal) DesktopVerb(CloudStreamIcons.Folder, stringResource(Res.string.downloads_show_in_finder), onReveal)
    DesktopVerb(CloudStreamIcons.Delete, stringResource(Res.string.delete), onDelete)
}

/**
 * A 24 cell with a 16 glyph (`DesktopDimens.FieldActionSize`), whose label is its content description and, after the
 * pointer rests on it, a tooltip under it. Its own press is consumed, so it never also selects the row.
 */
@Composable
internal fun DesktopVerb(icon: ImageVector, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        tip = false
        if (hovered) {
            delay(DownloadsScreenDesktopDefaults.TooltipDelayMillis)
            tip = true
        }
    }
    Box(Modifier.hoverable(source)) {
        DesktopIconButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            tone = DesktopButtonTone.Secondary,
            size = DesktopDimens.FieldActionSize,
            iconSize = CloudStreamIconSize.Small,
        )
        if (tip) {
            val below = with(LocalDensity.current) { (DesktopDimens.FieldActionSize + DesktopDimens.RelatedGap).roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, below),
                properties = PopupProperties(focusable = false),
            ) { Tooltip(label) }
        }
    }
}

@Composable
private fun Tooltip(label: String) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.small
    BasicText(
        text = label,
        style = CloudStreamTheme.typography.caption.copy(color = colors.ink),
        maxLines = 1,
        modifier = Modifier
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape)
            .padding(horizontal = DesktopDimens.RelatedGap, vertical = TOOLTIP_VERTICAL_PADDING),
    )
}

/**
 * Board D2's menu, at the pointer. It acts on the selection, which the right-click has already made include this row.
 * Each item names its shortcut. Open is not here: a double-click and Return open, and this app has no word for it yet.
 */
@Composable
private fun DownloadsContextMenu(
    at: Offset,
    canReveal: Boolean,
    onDismiss: () -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(at.x.roundToInt(), at.y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(DownloadsScreenDesktopDefaults.MenuWidth)
                .background(colors.raised, shape)
                .border(CloudStreamDimens.Hairline, colors.line, shape)
                .padding(DesktopDimens.RelatedGap / 2),
        ) {
            if (canReveal) {
                MenuItem(stringResource(Res.string.downloads_show_in_finder), "⌘R") { onDismiss(); onReveal() }
                Box(
                    Modifier
                        .padding(vertical = DesktopDimens.RelatedGap / 2, horizontal = DesktopDimens.RelatedGap)
                        .fillMaxWidth()
                        .height(CloudStreamDimens.Hairline)
                        .background(colors.line),
                )
            }
            MenuItem(stringResource(Res.string.delete), "⌘⌫") { onDismiss(); onDelete() }
        }
    }
}

@Composable
private fun MenuItem(label: String, shortcut: String, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DownloadsScreenDesktopDefaults.MenuItemHeight)
            .background(if (hovered) colors.line else Color.Transparent, CloudStreamTheme.shapes.small)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        BasicText(label, style = CloudStreamTheme.typography.body.copy(color = colors.ink), maxLines = 1, modifier = Modifier.weight(1f))
        BasicText(shortcut, style = CloudStreamTheme.typography.captionMono.copy(color = colors.muted), maxLines = 1)
    }
}

/** The second line: what it is, and on a narrow window its size too, because the size column has folded away (D6). */
@Composable
private fun subtitle(title: DownloadTitle, wide: Boolean, formatBytes: (Long) -> String): String {
    val size = size(title, formatBytes)
    return when (title.kind) {
        DownloadTitleKind.Film -> if (wide) {
            stringResource(Res.string.movies_singular)
        } else {
            UiString.Resource(Res.string.downloads_film_subtitle, listOf(stringResource(Res.string.movies_singular), size)).resolve()
        }

        DownloadTitleKind.Series -> if (wide) {
            val count = UiString.Plural(Res.plurals.downloads_episode_count, title.episodes).resolve()
            UiString.Resource(Res.string.downloads_joined, listOf(stringResource(Res.string.tv_series_singular), count)).resolve()
        } else {
            UiString.Resource(
                Res.string.downloads_series_subtitle,
                listOf(title.episodes, pluralStringResource(Res.plurals.episodes, title.episodes), size),
            ).resolve()
        }
    }
}

/** What is on the disk: all of it when done, else how far against the whole. */
@Composable
private fun size(title: DownloadTitle, formatBytes: (Long) -> String): String =
    if (title.state == DownloadTitleState.Done) {
        formatBytes(title.bytesTotal)
    } else {
        UiString.Resource(Res.string.download_size_format, listOf(formatBytes(title.bytesDone), formatBytes(title.bytesTotal))).resolve()
    }

private const val PERCENT = 100

/** A caption's 16 line in a 24 tip: 4 above and below. Mirrors `CloudStreamDimens.StackGap`. */
private val TOOLTIP_VERTICAL_PADDING = CloudStreamDimens.StackGap
