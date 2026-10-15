package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable

/**
 * What the search surface is showing when it has something to show.
 *
 * The screen has **two kinds of content, not one**, and that is the single biggest thing the
 * drawn design says that this module's first cut did not model. `design/TV Search.dc.html`
 * artboard **4c** — "Search — history (empty query)", captioned *"What you see before typing"* —
 * is a list of recent searches with a per-row remove and `Clear all` pinned at the foot. The
 * first cut made the empty query a `UiState.Empty` prompt, which is a headline, a line of body
 * copy and a button: the wrong shape for a list, and a regression against a screen that was
 * drawn with content in it.
 *
 * Wrapping both in one `UiState<SearchContent>` keeps a single state machine rather than two —
 * the six states still apply to each (recent searches can fail to load), and the screen reads one
 * `when` instead of deciding between a history field and a results field.
 */
@Immutable
sealed interface SearchContent {

    /**
     * Recent searches: artboard 4c, and the default state of the screen.
     *
     * Only reached with a blank query, and only when there is history. With no history the state
     * is a `UiState.Empty` carrying `SearchPrompts.idle()`, because a header over nothing is not
     * a screen.
     */
    @Immutable
    data class Recent(val entries: List<SearchHistoryEntry>) : SearchContent

    /**
     * Search results, grouped per provider: artboards **4a** and **4b**.
     *
     * 4a's caption is the reason for the grouping — *"One rail per provider, so you can see which
     * source answered"*. A flat list cannot render that, and "which source answered" is the whole
     * point of a surface that fans out over a dozen of them.
     */
    @Immutable
    data class Results(val groups: List<SearchResultGroup>) : SearchContent
}

/**
 * One provider's hits: one rail on screen.
 *
 * [items] is the whole group, not a window. The rail shows what fits and offers `See all`
 * (`SeeAllCard`) for the rest, so the count on the header — artboard 4b's `21 RESULTS` — is
 * [items]`.size` and cannot disagree with what the rail scrolls through. The suggestions cluster
 * was re-solved twice over exactly this class of defect: *"The status line and the rows have to
 * agree, and they did not."*
 */
@Immutable
data class SearchResultGroup(
    val providerName: String,
    val items: List<SearchResultItem>,
)

/**
 * One rail per provider, in the order the providers first appear.
 *
 * First-seen order rather than alphabetical or by-count: a provider the user put first in their
 * source list should answer first on screen, and `groupBy` preserves encounter order because it
 * builds a `LinkedHashMap`. Sorting by count would reshuffle the rails as late replies arrive,
 * which moves a focused card out from under the user — the failure `SingleActiveQuery` exists to
 * prevent at the request level.
 */
fun List<SearchResultItem>.groupByProvider(): List<SearchResultGroup> =
    groupBy { it.providerName }
        .map { (provider, items) -> SearchResultGroup(providerName = provider, items = items) }
