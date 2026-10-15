package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.ui.search.SearchHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the two parts of [DataStoreSearchHistory] that are not Android.
 *
 * Plain JUnit, no Robolectric, no `Context` — `:app`'s unit tests carry only `libs.junit` and
 * `libs.json` (`app/build.gradle.kts:221-222`), and adding a framework is a dependency decision.
 * That bounds what is testable: `recent`, `remove` and `clear` all bottom out in
 * `SharedPreferences` through `CloudStreamApp.Companion`, and the `whenLabel` resolver is
 * `android.text.format.DateUtils`. What is left is the ordering and field mapping
 * ([toRecentEntries]) and the key derivation ([historyKeyOf]) — which is also where the defects
 * would be, because both are agreements with a second live code path rather than local choices.
 *
 * The same split is why `StreamRequestAdapterTest` covers `localStream` and not `fromUri`: the
 * Uri-free part is tested, the framework-bound part is not pretended at.
 */
class DataStoreSearchHistoryTest {

    private fun item(searchText: String, searchedAt: Long) = SearchHistoryItem(
        searchedAt = searchedAt,
        searchText = searchText,
        // Always empty in the store: SearchViewModel.kt:219 carries `// TODO implement tv type`.
        type = emptyList(),
        key = searchText.hashCode().toString(),
    )

    /** Echoes the timestamp back, so a case can assert *which* value reached the resolver. */
    private val echo: (Long) -> String = { searchedAt -> "@$searchedAt" }

    /**
     * The contract says "Most recent first", and `getKeys` yields `SharedPreferences.all.keys` —
     * hash order, which is neither insertion nor time order. The sort is the only thing standing
     * between the store and a list that reshuffles itself between reads.
     */
    @Test
    fun ordersMostRecentFirst() {
        val entries = listOf(
            item("the wire", searchedAt = 1_000L),
            item("dune", searchedAt = 3_000L),
            item("arcane", searchedAt = 2_000L),
        ).toRecentEntries(echo)

        assertEquals(listOf("dune", "arcane", "the wire"), entries.map { it.query })
    }

    /**
     * Two searches can land in the same millisecond. `sortedByDescending` is stable, so they keep
     * the order the store yielded; an unstable sort would swap them between two reads of an
     * unchanged history and move a focused row out from under the user.
     */
    @Test
    fun tiesKeepStoreOrder() {
        val entries = listOf(
            item("first", searchedAt = 5_000L),
            item("second", searchedAt = 5_000L),
        ).toRecentEntries(echo)

        assertEquals(listOf("first", "second"), entries.map { it.query })
    }

    /**
     * `SearchHistoryItem` has two `String` fields and the row needs the other one: `key` is a hash
     * of the text, so confusing them compiles, renders a column of integers, and sends
     * `SearchAction.RecentActivated("1526069264")` into a provider search.
     */
    @Test
    fun rowCarriesSearchTextNotKey() {
        val entry = listOf(item("the wire", searchedAt = 1_000L)).toRecentEntries(echo).single()

        assertEquals("the wire", entry.query)
    }

    /**
     * The label is resolved from the entry's own `searchedAt`. The plausible wrong wiring is to
     * resolve it from "now" — which makes every row read "0 seconds ago" and looks right on a
     * history one search old.
     */
    @Test
    fun labelIsResolvedFromSearchedAt() {
        val entries = listOf(
            item("dune", searchedAt = 3_000L),
            item("arcane", searchedAt = 2_000L),
        ).toRecentEntries(echo)

        assertEquals(listOf("@3000", "@2000"), entries.map { it.whenLabel })
    }

    /** "Empty is normal — a new install has no history", so it must not become a one-row list. */
    @Test
    fun emptyHistoryMapsToEmptyList() {
        assertEquals(emptyList<Any>(), emptyList<SearchHistoryItem>().toRecentEntries(echo))
    }

    /**
     * The key agreement with the old path, pinned deliberately rather than as arithmetic.
     *
     * `SearchViewModel.kt:212-221` writes each row under `query.hashCode().toString()` and stores
     * that same value in the entry's `key` field; `SearchFragment.kt:573` removes by it. The
     * fixture above builds `key` the way the old path does, and this asserts [historyKeyOf] lands
     * on it — so changing either side alone fails here, instead of shipping a history whose rows
     * one of the two live screens cannot delete.
     */
    @Test
    fun keyMatchesTheKeyTheOldPathWrites() {
        val stored = item("the wire", searchedAt = 1_000L)

        assertEquals(stored.key, historyKeyOf("the wire"))
    }
}
