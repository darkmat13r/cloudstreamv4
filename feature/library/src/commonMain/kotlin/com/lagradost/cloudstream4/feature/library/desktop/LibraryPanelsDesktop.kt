package com.lagradost.cloudstream4.feature.library.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_remove_from_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sort_not_kept
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sort_supported
import com.lagradost.cloudstream4.core.strings.generated.resources.library_source
import com.lagradost.cloudstream4.core.strings.generated.resources.library_source_note
import com.lagradost.cloudstream4.core.strings.generated.resources.library_title_count
import com.lagradost.cloudstream4.core.strings.generated.resources.select_library
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.strings.generated.resources.sort
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import com.lagradost.cloudstream4.feature.library.tv.label
import com.lagradost.cloudstream4.feature.library.tv.line
import org.jetbrains.compose.resources.stringResource

/** Which panel is open. One at a time: each is a choice, and two choices at once is neither. */
private sealed interface LibraryPanel {
    /** L6, about one card. */
    data class Picker(val card: LibraryCard) : LibraryPanel

    /** L4. */
    data object Sort : LibraryPanel

    /** L5. */
    data object Sources : LibraryPanel
}

/**
 * The panel last shown, so it has something to draw while it slides out. A plain holder written during composition,
 * as the TV screens latch theirs: it is read by the same composition and drives nothing else.
 */
private class PanelLatch {
    var shown: LibraryPanel? = null
}

private fun LibraryState.openPanel(): LibraryPanel? = when {
    pickingCard != null -> LibraryPanel.Picker(checkNotNull(pickingCard))
    sortOpen -> LibraryPanel.Sort
    sourcesOpen -> LibraryPanel.Sources
    else -> null
}

private fun LibraryPanel.dismissal(): LibraryAction = when (this) {
    is LibraryPanel.Picker -> LibraryAction.PickerDismissed
    LibraryPanel.Sort -> LibraryAction.SortDismissed
    LibraryPanel.Sources -> LibraryAction.SourcesDismissed
}

/**
 * L4, L5 and L6: a panel from the right over a dimmed page, leaving the same way, as home's source picker. A click on
 * the dimmed page closes it, and so does its button and Escape, which the host routes.
 */
@Composable
internal fun LibraryPanelsDesktop(state: LibraryState, now: (() -> Long)?, onAction: (LibraryAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val open = state.openPanel()
    val latch = remember { PanelLatch() }
    if (open != null) latch.shown = open
    AnimatedVisibility(visible = open != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = LibraryScreenDesktopDefaults.ScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { open?.let { onAction(it.dismissal()) } },
                ),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        val spec: FiniteAnimationSpec<IntOffset> = DesktopMotion.spec()
        AnimatedVisibility(
            visible = open != null,
            enter = slideInHorizontally(spec) { it },
            exit = slideOutHorizontally(spec) { it },
        ) {
            val shown = open ?: latch.shown ?: return@AnimatedVisibility
            val dismiss = { onAction(shown.dismissal()) }
            Column(
                Modifier
                    .width(DesktopDimens.SidePanelWidth)
                    .fillMaxHeight()
                    .background(colors.surface)
                    .border(CloudStreamDimens.Hairline, colors.line)
                    .padding(DesktopDimens.RowPadding),
            ) {
                when (shown) {
                    is LibraryPanel.Picker -> PickerPanel(state, shown.card, dismiss, onAction)
                    LibraryPanel.Sort -> SortPanel(state, dismiss, onAction)
                    LibraryPanel.Sources -> SourcesPanel(state, now, dismiss, onAction)
                }
            }
        }
    }
}

/** A panel's title, its one way out, and the line under it that says what choosing here does. */
@Composable
private fun PanelHeader(title: String, note: String?, button: String, onButton: () -> Unit) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = DesktopDimens.RowPadding),
    ) {
        BasicText(
            text = title,
            style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DesktopButton(label = button, onClick = onButton)
    }
    BasicText(
        text = note.orEmpty(),
        style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = DesktopDimens.RowPadding, bottom = DesktopDimens.GroupGap),
    )
}

/** L4: every criterion, the applied one checked. One the source cannot sort by stays, greyed, with the reason. */
@Composable
private fun ColumnScope.SortPanel(state: LibraryState, dismiss: () -> Unit, onAction: (LibraryAction) -> Unit) {
    val source = state.shownSource
    val applied = state.appliedSort
    val all = LibrarySortCriterion.entries
    PanelHeader(
        title = stringResource(Res.string.sort),
        note = source?.let {
            UiString.Resource(
                Res.string.library_sort_supported,
                listOf(it.name, all.count { criterion -> criterion in it.sorts }, all.size),
            ).resolve()
        },
        button = stringResource(Res.string.setup_done),
        onButton = dismiss,
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        all.forEach { criterion ->
            val kept = source == null || criterion in source.sorts
            // The chosen row reads its direction; the rest the way pressing them first sorts.
            val sort = if (applied.criterion == criterion) applied else LibrarySort.of(criterion)
            PanelChoice(
                title = stringResource(sort.label()),
                subtitle = if (kept) null else source?.let {
                    UiString.Resource(Res.string.library_sort_not_kept, listOf(it.name)).resolve()
                },
                selected = applied.criterion == criterion,
                onClick = if (kept) ({ onAction(LibraryAction.SortChosen(criterion)) }) else null,
            )
        }
    }
}

/** L5: every source, the one shown checked. One that is not connected says so and cannot be chosen. */
@Composable
private fun ColumnScope.SourcesPanel(
    state: LibraryState,
    now: (() -> Long)?,
    dismiss: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    PanelHeader(
        title = stringResource(Res.string.library_source),
        note = stringResource(Res.string.library_source_note),
        button = stringResource(Res.string.setup_done),
        onButton = dismiss,
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        state.sources.forEach { source ->
            val shown = source.id == state.source
            PanelChoice(
                title = source.name,
                subtitle = source.line(if (shown) state.totalTitles else null, now),
                selected = shown,
                onClick = if (source.connected) ({ onAction(LibraryAction.SourceChosen(source.id)) }) else null,
            )
        }
    }
}

/** L6: every list with its size, the card's own checked, then Remove below a rule. */
@Composable
private fun ColumnScope.PickerPanel(
    state: LibraryState,
    card: LibraryCard,
    dismiss: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    PanelHeader(
        title = card.title,
        note = stringResource(Res.string.select_library),
        button = stringResource(Res.string.go_back),
        onButton = dismiss,
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        LibraryBucket.entries.forEach { bucket ->
            val count = if (state.totalTitles == null) null else state.counts[bucket] ?: 0
            PanelChoice(
                title = LibraryTab.Bucket(bucket).label(),
                subtitle = count?.let { UiString.Plural(Res.plurals.library_title_count, it).resolve() },
                selected = bucket == card.bucket,
                onClick = { onAction(LibraryAction.BucketChosen(card.id, bucket)) },
            )
        }
        Box(
            Modifier
                .padding(vertical = DesktopDimens.RelatedGap)
                .fillMaxWidth()
                .height(CloudStreamDimens.Hairline)
                .background(colors.line),
        )
        DesktopRow(
            title = stringResource(Res.string.action_remove_from_bookmarks),
            onClick = { onAction(LibraryAction.RemoveRequested(card.id)) },
        )
    }
}

/**
 * A row of a panel. Chosen is a standing fill, the row's hover colour, and a check (home's H3). Without [onClick] it
 * is greyed and takes no press: it stays in the list so the reason under it can be read (DS-22's L4).
 */
@Composable
private fun PanelChoice(title: String, subtitle: String?, selected: Boolean, onClick: (() -> Unit)?) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    Box(contentAlignment = Alignment.CenterEnd) {
        if (onClick != null) {
            DesktopRow(
                title = title,
                subtitle = subtitle,
                onClick = onClick,
                modifier = if (selected) Modifier.background(colors.raised, shape) else Modifier,
            )
        } else {
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DesktopDimens.RowMinHeight)
                    .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
            ) {
                BasicText(
                    text = title,
                    style = CloudStreamTheme.typography.label.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    BasicText(
                        text = subtitle,
                        style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected) {
            CloudStreamIcon(
                CloudStreamIcons.Done,
                null,
                size = CloudStreamIconSize.Small,
                modifier = Modifier.padding(end = DesktopDimens.RowPadding),
            )
        }
    }
}
