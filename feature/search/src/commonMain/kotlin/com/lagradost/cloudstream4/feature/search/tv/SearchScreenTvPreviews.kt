package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchState

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

private val PreviewCopy = SearchTvCopy(
    fieldPlaceholder = "Search all sources",
    sourcesLabel = "Sources",
    sourcesCount = { selected, total -> "$selected / $total" },
    recentHeadline = "Recent searches",
    recentCount = { "$it SAVED" },
    clearAll = "Clear all searches",
    resultCount = { "$it RESULTS" },
)

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

private fun prompt(headline: String, body: String, label: String) =
    StatePrompt(headline = headline, body = body, action = StateAction(label) {})

@Composable
private fun PreviewGround(content: @Composable () -> Unit) {
    CloudStreamTheme {
        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
    }
}

@Composable
private fun PreviewScreen(content: UiState<SearchContent>, query: String = "blade runner") {
    PreviewGround {
        SearchScreenTv(
            state = SearchState(query = query, sources = PreviewSources, content = content),
            copy = PreviewCopy,
            onAction = {},
        )
    }
}

/** Artboard 4a — one rail per provider. */
@Preview(name = "Results", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchResultsPreview() =
    PreviewScreen(UiState.Content(SearchContent.Results(PreviewGroups)))

/** Artboard 4b — three of twelve sources failed; the shortfall rides the chip row. */
@Preview(name = "Partial", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchPartialPreview() = PreviewScreen(
    UiState.Partial(
        value = SearchContent.Results(PreviewGroups),
        notice = FailureNotice(
            headline = "3 sources failed",
            detail = "TIMEOUT 10S",
            retry = StateAction("Retry failed") {},
        ),
    ),
)

/** Artboard 4c — what you see before typing. */
@Preview(name = "Recent", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchRecentPreview() =
    PreviewScreen(UiState.Content(SearchContent.Recent(PreviewRecent)), query = "")

/** Artboard 8c — every source answered and none of them carry it. */
@Preview(name = "No results", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchNoResultsPreview() = PreviewScreen(
    UiState.Empty(
        prompt = prompt(
            headline = "No results",
            body = "All twelve selected sources answered and none of them carry it.",
            label = "Search everything",
        ),
        detail = "12 QUERIED · 12 ANSWERED · 0 FAILED · 0 RESULTS",
    ),
)

/** First run: nothing typed and no history to show. */
@Preview(name = "Idle", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchIdlePreview() = PreviewScreen(
    UiState.Empty(
        prompt = prompt(
            headline = "Search every source you have",
            body = "Type a title. Results arrive one rail per source.",
            label = "Browse home",
        ),
    ),
    query = "",
)

/** The skeleton, which must be a portrait rail and not the host's landscape default. */
@Preview(name = "Loading", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchLoadingPreview() = PreviewScreen(UiState.Loading)

/** Every source failed. The detail is what a user can paste into a bug report. */
@Preview(name = "Error", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchErrorPreview() = PreviewScreen(
    UiState.Error(
        prompt = prompt(
            headline = "Search failed",
            body = "None of your twelve sources answered.",
            label = "Try again",
        ),
        detail = "12 QUERIED · 0 ANSWERED · 12 FAILED",
    ),
)

/** No network. Separate from Error because the user's next move differs. */
@Preview(name = "Offline", widthDp = TV_WIDTH_DP, heightDp = TV_HEIGHT_DP)
@Composable
private fun SearchOfflinePreview() = PreviewScreen(
    UiState.Offline(
        prompt = prompt(
            headline = "You are offline",
            body = "Downloads are still available. Search needs a connection.",
            label = "Try again",
        ),
    ),
)
