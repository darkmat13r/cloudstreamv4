package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream4.feature.home.HomeBookmark
import com.lagradost.cloudstream4.feature.home.HomeBookmarkStore
import com.lagradost.cloudstream4.feature.home.HomeHistory
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeWatchHistory
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.WatchPosition

/**
 * Home's Continue watching from the Mac's library store (`KMP-55`, `DS-21` H1), as `LibraryHomeHistory` is on Android.
 *
 * The ids are the store's, which are the detail page's, which are a card's (`DesktopSearchSource.encodeId`), so a
 * resume card opens the same way a row's card does, and a title on a row finds its own position by its id.
 */
internal class DesktopHomeHistory(private val store: LibraryStore) : HomeWatchHistory {

    override suspend fun read(): HomeHistory {
        val titles = store.titles().associateBy { it.id }
        val newest = store.positions().groupBy { it.id }.mapValues { (_, positions) -> positions.maxBy { it.watchedAt } }
        val resume = newest.values
            .filter(WatchPosition::resumable)
            .sortedByDescending { it.watchedAt }
            .mapNotNull { position ->
                titles[position.id]?.let { title ->
                    HomeResume(
                        id = title.id.value,
                        title = title.title,
                        posterUrl = title.posterUrl,
                        posterHeaders = title.posterHeaders,
                        progress = position.fraction,
                        sourceName = title.providerName,
                    )
                }
            }
            .take(MAX_RESUME)
        val progress = newest.mapKeys { it.key.value }.mapValues { it.value.fraction }.filterValues { it > 0f }
        return HomeHistory(resume = resume, progress = progress)
    }

    private companion object {
        /** As many as a full window shows in one line, and a few to spare. The library's page has the rest. */
        const val MAX_RESUME = 12
    }
}

/**
 * Home's Bookmarks from the Mac's library store, as `LibraryHomeBookmarks` is on Android. A bucket is a status; Favourites
 * is not one, and has its own list in the library.
 *
 * The chosen status is kept for as long as the window is open. There is no desktop preference store yet to keep it in
 * between launches; until there is, Home opens with none chosen, which 3f draws as a prompt to choose.
 */
internal class DesktopHomeBookmarks(private val store: LibraryStore) : HomeBookmarkStore {

    @Volatile
    private var chosen: HomeWatchStatus? = null

    override suspend fun read(): List<HomeBookmark> = store.titles().mapNotNull { title ->
        val status = title.bucket?.status ?: return@mapNotNull null
        HomeBookmark(
            item = HomeItem(title.id.value, title.title, title.posterUrl, title.posterHeaders),
            status = status,
            updatedAt = title.keptAt,
        )
    }

    override fun chosen(): HomeWatchStatus? = chosen

    override fun choose(status: HomeWatchStatus?) {
        chosen = status
    }

    private val LibraryBucket.status: HomeWatchStatus?
        get() = when (this) {
            LibraryBucket.Watching -> HomeWatchStatus.Watching
            LibraryBucket.PlanToWatch -> HomeWatchStatus.PlanToWatch
            LibraryBucket.OnHold -> HomeWatchStatus.OnHold
            LibraryBucket.Dropped -> HomeWatchStatus.Dropped
            LibraryBucket.Completed -> HomeWatchStatus.Completed
            LibraryBucket.Favourites -> null
        }
}
