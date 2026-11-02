package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchState
import com.lagradost.cloudstream4.feature.search.SearchStatus

/**
 * One preview per state the screen owes, at the artboard's own size.
 *
 * ## These are not screenshot tests, and cannot be yet
 *
 * `.claude/rules/screenshot-testing.md` asks every feature to ship cases for its load-bearing
 * states, and names the blocker: `com.android.compose.screenshot:0.0.1-alpha16` renders nothing on
 * this toolchain **and does not support KMP modules at all** — applied to a KMP module it
 * contributes zero tasks. `:feature:search` is a KMP module, so there is no suite that could hold
 * these. They are development previews, and the states they cover are exactly the cases that would
 * be recorded the day a working plugin (or Roborazzi, `DEC-10`) lands.
 *
 * Each is pinned to 960×540 dp, which is the 1920×1080 artboard halved — the design system's own
 * `artboard px / 2 = dp`. An unpinned preview resizes with its content and shows a size change
 * instead of the thing that moved.
 */
private const val TV_WIDTH_DP = 960
private const val TV_HEIGHT_DP = 540

private val PreviewSources = SearchSources(selected = 12, total = 48)

private fun previewItem(title: String, provider: String, quality: String? = null) =
    SearchResultItem(
        id = SearchResultId("$provider/$title"),
        title = title,
        providerName = provider,
        typeLabel = "Movie",
        qualityLabel = quality,
    )

private val PreviewGroups = listOf(
    SearchResultGroup(
        providerName = "Filmxy",
        items = listOf(
            previewItem("Blade Runner", "Filmxy", "4K"),
            previewItem("Blade Runner 2049", "Filmxy", "1080P"),
            previewItem("Blade Runner: Black Out", "Filmxy"),
        ),
    ),
    SearchResultGroup(
        providerName = "Vidplay",
        items = listOf(previewItem("Blade Runner", "Vidplay", "720P")),
    ),
)

private val PreviewRecent = listOf(
    SearchHistoryEntry("blade runner", "2 days ago"),
    SearchHistoryEntry("dune", "last week"),
)

@Composable
private fun PreviewGround(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
    }
}

@Composable
private fun PreviewScreen(status: SearchStatus, query: String = "blade runner") {
    PreviewGround {
        SearchScreenTv(
            state = SearchState(query = query, sources = PreviewSources, status = status),
            onAction = {},
        )
    }
}

/** Artboard 4a — one rail per provider. */
@Preview(name = "Results", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchResultsPreview() = PreviewScreen(SearchStatus.Showing(SearchContent.Results(PreviewGroups)))

/** Artboard 4b — three of twelve sources failed; the shortfall rides the chip row. */
@Preview(name = "Partial", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchPartialPreview() = PreviewScreen(
    SearchStatus.Partial(SearchContent.Results(PreviewGroups), answered = 9, total = 12, detail = "TIMEOUT 10S"),
)

/** Artboard 4c — what you see before typing. */
@Preview(name = "Recent", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchRecentPreview() =
    PreviewScreen(SearchStatus.Showing(SearchContent.Recent(PreviewRecent)), query = "")

/** `TV Search Suggestions` artboard 8c — every source answered and none of them carry it. */
@Preview(name = "No results", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchNoResultsPreview() = PreviewScreen(SearchStatus.NoResults(queried = 12))

/** First run: nothing typed and no history to show. */
@Preview(name = "Idle", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchIdlePreview() = PreviewScreen(SearchStatus.Idle, query = "")

/** The skeleton, which must be a portrait rail and not the host's landscape default. */
@Preview(name = "Loading", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchLoadingPreview() = PreviewScreen(SearchStatus.Loading)

/** Every source failed. The detail is what a user can paste into a bug report. */
@Preview(name = "Error", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchErrorPreview() = PreviewScreen(SearchStatus.Failed("12 QUERIED · 0 ANSWERED · 12 FAILED"))

/** No network. Separate from Error because the user's next move differs. */
@Preview(name = "Offline", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchOfflinePreview() = PreviewScreen(SearchStatus.Offline)
