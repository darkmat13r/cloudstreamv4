package com.lagradost.cloudstream4.feature.detail.desktop

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRowsSkeleton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.state.valueOrNull
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.label
import com.lagradost.cloudstream4.feature.detail.toLinksUiState
import org.jetbrains.compose.resources.stringResource

/** Which panel is open. One at a time, the sources first: they are what a press of Play is waiting on. */
private sealed interface DetailPanel {
    data class Sources(val links: UiState<List<StreamLink>>, val arriving: Boolean) : DetailPanel

    data class WatchStatus(val current: DetailWatchStatus?) : DetailPanel
}

/** The panel last shown, so it has something to draw while it slides out. Read and written by one composition. */
private class PanelLatch {
    var shown: DetailPanel? = null
}

/**
 * E3's sources and the watch list's panel: from the right, over a dimmed page, leaving the same way. A click on the
 * dimmed page closes the one that is open, and so do its button and Escape, which the host routes.
 */
@Composable
internal fun DetailPanelsDesktop(state: DetailState, onAction: (DetailAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val links = state.links?.toLinksUiState(onAction)
    val open: DetailPanel? = when {
        links != null -> DetailPanel.Sources(links, state.linksArriving)
        state.watchStatusPicking -> DetailPanel.WatchStatus(state.detail?.watchStatus)
        else -> null
    }
    val latch = remember { PanelLatch() }
    if (open != null) latch.shown = open
    val dismiss = { panel: DetailPanel ->
        onAction(if (panel is DetailPanel.Sources) DetailAction.LinksDismissed else DetailAction.PanelDismissed)
    }
    AnimatedVisibility(visible = open != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = DetailScreenDesktopDefaults.ScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { open?.let(dismiss) },
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
            Column(
                Modifier
                    .width(DesktopDimens.SidePanelWidth)
                    .fillMaxHeight()
                    .background(colors.surface)
                    .border(CloudStreamDimens.Hairline, colors.line)
                    .padding(DesktopDimens.RowPadding),
            ) {
                when (shown) {
                    is DetailPanel.Sources -> SourcesPanel(shown, { dismiss(shown) }, onAction)
                    is DetailPanel.WatchStatus -> WatchStatusPanel(shown.current, { dismiss(shown) }, onAction)
                }
            }
        }
    }
}

/** A panel's title and its one way out, a count beside the title where there is one, and its line under it. */
@Composable
private fun PanelHeader(title: String, count: Int?, button: String, onButton: () -> Unit, line: String?) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = DesktopDimens.RowPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (count != null) {
                Spacer(Modifier.width(DesktopDimens.RelatedGap))
                BasicText(
                    text = count.toString(),
                    style = CloudStreamTheme.typography.captionMono.copy(color = colors.muted),
                    maxLines = 1,
                )
            }
        }
        DesktopButton(label = button, onClick = onButton)
    }
    // Always this line's height, said or not, so the words changing when the search ends move no row (`BUG-19`).
    BasicText(
        text = line.orEmpty(),
        style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = DesktopDimens.RowPadding, bottom = DesktopDimens.GroupGap)
            .widthIn(max = DetailScreenDesktopDefaults.ArrivingLabelMaxWidth),
    )
}

/**
 * E3: the sources, as they arrive. The list can grow while someone is reading it, so a new link only ever joins the
 * end, the words saying more are on the way take no height of their own, and nothing asks for focus. The row under the
 * pointer stays the row under the pointer. Cancel stops looking and closes it, as the board names it.
 */
@Composable
private fun ColumnScope.SourcesPanel(
    panel: DetailPanel.Sources,
    dismiss: () -> Unit,
    onAction: (DetailAction) -> Unit,
) {
    PanelHeader(
        title = stringResource(Res.string.pick_source),
        count = panel.links.valueOrNull?.size,
        button = stringResource(Res.string.cancel),
        onButton = dismiss,
        line = if (panel.arriving) stringResource(Res.string.loading) else null,
    )
    UiStateHost(
        state = panel.links,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        skeleton = { DesktopRowsSkeleton(it) },
    ) { list ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(list.size, key = { "$it:${list[it].url}" }) { index ->
                val link = list[index]
                DesktopRow(
                    title = link.label,
                    subtitle = link.qualityLabel,
                    onClick = { onAction(DetailAction.LinkActivated(link)) },
                )
            }
        }
    }
}

/** The watch lists, the title's own checked and said as selected. Choosing one commits and closes (`DEC-17`). */
@Composable
private fun ColumnScope.WatchStatusPanel(
    current: DetailWatchStatus?,
    dismiss: () -> Unit,
    onAction: (DetailAction) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    PanelHeader(
        title = stringResource(Res.string.action_add_to_bookmarks),
        count = null,
        button = stringResource(Res.string.setup_done),
        onButton = dismiss,
        line = null,
    )
    Column(Modifier.weight(1f)) {
        DetailWatchStatus.entries.forEach { status ->
            val chosen = status == current
            Box(contentAlignment = Alignment.CenterEnd) {
                DesktopRow(
                    title = status.label(),
                    onClick = { onAction(DetailAction.WatchStatusPicked(status)) },
                    modifier = Modifier
                        .semantics { selected = chosen }
                        .then(if (chosen) Modifier.background(colors.raised, shape) else Modifier),
                )
                if (chosen) {
                    CloudStreamIcon(
                        CloudStreamIcons.Done,
                        null,
                        size = CloudStreamIconSize.Small,
                        modifier = Modifier.padding(end = DesktopDimens.RowPadding),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(DesktopDimens.GroupGap))
}
