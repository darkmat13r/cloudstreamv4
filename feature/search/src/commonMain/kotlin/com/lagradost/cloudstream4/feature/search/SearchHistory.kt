package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable

/**
 * One row of artboard 4c's "Recent searches" list.
 *
 * ## Why [whenLabel] is a resolved string
 *
 * The stored value is a Unix timestamp (`searchedAt`), and turning one into *"2 days ago"* is
 * locale work: the wording, the plural category and the digits all change per locale, and
 * `I18N-06` measured that even the digits differ between Temurin 17 and Android API 36 for `ar`.
 * A `commonMain` module with no string loader cannot do it, and a `Long` on this type would push
 * the problem into the screen, which is the one place that must not hold copy. So the producer
 * resolves it, exactly as `SearchPrompts` resolves the state copy.
 *
 * ## What the stored history does not have
 *
 * `:app` writes a `type` field on every history entry and **always writes it empty** —
 * `SearchViewModel.kt:219` carries `// TODO implement tv type`. So there is no media-type badge
 * on this row, and inventing one here would be modelling a field that has never been written.
 * The artboard does not draw one either.
 */
@Immutable
data class SearchHistoryEntry(
    val query: String,
    /** When it was searched, already resolved for display — e.g. "2 days ago". */
    val whenLabel: String,
)

/**
 * Stored search history: the seam `:app` implements over `DataStoreHelper`.
 *
 * Separate from [SearchSource] because they fail independently and for different reasons — a
 * disk read and a dozen network calls — and because history is the screen's *default* content
 * (artboard 4c), so it must be loadable without searching anything.
 *
 * Every method is `suspend`: the Android implementation reads and writes a preference store, and
 * a screen that blocks on it stutters on the keystroke that cleared the field.
 */
interface SearchHistoryStore {

    /** Most recent first. Empty is normal — a new install has no history. */
    suspend fun recent(): List<SearchHistoryEntry>

    /** Remove one entry, from a row's own remove affordance. */
    suspend fun remove(query: String)

    /** Remove all of them: artboard 4c's `Clear all searches`, pinned at the foot of the list. */
    suspend fun clear()
}

/**
 * How many of the user's sources this search is using: the chrome row's `Sources 12 / 48`.
 *
 * Drawn on every artboard in the cluster, and load-bearing rather than decorative —
 * `design/TV Search Suggestions.dc.html` 8c's
 * no-results copy reads *"All nine selected sources answered and none of them carry it"*, so the
 * number in the chrome row and the number in the empty state's copy are the same fact and must
 * agree. [selected] is what a search actually queries; [total] is how many are installed.
 *
 * The side panel that *changes* the selection is artboard 4d and is not built yet; this type is
 * the half the search screen needs to render, and it is read-only here.
 */
@Immutable
data class SearchSources(
    val selected: Int = 0,
    val total: Int = 0,
)
