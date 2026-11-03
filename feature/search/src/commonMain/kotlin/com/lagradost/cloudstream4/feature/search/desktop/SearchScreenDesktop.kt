package com.lagradost.cloudstream4.feature.search.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.state.failureNotice
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_clear_all
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_field_placeholder
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_label
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchState
import com.lagradost.cloudstream4.feature.search.resultCountText
import com.lagradost.cloudstream4.feature.search.sourcesCountText
import com.lagradost.cloudstream4.feature.search.toUiState
import org.jetbrains.compose.resources.stringResource

/**
 * Search for a resizable window driven by a pointer and a keyboard. `docs/plans/desktop-design.md`.
 *
 * Differs from the TV screen on purpose: typing is the point, so the field holds focus from the start
 * and an empty field has nothing to press. Results are a grid and not rails, because a mouse wheel
 * scrolls one way. Titles stay visible, because there is no D-pad focus to reveal them.
 *
 * @param artwork draws a result's poster. Image loading is the host's.
 */
@Composable
fun SearchScreenDesktop(
    state: SearchState,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (SearchResultItem) -> Unit = {},
) {
    val status = state.status.toUiState(onAction)
    Column(modifier.fillMaxSize()) {
        Toolbar(state, onAction)
        status.failureNotice?.let { notice ->
            Box(Modifier.padding(horizontal = DesktopDimens.WindowMargin)) { FailureBanner(notice = notice) }
        }
        val prompts = Modifier.fillMaxSize()
        when (val content = status) {
            is UiState.Empty -> {
                if (state.query.isBlank()) {
                    IdleHint(content.prompt)
                } else {
                    StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                }
            }
            // Never takes focus. A search that fails or finds nothing arrives while the user may still
            // be typing, and focus moving to its button would swallow the next keystrokes.
            is UiState.Offline -> StateView(content.prompt, prompts, requestInitialFocus = false)
            is UiState.Error -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
            else -> UiStateHost(
                state = content,
                modifier = Modifier.fillMaxSize(),
                skeleton = { SkeletonGrid() },
            ) { loaded ->
                when (loaded) {
                    is SearchContent.Recent -> Recent(loaded.entries, onAction)
                    is SearchContent.Results -> Results(loaded.groups, onAction, artwork)
                }
            }
        }
    }
}

@Composable
private fun Toolbar(state: SearchState, onAction: (SearchAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        modifier = Modifier.fillMaxWidth().padding(DesktopDimens.WindowMargin),
    ) {
        QueryField(state.query, stringResource(Res.string.search_tv_field_placeholder), onAction, Modifier.weight(1f))
        // A readout and deliberately not a chip: the source picker is not built, and something shaped
        // like a control has to behave like one.
        BasicText(
            text = "${stringResource(Res.string.search_tv_sources_label)} ${sourcesCountText(state.sources)}",
            style = typography.caption.copy(color = colors.muted),
            maxLines = 1,
        )
    }
}

@Composable
private fun QueryField(
    query: String,
    placeholder: String,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val shape = CloudStreamTheme.shapes.medium
    // A brighter hairline, not the white ring: the field holds focus most of the time and its caret
    // already says so. The ring stays for what Tab lands on, where nothing else would show it.
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .height(DesktopDimens.ToolbarFieldHeight)
            .background(colors.surface, shape)
            .border(CloudStreamDimens.Hairline, if (focused) colors.muted else colors.line, shape)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        BasicTextField(
            value = query,
            onValueChange = { onAction(SearchAction.QueryChanged(it)) },
            textStyle = typography.body.copy(color = colors.ink),
            singleLine = true,
            cursorBrush = SolidColor(colors.ink),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onAction(SearchAction.Submit) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    // Escape empties a field with something in it, as it does in every Mac search field.
                    val clears = event.key == Key.Escape && event.type == KeyEventType.KeyDown && query.isNotEmpty()
                    if (clears) onAction(SearchAction.Cleared)
                    clears
                },
        )
        if (query.isEmpty()) {
            BasicText(text = placeholder, style = typography.body.copy(color = colors.muted), maxLines = 1)
        }
    }
}

/**
 * What an empty field says. Words only: the action is typing, and the field already has the caret. The
 * prompt's own button is `Browse home`, and desktop has no home to go to.
 */
@Composable
private fun IdleHint(prompt: StatePrompt) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(DesktopDimens.WindowMargin),
    ) {
        BasicText(
            text = prompt.headline,
            style = typography.railTitle.copy(color = colors.ink, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(DesktopDimens.RelatedGap))
        BasicText(
            text = prompt.body,
            style = typography.body.copy(color = colors.muted, textAlign = TextAlign.Center),
        )
        // Optical centre: the block sits above the middle of the region, as a toolbar is above it.
        Spacer(Modifier.height(SearchScreenDesktopDefaults.IdleOpticalLift))
    }
}

@Composable
private fun Recent(entries: List<SearchHistoryEntry>, onAction: (SearchAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    LazyColumn(
        modifier = Modifier.widthIn(max = DesktopDimens.ReadingWidth).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = DesktopDimens.WindowMargin - DesktopDimens.RowPadding),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DesktopDimens.RowPadding, bottom = DesktopDimens.RelatedGap),
            ) {
                BasicText(
                    text = stringResource(Res.string.search_tv_recent_headline),
                    style = typography.railTitle.copy(color = colors.ink),
                    modifier = Modifier.weight(1f),
                )
                DesktopButton(label = stringResource(Res.string.search_tv_clear_all), onClick = { onAction(SearchAction.RecentCleared) })
            }
        }
        items(entries.size, key = { entries[it].query }) { index ->
            val entry = entries[index]
            DesktopRow(
                title = entry.query,
                subtitle = entry.whenLabel,
                onClick = { onAction(SearchAction.RecentActivated(entry.query)) },
            )
        }
    }
}

@Composable
private fun Results(
    groups: List<SearchResultGroup>,
    onAction: (SearchAction) -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(SearchScreenDesktopDefaults.CardMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = DesktopDimens.WindowMargin,
            end = DesktopDimens.WindowMargin,
            bottom = DesktopDimens.SectionGap,
        ),
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.WindowMargin),
    ) {
        groups.forEachIndexed { index, group ->
            item(key = "header:${group.providerName}", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(group, topGap = if (index == 0) 0.dp else DesktopDimens.SectionGap)
            }
            items(group.items.size, key = { group.items[it].id.value }) { position ->
                val item = group.items[position]
                ResultCard(item, onClick = { onAction(SearchAction.ResultActivated(item.id)) }, artwork)
            }
        }
    }
}

@Composable
private fun SectionHeader(group: SearchResultGroup, topGap: Dp) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.padding(top = topGap),
    ) {
        BasicText(text = group.providerName, style = typography.railTitle.copy(color = colors.ink), maxLines = 1)
        BasicText(
            text = resultCountText(group.items.size),
            style = typography.caption.copy(color = colors.muted),
            maxLines = 1,
            modifier = Modifier.padding(bottom = SearchScreenDesktopDefaults.CountBaselineNudge),
        )
    }
}

@Composable
private fun ResultCard(
    item: SearchResultItem,
    onClick: () -> Unit,
    artwork: @Composable (SearchResultItem) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.large
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        // Only the art moves. Scaling the words too would make them shimmer on every hover.
        Box(
            modifier = Modifier
                .desktopFeedback(source)
                .fillMaxWidth()
                .aspectRatio(SearchScreenDesktopDefaults.PosterRatio)
                .tvFocusRing(focused = focused, shape = shape)
                .clip(shape)
                .background(colors.surface),
        ) {
            artwork(item)
        }
        Column {
            BasicText(
                text = item.title,
                style = typography.label.copy(color = colors.ink),
                maxLines = SearchScreenDesktopDefaults.TitleLines,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(item.typeLabel, item.qualityLabel, item.dubLabel).joinToString(" · ")
            if (meta.isNotEmpty()) {
                BasicText(
                    text = meta,
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The shape of what lands, so results replacing it do not move anything. */
@Composable
private fun SkeletonGrid() {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    LazyVerticalGrid(
        columns = GridCells.Adaptive(SearchScreenDesktopDefaults.CardMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = DesktopDimens.WindowMargin),
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.WindowMargin),
        userScrollEnabled = false,
    ) {
        items(SearchScreenDesktopDefaults.SkeletonCount) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(SearchScreenDesktopDefaults.PosterRatio)
                    .clip(shape)
                    .background(colors.surface),
            )
        }
    }
}

object SearchScreenDesktopDefaults {
    val CardMinWidth: Dp = 160.dp
    const val PosterRatio = 2f / 3f
    const val TitleLines = 2
    const val SkeletonCount = 12
    /** Sits the idle words at about two fifths of the region's height. */
    val IdleOpticalLift: Dp = 96.dp
    /** Lifts the 12sp count onto the 24sp title's baseline: their line boxes differ by 16dp. */
    val CountBaselineNudge: Dp = 4.dp
}
