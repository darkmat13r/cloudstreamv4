package com.lagradost.cloudstream4.home

import android.content.Context
import android.content.res.Configuration
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.settings.SettingsUIScreen
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.feature.home.HomeBookmark
import com.lagradost.cloudstream4.feature.home.HomeBookmarkStore
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.library.AndroidLibraryStore
import java.util.Locale

/**
 * Home's bookmarks rail (`TV Home Extras` 3e), read through the library's own store (`KMP-34`), so Home and the library read
 * the same records the same way and there is no second store.
 *
 * **The filter is the old home's key,** `DataStoreHelper.homeBookmarkedList`, per account, so the old screen kept as the
 * rollback and this one agree on what was chosen. It holds `WatchType` ids; the old chips only ever write one, and an empty
 * one is "none chosen", which is also what a key never written reads as: the old home opens with no chip chosen too.
 *
 * Everything here came from a plugin when the title was bookmarked: the store cleans its text, headers and poster addresses,
 * and a title with nothing left of its name, or an address too long to open, is dropped here.
 *
 * @param qualityWords the badge's words for a quality, in the app's language. Called per read, because a held `Context`
 *   keeps the language it was made in.
 */
internal class LibraryHomeBookmarks(
    private val store: AndroidLibraryStore,
    private val qualityWords: () -> (SearchQuality) -> String?,
    private val readFilter: () -> IntArray = { DataStoreHelper.homeBookmarkedList },
    private val writeFilter: (IntArray) -> Unit = { DataStoreHelper.homeBookmarkedList = it },
) : HomeBookmarkStore {

    constructor(context: Context) : this(AndroidLibraryStore(context), { qualityLabels(context) })

    override suspend fun read(): List<HomeBookmark> {
        val words = qualityWords()
        return store.bookmarked().mapNotNull { (title, quality) ->
            val status = title.bucket?.toStatus() ?: return@mapNotNull null
            if (title.title.isEmpty() || title.id.value.length > MAX_ID_CHARS) return@mapNotNull null
            HomeBookmark(
                item = HomeItem(
                    id = title.id.value,
                    title = title.title,
                    posterUrl = title.posterUrl,
                    posterHeaders = title.posterHeaders,
                    badge = quality?.let(words)?.take(MAX_BADGE_CHARS)?.ifEmpty { null },
                ),
                status = status,
                updatedAt = title.keptAt,
            )
        }
    }

    override fun chosen(): HomeWatchStatus? =
        readFilter().asSequence().map { WatchType.fromInternalId(it) }.firstNotNullOfOrNull { it.toStatus() }

    override fun choose(status: HomeWatchStatus?) {
        writeFilter(status?.let { intArrayOf(it.toWatchType().internalId) } ?: intArrayOf())
    }

    private companion object {
        const val MAX_BADGE_CHARS = 16

        /** What `AndroidHomeSource` lets through to open a title: its address cap, plus the provider's name. */
        const val MAX_ID_CHARS = 4096 + 200 + 16
    }
}

private fun LibraryBucket.toStatus(): HomeWatchStatus? = when (this) {
    LibraryBucket.Watching -> HomeWatchStatus.Watching
    LibraryBucket.PlanToWatch -> HomeWatchStatus.PlanToWatch
    LibraryBucket.OnHold -> HomeWatchStatus.OnHold
    LibraryBucket.Dropped -> HomeWatchStatus.Dropped
    LibraryBucket.Completed -> HomeWatchStatus.Completed
    LibraryBucket.Favourites -> null
}

private fun WatchType.toStatus(): HomeWatchStatus? = when (this) {
    WatchType.WATCHING -> HomeWatchStatus.Watching
    WatchType.PLANTOWATCH -> HomeWatchStatus.PlanToWatch
    WatchType.ONHOLD -> HomeWatchStatus.OnHold
    WatchType.DROPPED -> HomeWatchStatus.Dropped
    WatchType.COMPLETED -> HomeWatchStatus.Completed
    WatchType.NONE -> null
}

private fun HomeWatchStatus.toWatchType(): WatchType = when (this) {
    HomeWatchStatus.Watching -> WatchType.WATCHING
    HomeWatchStatus.PlanToWatch -> WatchType.PLANTOWATCH
    HomeWatchStatus.OnHold -> WatchType.ONHOLD
    HomeWatchStatus.Dropped -> WatchType.DROPPED
    HomeWatchStatus.Completed -> WatchType.COMPLETED
}

/** A quality's words, as a rail card's badge has them (`posterLabels` in `AndroidHomeSource`), in the in-app language. */
private fun qualityLabels(context: Context): (SearchQuality) -> String? {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.getDefault())
    val labels = context.createConfigurationContext(configuration)
    return { quality -> labels.getString(with(SettingsUIScreen) { quality.toStringRes() }) }
}
