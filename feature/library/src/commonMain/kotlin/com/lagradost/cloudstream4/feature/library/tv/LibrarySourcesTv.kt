package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.target.ChromeButtonDefaults
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_hours_minutes_format
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_format
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.library_episodes_progress
import com.lagradost.cloudstream4.core.strings.generated.resources.library_not_connected
import com.lagradost.cloudstream4.core.strings.generated.resources.library_on_device
import com.lagradost.cloudstream4.core.strings.generated.resources.library_source
import com.lagradost.cloudstream4.core.strings.generated.resources.library_source_note
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sync_offline
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sync_unreachable
import com.lagradost.cloudstream4.core.strings.generated.resources.library_synced_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_day_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_min_format
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibrarySource
import com.lagradost.cloudstream4.feature.library.LibraryStale
import com.lagradost.cloudstream4.feature.library.LibraryState
import org.jetbrains.compose.resources.stringResource

/**
 * 5a's provider button: the shown source's name over what it is, in the chrome row's left slot. It opens 5e.
 *
 * Over a held read (5b, `KMP-51`) it says why and how old: `Unreachable · cached 2h ago` or `Offline · cached 2h ago`,
 * after a failed dot. The words and the dot's shape carry the failure, not a colour (`DEC-17`). Without a clock the
 * age is unknown, and it keeps its usual line beside the dot.
 *
 * @param now the time an age is measured to, or null to show none. From the host, because this module has no clock.
 */
@Composable
internal fun LibraryProviderButton(
    state: LibraryState,
    now: (() -> Long)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = state.shownSource
    val stale = state.stale
    LibraryChromeButton(
        title = shown?.name.orEmpty(),
        meta = stale?.let { shown?.staleLine(it, now) } ?: shown?.line(state.totalTitles, now),
        onClick = onClick,
        modifier = modifier,
        failed = stale != null,
    )
}

/**
 * The board's `.cbtn`, 5a's provider and sort buttons: `ChromeButton`'s ground, inset hairline, ring and padding, with
 * a two-line stack of 16 + 4 + 12 that centres at +8 in the 48dp row, as [LibraryScreenTvDefaults] and the readout it
 * replaces already do. It is not a `ChromeButton`: that one is a label and a count on one line, and these two are the
 * only two-line chrome buttons so far, both on this screen.
 */
@Composable
internal fun LibraryChromeButton(
    title: String,
    meta: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val titleStyle = typography.label
    // `.cbm` is 20/24px mono: the caption's, which fills the 12dp line box. The label's 32px line would clip it.
    val metaStyle = typography.captionMono
    Row(
        modifier = modifier
            .height(CloudStreamDimens.ChromeRowHeight)
            .tvFocusRing(focused = focused, shape = shape)
            .background(colors.raised, shape)
            .insetOutline(colors.line, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = ChromeButtonDefaults.HorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(LibraryChromeButtonDefaults.DotGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 5b's `.sg.fail`, 24px in and centred on the row; the stack moves right to 56px.
        if (failed) StatusDot(state = StatusDotState.Failed)
        LibraryChromeStack(title, meta, titleStyle, metaStyle)
    }
}

@Composable
private fun LibraryChromeStack(title: String, meta: String?, titleStyle: TextStyle, metaStyle: TextStyle) {
    val colors = CloudStreamTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap, Alignment.CenterVertically)) {
        BasicText(
            text = title,
            style = remember(titleStyle, colors) { titleStyle.copy(color = colors.ink) },
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(CloudStreamDimens.StackTitleLineBox),
        )
        if (meta != null) {
            BasicText(
                text = meta,
                style = remember(metaStyle, colors) { metaStyle.copy(color = colors.muted) },
                maxLines = SINGLE_LINE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(CloudStreamDimens.StackSubtitleLineBox),
            )
        }
    }
}

/** The chrome button's one value the theme does not name. */
internal object LibraryChromeButtonDefaults {
    /** 5b's dot at 24px and the stack at 56px, after the 16px dot: 56 - 24 - 16 = 16px, so 8dp, halved. */
    val DotGap: Dp = 8.dp
}

/**
 * 5e: every source in one place with its real state. One choice applies at once, as `DEC-17` has it for a
 * single-choice panel, and `Done` and Back close it. It opens on the shown source.
 *
 * A source that is not connected is listed, disabled, and still a stop, so its reason can be read at three metres:
 * the same choice `ListRowState.focusableWhenDisabled` exists for. What such a row does is decided here only.
 *
 * The board's leading disc is not drawn: it would hold the service's logo, which is a brand mark this module does
 * not have, and an empty disc says nothing.
 */
@Composable
internal fun LibrarySourcePanelTv(state: LibraryState, now: (() -> Long)?, onAction: (LibraryAction) -> Unit) {
    val chosen = state.sources.indexOfFirst { it.id == state.source }.coerceAtLeast(0)
    val list = rememberLazyListState(chosen)
    // As it opened: a choice changes `source` while the panel slides away, and the row must not then ask for focus.
    val landOn = remember { state.source }
    SidePanel(
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap)) {
                RailHeader(title = stringResource(Res.string.library_source))
                PanelNote(stringResource(Res.string.library_source_note))
            }
        },
        commit = {
            ListRow(
                state = ListRowState(title = stringResource(Res.string.setup_done)),
                onClick = { onAction(LibraryAction.SourcesDismissed) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
            // A focused row's ring needs this much room, and the list clips at its own edge.
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            items(items = state.sources, key = { it.id.value }) { source ->
                val requester = remember { FocusRequester() }
                // After `SidePanelHost` has moved focus into the panel, which lands on the first row it finds.
                LaunchedEffect(Unit) {
                    if (source.id == landOn) {
                        withFrameNanos { }
                        requester.requestFocusIfAttached()
                    }
                }
                val shownTotal = if (source.id == state.source) state.totalTitles else null
                ListRow(
                    modifier = Modifier.focusRequester(requester),
                    state = ListRowState(
                        title = source.name,
                        subtitle = source.line(shownTotal, now),
                        selected = source.id == state.source,
                        enabled = source.connected,
                        focusableWhenDisabled = true,
                    ),
                    onClick = { onAction(LibraryAction.SourceChosen(source.id)) },
                    trailing = {
                        StatusDot(state = if (source.connected) StatusDotState.Ok else StatusDotState.Neutral)
                    },
                )
            }
        }
    }
}

@Composable
private fun PanelNote(text: String) {
    val colors = CloudStreamTheme.colors
    // `.pmeta`, 20/24px mono.
    val style = CloudStreamTheme.typography.captionMono
    BasicText(
        text = text,
        style = remember(style, colors) { style.copy(color = colors.muted) },
        maxLines = SINGLE_LINE,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.height(CloudStreamDimens.StackSubtitleLineBox),
    )
}

/**
 * What a source is, in one line: `On device · 308` for the local list, `name · Synced 4m ago` for a signed-in
 * service, `Not connected` for one that is not (the owner, 2026-11-06).
 *
 * An age only where the app recorded the read ([LibrarySource.syncedAt]) and the host gave a clock. The local list's
 * count is what was read while it is shown, else what the host counted.
 */
@Composable
internal fun LibrarySource.line(shownTotal: Int?, now: (() -> Long)?): String? {
    if (local) {
        val where = stringResource(Res.string.library_on_device)
        val count = shownTotal ?: titleCount
        return if (count == null) where else joined(where, count.toString())
    }
    if (!connected) return stringResource(Res.string.library_not_connected)
    val age = syncedAt?.let { at -> now?.let { syncedAgo(it() - at) } }
    val name = account?.takeIf { it.isNotBlank() }
    return when {
        name != null && age != null -> joined(name, age)
        else -> name ?: age
    }
}

/**
 * 5b's line over a held read, one sentence per reason so the age sits where each language puts it: `Unreachable ·
 * cached 2h ago`, `Offline · cached 2h ago`. Null where the age is unknown. The keys are `I18N-15`'s (`#196`).
 */
@Composable
internal fun LibrarySource.staleLine(stale: LibraryStale, now: (() -> Long)?): String? {
    val age = syncedAt?.let { at -> now?.let { elapsed(it() - at) } } ?: return null
    val format = when (stale) {
        LibraryStale.Unreachable -> Res.string.library_sync_unreachable
        LibraryStale.Offline -> Res.string.library_sync_offline
    }
    return UiString.Resource(format, listOf(age)).resolve()
}

/** `Synced 4m ago`. */
@Composable
private fun syncedAgo(elapsedMillis: Long): String =
    UiString.Resource(Res.string.library_synced_format, listOf(elapsed(elapsedMillis))).resolve()

/** `4m`, `2h 5m`, `1d 2h 5m`: the formats the app already counts time in. A clock behind the record reads as now. */
@Composable
internal fun elapsed(elapsedMillis: Long): String {
    val minutes = (elapsedMillis.coerceAtLeast(0L) / MILLIS_PER_MINUTE).toInt()
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        hours == 0 -> UiString.Resource(Res.string.next_episode_time_min_format, listOf(minutes))
        days == 0 -> UiString.Resource(Res.string.duration_hours_minutes_format, listOf(hours, minutes % MINUTES_PER_HOUR))
        else -> UiString.Resource(
            Res.string.next_episode_time_day_format,
            listOf(days, hours % HOURS_PER_DAY, minutes % MINUTES_PER_HOUR),
        )
    }.resolve()
}

/**
 * A card's line under its title: a tracking service's count, `12 / 28 Episodes`, where it has one, else the
 * provider's name as before.
 */
@Composable
internal fun LibraryCard.metaLine(): String? {
    val watched = episodesWatched ?: return providerName
    val total = episodesTotal
    return if (total == null) {
        UiString.Resource(
            Res.string.episode_format,
            listOf(watched, UiString.Plural(Res.plurals.episodes, watched).resolve()),
        ).resolve()
    } else {
        UiString.Resource(
            Res.string.library_episodes_progress,
            listOf(watched, total, UiString.Plural(Res.plurals.episodes, total).resolve()),
        ).resolve()
    }
}

@Composable
private fun joined(first: String, second: String): String =
    UiString.Resource(Res.string.downloads_joined, listOf(first, second)).resolve()

private const val SINGLE_LINE = 1
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
