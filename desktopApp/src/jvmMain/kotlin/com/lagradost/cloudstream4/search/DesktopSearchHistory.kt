package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchHistoryStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop's search history: a tab-separated file under the user's home directory.
 *
 * ## Why not `:core:storage`
 *
 * `:core:storage` has JVM implementations and they are **unused** — `BUG-05` and `DEC-12` both
 * record that, and `DEC-12` says to revisit "when something first consumes `:core:storage`". This
 * is a candidate to be that something, and it should be, but adopting it is a change to a module
 * two other decisions are parked on. A twenty-line file store gets desktop working now without
 * pre-empting that; swapping it for `:core:storage` later touches only this class.
 *
 * ## Why not the Android format
 *
 * `:app` stores each entry as a JSON blob under `"<account>/search_history"` keyed by
 * `query.hashCode()`, through `CloudStreamApp`'s key store, which is `SharedPreferences`. None of
 * that exists on desktop. The two stores are therefore **not shared** — a search on the phone does
 * not appear on the desktop — which is correct for now and is a sync question, not a storage one.
 *
 * @param file where the history lives. Defaults to `~/.cloudstream4/search-history.tsv`.
 */
class DesktopSearchHistory(
    private val file: File = defaultFile(),
) : SearchHistoryStore {

    override suspend fun recent(): List<SearchHistoryEntry> = withContext(Dispatchers.IO) {
        read().sortedByDescending { it.searchedAt }.map { it.toEntry() }
    }

    override suspend fun remove(query: String) = withContext(Dispatchers.IO) {
        write(read().filterNot { it.query == query })
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
        Unit
    }

    /** Records a search. Not part of the contract — the host calls it when a search is submitted. */
    suspend fun record(query: String, atEpochMillis: Long) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext
        write(listOf(Row(trimmed, atEpochMillis)) + read().filterNot { it.query == trimmed })
    }

    private fun read(): List<Row> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        file.readLines().mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val query = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Row(query, at)
        }
    }.getOrDefault(emptyList())

    private fun write(rows: List<Row>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                rows.take(MAX_ENTRIES).joinToString("\n") { "${it.searchedAt}$SEPARATOR${it.query}" },
            )
        }
    }

    private data class Row(val query: String, val searchedAt: Long)

    /**
     * [SearchHistoryEntry.whenLabel] is an already-resolved display string, and **desktop resolves
     * it differently from Android** — deliberately.
     *
     * Android has `DateUtils.getRelativeTimeSpanString`, which gives "2 days ago" in the device
     * locale. The JVM has no equivalent in the standard library: `java.time` formats absolute
     * instants, and producing "2 days ago" would mean either hand-writing English (forbidden —
     * `I18N-02` exists because someone did) or shipping a plural string set per unit. So desktop
     * shows a **localised absolute date** instead.
     *
     * That is a real divergence between platforms for the same data, in the same family as the one
     * `I18N-06` measured for number symbols. It is recorded rather than hidden; the fix is a
     * shared relative-time formatter in `:core:strings`, which is `I18N-01`-shaped work.
     */
    private fun Row.toEntry() = SearchHistoryEntry(
        query = query,
        whenLabel = runCatching {
            DATE_FORMAT.format(Instant.ofEpochMilli(searchedAt).atZone(ZoneId.systemDefault()))
        }.getOrDefault(""),
    )

    companion object {
        private const val SEPARATOR = "\t"

        /** Enough to fill artboard 4c's list several times over; the file is read whole. */
        private const val MAX_ENTRIES = 100

        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

        private fun defaultFile() =
            File(System.getProperty("user.home"), ".cloudstream4/search-history.tsv")
    }
}
