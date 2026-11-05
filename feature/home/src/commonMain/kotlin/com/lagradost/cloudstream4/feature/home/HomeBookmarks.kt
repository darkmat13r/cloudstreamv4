package com.lagradost.cloudstream4.feature.home

import androidx.compose.runtime.Immutable

/**
 * The watch statuses a bookmark can have, in the order `TV Home Extras` 3e draws its chips: the old home's chip order.
 *
 * "In no list" is not one: a title with no status is not a bookmark.
 */
enum class HomeWatchStatus { Watching, PlanToWatch, OnHold, Dropped, Completed }

/**
 * One bookmarked title (3e). Local, so it is there with no source and no network.
 *
 * @property item what its card draws. [HomeItem.id] is the same opaque id as a rail card's, so opening it is the same flow,
 *   and it opens from its own source, whichever source the page shows.
 * @property updatedAt when the title's stored record last changed, which is the old rail's order, newest first.
 */
@Immutable
data class HomeBookmark(
    val item: HomeItem,
    val status: HomeWatchStatus,
    val updatedAt: Long = 0L,
)

/**
 * The bookmarks rail and its filter, as read from the device.
 *
 * @property all every bookmark, whatever its status. Empty is "no bookmarks at all", and the rail is absent (3e's note:
 *   `HomeViewModel.kt:186-190` in `:app` hides it).
 * @property chosen the status the filter shows, or null for none. One at a time, as the old chips allow; pressing the chosen
 *   one clears it (3f).
 */
@Immutable
data class HomeBookmarks(
    val all: List<HomeBookmark> = emptyList(),
    val chosen: HomeWatchStatus? = null,
) {
    /** The statuses that hold a title, in chip order. Only these get a chip. */
    val statuses: List<HomeWatchStatus> get() = HomeWatchStatus.entries.filter { status -> all.any { it.status == status } }

    /** The chosen status, if it still holds a title. One that no longer does is shown as no choice: its chip is gone. */
    val applied: HomeWatchStatus? get() = chosen?.takeIf { it in statuses }

    /** What the rail shows: the applied status's titles, newest first. Empty when no status is applied. */
    val shown: List<HomeItem>
        get() = applied?.let { status ->
            all.asSequence().filter { it.status == status }.sortedByDescending { it.updatedAt }.map { it.item }
                .distinctBy { it.id }.toList()
        }.orEmpty()
}

/**
 * Where bookmarks come from, and where the filter is kept between launches. A host reads the store its library already
 * reads, rather than keeping a second one, and the filter in the key its old home used, so the two agree for the release the
 * old one is kept.
 *
 * [read] reaches storage, so it may throw; the screen keeps what it had, and a first read that fails leaves the rail absent,
 * as the old one reported nothing.
 */
interface HomeBookmarkStore {
    suspend fun read(): List<HomeBookmark>

    /** The status chosen last, or null for none or never. */
    fun chosen(): HomeWatchStatus?

    fun choose(status: HomeWatchStatus?)

    /** Keeps nothing, for a host with no bookmarks to show. */
    object None : HomeBookmarkStore {
        override suspend fun read(): List<HomeBookmark> = emptyList()

        override fun chosen(): HomeWatchStatus? = null

        override fun choose(status: HomeWatchStatus?) = Unit
    }
}
