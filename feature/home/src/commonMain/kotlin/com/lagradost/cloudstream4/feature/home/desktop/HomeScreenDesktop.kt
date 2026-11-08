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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.state.failureNotice
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.continue_watching
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_offline_pill
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_hours_minutes_format
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_short
import com.lagradost.cloudstream4.core.strings.generated.resources.error_bookmarks_text
import com.lagradost.cloudstream4.core.strings.generated.resources.home_bookmarks_no_status_body
import com.lagradost.cloudstream4.core.strings.generated.resources.home_bookmarks_no_status_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.home_choose_source
import com.lagradost.cloudstream4.core.strings.generated.resources.home_more_info
import com.lagradost.cloudstream4.core.strings.generated.resources.home_none_body
import com.lagradost.cloudstream4.core.strings.generated.resources.home_none_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.home_random
import com.lagradost.cloudstream4.core.strings.generated.resources.home_reload_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source_none_line
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source_picker_body
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source_picker_title
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source_sections
import com.lagradost.cloudstream4.core.strings.generated.resources.load_more
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_day_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_min_format
import com.lagradost.cloudstream4.core.strings.generated.resources.none
import com.lagradost.cloudstream4.core.strings.generated.resources.reload_provider
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.strings.generated.resources.type_completed
import com.lagradost.cloudstream4.core.strings.generated.resources.type_dropped
import com.lagradost.cloudstream4.core.strings.generated.resources.type_on_hold
import com.lagradost.cloudstream4.core.strings.generated.resources.type_plan_to_watch
import com.lagradost.cloudstream4.core.strings.generated.resources.type_watching
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeBookmarks
import com.lagradost.cloudstream4.feature.home.HomeFeatured
import com.lagradost.cloudstream4.feature.home.HomeHero
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeMore
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeRow
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeState
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import com.lagradost.cloudstream4.feature.home.keptRows
import com.lagradost.cloudstream4.feature.home.rows
import com.lagradost.cloudstream4.feature.home.shown
import com.lagradost.cloudstream4.feature.home.toUiState
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
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
 * What the device keeps (`KMP-55`, `DS-21` H1): the hero as a card in the page, Continue watching with its resume rule in
 * `live`, and Bookmarks with its status chips, above the source's rows. They are local, so they stay through every state
 * of the source's, and the source's state draws under them. With none of them there is only the source's, as before.
 *
 * @param artwork draws a title's poster. Image loading is the host's.
 * @param picture draws the art at an address: a Continue watching poster or the hero's backdrop. Image loading is the
 *   host's, and an address it will not load draws nothing.
 * @param now the time in epoch milliseconds, for how old H4's kept rows are. A test passes its own.
 */
@Composable
fun HomeScreenDesktop(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (HomeItem) -> Unit = {},
    picture: @Composable (url: String?) -> Unit = {},
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val status = state.status.toUiState(state.selectedOption?.name, onAction)
    val local = state.hero is HomeHero.Shown || state.history.resume.isNotEmpty() || state.bookmarks.all.isNotEmpty()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, onAction)
            // H4: a failed reload keeps the rows, so it is the page with them, whatever the device keeps above.
            if (local || state.status is HomeStatus.Stale) {
                Page(state, status, onAction, artwork, picture, now)
                return@Column
            }
            status.failureNotice?.let { notice ->
                Box(Modifier.padding(horizontal = DesktopDimens.WindowMargin)) { FailureBanner(notice = notice) }
            }
            val prompts = Modifier.fillMaxSize()
            when (val content = status) {
                // Never takes focus, as in search: a window opening on a failure has not asked for it.
                is UiState.Empty -> StateView(state.desktopPrompt(content.prompt, onAction), prompts, content.detail, requestInitialFocus = false)
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
        val name = if (state.unsourced) stringResource(Res.string.none) else state.selectedOption?.name ?: return@Row
        BasicText(
            text = stringResource(Res.string.home_source),
            style = typography.label.copy(color = colors.muted),
            maxLines = 1,
        )
        DesktopButton(label = name, onClick = { onAction(HomeAction.PickerOpened) })
        // H4: offline costs no height. It sits beside the source, which is what it is about.
        if (state.status.shown is HomeStatus.Offline) OfflinePill()
        Spacer(Modifier.weight(1f))
        // 3j: named in the bar. Each has something to act on only while the source's rows are on screen, kept ones included (H4).
        if (state.status.keptRows.isNotEmpty() && !state.unsourced) {
            DesktopIconButton(
                icon = CloudStreamIcons.Retry,
                contentDescription = stringResource(Res.string.reload_provider),
                onClick = { onAction(HomeAction.ReloadRequested) },
            )
            DesktopIconButton(
                icon = CloudStreamIcons.Random,
                contentDescription = stringResource(Res.string.home_random),
                onClick = { onAction(HomeAction.RandomRequested()) },
            )
        }
    }
}

@Composable
private fun OfflinePill() {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .height(HomeScreenDesktopDefaults.PillHeight)
            .background(colors.raised, CloudStreamTheme.shapes.pill)
            .padding(horizontal = DesktopDimens.RelatedGap),
    ) {
        CloudStreamIcon(CloudStreamIcons.Offline, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        BasicText(
            text = stringResource(Res.string.downloads_offline_pill),
            style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
            maxLines = 1,
        )
    }
}

/** H5: with None chosen, the way on is to choose a source, said in the Mac's words. */
@Composable
private fun HomeState.desktopPrompt(prompt: StatePrompt, onAction: (HomeAction) -> Unit): StatePrompt =
    if (!unsourced) {
        prompt
    } else {
        StatePrompt(
            headline = stringResource(Res.string.home_none_headline),
            body = stringResource(Res.string.home_none_body),
            action = StateAction(stringResource(Res.string.home_choose_source), CloudStreamIcons.Source) {
                onAction(HomeAction.PickerOpened)
            },
        )
    }

/**
 * The page with the device's rails above the source's (H1, H5). One list, so it scrolls as one; the source's state takes
 * the place of its rows rather than the window, because what the device keeps is still worth seeing beside a failure.
 */
@Composable
private fun Page(
    state: HomeState,
    status: UiState<List<HomeRow>>,
    onAction: (HomeAction) -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
    picture: @Composable (String?) -> Unit,
    now: () -> Long,
) {
    val stale = state.status as? HomeStatus.Stale
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
            stale?.let {
                item(key = "stale") {
                    StaleBanner(state.selectedOption?.name.orEmpty(), it.loadedAt, now) { onAction(HomeAction.ReloadRequested) }
                }
            }
            (state.hero as? HomeHero.Shown)?.let { hero ->
                item(key = "hero") { HeroCard(hero.featured, width, onAction, picture) }
            }
            if (state.history.resume.isNotEmpty()) {
                item(key = "continue") {
                    ContinueRow(state.history.resume, width, topGap = state.topGap("continue"), onAction, picture)
                }
            }
            if (state.bookmarks.all.isNotEmpty()) {
                item(key = "bookmarks") { BookmarksHeader(state.bookmarks, state.topGap("bookmarks"), onAction) }
                bookmarkLines(state.bookmarks, width, onAction, artwork)
            }
            status.failureNotice?.let { notice -> item(key = "notice") { FailureBanner(notice = notice) } }
            if (stale != null) {
                // Under the banner alone, the first row takes the first row's gap, as the board draws it.
                val alone = state.hero !is HomeHero.Shown && state.history.resume.isEmpty() && state.bookmarks.all.isEmpty()
                rowItems(stale.kept, width, onAction, artwork, first = alone, kept = true)
                return@LazyColumn
            }
            when (status) {
                is UiState.Content -> rowItems(status.value, width, onAction, artwork, first = false)
                is UiState.Partial -> rowItems(status.value, width, onAction, artwork, first = false)
                UiState.Loading -> item(key = "loading") { Skeleton(Modifier.heightIn(max = HomeScreenDesktopDefaults.InlineStateHeight)) }
                is UiState.Empty -> item(key = "state") {
                    InlineState(state.desktopPrompt(status.prompt, onAction), status.detail)
                }
                is UiState.Offline -> item(key = "state") { InlineState(status.prompt, null) }
                is UiState.Error -> item(key = "state") { InlineState(status.prompt, status.detail) }
            }
        }
    }
}

private fun HomeState.topGap(section: String): Dp {
    val above = when (section) {
        "continue" -> hero is HomeHero.Shown
        else -> hero is HomeHero.Shown || history.resume.isNotEmpty()
    }
    return if (above) DesktopDimens.SectionGap else 0.dp
}

/**
 * H4: a reload failed and the rows from before stay, under this line saying so and how old they are. The age counts on
 * while the window is open, a minute at a time, as the line counts in minutes.
 */
@Composable
private fun StaleBanner(source: String, loadedAt: Long, now: () -> Long, onReload: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    val at by produceState(now(), loadedAt) {
        while (true) {
            delay(HomeScreenDesktopDefaults.AGE_TICK_MILLIS)
            value = now()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.raised, shape)
            .insetOutline(colors.line, shape)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
    ) {
        CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink)
        BasicText(
            text = stringResource(Res.string.home_reload_failed, source, homeAge(at - loadedAt).resolve()),
            style = CloudStreamTheme.typography.body.copy(color = colors.ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DesktopButton(label = stringResource(Res.string.search_state_retry), onClick = onReload, icon = CloudStreamIcons.Retry)
    }
}

/**
 * `4m`, `2h 5m`, `1d 2h 5m`, the age as `I18N-14` has `home_reload_failed` take it. A copy of the library's
 * (`LibrarySourcesTv.kt`), which is private to `:feature:library`. A clock behind the record reads as now.
 */
internal fun homeAge(elapsedMillis: Long): UiString {
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
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

/** A state where the source's rows would be, under what the device keeps (H5, 3g, 3m). */
@Composable
private fun InlineState(prompt: StatePrompt, detail: String?) {
    StateView(
        prompt,
        Modifier.fillMaxWidth().padding(top = DesktopDimens.SectionGap).height(HomeScreenDesktopDefaults.InlineStateHeight),
        detail,
        requestInitialFocus = false,
    )
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
            rowItems(rows, width, onAction, artwork, first = true)
        }
    }
}

/** Each row as a header and however many lines of cards it has open. [first] says whether nothing is above them. */
private fun LazyListScope.rowItems(
    rows: List<HomeRow>,
    width: Dp,
    onAction: (HomeAction) -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
    first: Boolean,
    kept: Boolean = false,
) {
            rows.forEachIndexed { index, row ->
                val columns = HomeScreenDesktopDefaults.columns(width, row.landscape)
                val shown = row.items.take(row.lines * columns)
                item(key = row.key) {
                    RowHeader(
                        row = row,
                        hasHidden = shown.size < row.items.size,
                        topGap = if (index == 0 && first) 0.dp else DesktopDimens.SectionGap,
                        // H4: a kept row's pages went with the reload, so it has no Load more; Try again is the way on.
                        onMore = if (kept) null else { { onAction(HomeAction.RowMoreRequested(row.key, columns)) } },
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

@Composable
private fun RowHeader(
    row: HomeRow,
    hasHidden: Boolean,
    topGap: Dp,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        // Held at the button's height, so the cards below stay put when the button becomes "Loading".
        modifier = modifier.fillMaxWidth().padding(top = topGap).heightIn(min = DesktopDimens.ButtonHeight),
    ) {
        BasicText(
            text = row.title,
            style = typography.railTitle.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onMore == null) return@Row
        when (val more = row.more) {
            HomeMore.Loading -> BasicText(
                text = stringResource(Res.string.loading),
                style = typography.caption.copy(color = colors.muted),
                maxLines = 1,
            )

            is HomeMore.Failed -> {
                BasicText(
                    text = stringResource(Res.string.downloads_joined, stringResource(Res.string.source_error), more.detail),
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = HomeScreenDesktopDefaults.RowFailureMaxWidth),
                )
                DesktopButton(label = stringResource(Res.string.search_state_retry), onClick = onMore)
            }

            HomeMore.Idle -> if (hasHidden || row.hasMore) {
                DesktopButton(label = stringResource(Res.string.load_more), onClick = onMore)
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

/** H1's hero: a card in the page, not full-bleed, since the sidebar holds the window's left edge. */
@Composable
private fun HeroCard(
    featured: HomeFeatured,
    width: Dp,
    onAction: (HomeAction) -> Unit,
    picture: @Composable (String?) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.large
    val height = HomeScreenDesktopDefaults.heroHeight(width)
    Box(Modifier.fillMaxWidth().height(height).clip(shape).background(colors.surface)) {
        Box(Modifier.fillMaxSize()) { picture(featured.backdropUrl) }
        Box(Modifier.fillMaxSize().background(HomeScreenDesktopDefaults.heroScrim(colors.bg)))
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = HomeScreenDesktopDefaults.HeroTextWidth)
                .padding(HomeScreenDesktopDefaults.HeroPadding),
        ) {
            BasicText(
                text = featured.title,
                style = (if (height < HomeScreenDesktopDefaults.HeroHeight) typography.railTitle else typography.display)
                    .copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            featured.meta()?.let { meta ->
                BasicText(text = meta, style = typography.body.copy(color = colors.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (height >= HomeScreenDesktopDefaults.HeroHeight) {
                featured.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    BasicText(
                        text = synopsis,
                        style = typography.body.copy(color = colors.ink2),
                        maxLines = HomeScreenDesktopDefaults.HeroSynopsisLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DesktopButton(
                label = stringResource(Res.string.home_more_info),
                onClick = { onAction(HomeAction.TitleActivated(featured.id)) },
                primary = true,
                icon = CloudStreamIcons.Info,
                modifier = Modifier.padding(top = DesktopDimens.RelatedGap),
            )
        }
    }
}

private fun HomeFeatured.meta(): String? =
    listOfNotNull(score, genres.take(HomeScreenDesktopDefaults.HeroGenres).joinToString(" · ").ifBlank { null }, year?.toString(), contentRating)
        .joinToString(HomeScreenDesktopDefaults.META_SEPARATOR)
        .ifBlank { null }

/** 3c's Continue watching, one line of 16:9 cards, each with how far through it is as a rule in `live`. */
@Composable
private fun ContinueRow(
    entries: List<HomeResume>,
    width: Dp,
    topGap: Dp,
    onAction: (HomeAction) -> Unit,
    picture: @Composable (String?) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val columns = HomeScreenDesktopDefaults.columns(width, landscape = true)
    val cardWidth = HomeScreenDesktopDefaults.cardWidth(width, columns)
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        modifier = Modifier.fillMaxWidth().padding(top = topGap),
    ) {
        Box(Modifier.heightIn(min = DesktopDimens.ButtonHeight), contentAlignment = Alignment.CenterStart) {
            BasicText(
                text = stringResource(Res.string.continue_watching),
                style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
            entries.take(columns).forEach { entry ->
                ResumeCard(entry, Modifier.width(cardWidth), { onAction(HomeAction.TitleActivated(entry.id)) }, picture)
            }
        }
    }
}

@Composable
private fun ResumeCard(entry: HomeResume, modifier: Modifier, onClick: () -> Unit, picture: @Composable (String?) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.large
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier.clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .desktopFeedback(source)
                .fillMaxWidth()
                .aspectRatio(HomeScreenDesktopDefaults.LandscapeRatio)
                .tvFocusRing(focused = focused, shape = shape)
                .clip(shape)
                .background(colors.surface),
        ) {
            picture(entry.posterUrl)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(HomeScreenDesktopDefaults.ResumeRule)
                    .background(colors.ink.copy(alpha = HomeScreenDesktopDefaults.RuleTrackAlpha)),
            ) {
                Box(Modifier.fillMaxWidth(entry.progress.coerceIn(0f, 1f)).fillMaxHeight().background(colors.live))
            }
        }
        Column {
            BasicText(text = entry.title, style = typography.label.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.line()?.let { line ->
                BasicText(text = line, style = typography.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeResume.line(): String? {
    val season = season?.let { "${stringResource(Res.string.season_short)}$it" }
    val episode = episode?.let { "${stringResource(Res.string.episode_short)}$it" }
    val part = listOfNotNull(season, episode).joinToString(" ").ifEmpty { null }
    return listOfNotNull(part, sourceName).joinToString(HomeScreenDesktopDefaults.META_SEPARATOR).ifEmpty { null }
}

/** 3e and 3f: the rail's name with a chip per status that holds a title, the chosen one outlined. */
@Composable
private fun BookmarksHeader(bookmarks: HomeBookmarks, topGap: Dp, onAction: (HomeAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        modifier = Modifier.fillMaxWidth().padding(top = topGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.heightIn(min = DesktopDimens.ButtonHeight),
        ) {
            BasicText(
                text = stringResource(Res.string.error_bookmarks_text),
                style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                maxLines = 1,
                modifier = Modifier.padding(end = DesktopDimens.RelatedGap),
            )
            bookmarks.statuses.forEach { status ->
                StatusChip(
                    label = stringResource(status.words),
                    count = bookmarks.all.count { it.status == status },
                    chosen = status == bookmarks.applied,
                    onClick = { onAction(HomeAction.BookmarkFilterPressed(status)) },
                )
            }
        }
        if (bookmarks.applied == null) {
            Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
                BasicText(
                    text = stringResource(Res.string.home_bookmarks_no_status_headline),
                    style = CloudStreamTheme.typography.label.copy(color = colors.ink),
                )
                BasicText(
                    text = stringResource(Res.string.home_bookmarks_no_status_body),
                    style = CloudStreamTheme.typography.body.copy(color = colors.muted),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, count: Int, chosen: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.pill
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .height(HomeScreenDesktopDefaults.ChipHeight)
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, if (chosen) colors.muted else colors.line, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        BasicText(text = label, style = CloudStreamTheme.typography.caption.copy(color = colors.ink), maxLines = 1)
        BasicText(text = count.toString(), style = CloudStreamTheme.typography.captionMono.copy(color = colors.muted), maxLines = 1)
    }
}

private fun LazyListScope.bookmarkLines(
    bookmarks: HomeBookmarks,
    width: Dp,
    onAction: (HomeAction) -> Unit,
    artwork: @Composable (HomeItem) -> Unit,
) {
    val columns = HomeScreenDesktopDefaults.columns(width, landscape = false)
    // One line, as a source's row starts: the rest are one press away on the library's page.
    val shown = bookmarks.shown.take(columns)
    if (shown.isEmpty()) return
    item(key = "bookmarks-line") {
        CardLine(shown, HomeScreenDesktopDefaults.cardWidth(width, columns), landscape = false, onAction, artwork)
    }
}

private val HomeWatchStatus.words: StringResource
    get() = when (this) {
        HomeWatchStatus.Watching -> Res.string.type_watching
        HomeWatchStatus.PlanToWatch -> Res.string.type_plan_to_watch
        HomeWatchStatus.OnHold -> Res.string.type_on_hold
        HomeWatchStatus.Dropped -> Res.string.type_dropped
        HomeWatchStatus.Completed -> Res.string.type_completed
    }

/** The shape of what lands, so rows replacing it do not move anything. */
@Composable
private fun Skeleton(modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
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
                        text = stringResource(Res.string.home_source_picker_title),
                        style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                        modifier = Modifier.weight(1f),
                    )
                    DesktopButton(label = stringResource(Res.string.setup_done), onClick = dismiss)
                }
                BasicText(
                    text = stringResource(Res.string.home_source_picker_body),
                    style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(start = DesktopDimens.RowPadding, bottom = DesktopDimens.GroupGap),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    // H3: None first, so the way to a home with no source's rows is never at the end of a long list.
                    item(key = HomeSourceId.None.value) {
                        SourceChoice(
                            title = stringResource(Res.string.none),
                            subtitle = stringResource(Res.string.home_source_none_line),
                            selected = state.unsourced,
                            onClick = { onAction(HomeAction.SourceSelected(HomeSourceId.None)) },
                        )
                    }
                    items(state.sources.size, key = { state.sources[it].id.value }) { index ->
                        val option = state.sources[index]
                        SourceRow(option, selected = option.id == state.selected, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(option: HomeSourceOption, selected: Boolean, onAction: (HomeAction) -> Unit) {
    SourceChoice(
        title = option.name,
        subtitle = UiString.Plural(Res.plurals.home_source_sections, option.sections).resolve(),
        selected = selected,
        onClick = { onAction(HomeAction.SourceSelected(option.id)) },
    )
}

/** A standing fill, the row's hover colour, and a check say which is chosen (H3). */
@Composable
private fun SourceChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    Box(contentAlignment = Alignment.CenterEnd) {
        DesktopRow(
            title = title,
            subtitle = subtitle,
            onClick = onClick,
            modifier = if (selected) Modifier.background(colors.raised, shape) else Modifier,
        )
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
    /** How often H4's age is read again: it counts in minutes. */
    const val AGE_TICK_MILLIS = 60_000L
    /** H4's offline pill. Mirrors `dimens.ChipVisualHeight`. */
    val PillHeight: Dp = 24.dp
    /** A bookmark status chip, 3f at desktop size. */
    val ChipHeight: Dp = 32.dp
    /** A state where the source's rows would be, under the device's rails. */
    val InlineStateHeight: Dp = 320.dp
    /** H1's hero at a full window. */
    val HeroHeight: Dp = 320.dp
    /** H6's hero in an 800×520 window: shorter, its title one size down, and no synopsis. */
    val HeroCompactHeight: Dp = 240.dp
    /** Below this content width the hero is the compact one. */
    val HeroCompactBelow: Dp = 720.dp
    val HeroTextWidth: Dp = 560.dp
    val HeroPadding: Dp = 32.dp
    const val HeroSynopsisLines = 2
    const val HeroGenres = 2
    /** Continue watching's progress rule. A stroke, so it sits on the card's foot. Mirrors `dimens.ProgressRule`. */
    val ResumeRule: Dp = 4.dp
    const val RuleTrackAlpha = 0.18f
    const val META_SEPARATOR = "  ·  "

    fun heroHeight(width: Dp): Dp = if (width < HeroCompactBelow) HeroCompactHeight else HeroHeight

    /** Darkest where the words are, clear by four fifths across, as H1 draws it. */
    fun heroScrim(bg: Color): Brush =
        Brush.horizontalGradient(
            0f to bg.copy(alpha = 0.88f),
            0.52f to bg.copy(alpha = 0.55f),
            0.86f to bg.copy(alpha = 0f),
        )

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
