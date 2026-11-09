package com.lagradost.cloudstream4.feature.downloads.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopOfflinePill
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBar
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBarDefaults
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRowsSkeleton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopStateView
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageMeter
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageUsage
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.download_queue
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_browse_library
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_column_size
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_free_up_space
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_offline_pill
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_play_from_link
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_selected_status
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_storage_full_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_storage_legend
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_storage_needs
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_titles_status
import com.lagradost.cloudstream4.core.strings.generated.resources.name
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.status
import com.lagradost.cloudstream4.core.strings.generated.resources.title_downloads
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.DownloadStorage
import com.lagradost.cloudstream4.feature.downloads.DownloadTitle
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsState
import com.lagradost.cloudstream4.feature.downloads.DownloadsStatus
import com.lagradost.cloudstream4.feature.downloads.StorageShortfall
import org.jetbrains.compose.resources.stringResource

/**
 * What is on the disk and on its way, for a resizable window driven by a pointer and a keyboard: boards D1 to D3, D5 and
 * D6 of `design/Desktop Downloads.dc.html` (`DS-18`, `KMP-54`). The page beside the window's sidebar; the sidebar, the menu
 * bar and the window are the shell's (`KMP-53`).
 *
 * Shares [DownloadsState] and its view model with TV and draws its own rows, because a Mac row behaves differently: it
 * selects on press, opens on a double-click, shows its verbs under the pointer and has a context menu (`DEC-19`).
 * Choosing is never a mode here; [DownloadsDesktopSelection] says the pointer's gestures as the view model's actions.
 *
 * Keys, with the list focused: ⌘A selects all, ⌘⌫ deletes the selection after asking, Return opens the one selected
 * title, ⌘R shows it in Finder, and Escape clears the selection or closes the menu.
 *
 * @param formatBytes formats a size for this locale. That is the platform's work.
 * @param confirming the titles the host is asking about, from [com.lagradost.cloudstream4.feature.downloads.DownloadsEffect.ConfirmDelete],
 *   until it is answered. Drawn as board D3's sheet, whose default is Cancel (`DEC-19`).
 * @param canReveal whether a title has something on the disk to show in Finder yet.
 * @param canPlayFromLink whether D1's Play from link has a page behind it on this host. Off, the button is not drawn: no
 *   dead controls (the PM's call, `KMP-75`). The Mac has none yet.
 * @param canOpenQueue the same for D1's Download queue.
 * @param artwork draws a title's poster. Image loading is the host's.
 */
@Composable
fun DownloadsScreenDesktop(
    state: DownloadsState,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
    confirming: Set<DownloadTitleId>? = null,
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    canReveal: (DownloadTitle) -> Boolean = { false },
    onReveal: (DownloadTitleId) -> Unit = {},
    artwork: @Composable (DownloadTitle) -> Unit = {},
    canPlayFromLink: Boolean = false,
    canOpenQueue: Boolean = false,
) {
    val send: (List<DownloadsAction>) -> Unit = { actions -> actions.forEach(onAction) }
    var menu by remember { mutableStateOf<DownloadsMenu?>(null) }
    val listFocus = remember { FocusRequester() }
    val reveal: (DownloadTitleId) -> Unit = { id -> if (state.titles.any { it.id == id && canReveal(it) }) onReveal(id) }
    Box(
        modifier
            .fillMaxSize()
            .background(CloudStreamTheme.colors.bg)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || confirming != null) return@onPreviewKeyEvent false
                val single = DownloadsDesktopSelection.single(state)
                when {
                    event.key == Key.Escape && menu != null -> menu = null
                    event.key == Key.Escape -> send(DownloadsDesktopSelection.clear(state))
                    event.isMetaPressed && event.key == Key.A -> send(DownloadsDesktopSelection.all(state))
                    event.isMetaPressed && event.key == Key.Backspace -> send(DownloadsDesktopSelection.delete(state, null))
                    event.isMetaPressed && event.key == Key.R && single != null -> reveal(single)
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) && single != null ->
                        send(DownloadsDesktopSelection.open(state, single))

                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, onAction, canPlayFromLink, canOpenQueue)
            Header(state, formatBytes)
            state.storageFull?.let { StorageFullBanner(it, onAction, formatBytes) }
            Body(
                state = state,
                onAction = onAction,
                formatBytes = formatBytes,
                canReveal = canReveal,
                onReveal = onReveal,
                menu = menu,
                onMenu = { menu = it },
                artwork = artwork,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(listFocus)
                    .focusable(),
                onPointerDown = { listFocus.requestFocus() },
            )
            Footer(state, formatBytes)
        }
        confirming?.let { ids ->
            val bytes = state.titles.filter { it.id in ids }.sumOf { it.bytesDone }
            DesktopDeleteSheet(ids.size, bytes, formatBytes, onConfirmDelete, onCancelDelete)
        }
    }
}

/**
 * Play from link and the queue, at the trailing end of the window's toolbar band: each only where the host has
 * something behind it. The band stays either way, so the page below does not move when one arrives.
 */
@Composable
private fun Toolbar(state: DownloadsState, onAction: (DownloadsAction) -> Unit, canPlayFromLink: Boolean, canOpenQueue: Boolean) {
    // KMP-98: the page bar every Mac page starts with (board D1): a hairline under it, not a box round it.
    DesktopPageBar(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End)) {
        if (canPlayFromLink) {
            DesktopButton(
                label = stringResource(Res.string.downloads_play_from_link),
                onClick = { onAction(DownloadsAction.PlayFromUrlRequested) },
                icon = CloudStreamIcons.Link,
            )
        }
        if (!canOpenQueue) return@DesktopPageBar
        val queue = stringResource(Res.string.download_queue)
        val label = if (state.queue.total > 0) {
            // The count joins the word through the shared separator key, so a translation and RTL order it.
            UiString.Resource(Res.string.downloads_joined, listOf(queue, "${state.queue.running}/${state.queue.total}")).resolve()
        } else {
            queue
        }
        DesktopButton(
            label = label,
            onClick = { onAction(DownloadsAction.QueueRequested) },
        )
    }
}

/** The title, the offline pill beside it, and the disk under it. */
@Composable
private fun Header(state: DownloadsState, formatBytes: (Long) -> String) {
    val colors = CloudStreamTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.fillMaxWidth().padding(
            start = DesktopDimens.WindowMargin,
            end = DesktopDimens.WindowMargin,
            top = DesktopDimens.WindowMargin,
            bottom = DesktopDimens.GroupGap,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
            BasicText(
                text = stringResource(Res.string.title_downloads),
                style = CloudStreamTheme.typography.h1.copy(color = colors.ink),
                maxLines = 1,
            )
            if (state.offline) DesktopOfflinePill(stringResource(Res.string.downloads_offline_pill))
        }
        DiskMeter(state.storage, formatBytes)
    }
}

/** The design system's meter, at the width the board gives it. Undivided until the disk has been read. */
@Composable
private fun DiskMeter(storage: DownloadStorage?, formatBytes: (Long) -> String) {
    val usage = storage?.takeIf { it.totalBytes > 0L }?.let {
        val total = it.totalBytes.toFloat()
        StorageUsage(other = (it.usedBytes - it.downloadBytes) / total, downloads = it.downloadBytes / total)
    }
    val legend = storage?.let {
        UiString.Resource(
            Res.string.downloads_storage_legend,
            listOf(formatBytes(it.usedBytes), formatBytes(it.totalBytes), formatBytes(it.downloadBytes)),
        ).resolve()
    }
    StorageMeter(usage = usage, legend = legend, modifier = Modifier.width(DownloadsScreenDesktopDefaults.MeterWidth))
}

/**
 * Board D5's banner: what is short, and the fix. The fix's words stay "Free up space" in both of its meanings, because the
 * view model decides whether it chooses rows to delete or sends the user to the system's storage settings.
 */
@Composable
private fun StorageFullBanner(shortfall: StorageShortfall, onAction: (DownloadsAction) -> Unit, formatBytes: (Long) -> String) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    val headline = stringResource(Res.string.downloads_storage_full_headline)
    val text = shortfall.bytesShort?.let {
        val needs = UiString.Resource(Res.string.downloads_storage_needs, listOf(formatBytes(it))).resolve()
        UiString.Resource(Res.string.downloads_joined, listOf(headline, needs)).resolve()
    } ?: headline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .padding(horizontal = DesktopDimens.WindowMargin - DesktopDimens.RowPadding)
            .padding(bottom = DesktopDimens.GroupGap)
            .fillMaxWidth()
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
    ) {
        // Decoration: the words beside it say what is wrong.
        CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small)
        BasicText(
            text = text,
            style = CloudStreamTheme.typography.body.copy(color = colors.ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DesktopButton(
            label = stringResource(Res.string.downloads_free_up_space),
            // Text-only: rule 3 of `ui-components.md` lists Free up space, and Settings would claim the wrong action.
            onClick = { onAction(DownloadsAction.FreeUpSpace) },
        )
    }
}

@Composable
private fun Body(
    state: DownloadsState,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
    canReveal: (DownloadTitle) -> Boolean,
    onReveal: (DownloadTitleId) -> Unit,
    menu: DownloadsMenu?,
    onMenu: (DownloadsMenu?) -> Unit,
    artwork: @Composable (DownloadTitle) -> Unit,
    modifier: Modifier,
    onPointerDown: () -> Unit,
) {
    val prompts = Modifier.fillMaxSize().padding(DesktopDimens.WindowMargin)
    when (val status = state.status) {
        DownloadsStatus.Loading -> DesktopRowsSkeleton(modifier.padding(horizontal = DesktopDimens.WindowMargin - DesktopDimens.RowPadding))
        // Never takes focus, as in the other desktop screens: a window opening on a failure has not asked for it.
        is DownloadsStatus.Failed -> DesktopStateView(
            prompt = StatePrompt(
                headline = stringResource(Res.string.downloads_load_failed_headline),
                body = stringResource(Res.string.downloads_load_failed_body),
                action = StateAction(stringResource(Res.string.player_source_retry), CloudStreamIcons.Retry) {
                    onAction(DownloadsAction.Retry)
                },
            ),
            modifier = prompts,
            detail = status.detail,
            requestInitialFocus = false,
        )

        DownloadsStatus.Empty -> DesktopStateView(
            prompt = StatePrompt(
                headline = stringResource(Res.string.downloads_empty_headline),
                body = stringResource(Res.string.downloads_empty_body),
                action = StateAction(stringResource(Res.string.downloads_browse_library), CloudStreamIcons.Library) {
                    onAction(DownloadsAction.LibraryRequested)
                },
            ),
            modifier = prompts,
            requestInitialFocus = false,
        )

        is DownloadsStatus.Showing -> BoxWithConstraints(modifier) {
            val wide = maxWidth >= DownloadsScreenDesktopDefaults.WideMinWidth
            val anchor = remember { mutableStateOf<DownloadTitleId?>(null) }
            Column(Modifier.fillMaxSize()) {
                ColumnHeaders(wide)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        // A press on no row clears the selection, as in Finder. Rows consume their own presses.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onPointerDown()
                            DownloadsDesktopSelection.clear(state).forEach(onAction)
                        },
                    contentPadding = PaddingValues(
                        start = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                        end = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                        bottom = DesktopDimens.GroupGap,
                    ),
                ) {
                    items(status.titles, key = { it.id.value }) { title ->
                        DownloadsDesktopRow(
                            title = title,
                            selected = title.id in state.selection.orEmpty(),
                            wide = wide,
                            formatBytes = formatBytes,
                            canReveal = canReveal(title),
                            artwork = { artwork(title) },
                            onPress = { press ->
                                onPointerDown()
                                val actions = when {
                                    press.secondary -> DownloadsDesktopSelection.contextMenu(state, title.id)
                                    press.double -> DownloadsDesktopSelection.open(state, title.id)
                                    press.shift -> DownloadsDesktopSelection.shiftClick(state, anchor.value, title.id)
                                    press.command -> DownloadsDesktopSelection.commandClick(state, title.id)
                                    else -> DownloadsDesktopSelection.click(state, title.id)
                                }
                                if (!press.shift && !press.secondary) anchor.value = title.id
                                actions.forEach(onAction)
                                if (press.secondary) onMenu(DownloadsMenu(title.id, press.at))
                            },
                            onResume = { onAction(DownloadsAction.ResumeTitle(title.id)) },
                            onReveal = { onReveal(title.id) },
                            onDelete = { DownloadsDesktopSelection.delete(state, title.id).forEach(onAction) },
                            menuAt = menu?.takeIf { it.id == title.id }?.at,
                            onMenuDismiss = { onMenu(null) },
                        )
                    }
                }
            }
        }
    }
}

/** NAME, STATUS and SIZE, over the columns [DownloadsDesktopRow] lays out the same way. The size folds away when narrow. */
@Composable
private fun ColumnHeaders(wide: Boolean) {
    val style = CloudStreamTheme.typography.captionMono.copy(color = CloudStreamTheme.colors.muted)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesktopDimens.WindowMargin)
            .heightIn(min = DownloadsScreenDesktopDefaults.HeaderRowHeight)
            .bottomRule(CloudStreamTheme.colors.line),
    ) {
        BasicText(stringResource(Res.string.name).uppercase(), style = style, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(DesktopDimens.GroupGap))
        BasicText(
            stringResource(Res.string.status).uppercase(),
            style = style,
            maxLines = 1,
            modifier = Modifier.width(DownloadsScreenDesktopDefaults.StatusWidth),
        )
        if (wide) {
            Spacer(Modifier.width(DesktopDimens.GroupGap))
            BasicText(
                stringResource(Res.string.downloads_column_size).uppercase(),
                style = style.copy(textAlign = TextAlign.End),
                maxLines = 1,
                modifier = Modifier.width(DownloadsScreenDesktopDefaults.SizeWidth),
            )
        }
        Spacer(Modifier.width(DesktopDimens.RelatedGap + DownloadsScreenDesktopDefaults.VerbsWidth))
    }
}

/** Finder's status bar: what is here, or what is chosen and the room it takes. */
@Composable
private fun Footer(state: DownloadsState, formatBytes: (Long) -> String) {
    val titles = state.titles
    if (titles.isEmpty()) return
    val chosen = state.selection.orEmpty()
    val text = if (chosen.isEmpty()) {
        UiString.Plural(Res.plurals.downloads_titles_status, titles.size, listOf(titles.size, formatBytes(titles.sumOf { it.bytesDone })))
            .resolve()
    } else {
        UiString.Resource(Res.string.downloads_selected_status, listOf(chosen.size, titles.size, formatBytes(state.selectedBytes)))
            .resolve()
    }
    val colors = CloudStreamTheme.colors
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(DownloadsScreenDesktopDefaults.FooterHeight)
            .background(colors.surface)
            .border(CloudStreamDimens.Hairline, colors.line)
            .padding(horizontal = DesktopDimens.WindowMargin),
    ) {
        BasicText(text, style = CloudStreamTheme.typography.caption.copy(color = colors.ink2), maxLines = 1)
    }
}

/** A hairline under the column names, as a Finder list draws it. A stroke, so it is exempt from the 4 dp step. */
private fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    val stroke = CloudStreamDimens.Hairline.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
}

/** Where a right-click opened the menu, and on which row. */
internal data class DownloadsMenu(val id: DownloadTitleId, val at: Offset)

/** Values this screen needs that `:core:theme` and `DesktopDimens` do not name. */
object DownloadsScreenDesktopDefaults {
    /** The page bar's band, which the delete sheet hangs under. Mirrors `DesktopPageBarDefaults.Height`. */
    val ToolbarHeight: Dp = DesktopPageBarDefaults.Height

    /** A title's row, board D1: 48 of art and 4 above and below. */
    val RowHeight: Dp = 56.dp

    val ArtWidth: Dp = 32.dp
    val ArtHeight: Dp = 48.dp

    /** The status column, wide enough for "Downloading · 100%" in the desktop body. */
    val StatusWidth: Dp = 192.dp

    /** The size column, wide enough for "4.24 GB / 5.10 GB" in the caption mono (`DS-18`'s measured fix). */
    val SizeWidth: Dp = 160.dp

    /** Three 24 verbs and two 8 gaps: board D1's hover verbs. Mirrors `DesktopDimens.FieldActionSize`. */
    val VerbsWidth: Dp = 88.dp

    /** Below this the size folds into the second line (board D6): 1000 window less the 220 sidebar. */
    val WideMinWidth: Dp = 780.dp

    val HeaderRowHeight: Dp = 24.dp
    val FooterHeight: Dp = 32.dp
    val MeterWidth: Dp = 320.dp
    val SheetWidth: Dp = 404.dp
    val MenuWidth: Dp = 240.dp

    /** A menu item: a 24 body line and 4 above and below. */
    val MenuItemHeight: Dp = 32.dp

    /** The rule under a moving row's words. */
    val RuleWidth: Dp = 160.dp

    /** How long the pointer rests on a verb before it names itself, as macOS's own tooltips wait. */
    const val TooltipDelayMillis = 600L

    /** As the library's picker dims the page behind it. */
    const val ScrimAlpha = 0.56f
}
