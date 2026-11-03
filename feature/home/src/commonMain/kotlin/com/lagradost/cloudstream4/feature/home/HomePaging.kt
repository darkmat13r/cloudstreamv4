package com.lagradost.cloudstream4.feature.home

/**
 * The fold from replies to rows. Pure, and every limit in it is there because the input is a plugin's.
 */
internal object HomePaging {
    const val FIRST_PAGE = 1
    const val LINES_PER_PRESS = 2

    /** A source that claims a next page forever must stop growing this process. */
    const val MAX_ROW_ITEMS = 500
    const val MAX_ROWS = 64

    private const val MAX_DETAIL_REASONS = 3
    private const val DETAIL_SEPARATOR = " · "
    const val OFFLINE_DETAIL = "OFFLINE"

    /**
     * Adds one page of [section] to [rows].
     *
     * A page that adds nothing ends the section whatever it claims: without this, a source repeating
     * itself with `hasNext = true` would keep a button alive that can never do anything.
     */
    fun merge(rows: List<HomeRow>, section: Int, page: Int, reply: HomePageReply.Loaded): List<HomeRow> {
        var result = rows
        var added = 0
        reply.rows.forEach { incoming ->
            val key = HomeRowKey(section, incoming.title)
            val existing = result.firstOrNull { it.key == key }
            if (existing != null) {
                val items = (existing.items + incoming.items).distinctBy { it.id }.take(MAX_ROW_ITEMS)
                added += items.size - existing.items.size
                result = result.map { if (it.key == key) it.copy(items = items) else it }
            } else if (incoming.items.isNotEmpty() && result.size < MAX_ROWS) {
                val items = incoming.items.distinctBy { it.id }.take(MAX_ROW_ITEMS)
                added += items.size
                val row = HomeRow(key, incoming.landscape, items)
                // In section order, so a section that answers on a retry lands where it belongs.
                val at = result.indexOfLast { it.key.section <= section } + 1
                result = result.take(at) + row + result.drop(at)
            }
        }
        val hasMore = reply.hasNext && added > 0
        return result.map { row ->
            if (row.key.section != section) {
                row
            } else {
                row.copy(page = page, hasMore = hasMore && row.items.size < MAX_ROW_ITEMS, more = HomeMore.Idle)
            }
        }
    }

    /**
     * What a round of first pages adds up to.
     *
     * @param previous what is already on screen, on a retry of a partial page.
     * @param replies by section index.
     * @param total how many sections the source has, which on a retry is more than were asked.
     */
    fun fold(previous: List<HomeRow>, replies: List<Pair<Int, HomePageReply>>, total: Int): HomeStatus {
        var rows = previous
        replies.forEach { (section, reply) ->
            if (reply is HomePageReply.Loaded) rows = merge(rows, section, FIRST_PAGE, reply)
        }
        val failed = replies.filter { it.second !is HomePageReply.Loaded }
        return when {
            failed.isEmpty() -> if (rows.isEmpty()) HomeStatus.Empty else HomeStatus.Showing(rows)
            rows.isNotEmpty() -> HomeStatus.Partial(rows, failed.map { it.first }, total, failed.detail())
            failed.size == replies.size && failed.all { it.second is HomePageReply.Offline } -> HomeStatus.Offline
            else -> HomeStatus.Failed(failed.detail())
        }
    }

    /** Distinct reasons: three sections failing alike and three failing apart are different problems. */
    private fun List<Pair<Int, HomePageReply>>.detail(): String =
        map { (_, reply) -> (reply as? HomePageReply.Failed)?.detail ?: OFFLINE_DETAIL }
            .distinct()
            .take(MAX_DETAIL_REASONS)
            .joinToString(DETAIL_SEPARATOR)
}

/** The rows on screen, whichever state is holding them. */
internal val HomeStatus.rows: List<HomeRow>
    get() = when (this) {
        is HomeStatus.Showing -> rows
        is HomeStatus.Partial -> rows
        else -> emptyList()
    }

/** The same state, holding different rows. Anything without rows has nothing to change. */
internal fun HomeStatus.withRows(next: List<HomeRow>): HomeStatus = when (this) {
    is HomeStatus.Showing -> copy(rows = next)
    is HomeStatus.Partial -> copy(rows = next)
    else -> this
}
