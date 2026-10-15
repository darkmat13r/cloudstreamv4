package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonRail
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.state.failureNotice
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchState

/**
 * The TV search screen: `design/TV Search.dc.html` artboards **4a**, **4b** and **4c**, plus
 * **8c** from `design/TV Search Suggestions.dc.html`.
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
 * @param state the screen's state.
 * @param copy every word on screen, resolved. See [SearchTvCopy], and the open question in it.
 * @param onAction where the user's intent goes.
 */
@Composable
fun SearchScreenTv(
    state: SearchState,
    copy: SearchTvCopy,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(CloudStreamDimens.ChromeRowTop))

        SearchChromeRow(
            query = state.query,
            sources = state.sources,
            copy = copy,
            onAction = onAction,
            modifier = Modifier.contentInset().fillMaxWidth(),
        )

        Spacer(Modifier.height(SearchScreenTvDefaults.ChromeToChipGap))

        SearchChipRow(
            notice = state.content.failureNotice,
            modifier = Modifier.contentInset(),
        )

        Spacer(Modifier.height(SearchScreenTvDefaults.ChipToContentGap))

        UiStateHost(
            state = state.content,
            modifier = Modifier.contentRegionInset().fillMaxSize(),
            skeleton = { skeletonModifier ->
                SearchSkeleton(modifier = skeletonModifier)
            },
        ) { content ->
            when (content) {
                is SearchContent.Recent -> SearchRecentColumn(
                    entries = content.entries,
                    copy = copy,
                    onAction = onAction,
                    modifier = Modifier.contentRegionInset().fillMaxSize(),
                )

                is SearchContent.Results -> SearchResultRails(
                    groups = content.groups,
                    copy = copy,
                    onAction = onAction,
                    modifier = Modifier
                        .padding(bottom = SearchScreenTvDefaults.ContentInsetBottom)
                        .fillMaxSize(),
                )
            }
        }
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
