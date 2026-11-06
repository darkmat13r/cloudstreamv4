package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable

/**
 * Titles to suggest for what has been typed: artboards **8a** and **8b** of `design/TV Search Suggestions.dc.html`.
 *
 * On Android this wraps the old `SearchSuggestionApi` rather than moving it (`kmp-migration.md` §5.3): it is in
 * `classes.jar`, and the key it sends ships there already.
 */
fun interface SearchSuggestionSource {

    /**
     * Most likely first. Empty when nothing matches **and** when the service could not be reached: the old API
     * catches its own failures and returns an empty list, so the two cannot be told apart here.
     */
    suspend fun suggest(query: String): List<String>
}

/**
 * One row of 8a's ranked column.
 *
 * A recent search and a suggestion with the same words, ignoring case, are **one** row with both origins. That is the
 * board's first finding: the two-column draft drew `dune` at rank 1 with no count and `Dune` at rank 5 with seven.
 *
 * @property query what activating the row searches for, as it was written by whichever origin came first.
 * @property recentWhen when it was searched, already resolved for display, or `null` if it is not a recent search.
 * @property suggested whether the suggestion service named it.
 * @property sources how many of the providers that answered carry a title with these words, or `null` while their
 *   answers have not all arrived. Never a partial count: the board's other finding is that a count and the status
 *   line have to agree.
 */
@Immutable
data class SearchSuggestion(
    val query: String,
    val recentWhen: String? = null,
    val suggested: Boolean = false,
    val sources: Int? = null,
)

/**
 * 8a's column, from both origins. At most [limit] rows, the board's six.
 *
 * The order: the words typed, if either origin has them; then the service's suggestions in its order; then recent
 * searches that were not suggested, newest first. Recent searches keep [RECENT_SLOTS] of the rows even when there are
 * more suggestions than fit, so a long answer from the service cannot hide what the user searched for before.
 *
 * A recent search matches when it contains the typed words, ignoring case.
 */
fun mergeSuggestions(
    typed: String,
    suggestions: List<String>,
    recent: List<SearchHistoryEntry>,
    limit: Int = SUGGESTION_ROWS,
): List<SearchSuggestion> {
    val words = typed.trim()
    if (words.isEmpty()) return emptyList()
    val recentByKey = LinkedHashMap<String, SearchHistoryEntry>()
    for (entry in recent) {
        if (entry.query.contains(words, ignoreCase = true)) recentByKey.putIfAbsent(entry.query.key(), entry)
    }
    val suggested = LinkedHashMap<String, String>()
    for (title in suggestions) {
        val clean = title.trim()
        if (clean.isNotEmpty()) suggested.putIfAbsent(clean.key(), clean)
    }

    val both = suggested.mapNotNull { (key, title) ->
        recentByKey[key]?.let { SearchSuggestion(query = it.query, recentWhen = it.whenLabel, suggested = true) }
            ?: SearchSuggestion(query = title, suggested = true)
    }
    val recentOnly = recentByKey.filterKeys { it !in suggested }.values.map {
        SearchSuggestion(query = it.query, recentWhen = it.whenLabel)
    }

    val typedKey = words.key()
    val first = (both + recentOnly).firstOrNull { it.query.key() == typedKey }
    val restSuggested = both.filter { it !== first }
    val restRecent = recentOnly.filter { it !== first }
    val room = limit - listOfNotNull(first).size
    val recentShown = restRecent.take(minOf(RECENT_SLOTS, room).coerceAtLeast(0))
    val suggestedShown = restSuggested.take((room - recentShown.size).coerceAtLeast(0))
    val recentFill = restRecent.drop(recentShown.size).take((room - recentShown.size - suggestedShown.size).coerceAtLeast(0))
    return listOfNotNull(first) + suggestedShown + recentShown + recentFill
}

/**
 * Each row's count from one fan-out's results: the providers with a title equal to the row's words, ignoring case.
 *
 * A provider counts once, however many of its hits match, because the count is of sources and 4a draws a rail per source.
 */
fun List<SearchSuggestion>.withSourceCounts(items: List<SearchResultItem>): List<SearchSuggestion> {
    val providersByTitle = HashMap<String, MutableSet<String>>()
    for (item in items) providersByTitle.getOrPut(item.title.key()) { HashSet() }.add(item.providerName)
    return map { it.copy(sources = providersByTitle[it.query.key()]?.size ?: 0) }
}

private fun String.key(): String = trim().lowercase()

/** 8a's one ranked column of six. */
const val SUGGESTION_ROWS = 6

/** How many of the rows recent searches keep, when they and the suggestions together have more than fit. */
const val RECENT_SLOTS = 2
