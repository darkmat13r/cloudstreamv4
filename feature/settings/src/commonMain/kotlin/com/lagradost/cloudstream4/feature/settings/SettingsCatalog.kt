package com.lagradost.cloudstream4.feature.settings

/**
 * The panes the window shows, in `DS-24`'s order, and the rows each holds today.
 *
 * A pane is listed only once it has a row that works (`KMP-90`): an empty pane is never in the sidebar, so General,
 * Appearance and the other six arrive with their first row. [SettingsRow.entries] is everything search can find.
 */
enum class SettingsPane(val rows: List<SettingsRow>) {
    Playback(listOf(SettingsRow.PlayNextEpisode)),
    Downloads(listOf(SettingsRow.DownloadFolder, SettingsRow.DownloadsAtOnce)),
}

/** One row of a pane, as search sees it. The words are resolved where they are drawn, then handed to [SettingsSearch]. */
enum class SettingsRow {
    PlayNextEpisode,
    DownloadFolder,
    DownloadsAtOnce,
}

/** What a search leaves in the sidebar. Pure, so it is tested without a window. */
object SettingsSearch {
    /**
     * The panes holding a row whose words contain [query], in pane order, ignoring case and surrounding space.
     * An empty query keeps every pane.
     *
     * @param words each row's visible words in the current language: its title and the line under it.
     */
    fun panes(query: String, words: (SettingsRow) -> List<String>): List<SettingsPane> {
        val needle = query.trim()
        if (needle.isEmpty()) return SettingsPane.entries
        return SettingsPane.entries.filter { pane ->
            pane.rows.any { row -> words(row).any { it.contains(needle, ignoreCase = true) } }
        }
    }

    /** The rows in [pane] that [query] matches, which the pane marks. Empty for an empty query. */
    fun rows(pane: SettingsPane, query: String, words: (SettingsRow) -> List<String>): Set<SettingsRow> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptySet()
        return pane.rows.filterTo(mutableSetOf()) { row -> words(row).any { it.contains(needle, ignoreCase = true) } }
    }
}
