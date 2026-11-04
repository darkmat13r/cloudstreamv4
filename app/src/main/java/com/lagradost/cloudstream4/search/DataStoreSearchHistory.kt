package com.lagradost.cloudstream4.search

import android.text.format.DateUtils
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ui.search.SEARCH_HISTORY_KEY
import com.lagradost.cloudstream3.ui.search.SearchHistoryItem
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:app`'s [SearchHistoryStore]: the same stored search history the old search path reads.
 *
 * ## Both paths are live, so this is a parity implementation, not a port
 *
 * `ui/search/SearchFragment` is the rollback lever for the whole of plan row 18 and keeps running
 * beside the new screen, which means this class and `SearchViewModel` read and write **one**
 * preference folder at the same time. Every value below is therefore taken from the old path
 * rather than re-decided here:
 *
 * | What | Where the old path does it |
 * |---|---|
 * | folder `"$currentAccount/$SEARCH_HISTORY_KEY"` | `SearchViewModel.kt:86` (read), `:214` (write) |
 * | one entry per `query.hashCode().toString()` | `SearchViewModel.kt:212` |
 * | `getKeys(folder)` paths fed to the single-arg `getKey` | `SearchViewModel.kt:86-87` |
 * | `sortedByDescending { it.searchedAt }` | `SearchViewModel.kt:88` |
 * | remove one by the entry's own `key` | `SearchFragment.kt:573` |
 * | clear by `removeKeys(folder)` | `SearchFragment.kt:585` |
 *
 * [SEARCH_HISTORY_KEY] is imported rather than restated as `"search_history"` for that reason: a
 * literal here could drift from the literal there, and the two paths would then keep two histories
 * that each look empty to the other.
 *
 * ## Why there is no constructor parameter
 *
 * No `Context`: the store is reached through `CloudStreamApp.Companion`, which holds the
 * application context in a `WeakReference` and returns `null` rather than throwing once it is gone
 * (`CloudStreamApp.kt:116-170`). No dispatcher either — unlike
 * `com.lagradost.cloudstream4.feature.search.SearchViewModel`, which injects one so tests can drive
 * it, nothing here is reachable from a JVM test: every call bottoms out in `SharedPreferences` or
 * `android.text.format.DateUtils`. The testable arithmetic is factored out as the two internal
 * top-level functions below, which `DataStoreSearchHistoryTest` covers directly.
 *
 * ## Visibility
 *
 * `internal`, per the `classes.jar` contract — but note that this does **not** keep the class out of
 * `app/api/classes-jar.api`. Kotlin `internal` has no JVM representation, so the dump (which reads
 * `ACC_PUBLIC` from the bytecode, `ExtensionApiDump.kt:112-130`) lists internal `:app` classes
 * exactly like public ones; `ui/player/StreamRequestAdapter` is `internal object` and is in the dump
 * at `classes-jar.api:28131`. The members implementing [SearchHistoryStore] cannot be `internal`
 * either — Kotlin forbids narrowing an override. So this class adds one block to the dump, and the
 * dump needs regenerating when it lands. The addition is purely additive and cannot break a
 * published extension.
 */
internal class DataStoreSearchHistory : SearchHistoryStore {

    /**
     * Read on `Dispatchers.IO` even though `SharedPreferences.all` is an in-memory map: the first
     * touch of the file is a blocking load, and this is called from `viewModelScope` on every
     * keystroke that empties the field (`SearchViewModel.showIdle`).
     */
    override suspend fun recent(): List<SearchHistoryEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        storedItems().toRecentEntries { searchedAt -> relativeWhenLabel(searchedAt, now) }
    }

    /**
     * Records a submitted search. Not part of the contract: the host calls it, so the state machine
     * stays unaware of storage.
     *
     * The same row `SearchViewModel.kt:212-222` writes, under the same key, so a search repeated from
     * either path moves its one row to the top rather than adding a second.
     */
    suspend fun record(query: String, searchedAt: Long = System.currentTimeMillis()) {
        if (!isRecordable(query)) return
        withContext(Dispatchers.IO) {
            val key = historyKeyOf(query)
            setKey(
                historyFolder(),
                key,
                SearchHistoryItem(
                    searchedAt = searchedAt,
                    searchText = query,
                    type = emptyList(),
                    key = key,
                ),
            )
        }
    }

    /**
     * Removes by the stored entry's own `key`, which is what `SearchFragment.kt:573` removes.
     *
     * The key is recoverable without a read — it is [historyKeyOf] of the query — so the read looks
     * redundant. It is not: it makes this agree with the old path for an entry whose stored `key`
     * was written by *any* scheme, present or past, and it removes every entry carrying the text
     * rather than assuming there is one. [historyKeyOf] is the fallback for the case the read cannot
     * cover — the row was already gone, or its JSON no longer parses, so no stored key exists to
     * use — because a remove that silently does nothing leaves a row the user cannot get rid of.
     */
    override suspend fun remove(query: String) {
        withContext(Dispatchers.IO) {
            val folder = historyFolder()
            val stored = storedItems().filter { it.searchText == query }.map { it.key }
            stored.ifEmpty { listOf(historyKeyOf(query)) }.forEach { key -> removeKey(folder, key) }
        }
    }

    /**
     * `removeKeys` returns the number of keys it removed, and that number is not a success signal:
     * `DataStore.kt:146-158` returns `0` both for an empty folder and for a swallowed failure, and
     * `CloudStreamApp.removeKeys` returns `null` when the context has gone. There is nothing to
     * report, which is why the contract's return type is `Unit`.
     */
    override suspend fun clear() {
        withContext(Dispatchers.IO) { removeKeys(historyFolder()) }
    }

    /**
     * The stored entries, unordered, in the old path's exact two steps: `getKeys` yields **full
     * paths**, so each one goes to the single-argument `getKey` — the `(folder, path)` overload
     * would prepend the folder a second time and find nothing.
     *
     * `runCatching` because the seam has no failure channel and
     * `com.lagradost.cloudstream4.feature.search.SearchViewModel.refreshIdle` does not catch: a
     * throw here would take the screen down. Every call inside already swallows its own exceptions
     * (`DataStore.kt:207-215`), so this guards the parse of a value shaped by an older release, not
     * a known throw.
     */
    private fun storedItems(): List<SearchHistoryItem> = runCatching {
        getKeys(historyFolder())?.mapNotNull { path -> getKey<SearchHistoryItem>(path) }.orEmpty()
    }.getOrDefault(emptyList())
}

/**
 * The folder, recomputed per call.
 *
 * `DataStoreHelper.currentAccount` is `selectedKeyIndex.toString()` (`DataStoreHelper.kt:168`) and
 * changes when the user switches profile. Caching it in a field would keep the previous profile's
 * history on screen until the store was rebuilt, and the store outlives a profile switch because
 * the ViewModel does.
 */
private fun historyFolder(): String = "$currentAccount/$SEARCH_HISTORY_KEY"

/**
 * One entry's key within the folder, as `SearchViewModel.kt:212` derives it.
 *
 * This is a **cross-path contract, not an implementation detail**: the old fragment writes a row
 * under this key and removes it under this key, so a "better" derivation here would make rows
 * written by one path unremovable by the other. `DataStoreSearchHistoryTest` pins it for that
 * reason, and the test failing is the intended signal that the two paths have diverged.
 */
internal fun historyKeyOf(query: String): String = query.hashCode().toString()

/** The old path's guard (`SearchViewModel.kt:206`): it does not search or store one character. */
internal fun isRecordable(query: String): Boolean = query.length > 1

/**
 * Stored entries to the screen's rows: the whole of the mapping, and the only part of this file a
 * JVM test can reach.
 *
 * Ordering is `sortedByDescending { it.searchedAt }`, matching `SearchViewModel.kt:88` and the
 * contract's *"Most recent first"*. Kotlin's sort is stable, so entries written in the same
 * millisecond keep the order the store yielded them in rather than swapping between reads.
 *
 * [whenLabel] is a parameter because resolving a timestamp into *"2 days ago"* is Android work (see
 * [relativeWhenLabel]) and the ordering and field mapping are not; splitting them is what makes the
 * second testable at all.
 *
 * `SearchHistoryItem.type` is deliberately dropped: `SearchViewModel.kt:219` writes it as
 * `emptyList()` with `// TODO implement tv type`, so it has never held anything, and
 * `SearchHistoryEntry` has no field for it.
 */
internal fun List<SearchHistoryItem>.toRecentEntries(
    whenLabel: (searchedAt: Long) -> String,
): List<SearchHistoryEntry> =
    sortedByDescending { it.searchedAt }
        .map { item ->
            SearchHistoryEntry(
                // searchText, not key: the key is a hash and would render as a number.
                query = item.searchText,
                whenLabel = whenLabel(item.searchedAt),
            )
        }

/**
 * `searchedAt` as the row's already-resolved *"2 days ago"*.
 *
 * The platform formats it. Writing the English out here is what `I18N-02` is still open about
 * (`AccountManager.secondsToReadable` emits hardcoded `d`/`h`/`m` on the published extension
 * surface), and this codebase has no relative-time helper to reuse: the only other `DateUtils` call
 * in the tree is `PlayerView.kt:814`'s `formatElapsedTime`, which is a duration, not an age.
 *
 * Two choices worth stating:
 *
 * - **`SECOND_IN_MILLIS` resolution, not `MINUTE_IN_MILLIS`.** `minResolution` is the smallest unit
 *   reported, and history's newest row is usually seconds old — the row the user just created by
 *   searching. At minute resolution that row reads *"0 minutes ago"*.
 * - **`flags = 0`, not the three-argument overload.** That overload passes `FORMAT_ABBREV_RELATIVE`,
 *   which gives *"2 days ago"* as an abbreviation; the contract's example and artboard 4c are
 *   unabbreviated.
 *
 * Beyond a week `DateUtils` stops counting and formats an absolute date, which is the right answer
 * for a row that old and is also localized.
 *
 * **Language.** The static overload resolves against `Locale.getDefault()` (read in
 * `sdk/sources/android-36/android/text/format/DateUtils.java`; no older source was to hand), which
 * `CommonActivity.setLocale` sets, so this follows the in-app language.
 */
private fun relativeWhenLabel(searchedAt: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(
        searchedAt,
        now,
        DateUtils.SECOND_IN_MILLIS,
        0,
    ).toString()
