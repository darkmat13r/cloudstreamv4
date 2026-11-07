package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailResume
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import java.util.concurrent.ConcurrentHashMap

/**
 * A title's page with what this computer remembers about it (`KMP-58`): where it was left, which list it is on, and
 * its seasons. The provider says what the title is; the library says what the user did with it.
 *
 * The page's id and the library's are the same string (`Main.kt` keeps a title as `LibraryTitleId(id)`), so nothing
 * is translated between them. A library that cannot be read costs only these extras: the page still opens, as it did
 * before there were any.
 *
 * @param clock when a title is put on a list for the first time, for the order the library sorts by.
 */
internal class LibraryDetailSource(
    private val base: DetailSource,
    private val store: LibraryStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : DetailSource by base {

    /** What a title was last opened as, so putting it on a list can keep its name and poster without asking again. */
    private val seen = ConcurrentHashMap<String, TitleDetail>()

    override suspend fun details(id: String): TitleDetail {
        val detail = base.details(id)
        seen[id] = detail
        val key = LibraryTitleId(id)
        val kept = runCatching { store.titles().firstOrNull { it.id == key } }.getOrNull()
        val positions = runCatching { store.positions().filter { it.id == key } }.getOrNull().orEmpty()
        return detail.copy(
            seasons = detail.seasons.ifEmpty { seasonsOf(detail.episodes) },
            resume = detail.resume ?: resumeOf(detail, positions),
            watchStatus = detail.watchStatus ?: kept?.bucket.toWatchStatus(),
        )
    }

    override suspend fun setWatchStatus(id: String, status: DetailWatchStatus) {
        val key = LibraryTitleId(id)
        val bucket = status.toBucket()
        val kept = store.titles().firstOrNull { it.id == key }
        when {
            // Favourites reads as None here, so None must not take a title out of it: that is not what was shown.
            kept != null && bucket == null && kept.bucket == LibraryBucket.Favourites -> Unit
            kept != null -> store.setBucket(key, bucket)
            bucket == null -> Unit
            else -> {
                // First time on a list: the card needs its words and poster, which only the page had.
                val detail = seen[id] ?: return
                store.remember(
                    LibraryTitle(
                        id = key,
                        title = detail.title,
                        bucket = bucket,
                        keptAt = clock(),
                        posterUrl = detail.posterUrl,
                        providerName = detail.providerName,
                    ),
                )
            }
        }
    }

    internal companion object {
        /**
         * The newest position worth coming back to, as the page's Resume.
         *
         * An episode's position counts only while that episode is still in the provider's list: an episode whose
         * handle changed is one Resume could not play. A title that plays itself uses its own data.
         */
        fun resumeOf(detail: TitleDetail, positions: List<WatchPosition>): DetailResume? {
            val latest = positions.filter { it.resumable }.maxByOrNull { it.watchedAt } ?: return null
            val episode = latest.episodeData?.let { data -> detail.episodes.firstOrNull { it.data == data } }
            val data = when {
                latest.episodeData == null -> detail.playData
                episode != null -> episode.data
                else -> null
            } ?: return null
            return DetailResume(data, latest.positionMillis, latest.durationMillis, episode)
        }

        /** The seasons the episodes name, in order, each with how many it holds. An episode with none is in none. */
        fun seasonsOf(episodes: List<DetailEpisode>): List<DetailSeason> =
            episodes.mapNotNull { it.season }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
                .map { (number, count) -> DetailSeason(number, count) }

        fun LibraryBucket?.toWatchStatus(): DetailWatchStatus = when (this) {
            LibraryBucket.Watching -> DetailWatchStatus.Watching
            LibraryBucket.Completed -> DetailWatchStatus.Completed
            LibraryBucket.OnHold -> DetailWatchStatus.OnHold
            LibraryBucket.Dropped -> DetailWatchStatus.Dropped
            LibraryBucket.PlanToWatch -> DetailWatchStatus.PlanToWatch
            // Not a watch status: the picker has no row for it, so the page says it is on none of them.
            LibraryBucket.Favourites, null -> DetailWatchStatus.None
        }

        fun DetailWatchStatus.toBucket(): LibraryBucket? = when (this) {
            DetailWatchStatus.Watching -> LibraryBucket.Watching
            DetailWatchStatus.Completed -> LibraryBucket.Completed
            DetailWatchStatus.OnHold -> LibraryBucket.OnHold
            DetailWatchStatus.Dropped -> LibraryBucket.Dropped
            DetailWatchStatus.PlanToWatch -> LibraryBucket.PlanToWatch
            DetailWatchStatus.None -> null
        }
    }
}
