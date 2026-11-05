package com.lagradost.cloudstream4.library

import android.content.Context
import android.content.res.Configuration
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.search.SearchResultTarget
import com.lagradost.cloudstream4.search.decodeId
import com.lagradost.cloudstream4.search.encodeId
import com.lagradost.cloudstream4.feature.search.SearchResultId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** The keys the local library is stored under, one seam wide so the mapping can be tested off a device. */
internal interface LocalLibraryRecords {
    /** Every title given a watch status, by the `Int` id it is stored under. */
    fun watchStates(): List<Pair<Int, WatchType>>
    fun bookmark(id: Int): DataStoreHelper.BookmarkedData?
    fun favourites(): List<DataStoreHelper.FavoritesData>
    fun lastWatched(id: Int): DownloadObjects.ResumeWatching?
    fun viewPosition(id: Int): DataStoreHelper.PosDur?

    /** Every title with a resume record, by the `Int` id it is stored under. Empty where a host keeps none. */
    fun resumeIds(): List<Int> = emptyList()

    /** What a resume record's title looks like, which the record itself does not carry. */
    fun resumeHeader(parentId: Int): DownloadObjects.DownloadHeaderCached? = null
}

/** What `LocalList.library()` reads, for the current account. Read again on every call. */
internal object DataStoreLocalLibrary : LocalLibraryRecords {
    override fun watchStates() = DataStoreHelper.getAllWatchStateIds().orEmpty()
        .distinct()
        .map { it to DataStoreHelper.getResultWatchState(it) }

    override fun bookmark(id: Int) = DataStoreHelper.getBookmarkedData(id)
    override fun favourites() = DataStoreHelper.getAllFavorites()
    override fun lastWatched(id: Int) = DataStoreHelper.getLastWatched(id)

    /** Snapped to 0 and full as the old cards do, so the two screens draw the same rule. */
    override fun viewPosition(id: Int) = DataStoreHelper.getViewPos(id)?.fixVisual()

    override fun resumeIds() = DataStoreHelper.getAllResumeStateIds().orEmpty()

    /**
     * Where `HomeViewModel.getResumeWatching` looks, the backup included. That one also writes the backup back into the
     * cache, which a screen that only reads leaves to the old home: the result is the same either way.
     */
    override fun resumeHeader(parentId: Int) =
        getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, parentId.toString())
            ?: getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE_BACKUP, parentId.toString())
}

/**
 * A title on Continue watching, as the old home lists it: the title, where it resumes, and the part it resumes from.
 *
 * @property position zero long and at zero where the part to resume has never played, which is what a series looks like
 *   once it has moved on to its next episode.
 */
internal data class Resuming(
    val title: LibraryTitle,
    val position: WatchPosition,
    val season: Int?,
    val episode: Int?,
)

/**
 * [LibraryStore] over what `:app` really keeps, so a TV user's existing lists appear.
 *
 * ## What it reads, and why not `SyncAPI`
 *
 * Android's library screen is `SyncAPI`, and the local list behind it is `LocalList`: watch status in
 * `RESULT_WATCH_STATE`, each title's fields in `RESULT_WATCH_STATE_DATA`, favourites in their own
 * `RESULT_FAVORITES_STATE_DATA`, all under the current account. This reads those keys through the same
 * `DataStoreHelper` calls and leaves subscriptions out for the reason `LocalList` does on a true TV.
 *
 * Going through `LocalList.library()` instead would hand back lists named by a `UiText`, to be matched back
 * to buckets, and items with nothing in the fields a card shows that the store does not already have. A
 * remote provider's lists (5e) are a different shape and arrive with that panel. `SyncAPI` is unchanged.
 *
 * ## Facts, and the ones it does not have
 *
 * Local bookmarks hold no episode count and no rating of the user's own: `BookmarkedData.toLibraryItem`
 * passes null for `episodesCompleted`, `episodesTotal` and `personalRating`. A [WatchPosition] is the last
 * played part's real position, from the same keys `HomeViewModel.getResumeWatching` reads. A title with no
 * resume record falls back to a position under its own id, which is where a film's lives.
 *
 * ## Writes refuse
 *
 * The contract reports failure by throwing, and the ViewModel treats a refused write as a change that did
 * not happen. Nothing on Android calls one: the screen has no controls that write (5a and 5c draw none), a
 * title's list is changed on its result page, and the player keeps its own positions. Writing through here
 * would be a second path into `DataStoreHelper` with no caller to test it. The first screen that needs one
 * implements it, as `AndroidExtensionRuntime.install` does.
 *
 * @param labels gives the words for a type. Called per read, for the same reason as `labelContext` in
 *   `ApiRepositorySearchSource`: a held `Context` keeps the language it was made in.
 */
internal class AndroidLibraryStore(
    private val records: LocalLibraryRecords,
    private val labels: () -> (TvType) -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibraryStore {

    constructor(context: Context) : this(DataStoreLocalLibrary, { typeWords(context) })

    override suspend fun titles(): List<LibraryTitle> = withContext(io) {
        val words = labels()
        rows().map { row -> row.toTitle(words) }
            // Two ids can hold one title, when a provider's id scheme changed. A grid needs one key per card.
            .sortedByDescending { it.keptAt }
            .distinctBy { it.id to it.bucket }
    }

    override suspend fun positions(): List<WatchPosition> = withContext(io) {
        rows()
            .distinctBy { it.storedId }
            .mapNotNull { row -> positionOf(row) }
    }

    /**
     * Continue watching, as the old home lists it (`HomeViewModel.getResumeWatching`): every resume record whose title is
     * known, newest first, each with its own part's position.
     *
     * Not `LibraryShelf`'s rule, which keeps only a position past 30 s and short of 95%. On Android the resume record is
     * what decides: the player removes it when a title is finished and moves it to the next episode at 90%, so a record at
     * zero is the next episode waiting, and it is listed.
     *
     * The titles are not in [titles], so the library's lists are what they were.
     */
    suspend fun resuming(): List<Resuming> = withContext(io) {
        records.resumeIds()
            .mapNotNull { records.lastWatched(it) }
            .sortedByDescending { it.updateTime }
            .mapNotNull { resume -> resumingOf(resume) }
            .distinctBy { it.title.id }
            .take(MAX_RESUMING)
    }

    private fun resumingOf(resume: DownloadObjects.ResumeWatching): Resuming? {
        val header = records.resumeHeader(resume.parentId) ?: return null
        val title = header.name.cleanText().ifEmpty { return null }
        if (header.url.isBlank() || header.apiName.isBlank()) return null
        val id = LibraryTitleId(encodeId(header.apiName, header.url).value)
        val position = records.viewPosition(resume.episodeId ?: resume.parentId)
        return Resuming(
            title = LibraryTitle(
                id = id,
                title = title,
                bucket = null,
                keptAt = resume.updateTime,
                posterUrl = header.poster?.takeIf { it.isPlainHttp() },
                providerName = header.apiName.cleanText().ifEmpty { null },
            ),
            position = WatchPosition(
                id = id,
                episodeData = resume.episodeId?.toString(),
                positionMillis = position?.position ?: 0L,
                durationMillis = position?.duration ?: 0L,
                watchedAt = resume.updateTime,
            ),
            season = resume.season,
            episode = resume.episode,
        )
    }

    private fun rows(): List<Row> {
        val watching = records.watchStates().mapNotNull { (id, type) ->
            val bucket = type.toBucket() ?: return@mapNotNull null
            records.bookmark(id)?.let { Row(id, bucket, it) }
        }
        val favourites = records.favourites().mapNotNull { favourite ->
            favourite.id?.let { Row(it, LibraryBucket.Favourites, favourite) }
        }
        return watching + favourites
    }

    private fun positionOf(row: Row): WatchPosition? {
        val resume = records.lastWatched(row.storedId)
        val position = records.viewPosition(resume?.episodeId ?: row.storedId) ?: return null
        return WatchPosition(
            id = row.record.titleId(),
            episodeData = resume?.episodeId?.toString(),
            positionMillis = position.position,
            durationMillis = position.duration,
            watchedAt = resume?.updateTime ?: 0L,
        )
    }

    override suspend fun remember(title: LibraryTitle) = refuse()
    override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = refuse()
    override suspend fun record(position: WatchPosition) = refuse()
    override suspend fun clearPositions(id: LibraryTitleId) = refuse()

    private fun refuse(): Nothing = throw UnsupportedOperationException("The Android library is read-only here")

    private data class Row(
        val storedId: Int,
        val bucket: LibraryBucket,
        val record: DataStoreHelper.LibrarySearchResponse,
    ) {
        /**
         * Everything here came from a plugin, so it is sanitised on the way out, as desktop's store does. The
         * id is left alone: it is opaque and reopens the title, so it has to stay exactly what was stored.
         */
        fun toTitle(words: (TvType) -> String) = LibraryTitle(
            id = record.titleId(),
            title = record.name.cleanText(),
            bucket = bucket,
            // `latestUpdatedTime`, as the old screen's "Updated" sort, which is 5a's default order.
            keptAt = record.latestUpdatedTime,
            posterUrl = record.posterUrl?.takeIf { it.isPlainHttp() },
            providerName = record.apiName.cleanText().ifEmpty { null },
            typeLabel = record.type?.let { words(it).cleanText().uppercase(Locale.getDefault()) },
            posterHeaders = record.posterHeaders
                ?.filter { (key, value) -> key.isHeaderSafe() && value.isHeaderSafe() }
                ?.ifEmpty { null },
        )
    }

    private companion object {
        const val MAX_TEXT_CHARS = 200

        /** Mirrors `LibraryShelf.MAX_CONTINUE`. The old home lists every record, with no cap. */
        const val MAX_RESUMING = 60

        fun String.cleanText(): String =
            filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(MAX_TEXT_CHARS)

        fun String.isPlainHttp(): Boolean =
            none { it.isISOControl() || it.isWhitespace() } &&
                (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true))

        fun String.isHeaderSafe(): Boolean = none { it.isISOControl() }

        /**
         * `SettingsProvidersScreen.toStringRes()`, as the search store uses, in the app's language.
         * See `labelContext` in `ApiRepositorySearchSource` for why the application context is not enough.
         */
        @Suppress("DEPRECATION")
        fun typeWords(context: Context): (TvType) -> String {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.getDefault())
            val localized = context.createConfigurationContext(configuration)
            return { type -> localized.getString(with(SettingsProvidersScreen) { type.toStringRes() }) }
        }
    }
}

/** `WatchType` -> bucket. [WatchType.NONE] is "in no list", which `:app` stores by deleting the row. */
internal fun WatchType.toBucket(): LibraryBucket? = when (this) {
    WatchType.WATCHING -> LibraryBucket.Watching
    WatchType.COMPLETED -> LibraryBucket.Completed
    WatchType.ONHOLD -> LibraryBucket.OnHold
    WatchType.DROPPED -> LibraryBucket.Dropped
    WatchType.PLANTOWATCH -> LibraryBucket.PlanToWatch
    WatchType.NONE -> null
}

/** The search store's id for the same pair, so opening a title reuses its one codec. */
internal fun DataStoreHelper.LibrarySearchResponse.titleId() = LibraryTitleId(encodeId(apiName, url).value)

internal fun LibraryTitleId.target(): SearchResultTarget? = decodeId(SearchResultId(value))
