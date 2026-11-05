package com.lagradost.cloudstream4.feature.library

/**
 * What the screen shows, worked out from what the store holds. Pure, so these are the tests that reach
 * iOS — the ViewModel's need a dispatcher and stop at the JVM.
 */
internal object LibraryShelf {

    /**
     * The newest position per title, so a series that was watched across four episodes is one card.
     *
     * Ties go to the one later in the list rather than being an error: two positions written in the same
     * millisecond is a real thing on a fast disk, and either answer is as good as the other.
     */
    fun latestByTitle(positions: List<WatchPosition>): Map<LibraryTitleId, WatchPosition> {
        val newest = LinkedHashMap<LibraryTitleId, WatchPosition>()
        for (position in positions) {
            val held = newest[position.id]
            if (held == null || position.watchedAt >= held.watchedAt) newest[position.id] = position
        }
        return newest
    }

    /**
     * The cards for [tab].
     *
     * **Continue watching** is every resumable position, newest first, capped at [MAX_CONTINUE]. A
     * position whose title the store does not know is dropped: there is no name and no poster to draw it
     * with, and asking a provider for one would mean a network call to render a list.
     *
     * **A bucket** is its titles, newest kept first, each carrying the progress of its newest position
     * whether or not that position is resumable — a finished thing in "Watching" should look finished.
     *
     * @param filter keeps titles containing this text, ignoring case. Blank keeps everything.
     */
    fun cards(
        tab: LibraryTab,
        titles: List<LibraryTitle>,
        positions: List<WatchPosition>,
        filter: String = "",
    ): List<LibraryCard> {
        val newest = latestByTitle(positions)
        val needle = filter.trim()
        val cards = when (tab) {
            LibraryTab.ContinueWatching -> {
                val known = titles.associateBy { it.id }
                newest.values
                    .filter { it.resumable }
                    .sortedByDescending { it.watchedAt }
                    .mapNotNull { position -> known[position.id]?.let { it.toCard(position) } }
                    .take(MAX_CONTINUE)
            }

            is LibraryTab.Bucket -> titles
                .filter { it.bucket == tab.bucket }
                .sortedByDescending { it.keptAt }
                .map { it.toCard(newest[it.id]) }
        }
        return if (needle.isEmpty()) cards else cards.filter { it.title.contains(needle, ignoreCase = true) }
    }

    /** How many rows each list holds. A list with nothing in it has no entry. */
    fun counts(titles: List<LibraryTitle>): Map<LibraryBucket, Int> =
        titles.mapNotNull { it.bucket }.groupingBy { it }.eachCount()

    /** Different titles in any list: one kept in two lists is one title. */
    fun totalTitles(titles: List<LibraryTitle>): Int =
        titles.filter { it.bucket != null }.distinctBy { it.id }.size

    private fun LibraryTitle.toCard(position: WatchPosition?) = LibraryCard(
        id = id,
        title = title,
        posterUrl = posterUrl,
        providerName = providerName,
        bucket = bucket,
        // Zero is not progress, and a full bar on a card you can no longer resume is a lie worth avoiding.
        progress = position?.fraction?.takeIf { it > 0f },
        resume = position
            ?.takeIf { it.resumable }
            ?.let { LibraryResume(it.episodeData, it.positionMillis) },
        typeLabel = typeLabel,
        posterHeaders = posterHeaders,
    )

    /** Rows with nothing to say: in no list, and with no position worth resuming. */
    fun forgettable(titles: List<LibraryTitle>, positions: List<WatchPosition>): List<LibraryTitleId> {
        val withPositions = positions.map { it.id }.toSet()
        return titles.filter { it.bucket == null && it.id !in withPositions }.map { it.id }
    }

    /**
     * Enough that nobody reaches the end of it, and small enough that the file stays a file. `:app` caps
     * this at nothing at all, which is why its own resume row depends on a cache the downloader prunes.
     */
    const val MAX_CONTINUE = 60
}
