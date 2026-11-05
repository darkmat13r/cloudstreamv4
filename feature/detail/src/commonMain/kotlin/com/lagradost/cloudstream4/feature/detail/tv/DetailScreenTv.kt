package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.target.IconCell
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.favorite
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.no_episodes_found
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.theme.CloudStreamColors
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailFocus
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.META_SEPARATOR
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.code
import com.lagradost.cloudstream4.feature.detail.metaRun
import com.lagradost.cloudstream4.feature.detail.resumeCaption
import com.lagradost.cloudstream4.feature.detail.toLinksUiState
import com.lagradost.cloudstream4.feature.detail.toPageUiState
import com.lagradost.cloudstream4.feature.detail.typeLabel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Artboards 7a, 7b and 7a-ii of `design/TV Detail Player.dc.html`, at px/2, and a reduced 7c.
 *
 * ## One scrolling surface, not two screens
 *
 * 7a and 7a-ii are the same page at two scroll offsets. So this is a `LazyColumn` whose first item is
 * the hero, the text block and the action row, and the hero and the chrome row scroll away as that
 * item leaves — which is what 7a-ii draws.
 *
 * **The jump is fixed, and this KDoc used to deny it.** It read "there is no fixed 292dp jump"; the
 * boards put the cast avatars at 364dp on 7a and 72dp on 7a-ii, which is a 292dp jump exactly. Those
 * are the stops [DetailFocusScroll] now scrolls to, and arriving at a stop nobody drew was `BUG-38`.
 *
 * Each section after the head block opens with its own [DetailScreenTvDefaults.SectionGap], so a rail
 * with nothing in it costs nothing: with no cast the recommendations header moves up into the board's
 * y320 slot by itself, which is what §2.8's EMPTY state asks for. "The band is never a header over
 * nothing."
 *
 * ## Where this departs from the artboards
 *
 * - **The nav rail is the shell's, and it shows here** (owner, 2026-11-05). `MainActivity.updateNavBar`
 *   names this destination alongside its visible-nav list, so `TvRail` lays the page out beside the
 *   76dp rail; [DetailScreenTvDefaults.ContentStart] is what puts the content back on the board's x100.
 *   LEFT from a leading stop goes into the rail and RIGHT comes back to the stop that yielded focus,
 *   both of which `TvRail` already owns. BACK still leaves the screen.
 * - **The episode list stays on this page**, below the two rails. The boards put episodes on their own
 *   panel (board 6a), which is a later task; removing the list without it would leave no way to pick
 *   an episode. `Season N` and `Episodes · N` therefore scroll to that list rather than opening the
 *   panel they are drawn for, and they are drawn **only for a title with exactly one season**: the
 *   board's pair is a season and that season's own count, and with no panel there is no selector to
 *   say which season a multi-season page is showing. See `TitleDetail.namedSeason`.
 * - **No cast header and no see-all cards.** `:core:strings` has no word for "Cast", "Full cast" or
 *   "See all", and `CardSize`'s constructor is `internal` to `:core:theme`, so a feature module cannot
 *   build the boards' 76x120 and 76x144 cards at all. The rails are the rows alone.
 * - **No "38 · TMDB" provenance.** Nothing in the tree records which metadata provider supplied the
 *   cast, so the related rail's count line is the count alone and the cast rail has none.
 * - **Wording the strings do not have:** `synopsis` ("Synopsis") for "Full synopsis", `recommended`
 *   ("Recommended") for "More like this", `play_movie_button`/`play_episode` for "Start over",
 *   `resume` for "Resume from 1:24:07", and `duration_format` ("136 min") for "2h 16m". A new key
 *   costs 62 translated directories (`I18N-08`), so this screen adds none.
 * - **No Download button.** 7b draws one; `DetailAction` has no download action to send.
 * - **The loading skeleton is the host's generic one.** §2.8 of the spec gives the exact shimmer
 *   geometry, but `SkeletonBlock` is `internal` to `:core:designsystem` and a feature module cannot
 *   reach it to draw title, meta and plot bars.
 * - **7c is a plain list, not rows filling in live.** There is no per-extractor progress to draw yet.
 *
 * @param onBack leaves this screen. Going back from the source list is handled here.
 * @param artwork draws an image the host has loaded — the hero, a headshot, a poster.
 */
@Composable
fun DetailScreenTv(
    state: DetailState,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted out of the lazy column: item 0 is disposed when it scrolls away, and an effect inside it
    // would run again on the way back and pull focus off wherever the user had moved it.
    var entryFocusPlaced by remember { mutableStateOf(false) }
    val links = state.links?.toLinksUiState(onAction)
    Box(modifier.fillMaxSize()) {
        if (links == null) {
            DetailPanelsTv(state, onAction) { openWatchStatus ->
                UiStateHost(
                    state = state.content.toPageUiState(onAction),
                    // The band, so the state view centres on the board's x516 rather than on the
                    // page area, and so the skeleton starts at x100 instead of the screen's edge.
                    modifier = Modifier.fillMaxSize().contentInset(),
                    glyph = { DetailStateGlyph() },
                    skeleton = { DetailSkeletonTv(it) },
                ) { detail ->
                    TitlePage(
                        detail = detail,
                        state = state,
                        onAction = onAction,
                        onBack = onBack,
                        onWatchStatusRequested = openWatchStatus,
                        artwork = artwork,
                        entryFocusPlaced = entryFocusPlaced,
                        onEntryFocusPlaced = { entryFocusPlaced = true },
                    )
                }
                // Loading and the two failures draw no chrome row of their own, and the board keeps the
                // back cell live through all three. §2.8 rests focus on it while loading; on a failure
                // `StateView` has already put focus on Retry, which is where that state wants it.
                if (state.content !is DetailLoad.Loaded) {
                    // With the ladder's own top inset: the loaded page reaches this row through
                    // `HeadBlock`'s `ChromeTop` spacer, and without it here the cell sat at y0 in all
                    // three states and overlapped whatever the state below it drew.
                    ChromeRow(
                        modifier = Modifier.padding(top = DetailScreenTvDefaults.ChromeTop),
                        detail = null,
                        onAction = onAction,
                        onBack = onBack,
                        onWatchStatusRequested = openWatchStatus,
                        takeFocus = !entryFocusPlaced && state.content is DetailLoad.Loading,
                        // Deliberately not recorded as the entry focus: the board says focus rests here
                        // and "moves once, to Resume" when the title lands.
                        onFocusTaken = {},
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ChromeRow(
                    modifier = Modifier.padding(top = DetailScreenTvDefaults.ChromeTop),
                    detail = null,
                    onAction = onAction,
                    onBack = { onAction(DetailAction.LinksDismissed) },
                    onWatchStatusRequested = {},
                    takeFocus = false,
                    onFocusTaken = {},
                )
                UiStateHost(state = links, modifier = Modifier.fillMaxSize()) { list ->
                    LinkList(list, onAction)
                }
            }
        }
    }
}

/**
 * The surface: the hero block, then the cast rail, then the recommendations rail, then the episodes.
 *
 * Each section is one lazy item so the whole page is a single scroll. The episode rows are items of
 * **this** column rather than of a nested one: a `LazyColumn` inside a `LazyColumn` has no height to
 * measure against.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitlePage(
    detail: TitleDetail,
    state: DetailState,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    onWatchStatusRequested: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    entryFocusPlaced: Boolean,
    onEntryFocusPlaced: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val returnTo = state.returnTo
    val episodesIndex = episodesHeaderIndex(detail)
    // Set once the jump has landed, and cleared when the first episode row has taken focus. Hoisted
    // here because the button that starts the jump is in item 0, which the jump itself disposes.
    var jumpedToEpisodes by remember { mutableStateOf(false) }
    // Entry focus is Resume on both boards. With nothing playable there is no Resume, and the back cell
    // takes it instead rather than leaving the screen with nothing focused at all.
    val entryOnPrimary = !entryFocusPlaced && detail.primaryData != null
    val handled = { onAction(DetailAction.ReturnHandled) }
    val density = LocalDensity.current
    val castIndex = if (detail.cast.isNotEmpty()) HEAD_ITEMS else null
    val relatedIndex = if (detail.recommendations.isNotEmpty()) {
        HEAD_ITEMS + (if (detail.cast.isNotEmpty()) ONE_ITEM else NO_ITEM)
    } else {
        null
    }
    // BUG-38: the boards' stops, in place of the pivot Compose would pick. Each band asks for its own
    // when it takes focus, because the list itself refuses to scroll for focus — see DetailFocusScroll.
    val bandInset = with(density) { CloudStreamDimens.FocusBandInset.toPx() }
    val bandSpec = remember(bandInset) { DetailBandScrollSpec(bandInset) }
    val scrollToStop: (Int) -> Unit = { index ->
        val stop = DetailFocusScroll.stopFor(index, castIndex, relatedIndex)
        scope.launch {
            listState.scrollToItem(stop.index, with(density) { stop.offsetDp.dp.roundToPx() })
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides bandSpec) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = CloudStreamDimens.FocusBandInset),
    ) {
        item(key = HEAD_KEY) {
            HeadBlock(
                modifier = Modifier.onFocusChanged { if (it.hasFocus) scrollToStop(HEAD_INDEX) },
                detail = detail,
                onAction = onAction,
                onBack = onBack,
                onWatchStatusRequested = onWatchStatusRequested,
                artwork = artwork,
                takePrimaryFocus = entryOnPrimary ||
                    (returnTo?.ready == true && returnTo.target == DetailFocus.Primary),
                onPrimaryFocusTaken = {
                    onEntryFocusPlaced()
                    if (returnTo != null) handled()
                },
                takeBackFocus = !entryFocusPlaced && detail.primaryData == null,
                onBackFocusTaken = onEntryFocusPlaced,
                onScrollToEpisodes = {
                    scope.launch {
                        // Not animated, and not optional: the jump disposes item 0, which holds the
                        // button that was just pressed, so between the two frames nothing owns focus.
                        // An instant scroll closes that gap to one frame and the row below the header
                        // then takes it — a TV screen with no focus owner is inert until some key
                        // press happens to re-acquire it.
                        listState.scrollToItem(episodesIndex)
                        jumpedToEpisodes = true
                    }
                },
            )
        }
        if (detail.cast.isNotEmpty()) {
            item(key = CAST_KEY) {
                DetailCastRowTv(
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) scrollToStop(HEAD_ITEMS) },
                    cast = detail.cast,
                    swappedRoles = state.swappedRoles,
                    onAction = onAction,
                    artwork = artwork,
                )
            }
        }
        if (detail.recommendations.isNotEmpty()) {
            item(key = RELATED_KEY) {
                DetailRelatedRowTv(
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) scrollToStop(relatedIndex ?: HEAD_INDEX)
                    },
                    recommendations = detail.recommendations,
                    providerName = detail.providerName,
                    onAction = onAction,
                    artwork = artwork,
                    takeFocus = returnTo?.ready == true && returnTo.target == DetailFocus.Recommendations,
                    onFocusTaken = handled,
                )
            }
        }
        if (detail.episodes.isNotEmpty()) {
            item(key = EPISODES_KEY) {
                Column(Modifier.contentInset()) {
                    Spacer(Modifier.height(DetailScreenTvDefaults.SectionGap))
                    RailHeader(
                        title = stringResource(Res.string.episodes),
                        count = detail.episodes.size.toString(),
                    )
                }
            }
            // The index is in the key because provider data can repeat, and a duplicate key crashes.
            items(detail.episodes.size, key = { index -> "$index:${detail.episodes[index].data}" }) { index ->
                EpisodeRow(
                    episode = detail.episodes[index],
                    index = index,
                    onAction = onAction,
                    takeFocus = index == FIRST_ROW && (
                        jumpedToEpisodes ||
                            (returnTo?.ready == true && returnTo.target == DetailFocus.Episodes)
                        ),
                    onFocusTaken = {
                        jumpedToEpisodes = false
                        if (returnTo != null) handled()
                    },
                )
            }
        }
    }
    }
}

/**
 * Item 0: the hero, the chrome row, the text block, the caption and the action row — 288dp tall, which
 * is the board's action-row bottom at y576.
 *
 * The ladder is §2.2 of the spec, gap by gap, and never an absolute y: 28 + 48 + 12 lands the title at
 * y88, and every spacer after it is the gap the board measures. The board's single 52dp from the plot
 * to the actions is split into 12 + the 24dp caption box + 16, which is what adds the caption without
 * moving anything else.
 */
@Composable
private fun HeadBlock(
    detail: TitleDetail,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    onWatchStatusRequested: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    takePrimaryFocus: Boolean,
    onPrimaryFocusTaken: () -> Unit,
    takeBackFocus: Boolean,
    onBackFocusTaken: () -> Unit,
    onScrollToEpisodes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    // The hero draws behind the column and does **not** set this item's height.
    //
    // It did, and that was a 12dp error: the hero is 300dp, the column's ladder ends at the action
    // row's bottom near 283dp, so a `Box` took the hero's 300 and the next section's `SectionGap`
    // counted from the hero's foot. The board counts it from the action row — 288 + 32 = 320 for the
    // cast header — and we were putting it at 332. Measured from the recorded references before and
    // after; see `KMP-38`.
    //
    // The hero still paints its full 300dp, which is what 7a draws: it reaches 12dp past the action
    // row and under the band below, where it has already faded to `bg`. The section after this one is
    // a later lazy item, so it draws over that tail rather than under it.
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Hero(detail.backdropUrl ?: detail.posterUrl, artwork)
            HeadColumn(
                detail = detail,
                onAction = onAction,
                onBack = onBack,
                onWatchStatusRequested = onWatchStatusRequested,
                takePrimaryFocus = takePrimaryFocus,
                onPrimaryFocusTaken = onPrimaryFocusTaken,
                takeBackFocus = takeBackFocus,
                onBackFocusTaken = onBackFocusTaken,
                onScrollToEpisodes = onScrollToEpisodes,
                colors = colors,
                typography = typography,
            )
        },
    ) { measurables, constraints ->
        val hero = measurables[HERO_CHILD].measure(constraints)
        val column = measurables[COLUMN_CHILD].measure(constraints)
        layout(constraints.maxWidth, column.height) {
            hero.place(0, 0)
            column.place(0, 0)
        }
    }
}

/** The chrome row, the text block, the caption and the action row: everything [HeadBlock] measures. */
@Composable
private fun HeadColumn(
    detail: TitleDetail,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    onWatchStatusRequested: () -> Unit,
    takePrimaryFocus: Boolean,
    onPrimaryFocusTaken: () -> Unit,
    takeBackFocus: Boolean,
    onBackFocusTaken: () -> Unit,
    onScrollToEpisodes: () -> Unit,
    colors: CloudStreamColors,
    typography: CloudStreamTypography,
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(DetailScreenTvDefaults.ChromeTop))
        ChromeRow(
            detail = detail,
            onAction = onAction,
            onBack = onBack,
            onWatchStatusRequested = onWatchStatusRequested,
            takeFocus = takeBackFocus,
            onFocusTaken = onBackFocusTaken,
        )
        Column(Modifier.contentInset()) {
            Spacer(Modifier.height(DetailScreenTvDefaults.ChromeToTitle))
            TextLine(
                text = detail.title,
                style = typography.h1,
                color = colors.ink,
                modifier = Modifier.widthIn(max = DetailScreenTvDefaults.TitleMaxWidth),
            )
            Spacer(Modifier.height(DetailScreenTvDefaults.TitleToMeta))
            TextLine(
                // The provider and the type are in the chrome row, as on the artboard.
                text = metaRun(detail),
                style = typography.label,
                color = colors.ink2,
                modifier = Modifier.widthIn(max = DetailScreenTvDefaults.TitleMaxWidth),
            )
            Spacer(Modifier.height(DetailScreenTvDefaults.MetaToPlot))
            BasicText(
                text = detail.plot.orEmpty(),
                style = remember(typography, colors) { typography.body.copy(color = colors.ink2) },
                maxLines = DetailScreenTvDefaults.PlotLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .height(DetailScreenTvDefaults.PlotHeight)
                    .widthIn(max = DetailScreenTvDefaults.PlotMaxWidth),
            )
            Spacer(Modifier.height(DetailScreenTvDefaults.PlotToCaption))
            CaptionSlot(detail)
            Spacer(Modifier.height(DetailScreenTvDefaults.CaptionToActions))
            DetailActionRowTv(
                detail = detail,
                onAction = onAction,
                takeFocus = takePrimaryFocus,
                onFocusTaken = onPrimaryFocusTaken,
                onScrollToEpisodes = onScrollToEpisodes,
            )
        }
    }
}

/**
 * The chrome row, y28 to y76: the back cell at x100, the provider-and-type label at x160, and the two
 * state cells at x824 and x884, which is the band's right edge less two 48dp cells and a 12dp gap.
 *
 * A [detail] of null is the row loading, failed, or over the source list: the back cell alone.
 *
 * The board draws the free 48dp slot at x764 for Subscribe, which is emulator-only in the tree today.
 * Nothing is drawn there, and nothing has to be: the two state cells are anchored to the band's right
 * edge, so the slot appears of its own accord.
 */
@Composable
private fun ChromeRow(
    detail: TitleDetail?,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    onWatchStatusRequested: () -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val back = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        back.requestFocusIfAttached()
        onFocusTaken()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .contentInset()
            .height(CloudStreamDimens.ChromeRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCell(onClick = onBack, modifier = Modifier.focusRequester(back)) { focused ->
            ChromeGlyph(CloudStreamIcons.Back, stringResource(Res.string.go_back), focused)
        }
        Spacer(Modifier.width(DetailScreenTvDefaults.ChromeGap))
        TextLine(
            text = listOfNotNull(detail?.providerName, detail?.typeName?.let { typeLabel(it) })
                .joinToString(META_SEPARATOR),
            style = CloudStreamTheme.typography.label,
            color = colors.muted,
            modifier = Modifier.weight(REMAINING_WIDTH),
        )
        // Drawn only where the host has the feature at all: a dead control is worse than none.
        val watchStatus = detail?.watchStatus
        if (watchStatus != null) {
            IconCell(onClick = onWatchStatusRequested) { focused ->
                ChromeGlyph(
                    icon = CloudStreamIcons.Bookmark,
                    label = stringResource(Res.string.action_add_to_bookmarks),
                    focused = focused,
                    selected = watchStatus != DetailWatchStatus.None,
                )
            }
            Spacer(Modifier.width(DetailScreenTvDefaults.ChromeGap))
        }
        val favourite = detail?.isFavourite
        if (favourite != null) {
            IconCell(onClick = { onAction(DetailAction.FavouriteToggled) }) { focused ->
                ChromeGlyph(
                    icon = CloudStreamIcons.Favorite,
                    label = stringResource(Res.string.favorite),
                    focused = focused,
                    selected = favourite,
                )
            }
        }
    }
}

/**
 * One chrome glyph: `muted` at rest, `ink` under focus, and `ink` while the state it reports is on.
 *
 * The boards draw all three cells as undifferentiated `muted` squares and say nothing about a selected
 * state, so the `ink` of a set bookmark or a favourite is a decision taken here and worth revisiting
 * with the design: without it a 48dp cell reports a two-state feature with one appearance.
 */
@Composable
private fun ChromeGlyph(
    icon: ImageVector,
    label: String,
    focused: Boolean,
    selected: Boolean = false,
) {
    val colors = CloudStreamTheme.colors
    CloudStreamIcon(
        icon = icon,
        contentDescription = label,
        size = CloudStreamIconSize.Small,
        tint = if (focused || selected) colors.ink else colors.muted,
    )
}

/**
 * y216, a fixed 24dp box holding two mono lines.
 *
 * It is two lines because `resume_time_left` is `%dm\nremaining` and a one-line box clips it, and it is
 * a **fixed** box so that gaining or losing a stored position never moves the action row. With no
 * resume the slot is empty and the board's 52dp from the plot to the actions is unchanged; with nothing
 * playable at all it carries `no_episodes_found`, which is where §2.8's EMPTY state puts it.
 */
@Composable
private fun CaptionSlot(detail: TitleDetail) {
    val colors = CloudStreamTheme.colors
    val style = CloudStreamTheme.typography.captionMono
    val caption = resumeCaption(detail.resume)
        ?: stringResource(Res.string.no_episodes_found).takeIf { detail.primaryData == null }
    Box(Modifier.height(DetailScreenTvDefaults.CaptionHeight)) {
        if (caption != null) {
            BasicText(
                text = caption,
                style = remember(style, colors) { style.copy(color = colors.muted) },
                maxLines = DetailScreenTvDefaults.CaptionLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The hero: 960x300, the artwork the host loaded, and the two gradients of §2.6. */
@Composable
private fun Hero(imageUrl: String?, artwork: @Composable (String?) -> Unit) {
    val black = CloudStreamTheme.colors.bg
    val sideScrim = remember(black) {
        Brush.horizontalGradient(
            0f to black.copy(alpha = DetailScreenTvDefaults.ScrimStartAlpha),
            DetailScreenTvDefaults.ScrimMidStop to black.copy(alpha = DetailScreenTvDefaults.ScrimMidAlpha),
            DetailScreenTvDefaults.ScrimEndStop to Color.Transparent,
        )
    }
    val floorScrim = remember(black) {
        Brush.verticalGradient(
            DetailScreenTvDefaults.FloorScrimStop to Color.Transparent,
            1f to black,
        )
    }
    Box(Modifier.fillMaxWidth().height(DetailScreenTvDefaults.HeroHeight)) {
        artwork(imageUrl)
        Box(Modifier.fillMaxSize().background(sideScrim))
        Box(Modifier.fillMaxSize().background(floorScrim))
    }
}

/**
 * One episode.
 *
 * Every row keeps the same modifier chain, [FocusRequester] included. Attaching one only to the row
 * being returned to rebuilds its focus node when the flag goes false and drops the focus it has just
 * taken; on the real device focus went to the rail (`KMP-34`, measured on `Television_720p`).
 */
@Composable
private fun EpisodeRow(
    episode: DetailEpisode,
    index: Int,
    onAction: (DetailAction) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // After a frame: a row composed in this one has not been placed.
        withFrameNanos { }
        focusRequester.requestFocusIfAttached()
        onFocusTaken()
    }
    val code = episode.code(index)
    ListRow(
        state = ListRowState(
            title = episode.name ?: code,
            subtitle = code.takeIf { episode.name != null },
        ),
        onClick = { onAction(DetailAction.PlaybackRequested(episode.data, fromStart = false)) },
        modifier = Modifier
            .fillMaxWidth()
            .contentInset()
            .padding(top = CloudStreamDimens.MinFocusGap)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun LinkList(links: List<StreamLink>, onAction: (DetailAction) -> Unit) {
    val first = remember { FocusRequester() }
    // Above the list, because a lazy row is disposed when it scrolls away: an effect inside row 0 would
    // run again on scrolling back and pull focus away from wherever the user had moved it.
    var focusPlaced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().contentInset()) {
        Spacer(Modifier.height(DetailScreenTvDefaults.ChromeToTitle))
        RailHeader(title = stringResource(Res.string.pick_source), count = links.size.toString())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            items(links.size, key = { index -> "$index:${links[index].url}" }) { index ->
                val link = links[index]
                if (index == 0 && !focusPlaced) {
                    LaunchedEffect(first) {
                        first.requestFocus()
                        focusPlaced = true
                    }
                }
                ListRow(
                    state = ListRowState(title = link.label, subtitle = link.qualityLabel),
                    onClick = { onAction(DetailAction.LinkActivated(link)) },
                    modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                )
            }
        }
    }
}

@Composable
internal fun TextLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = remember(style, color) { style.copy(color = color) },
        maxLines = SINGLE_LINE,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Which lazy item the episode header is, so `Season N` and `Episodes · N` can scroll to it.
 *
 * Derived from the same conditions the column is built from rather than tracked, so the two cannot
 * drift: item 0 is always the head block, and each rail that has content is one item after it.
 */
private fun episodesHeaderIndex(detail: TitleDetail): Int =
    HEAD_ITEMS +
        (if (detail.cast.isNotEmpty()) ONE_ITEM else NO_ITEM) +
        (if (detail.recommendations.isNotEmpty()) ONE_ITEM else NO_ITEM)

/**
 * The marker above a failure's headline: 7a's ERROR glyph, 96x96 on the artboard.
 *
 * Hollow and neutral, which `DEC-17` asks for and which `LibraryEmptyGlyph` is the precedent for: the
 * `raised` tile with a `line` hairline, and the meaning in a `muted` glyph rather than in a colour.
 * `StateView` measures it into its own `GlyphSize` box, so this fills whatever it is given.
 *
 * One glyph for both states this page can reach. `Empty` and `Failed` are drawn identically on
 * purpose — `toPageUiState`'s own note says a source either returns a title or fails, and the page
 * has nothing truer to say about which.
 */
@Composable
private fun DetailStateGlyph() {
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
            icon = CloudStreamIcons.Warning,
            contentDescription = null,
            size = CloudStreamIconSize.Large,
            tint = colors.muted,
        )
    }
}

/** The band, x100 to x932. A lazy row takes this as content padding instead; see the rails. */
internal fun Modifier.contentInset(): Modifier = padding(
    start = DetailScreenTvDefaults.ContentStart,
    end = DetailScreenTvDefaults.ContentEnd,
)

/** A `FocusRequester` with no node attached throws, and "nothing to focus yet" is not an error here. */
internal fun FocusRequester.requestFocusIfAttached() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // See above.
    }
}

private const val SINGLE_LINE = 1
private const val REMAINING_WIDTH = 1f
private const val HEAD_KEY = "head"
private const val CAST_KEY = "cast"
private const val RELATED_KEY = "related"
private const val EPISODES_KEY = "episodes"
private const val HERO_CHILD = 0
private const val COLUMN_CHILD = 1
private const val HEAD_INDEX = 0
private const val HEAD_ITEMS = 1
private const val ONE_ITEM = 1
private const val NO_ITEM = 0
private const val FIRST_ROW = 0

/**
 * Artboard 7a/7b/7a-ii's geometry at px/2. Every value is a multiple of 4dp, and every one that a
 * `CloudStreamDimens` token already means is that token rather than its number.
 */
object DetailScreenTvDefaults {
    /**
     * x200 on the artboard, which is 24dp into this page.
     *
     * `ContentOriginX` (100dp) less `NavRailWidth` (76dp): the rail belongs to the shell, and the page
     * is laid out beside it. The same derivation as `HomeScreenTvDefaults.ContentInsetStart` and
     * `SearchScreenTvDefaults.ContentInsetStart`, for the same reason.
     */
    val ContentStart: Dp = CloudStreamDimens.ContentOriginX - CloudStreamDimens.NavRailWidth

    /**
     * The band ends at x1864 of 1920, which is 28dp in from the right. Mirrors
     * `CloudStreamDimens.FocusBandInset`; it read 48dp until the spec measured `.rhd`'s 1664 width.
     */
    val ContentEnd: Dp = CloudStreamDimens.FocusBandInset

    /** y56 on the artboard. Mirrors `CloudStreamDimens.ChromeRowTop`. */
    val ChromeTop: Dp = CloudStreamDimens.ChromeRowTop

    /**
     * Back cell ends at x296, the provider label starts at x320. Mirrors
     * `CloudStreamDimens.MinFocusGap`, and is the same 12dp between the two state cells; it read 8dp
     * until the spec measured it.
     */
    val ChromeGap: Dp = CloudStreamDimens.MinFocusGap

    /** Chrome ends at y152, the title starts at y176. */
    val ChromeToTitle: Dp = 12.dp
    val TitleToMeta: Dp = 4.dp
    val MetaToPlot: Dp = 12.dp
    val PlotHeight: Dp = 40.dp
    const val PlotLines = 2

    /** Plot ends at y408, the caption starts at y432. Mirrors `CloudStreamDimens.MinFocusGap`. */
    val PlotToCaption: Dp = CloudStreamDimens.MinFocusGap

    /**
     * Two mono lines, 2 x 24px on the artboard. Mirrors `CloudStreamDimens.ChipVisualHeight`
     * numerically only — that token is a chip's visual and this is a text box.
     */
    val CaptionHeight: Dp = 24.dp
    const val CaptionLines = 2

    /**
     * Caption ends at y480, the action row starts at y512. Mirrors
     * `CloudStreamDimens.LiftedRowClearance` numerically only; nothing lifts here.
     */
    val CaptionToActions: Dp = 16.dp

    /**
     * Between a section and the next header: y576 to y640 on 7a, and the same 64px between 7a-ii's cast
     * row and its related header. Mirrors `CloudStreamDimens.TextButtonHeight` numerically only.
     */
    val SectionGap: Dp = 32.dp

    /** 600 on the artboard. */
    val HeroHeight: Dp = 300.dp
    val TitleMaxWidth: Dp = 600.dp
    val PlotMaxWidth: Dp = 532.dp
    const val ScrimStartAlpha = 0.92f
    const val ScrimMidAlpha = 0.72f
    const val ScrimMidStop = 0.66f
    const val ScrimEndStop = 0.96f
    const val FloorScrimStop = 0.66f

    /** The Resume button, 440px on the artboard. The ghost buttons beside it are intrinsically wide. */
    val ResumeWidth: Dp = 220.dp

    /**
     * The progress bar inside Resume, 8px on the artboard. Mirrors
     * `CloudStreamDimens.ListRowMarkerWidth` numerically only, and matches
     * `MediaCardDefaults.ProgressRuleHeight`; `ProgressRuleDefaults.Thickness` is 2dp and is the
     * conflict the spec records as open.
     */
    val ResumeProgressHeight: Dp = 4.dp

    /** 24% black on the white primary fill: `.pbar`'s track. */
    const val ResumeTrackAlpha = 0.24f

    /** The cast block, 192x240 on the artboard. Deliberately not a `CardSize`: a person is not media. */
    val CastBlockWidth: Dp = 96.dp
    val CastBlockHeight: Dp = 120.dp

    /** Avatar to name, 16px on the artboard. Mirrors `CloudStreamDimens.ListRowPadding`. */
    val CastAvatarToName: Dp = CloudStreamDimens.ListRowPadding
}
