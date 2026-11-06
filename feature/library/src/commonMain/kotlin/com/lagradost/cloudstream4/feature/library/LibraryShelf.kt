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
     * @param sort orders a bucket's titles. Continue watching is always newest watched first: it is what to resume.
     */
    fun cards(
        tab: LibraryTab,
        titles: List<LibraryTitle>,
        positions: List<WatchPosition>,
        filter: String = "",
        sort: LibrarySort = LibrarySort.Default,
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
                .sortedBy(sort)
                .map { it.toCard(newest[it.id]) }

            // A service's list is not a bucket of the store's: see [syncedCards].
            is LibraryTab.Synced -> emptyList()
        }
        return cards.narrowedTo(needle)
    }

    /**
     * The cards for one of a tracking service's lists, newest kept first as a bucket's are.
     *
     * No position is joined: the service's titles are not the store's, and a position under the same id would be
     * a coincidence. The bar is the service's own count, episodes seen over episodes in all, where it has both.
     */
    fun syncedCards(list: SyncedList, filter: String = "", sort: LibrarySort = LibrarySort.Default): List<LibraryCard> =
        list.titles
            .sortedBy(sort)
            .distinctBy { it.id }
            .map { it.toCard(position = null) }
            .narrowedTo(filter.trim())

    /** Different titles across a service's lists: one in two lists is one title. */
    fun syncedTotal(lists: List<SyncedList>): Int = lists.flatMap { it.titles }.distinctBy { it.id }.size

    /**
     * [sort] over titles newest kept first, so titles that tie keep that order. A title with nothing to sort by, no year
     * or no rating, goes last whichever way round.
     */
    internal fun List<LibraryTitle>.sortedBy(sort: LibrarySort): List<LibraryTitle> {
        val newest = sortedByDescending { it.keptAt }
        fun <T : Comparable<T>> by(key: (LibraryTitle) -> T?): Comparator<LibraryTitle> =
            compareBy(nullsLast(if (sort.descending) reverseOrder() else naturalOrder()), key)
        return when (sort.criterion) {
            LibrarySortCriterion.Updated -> if (sort.descending) newest else newest.reversed()
            LibrarySortCriterion.Title -> {
                val names = compareBy(String.CASE_INSENSITIVE_ORDER) { title: LibraryTitle -> title.title }
                newest.sortedWith(if (sort.descending) names.reversed() else names)
            }
            LibrarySortCriterion.Released -> newest.sortedWith(by { it.releaseYear })
            LibrarySortCriterion.Rating -> newest.sortedWith(by { it.ratingValue })
        }
    }

    private fun List<LibraryCard>.narrowedTo(needle: String) =
        if (needle.isEmpty()) this else filter { it.title.contains(needle, ignoreCase = true) }

    /** Episodes seen over episodes in all, where both are known and it is more than nothing. */
    private fun LibraryTitle.episodeProgress(): Float? {
        val total = episodesTotal?.takeIf { it > 0 } ?: return null
        val watched = episodesWatched ?: return null
        return (watched.toFloat() / total).coerceIn(0f, 1f).takeIf { it > 0f }
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
        progress = position?.fraction?.takeIf { it > 0f } ?: episodeProgress(),
        resume = position
            ?.takeIf { it.resumable }
            ?.let { LibraryResume(it.episodeData, it.positionMillis) },
        typeLabel = typeLabel,
        posterHeaders = posterHeaders,
        episodesWatched = episodesWatched,
        episodesTotal = episodesTotal?.takeIf { it > 0 },
        rating = rating,
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
