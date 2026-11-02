package com.lagradost.cloudstream4.feature.home.desktop

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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.state.failureNotice
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.next
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeMore
import com.lagradost.cloudstream4.feature.home.HomeRow
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeState
import com.lagradost.cloudstream4.feature.home.toUiState
import org.jetbrains.compose.resources.stringResource

/**
 * A source's main page, for a resizable window driven by a pointer. `docs/plans/desktop-design.md`.
 *
 * Rows are sections stacked down the window and not sideways rails, because a mouse wheel scrolls one
 * way. Each starts as one line of however many cards fit, so every row is in reach without scrolling
 * past the one above. Its `Next` stays in the row's header, so pressing it again does not mean chasing it
 * down the page. The toolbar stays through every state, because choosing another source is the way out
 * of one that hangs.
 *
 * @param artwork draws a title's poster. Image loading is the host's.
 */
@Composable
fun HomeScreenDesktop(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (HomeItem) -> Unit = {},
) {
    val status = state.status.toUiState(state.selectedOption?.name, onAction)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, onAction)
            status.failureNotice?.let { notice ->
                Box(Modifier.padding(horizontal = DesktopDimens.WindowMargin)) { FailureBanner(notice = notice) }
            }
            val prompts = Modifier.fillMaxSize()
            when (val content = status) {
                // Never takes focus, as in search: a window opening on a failure has not asked for it.
                is UiState.Empty -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                is UiState.Offline -> StateView(content.prompt, prompts, requestInitialFocus = false)
                is UiState.Error -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                else -> UiStateHost(
                    state = content,
                    modifier = Modifier.fillMaxSize(),
                    skeleton = { Skeleton() },
                ) { rows -> Rows(rows, onAction, artwork) }
            }
        }
        SourcePanel(state, onAction)
    }
}

@Composable
private fun Toolbar(state: HomeState, onAction: (HomeAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesktopDimens.WindowMargin, vertical = DesktopDimens.GroupGap),
    ) {
        val selected = state.selectedOption ?: return@Row
        BasicText(
            text = stringResource(Res.string.home_source),
            style = typography.label.copy(color = colors.muted),
            maxLines = 1,
        )
        DesktopButton(label = selected.name, onClick = { onAction(HomeAction.PickerOpened) })
    }
}

/** One title row after another, each as a header and however many lines of cards it has open. */
@Composable
private fun Rows(
    rows: List<HomeRow>,
    onAction: (HomeAction) -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - DesktopDimens.WindowMargin * 2
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesktopDimens.WindowMargin,
                end = DesktopDimens.WindowMargin,
                bottom = DesktopDimens.SectionGap,
            ),
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        ) {
            rows.forEachIndexed { index, row ->
                val columns = HomeScreenDesktopDefaults.columns(width, row.landscape)
                val shown = row.items.take(row.lines * columns)
                item(key = row.key) {
                    RowHeader(
                        row = row,
                        hasHidden = shown.size < row.items.size,
                        topGap = if (index == 0) 0.dp else DesktopDimens.SectionGap,
                        onMore = { onAction(HomeAction.RowMoreRequested(row.key, columns)) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = DesktopMotion.spec(),
                            fadeOutSpec = null,
                        ),
                    )
                }
                val lines = shown.chunked(columns)
                items(lines.size, key = { row.key to it }) { line ->
                    CardLine(
                        items = lines[line],
                        cardWidth = HomeScreenDesktopDefaults.cardWidth(width, columns),
                        landscape = row.landscape,
                        onAction = onAction,
                        artwork = artwork,
                        modifier = Modifier.animateItem(
                            fadeInSpec = DesktopMotion.spec(),
                            placementSpec = DesktopMotion.spec(),
                            fadeOutSpec = null,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowHeader(
    row: HomeRow,
    hasHidden: Boolean,
    topGap: Dp,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        modifier = modifier.fillMaxWidth().padding(top = topGap),
    ) {
        BasicText(
            text = row.title,
            style = typography.railTitle.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (val more = row.more) {
            HomeMore.Loading -> BasicText(
                text = stringResource(Res.string.loading),
                style = typography.caption.copy(color = colors.muted),
                maxLines = 1,
            )

            is HomeMore.Failed -> {
                BasicText(
                    text = "${stringResource(Res.string.source_error)} · ${more.detail}",
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = HomeScreenDesktopDefaults.RowFailureMaxWidth),
                )
                DesktopButton(label = stringResource(Res.string.search_state_retry), onClick = onMore)
            }

            HomeMore.Idle -> if (hasHidden || row.hasMore) {
                DesktopButton(label = stringResource(Res.string.next), onClick = onMore)
            }
        }
    }
}

@Composable
private fun CardLine(
    items: List<HomeItem>,
    cardWidth: Dp,
    landscape: Boolean,
    onAction: (HomeAction) -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap), modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            TitleCard(
                item = item,
                landscape = landscape,
                onClick = { onAction(HomeAction.TitleActivated(item.id)) },
                artwork = artwork,
                modifier = Modifier.width(cardWidth),
            )
        }
    }
}

@Composable
private fun TitleCard(
    item: HomeItem,
    landscape: Boolean,
    onClick: () -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.large
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier.clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        // Only the art moves. Scaling the words too would make them shimmer on every hover.
        Box(
            modifier = Modifier
                .desktopFeedback(source)
                .fillMaxWidth()
                .aspectRatio(HomeScreenDesktopDefaults.ratio(landscape))
                .tvFocusRing(focused = focused, shape = shape)
                .clip(shape)
                .background(colors.surface),
        ) {
            artwork(item)
        }
        BasicText(
            text = item.title,
            style = CloudStreamTheme.typography.label.copy(color = colors.ink),
            maxLines = HomeScreenDesktopDefaults.TitleLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The shape of what lands, so rows replacing it do not move anything. */
@Composable
private fun Skeleton() {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = maxWidth - DesktopDimens.WindowMargin * 2
        val columns = HomeScreenDesktopDefaults.columns(width, landscape = false)
        val cardWidth = HomeScreenDesktopDefaults.cardWidth(width, columns)
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .padding(horizontal = DesktopDimens.WindowMargin),
        ) {
            repeat(HomeScreenDesktopDefaults.SkeletonRows) {
                Box(
                    Modifier
                        .width(HomeScreenDesktopDefaults.PortraitCardMinWidth)
                        .height(DesktopDimens.ButtonHeight)
                        .clip(shape)
                        .background(colors.surface),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
                    repeat(columns) {
                        Box(
                            Modifier
                                .width(cardWidth)
                                .aspectRatio(HomeScreenDesktopDefaults.ratio(landscape = false))
                                .clip(shape)
                                .background(colors.surface),
                        )
                    }
                }
            }
        }
    }
}

/** A panel from the right over a dimmed page, leaving the same way, as the detail page's source list. */
@Composable
private fun SourcePanel(state: HomeState, onAction: (HomeAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val dismiss = { onAction(HomeAction.PickerDismissed) }
    AnimatedVisibility(visible = state.pickerOpen, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = HomeScreenDesktopDefaults.ScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                ),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        val spec: FiniteAnimationSpec<IntOffset> = DesktopMotion.spec()
        AnimatedVisibility(
            visible = state.pickerOpen,
            enter = slideInHorizontally(spec) { it },
            exit = slideOutHorizontally(spec) { it },
        ) {
            Column(
                Modifier
                    .width(DesktopDimens.SidePanelWidth)
                    .fillMaxHeight()
                    .background(colors.surface)
                    .border(CloudStreamDimens.Hairline, colors.line)
                    .padding(DesktopDimens.RowPadding),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = DesktopDimens.RowPadding, bottom = DesktopDimens.GroupGap),
                ) {
                    BasicText(
                        text = stringResource(Res.string.pick_source),
                        style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                        modifier = Modifier.weight(1f),
                    )
                    DesktopButton(label = stringResource(Res.string.go_back), onClick = dismiss)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sources.size, key = { state.sources[it].id.value }) { index ->
                        val option = state.sources[index]
                        SourceRow(option, selected = option.id == state.selected, onAction)
                    }
                }
            }
        }
    }
}

/** A standing fill, the row's hover colour, says which is chosen. There is no glyph to say it with yet. */
@Composable
private fun SourceRow(option: HomeSourceOption, selected: Boolean, onAction: (HomeAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    DesktopRow(
        title = option.name,
        subtitle = option.language?.takeIf { it.isNotBlank() },
        onClick = { onAction(HomeAction.SourceSelected(option.id)) },
        modifier = if (selected) Modifier.background(colors.raised, shape) else Modifier,
    )
}

object HomeScreenDesktopDefaults {
    val PortraitCardMinWidth: Dp = 160.dp
    /** Wider, because a 16:9 card at the portrait width is too small to read. */
    val LandscapeCardMinWidth: Dp = 280.dp
    const val PortraitRatio = 2f / 3f
    const val LandscapeRatio = 16f / 9f
    const val TitleLines = 2
    const val SkeletonRows = 3
    /** A failed page's reason stops here, so a long one cannot take the row's title with it. */
    val RowFailureMaxWidth: Dp = 400.dp
    const val ScrimAlpha = 0.6f

    /**
     * The same width for every card in a row. Weights would share out the odd pixels, and a 2:3 card one
     * pixel wider is a line of titles that does not sit level.
     */
    fun cardWidth(width: Dp, columns: Int): Dp =
        ((width - DesktopDimens.GroupGap * (columns - 1)) / columns).coerceAtLeast(0.dp)

    fun ratio(landscape: Boolean): Float = if (landscape) LandscapeRatio else PortraitRatio

    /** How many cards fit in [width], at least one, the same way an adaptive grid decides. */
    fun columns(width: Dp, landscape: Boolean): Int {
        val minimum = if (landscape) LandscapeCardMinWidth else PortraitCardMinWidth
        return ((width + DesktopDimens.GroupGap) / (minimum + DesktopDimens.GroupGap)).toInt().coerceAtLeast(1)
    }
}
