package com.lagradost.cloudstream4.feature.downloads.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPill
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPillState
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageMeter
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageUsage
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.ChipRow
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.delete
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_delete_chosen
import com.lagradost.cloudstream4.core.strings.generated.resources.deselect_all
import com.lagradost.cloudstream4.core.strings.generated.resources.download_queue
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_browse_library
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_delete_select
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_free_up_space
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_offline_pill
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_play_from_link
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_queued
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_select
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_storage_full_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_storage_legend
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_storage_needs
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.select_all
import com.lagradost.cloudstream4.core.strings.generated.resources.title_downloads
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.DownloadStorage
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsState
import com.lagradost.cloudstream4.feature.downloads.DownloadsStatus
import com.lagradost.cloudstream4.feature.downloads.DownloadsStop
import com.lagradost.cloudstream4.feature.downloads.StorageShortfall
import org.jetbrains.compose.resources.stringResource

/**
 * The TV downloads screen: `design/TV Episodes Downloads.dc.html` boards **6c** (titles and the meter) and **6e**
 * (empty, offline, storage full).
 *
 * Stateless: a [DownloadsState] in, [DownloadsAction]s out.
 *
 * ## The two chromes
 *
 * 6c has the meter in the top row and the chips in the band below it. On 6e the band belongs to the storage strip
 * or, when the disk is full, to the banner that replaces it, so `Play from URL` and the queue move up to the top
 * row as buttons. `Select` has no place on 6e and does not need one: with no titles there is nothing to choose,
 * and with a full disk the banner's `Free up space` is the same action. Being offline costs nothing, because the
 * pill rides the empty middle of the top row in every layout.
 *
 * Choosing what to delete has no board. It takes the band, in the same chips.
 *
 * ## Focus
 *
 * Nothing takes focus on arrival: the shell's rail holds it until the user presses Right. A stop that is about to
 * vanish hands focus on first, because a focused node that leaves composition drops focus out of the window.
 *
 * @param formatBytes formats a size for this locale. That is the platform's work.
 */
@Composable
fun DownloadsScreenTv(
    state: DownloadsState,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val focus = remember { DownloadsFocus() }
    val memory = remember { DownloadsFocusMemory() }
    DownloadsFocusEffects(state, focus, memory, onAction)

    val selecting = state.selection != null
    val showing = state.status is DownloadsStatus.Showing || state.status is DownloadsStatus.Loading
    val buttonsOnTop = state.usesTopRowButtons
    Column(modifier.fillMaxSize().onFocusChanged { focus.screenHasFocus = it.hasFocus }) {
        Spacer(Modifier.height(CloudStreamDimens.ChromeRowTop))
        DownloadsChromeRow(state, buttonsOnTop, focus, memory, onAction, formatBytes, Modifier.contentInset())
        Spacer(Modifier.height(DownloadsScreenTvDefaults.ChromeToBandGap))
        Box(
            modifier = Modifier.contentInset().fillMaxWidth().height(CloudStreamDimens.ChromeRowHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                selecting -> SelectionChips(state, focus, memory, onAction, formatBytes)
                state.storageFull != null ->
                    StorageFullBanner(state, state.storageFull, focus, memory, onAction, formatBytes)
                showing -> DownloadChips(state, focus, memory, onAction)
                else -> DownloadsStorageMeter(state.storage, formatBytes, Modifier.fillMaxWidth())
            }
        }
        DownloadsBody(state, focus, memory, onAction, formatBytes, Modifier.fillMaxWidth().weight(FILL))
    }
}

/** 6e's top row rather than 6c's: the band is taken by the banner or the storage strip, and the chips have nowhere to go. */
private val DownloadsState.usesTopRowButtons: Boolean
    get() = selection == null &&
        (storageFull != null || status is DownloadsStatus.Empty || status is DownloadsStatus.Failed)

/** The requesters that outlive a change of layout, and the moves between them. */
@Stable
internal class DownloadsFocus {
    val queue = FocusRequester()
    val playFromUrl = FocusRequester()
    /** `Select`, or `Free up space` where the banner has replaced it. */
    val selectEntry = FocusRequester()
    val selectAll = FocusRequester()
    val cancel = FocusRequester()
    val delete = FocusRequester()
    private val rows = mutableMapOf<DownloadTitleId, FocusRequester>()

    var screenHasFocus by mutableStateOf(false)

    fun row(id: DownloadTitleId): FocusRequester = rows.getOrPut(id) { FocusRequester() }

    /** Rows that have left the list, as requesters, and forgets them. */
    fun forgetRowsOtherThan(kept: Set<DownloadTitleId>): List<FocusRequester> {
        val gone = rows.keys - kept
        return gone.map { rows.remove(it)!! }
    }

    /** Somewhere that exists whatever the state is: `Play from URL` is on both chromes. */
    fun requestEntry() {
        if (!selectEntry.requestFocusIfAttached()) playFromUrl.requestFocusIfAttached()
    }

    val selectionStops: List<FocusRequester> get() = listOf(selectAll, cancel, delete)

    fun requesterOf(stop: DownloadsStop): FocusRequester = when (stop) {
        is DownloadsStop.Title -> row(stop.id)
        DownloadsStop.Queue -> queue
        DownloadsStop.PlayFromUrl -> playFromUrl
    }
}

@Composable
private fun DownloadsFocusEffects(
    state: DownloadsState,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
) {
    // Read while composing, before the change is applied. When a focused stop leaves, the view hands focus to some other
    // stop by itself and `last` follows, so by the time an effect runs it no longer says what left (measured).
    val heldFocus = memory.last.takeIf { memory.inside }

    val titleIds = state.titles.map { it.id }
    LaunchedEffect(titleIds) {
        if (state.status is DownloadsStatus.Loading) return@LaunchedEffect
        val gone = focus.forgetRowsOtherThan(titleIds.toSet())
        if (heldFocus in gone) {
            withFrameNanos { }
            val next = titleIds.firstOrNull()?.let(focus::row)
            if (next?.requestFocusIfAttached() != true) focus.requestEntry()
        }
    }

    // Choosing ended while one of its chips held focus, and those chips have gone.
    val selecting = state.selection != null
    LaunchedEffect(selecting) {
        if (!selecting && heldFocus in focus.selectionStops) {
            withFrameNanos { }
            focus.requestEntry()
        }
    }

    // The top row's buttons and the band's chips are the same two stops in two places. Changing layout under one drops
    // focus, so it follows them.
    val buttonsOnTop = state.usesTopRowButtons
    LaunchedEffect(buttonsOnTop) {
        if (heldFocus === focus.queue || heldFocus === focus.playFromUrl) {
            withFrameNanos { }
            heldFocus.requestFocusIfAttached()
        }
    }

    LaunchedEffect(memory.pending) {
        val target = memory.pending ?: return@LaunchedEffect
        if (!target.requestFocusIfAttached()) memory.settle()
        memory.pending = null
    }

    // Through state: moving between two stops reports "no focus" for a moment, in the same frame (`KMP-34`).
    LaunchedEffect(focus.screenHasFocus) {
        if (!focus.screenHasFocus) memory.left()
    }

    // A row takes it for itself once it is composed. Anything else, or a row that has gone, is done here.
    val returnTo = state.returnTo
    LaunchedEffect(returnTo, titleIds) {
        if (returnTo == null || !returnTo.ready || state.status is DownloadsStatus.Loading) return@LaunchedEffect
        val stop = returnTo.stop
        if (stop is DownloadsStop.Title && stop.id in titleIds) return@LaunchedEffect
        withFrameNanos { }
        if (!focus.requesterOf(stop).requestFocusIfAttached()) focus.requestEntry()
        onAction(DownloadsAction.ReturnHandled)
    }
}

/** 6c's and 6e's top row: the name, the offline pill in the empty middle, and on the right the meter or two buttons. */
@Composable
private fun DownloadsChromeRow(
    state: DownloadsState,
    buttonsOnTop: Boolean,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val title = CloudStreamTheme.typography.railTitle
    Row(
        modifier = modifier.fillMaxWidth().height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.ChromeGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(Res.string.title_downloads),
            style = remember(title, colors) { title.copy(color = colors.ink) },
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(FILL),
        )
        if (state.offline) StatusPill(StatusPillState.Failed, stringResource(Res.string.downloads_offline_pill))
        when {
            buttonsOnTop -> {
                GhostTextButton(
                    label = stringResource(Res.string.downloads_play_from_link),
                    icon = CloudStreamIcons.Link,
                    onClick = { onAction(DownloadsAction.PlayFromUrlRequested) },
                    modifier = Modifier.downloadsStop(focus.playFromUrl, memory),
                )
                GhostTextButton(
                    label = queueLabel(state),
                    onClick = { onAction(DownloadsAction.QueueRequested) },
                    modifier = Modifier.downloadsStop(focus.queue, memory),
                )
            }

            else -> DownloadsStorageMeter(
                storage = state.storage,
                formatBytes = formatBytes,
                modifier = Modifier.width(DownloadsScreenTvDefaults.MeterWidth),
            )
        }
    }
}

/** The old button's own text, `Download queue (1/4)`, the word coming from the same key. */
@Composable
private fun queueLabel(state: DownloadsState): String {
    val base = stringResource(Res.string.download_queue)
    return if (state.queue.total > 0) "$base (${queueCount(state)})" else base
}

private fun queueCount(state: DownloadsState): String = "${state.queue.running}/${state.queue.total}"

/** 6c's meter. Undivided and with no legend until the disk has been read, so nothing moves when it has. */
@Composable
private fun DownloadsStorageMeter(
    storage: DownloadStorage?,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val usage = storage?.takeIf { it.totalBytes > 0L }?.let {
        val total = it.totalBytes.toFloat()
        StorageUsage(other = (it.usedBytes - it.downloadBytes) / total, downloads = it.downloadBytes / total)
    }
    val legend = storage?.let {
        UiString.Resource(Res.string.downloads_storage_legend, listOf(formatBytes(it.usedBytes),
            formatBytes(it.totalBytes),
            formatBytes(it.downloadBytes))).resolve()
    }
    StorageMeter(usage = usage, legend = legend, modifier = modifier)
}

/** 6c's chips. */
@Composable
private fun DownloadChips(
    state: DownloadsState,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
) {
    ChipRow {
        Chip(
            label = stringResource(Res.string.download_queue),
            selected = false,
            onClick = { onAction(DownloadsAction.QueueRequested) },
            modifier = Modifier.downloadsStop(focus.queue, memory),
            count = if (state.queue.total > 0) queueCount(state) else null,
        )
        Chip(
            label = stringResource(Res.string.downloads_play_from_link),
            selected = false,
            onClick = { onAction(DownloadsAction.PlayFromUrlRequested) },
            modifier = Modifier.downloadsStop(focus.playFromUrl, memory),
        )
        Chip(
            label = stringResource(Res.string.downloads_select),
            selected = false,
            onClick = {
                // Before the chip goes: what comes next is choosing a row.
                state.titles.firstOrNull()?.let { focus.row(it.id).requestFocusIfAttached() }
                onAction(DownloadsAction.SelectionStarted)
            },
            modifier = Modifier.downloadsStop(focus.selectEntry, memory),
        )
    }
}

/** Choosing what to delete, which has no board. The old screen's three controls, in the same order of words. */
@Composable
private fun SelectionChips(
    state: DownloadsState,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
) {
    val chosen = state.selection.orEmpty()
    val allChosen = chosen.size == state.titles.size
    ChipRow {
        Chip(
            label = stringResource(if (allChosen) Res.string.deselect_all else Res.string.select_all),
            selected = false,
            onClick = { onAction(DownloadsAction.SelectAllToggled) },
            modifier = Modifier.downloadsStop(focus.selectAll, memory),
        )
        Chip(
            label = stringResource(Res.string.cancel),
            selected = false,
            onClick = { onAction(DownloadsAction.SelectionCancelled) },
            modifier = Modifier.downloadsStop(focus.cancel, memory),
        )
        if (chosen.isEmpty()) {
            DownloadsLabel(
                text = stringResource(Res.string.downloads_delete_select),
                style = CloudStreamTheme.typography.label,
                color = CloudStreamTheme.colors.ink2,
            )
        } else {
            Chip(
                label = UiString.Resource(
                    Res.string.downloads_delete_chosen,
                    listOf(stringResource(Res.string.delete), chosen.size, formatBytes(state.selectedBytes)),
                ).resolve(),
                selected = false,
                onClick = { onAction(DownloadsAction.DeleteSelected) },
                modifier = Modifier.downloadsStop(focus.delete, memory),
            )
        }
    }
}

/**
 * 6e's banner, in the band the meter would otherwise have. A hollow marker and a `muted` rule, never `live`
 * (`DEC-17`), and it states the shortfall rather than the condition.
 */
@Composable
private fun StorageFullBanner(
    state: DownloadsState,
    shortfall: StorageShortfall,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    val needs = shortfall.bytesShort?.let { UiString.Resource(Res.string.downloads_storage_needs, listOf(formatBytes(it))).resolve() }
    val waiting = state.queue.waiting
    val queued = if (waiting > 0) UiString.Plural(Res.plurals.downloads_queued, waiting).resolve() else null
    val detail = listOfNotNull(needs, queued).joinToString(DETAIL_SEPARATOR)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CloudStreamDimens.ChromeRowHeight)
            .clip(shape)
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(DownloadsScreenTvDefaults.BannerRule)
                .fillMaxHeight()
                .background(colors.muted),
        )
        Spacer(Modifier.width(DownloadsScreenTvDefaults.BannerMarkerInset))
        StatusDot(StatusDotState.Failed)
        Spacer(Modifier.width(DownloadsScreenTvDefaults.BannerTextGap))
        Column(
            modifier = Modifier.weight(FILL),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap),
        ) {
            DownloadsLabel(
                text = stringResource(Res.string.downloads_storage_full_headline),
                style = CloudStreamTheme.typography.body,
                color = colors.ink,
            )
            if (detail.isNotEmpty()) {
                DownloadsLabel(text = detail, style = CloudStreamTheme.typography.captionMono, color = colors.ink2)
            }
        }
        GhostTextButton(
            label = stringResource(Res.string.downloads_free_up_space),
            onClick = {
                state.titles.firstOrNull()?.let { focus.row(it.id).requestFocusIfAttached() }
                onAction(DownloadsAction.FreeUpSpace)
            },
            modifier = Modifier
                .padding(end = DownloadsScreenTvDefaults.BannerActionInset)
                .downloadsStop(focus.selectEntry, memory),
        )
    }
}

/** The rows, their skeleton, or the one state view that empty and a failed read share. */
@Composable
private fun DownloadsBody(
    state: DownloadsState,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    when (val status = state.status) {
        DownloadsStatus.Loading -> DownloadsSkeleton(modifier)
        is DownloadsStatus.Showing -> DownloadRows(status.titles, state, focus, memory, onAction, formatBytes, modifier)
        DownloadsStatus.Empty -> StateView(
            prompt = StatePrompt(
                headline = stringResource(Res.string.downloads_empty_headline),
                body = stringResource(Res.string.downloads_empty_body),
                action = StateAction(
                    label = stringResource(Res.string.downloads_browse_library),
                    icon = CloudStreamIcons.Library,
                    onActivate = { onAction(DownloadsAction.LibraryRequested) },
                ),
            ),
            modifier = modifier,
            requestInitialFocus = false,
            glyph = { DownloadsEmptyGlyph() },
        )

        is DownloadsStatus.Failed -> StateView(
            prompt = StatePrompt(
                headline = stringResource(Res.string.downloads_load_failed_headline),
                body = stringResource(Res.string.downloads_load_failed_body),
                action = StateAction(
                    label = stringResource(Res.string.player_source_retry),
                    icon = CloudStreamIcons.Retry,
                    onActivate = {
                        // Before the button goes with its state. The stop then follows the layout change above.
                        focus.playFromUrl.requestFocusIfAttached()
                        onAction(DownloadsAction.Retry)
                    },
                ),
            ),
            modifier = modifier,
            detail = status.detail,
            requestInitialFocus = false,
        )
    }
}

/** 6e's tile: the box `StateView` reserves, filled raised with a hairline, and the screen's own icon in it. */
@Composable
private fun DownloadsEmptyGlyph() {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        CloudStreamIcon(
            icon = CloudStreamIcons.Download,
            contentDescription = null,
            size = CloudStreamIconSize.Large,
            tint = colors.muted,
        )
    }
}

/** One line of words that is not a control, on the screen's own colour. */
@Composable
private fun DownloadsLabel(text: String, style: TextStyle, color: Color) {
    BasicText(
        text = text,
        style = remember(style, color) { style.copy(color = color) },
        maxLines = SINGLE_LINE,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The screen's horizontal inset. The list carries its own, because a lazy list must not clip a ring. */
internal fun Modifier.contentInset(): Modifier = padding(
    start = DownloadsScreenTvDefaults.ContentInsetStart,
    end = DownloadsScreenTvDefaults.ContentInsetEnd,
)

/** 6c's and 6e's geometry, halved from the artboards. Each number mirrors the token or the board it names. */
object DownloadsScreenTvDefaults {

    /** `ContentOriginX` less `NavRailWidth`: the rail is the shell's, so x100 is 24dp into this area. */
    val ContentInsetStart: Dp = CloudStreamDimens.ContentOriginX - CloudStreamDimens.NavRailWidth

    /** Mirrors `FocusBandInset`. */
    val ContentInsetEnd: Dp = CloudStreamDimens.FocusBandInset

    /** `ChipRowTop` less `ChromeRowBottom`. Equals `MinFocusGap`. */
    val ChromeToBandGap: Dp = CloudStreamDimens.ChipRowTop - CloudStreamDimens.ChromeRowBottom

    /** Between the top row's parts. Mirrors `MinFocusGap`, which is what two rings need. */
    val ChromeGap: Dp = CloudStreamDimens.MinFocusGap

    /** 6c's meter, x1064 to x1864 on the artboard. */
    val MeterWidth: Dp = 400.dp

    /** 6e's leading rule, 4px on the artboard. A stroke weight. Mirrors `FocusRingStroke`. */
    val BannerRule: Dp = CloudStreamDimens.FocusRingStroke

    /** The hollow marker's x on the banner, 24px on the artboard, 2dp of it the rule. Totals `MinFocusGap`. */
    val BannerMarkerInset: Dp = CloudStreamDimens.MinFocusGap - CloudStreamDimens.FocusRingStroke

    /** From the marker to the words, x224 + 16 to x256 on the artboard. Mirrors `ListRowPadding`. */
    val BannerTextGap: Dp = CloudStreamDimens.ListRowPadding

    /** `Free up space` to the banner's end, 24px on the artboard. Mirrors `MinFocusGap`. */
    val BannerActionInset: Dp = CloudStreamDimens.MinFocusGap

    /** From the band to the first row, y272 to y296 on the artboard. Mirrors `MinFocusGap`, and holds the ring. */
    val BandToRowGap: Dp = CloudStreamDimens.MinFocusGap

    /** Row to row, 120px of pitch less the 96px row. Mirrors `MinFocusGap`. */
    val RowGap: Dp = CloudStreamDimens.MinFocusGap

    /** Below the last row, for its ring. Mirrors `FocusBandInset`. */
    val ListBottomInset: Dp = CloudStreamDimens.FocusBandInset
}

internal const val FILL = 1f
private const val SINGLE_LINE = 1
private const val DETAIL_SEPARATOR = " · "
