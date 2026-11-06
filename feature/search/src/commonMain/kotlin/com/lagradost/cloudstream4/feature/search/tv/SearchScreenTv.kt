package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonRail
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.state.failureNotice
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchState
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.toUiState

/**
 * The TV search screen: `design/TV Search.dc.html` artboards **4a**, **4b** and **4c**, plus
 * **8a**, **8b** and **8c** from `design/TV Search Suggestions.dc.html`.
 *
 * ## What it is, and what it is not
 *
 * Stateless by design: it takes a [SearchState] and emits [SearchAction]s. The `collectAsState`
 * that `.claude/rules/mvi-viewmodel.md` asks for happens in the glue (Phase 7 step 3), which is
 * also the only place that can own a `ViewModel`. A screen that collected its own state could not
 * be previewed in any of its six states, which is most of what this file is worth.
 *
 * **This is the first first-party screen in the project.** The seven migrated settings screens are
 * in the vendored `:core:mihon` tree and follow Mihon's conventions, not this chassis.
 *
 * ## The chassis, which is shared with the other three artboards in the cluster
 *
 * The design file's own summary: *"Four screens on one chassis: the same 96px chrome row, the same
 * chip row, the same 96px list row, the same 560-wide right panel."* Every number below is that
 * chassis halved — the artboard is 1920×1080 and `artboard px / 2 = dp` — and every one of them
 * is already a `CloudStreamDimens` token, which is the payoff of having drawn the design system
 * first:
 *
 * | Band | Artboard | dp | Token |
 * |---|---|---|---|
 * | chrome row | y56…152 | 28…76 | `ChromeRowTop`, `ChromeRowHeight`, `ChromeRowBottom` |
 * | chip row | y176…272 | 88…136 | `ChipRowTop`, `ChipRowBottom` |
 * | content | y296 | 148 | [SearchScreenTvDefaults.ContentRegionTop] |
 * | content x | 200…1864 | 100…932 | `ContentOriginX`, `ContentEndX` |
 *
 * The two gaps between the bands both come out at `MinFocusGap` (12dp), which is not a
 * coincidence: the bands are focus rows and 12dp is the minimum legal gap between focusables.
 *
 * ## What rides where
 *
 * The failure notice goes in the **chip row**, never in a band of its own. That is the design's
 * most load-bearing decision in this cluster — artboard 4b's caption says a full-width banner
 * *"cost 120px and pushed the second rail past the focus band"* — and it is why `UiStateHost`
 * refuses to place it for you.
 *
 * ## Where the D-pad goes
 *
 * The boards fix one of these, 4a's *"DOWN is a fixed id"*, and the rest are the choices a TV user expects:
 *
 * - **Down from the field** goes to the next row that has a stop: the first chip, else 8c's action, else
 *   the first card of the first row. By geometry it went to the third card, the one nearest the field's centre.
 * - **Down from a chip or the row's action** goes to the first card of the first row.
 * - **Right at the end of a row stays put**, on a rail, on the chips, on `Sources`.
 * - **Left from the field** leaves for the host's rail, through [onLeftEdge].
 *
 * - **Right from a host's rail** comes back to the stop that left for the rail, or to the field the first time.
 *
 * The content region is one always-mounted focus group, and a request on a group lands on its first stop,
 * so nothing here depends on which state is showing or whether a rail is composed.
 *
 * @param state the screen's state.
 * @param onAction where the user's intent goes.
 * @param artwork draws a result's poster. Image loading is the host's, so this module stays free of
 *   an image library. The default draws nothing, which leaves the card's own placeholder.
 * A host on Android wires platform back to [SearchAction.SourcePanelDismissed] while the panel is open,
 * as `SidePanelHost` says: on API 36 the key does not reach this composition at all.
 *
 * @param onLeftEdge moves focus out to the host when Left from the field has nowhere to go here. A host
 *   with a rail in its own views has to, because the field consumes that key (`BUG-34`).
 */
@Composable
fun SearchScreenTv(
    state: SearchState,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (SearchResultItem) -> Unit = {},
    onLeftEdge: (() -> Unit)? = null,
) {
    val content = state.status.toUiState(onAction, narrowedBy = state.typeFilters.selected)
    val notice = content.failureNotice
    // 8b's and 8c's action rides the chip row: the cluster's closing note calls a floating action a defect.
    val stateOnTheRow = state.status is SearchStatus.NoResults || state.status is SearchStatus.NoSuggestions
    val rowAction = (content as? UiState.Empty)?.prompt?.action?.takeIf { stateOnTheRow }
    val contentEntry = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    val trailing = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val stops = remember { SearchStops() }
    // Only these states draw stops of this screen's own. The others hold at most `StateView`'s one action.
    val contentHasOwnStops = content is UiState.Content || content is UiState.Partial
    val panelOpen = state.sourcePanel != null
    val hasChips = state.typeFilters.available.isNotEmpty()
    val fieldDown = when {
        hasChips -> firstChip
        rowAction != null -> trailing
        else -> contentEntry
    }
    val sourcesDown = if (hasChips || notice != null || rowAction != null) FocusRequester.Default else contentEntry

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().searchStopsGroup(stops, first = fieldFocus)) {
            Spacer(Modifier.height(CloudStreamDimens.ChromeRowTop))

            SearchChromeRow(
                query = state.query,
                sources = state.sources,
                onAction = onAction,
                fieldDown = fieldDown,
                sourcesDown = sourcesDown,
                sourcesFocus = sourcesFocus,
                fieldFocus = fieldFocus,
                stops = stops,
                onLeftEdge = onLeftEdge,
                modifier = Modifier.contentInset().fillMaxWidth(),
            )

            Spacer(Modifier.height(SearchScreenTvDefaults.ChromeToChipGap))

            SearchChipRow(
                filters = state.typeFilters,
                notice = notice,
                action = rowAction,
                onAction = onAction,
                trailingFocus = trailing,
                firstChipFocus = firstChip,
                contentDown = contentEntry,
                stops = stops,
                actionTakesFocus = state.status !is SearchStatus.NoSuggestions,
            )

            Spacer(Modifier.height(SearchScreenTvDefaults.ChipToContentGap))

            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentEntry)
                    .onFocusChanged { if (it.hasFocus && !contentHasOwnStops) stops.last = contentEntry }
                    .focusGroup(),
            ) {
                UiStateHost(
                    state = content,
                    modifier = Modifier.contentRegionInset().fillMaxSize(),
                    glyph = { if (stateOnTheRow) NoResultsGlyph() },
                    emptyActionElsewhere = rowAction != null,
                    skeleton = { skeletonModifier ->
                        SearchSkeleton(modifier = skeletonModifier)
                    },
                ) { loaded ->
                    when (loaded) {
                        is SearchContent.Recent -> SearchRecentColumn(
                            entries = loaded.entries,
                            onAction = onAction,
                            stops = stops,
                            modifier = Modifier.contentRegionInset().fillMaxSize(),
                        )

                        is SearchContent.Suggestions -> SearchSuggestionColumn(
                            rows = loaded.rows,
                            onAction = onAction,
                            stops = stops,
                            modifier = Modifier.contentRegionInset().fillMaxSize(),
                        )

                        is SearchContent.Results -> SearchResultRails(
                            groups = loaded.groups,
                            onAction = onAction,
                            artwork = artwork,
                            stops = stops,
                            modifier = Modifier
                                .padding(bottom = SearchScreenTvDefaults.ContentInsetBottom)
                                .fillMaxSize(),
                        )
                    }
                }
            }
        }

        // The screen is a sibling of the host rather than its content: [SearchStops] brings the rail's RIGHT
        // back, and the host's `focusRestorer` would be a second answer to that. So closing gives focus back
        // here, to what opened the panel.
        SidePanelHost(
            expanded = panelOpen,
            onDismissRequest = { onAction(SearchAction.SourcePanelDismissed) },
            panel = {
                // Composed for the slide after the state goes null, so hold the last panel to slide out with.
                val panel = rememberLastNonNull(state.sourcePanel)
                if (panel != null) SearchSourcePanel(panel = panel, sources = state.sources, onAction = onAction)
            },
            content = {},
        )
    }

    var panelWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (panelWasOpen && !panelOpen) sourcesFocus.requestFocus()
        panelWasOpen = panelOpen
    }
}

/**
 * The last stop that held focus, so coming back into the page lands on it.
 *
 * `focusRestorer` does not do this: a page's view losing focus to a rail in the host's views is an exit it does
 * not see (`KMP-33`), and RIGHT then goes by geometry from a rail as wide as the screen. It found `Sources`
 * (`TvRailNavigationTest`). `HomeStops` is the same answer, measured on the same device.
 */
internal class SearchStops {
    var last: FocusRequester? = null
}

/** A page's focus stop. */
@Composable
internal fun Modifier.searchStop(stops: SearchStops): Modifier {
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) stops.last = requester }
}

/** Sends focus entering the page to the last stop, else to [first]. If neither is there, the default takes over. */
private fun Modifier.searchStopsGroup(stops: SearchStops, first: FocusRequester): Modifier = focusProperties {
    onEnter = {
        if ((stops.last ?: first).tryRequestFocus()) cancelFocusChange()
    }
}.focusGroup()

/** A `FocusRequester` with no node attached throws: a stop that scrolled away or went with its state. */
private fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}

/** The last value that was not null, so something closing can keep drawing what it closed on. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

/** 8b's and 8c's `.svg1`: a raised tile, 48dp, round at 12dp, holding the glyph. */
@Composable
private fun NoResultsGlyph() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        CloudStreamIcon(
            icon = CloudStreamIcons.Search,
            contentDescription = null,
            size = CloudStreamIconSize.Large,
            tint = CloudStreamTheme.colors.muted,
        )
    }
}

/** The screen's horizontal inset. Not applied to the whole column: the rails need their own. */
private fun Modifier.contentInset(): Modifier = padding(
    start = SearchScreenTvDefaults.ContentInsetStart,
    end = SearchScreenTvDefaults.ContentInsetEnd,
)

/** The content region's inset: horizontal, plus room at the foot for the last row's ring. */
private fun Modifier.contentRegionInset(): Modifier =
    contentInset().padding(bottom = SearchScreenTvDefaults.ContentInsetBottom)

/**
 * The geometry this screen writes down, each derived from a token or from a named artboard.
 *
 * Rule 2 of `.claude/rules/dimension-scale.md`: a plain `object` has no composition to read
 * `CloudStreamTheme.dimens` from, so it restates the numbers and the KDoc says which token each
 * one mirrors.
 */
object SearchScreenTvDefaults {

    /** Between two badges on a card: `.badges { gap: 8px }` in 4a, halved. Equal to `MediaCardDefaults.ContentPadding`. */
    val BadgeGap: Dp = 4.dp

    /**
     * The y every artboard in the cluster mounts its one content region at — 296 artboard px.
     *
     * The design file is explicit that there is exactly one: *"Today the four result views are
     * siblings in a FrameLayout and the chip row's DOWN target is rewired at runtime; here exactly
     * one result region is mounted at y296, so DOWN is a fixed id."*
     */
    val ContentRegionTop: Dp = 148.dp

    /**
     * Inset from the leading edge of the area this screen is given.
     *
     * `ContentOriginX` (100dp) less `NavRailWidth` (76dp): the nav rail belongs to the shell, not
     * to a screen, so the screen's own origin is 24dp inside the area it receives.
     */
    val ContentInsetStart: Dp = CloudStreamDimens.ContentOriginX - CloudStreamDimens.NavRailWidth

    /** Mirrors `FocusBandInset` — 960dp of artboard width less `ContentEndX` (932dp). */
    val ContentInsetEnd: Dp = CloudStreamDimens.FocusBandInset

    /**
     * Bottom inset, so the last row's focus ring has somewhere to draw.
     *
     * Mirrors [ContentInsetEnd] / `FocusBandInset`. Measured rather than assumed: the first render
     * of artboard 4c put `Clear all searches` flush against the bottom edge, where its ring would
     * have been cut.
     */
    val ContentInsetBottom: Dp = CloudStreamDimens.FocusBandInset

    /** `.srcbtn`'s x1440–1864, halved. */
    val SourcesButtonWidth: Dp = 212.dp

    /**
     * Where the board's `.ibx` stands between the field and `Sources`, x1296–1440 halved: a 48dp cell with
     * `MinFocusGap` either side. The owner dropped voice search, which is what it was, so it stays empty.
     */
    val DroppedIconSlot: Dp = 72.dp

    /** `ChipRowTop` (88dp) less `ChromeRowBottom` (76dp). Equals `MinFocusGap`. */
    val ChromeToChipGap: Dp = CloudStreamDimens.ChipRowTop - CloudStreamDimens.ChromeRowBottom

    /** [ContentRegionTop] (148dp) less `ChipRowBottom` (136dp). Also `MinFocusGap`. */
    val ChipToContentGap: Dp = ContentRegionTop - CloudStreamDimens.ChipRowBottom
}

/**
 * `UiState.Loading`'s skeleton, shaped like what lands.
 *
 * `UiStateHost`'s default is a rail of **landscape** cards, and its KDoc warns that a screen whose
 * content is not that will visibly jump. Search results are 2:3 posters, so this is a portrait
 * rail at the same rung the rails use.
 */
@Composable
private fun SearchSkeleton(modifier: Modifier = Modifier) {
    SkeletonRail(
        rung = CloudStreamCardSizes.PortraitMedium,
        modifier = modifier,
    )
}
