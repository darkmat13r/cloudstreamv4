package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream4.feature.home.HomeHistory
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeWatchHistory
import com.lagradost.cloudstream4.library.AndroidLibraryStore

/**
 * Home's Continue watching and card progress, read through the library's own store (`KMP-34`), so Home and the library read
 * the same records the same way and there is no second store.
 *
 * - **Continue watching** is [AndroidLibraryStore.resuming]: the old home's list, newest first.
 * - **Progress** is every position the store knows, bookmarked titles and resume records alike, newest per title. Zero is
 *   left out: a rule at nothing says nothing on a card that is not on Continue watching.
 *
 * The ids are the store's, `encodeId(apiName, url)`, which is also what a card's id is, so a title on a rail finds its own
 * position by its id.
 */
internal class LibraryHomeHistory(private val store: AndroidLibraryStore) : HomeWatchHistory {

    override suspend fun read(): HomeHistory {
        val resuming = store.resuming()
        val progress = (store.positions() + resuming.map { it.position })
            .groupBy { it.id.value }
            .mapValues { (_, positions) -> positions.maxBy { it.watchedAt }.fraction }
            .filterValues { it > 0f }
        return HomeHistory(
            resume = resuming.map { entry ->
                HomeResume(
                    id = entry.title.id.value,
                    title = entry.title.title,
                    posterUrl = entry.title.posterUrl,
                    posterHeaders = entry.title.posterHeaders,
                    progress = entry.position.fraction,
                    season = entry.season,
                    episode = entry.episode,
                    sourceName = entry.title.providerName,
                )
            },
            progress = progress,
        )
    }
}
