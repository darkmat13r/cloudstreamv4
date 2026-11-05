package com.lagradost.cloudstream4.detail

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.EpisodeSlot
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.result.episodeSlots
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.AppContextUtils.updateHasTrailers
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailRecommendation
import com.lagradost.cloudstream4.feature.detail.DetailResume
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.search.decodeId
import com.lagradost.cloudstream4.search.encodeId
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * [DetailSource] over `:app`'s real providers, as `ApiRepositorySearchSource` is for search.
 *
 * ## Why not `ResultViewModel2`
 *
 * `ResultViewModel2` cannot be read headlessly: its `load` takes an `Activity`, every output is
 * `LiveData`, it reads the process-global `CommonActivity.activity` in five places, and 24 of
 * `ResultData`'s 29 fields are pre-rendered `UiText` that need a `Context`
 * (`docs/plans/tv-detail-data-survey.md` §1). `APIRepository.load(url)` is headless — no `Context`,
 * never throws, its own ten-minute rolling cache and a clamped timeout — so this maps `LoadResponse`
 * to [TitleDetail] itself.
 *
 * It deliberately does **not** go through `LoadResponse.toResultData`: that function nulls the whole
 * cast list unless the *first* actor has an image (`ResultViewModel2.kt:277`, `:339`), which is the
 * per-person image defect [DetailCastMember] exists to fix.
 *
 * ## Why `internal` is not free
 *
 * `app/api/classes-jar.api` dumps everything under `com/lagradost/` and reads raw `ACC_PUBLIC`, so a
 * Kotlin `internal class` is still in it — see `ApiRepositorySearchSource`'s KDoc, which this follows
 * in every respect, including the opaque length-prefixed id it reuses rather than inventing a second.
 *
 * ## Provider text
 *
 * Everything a provider sends is third-party output. The plot and each episode name are stripped of
 * markup, as `DesktopDetailSource.withoutMarkup()` does: providers send HTML (`<br>` in a YouTube
 * description) and this text goes into Compose `BasicText`, never an HTML parser, so the point is
 * legibility rather than injection. The **old** screen does hand provider plots to
 * `HtmlCompat.fromHtml` with no allowlist (`ResultFragmentTv.kt:883`, `:794`, `:894`); that is
 * reported, not fixed here.
 *
 * @param context the **application** context. Nothing here resolves a display string yet, but the
 *   trailer preference does read preferences, and a `DetailSource` outlives the Fragment that made it
 *   for as long as the ViewModel does, so an Activity here would leak.
 */
internal class AndroidDetailSource(
    private val context: Context,
    private val records: LocalDetailRecords = DataStoreDetailRecords,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DetailSource {

    /**
     * What the host needs about the title [details] last answered with, keyed by that title's id.
     *
     * Kept here rather than put on [TitleDetail] because none of it is the screen's business: an
     * `Int` storage id, a provider's trailer URLs and its poster headers would all be `:app` shapes
     * in a module that has none. Read only for the title the page is showing, which is the one just
     * loaded, and null before that — [details] always runs first, in the ViewModel's `init`.
     */
    @Volatile
    private var lastFacts: Pair<String, DetailHostFacts>? = null

    internal fun hostFacts(id: String): DetailHostFacts? =
        lastFacts?.takeIf { it.first == id }?.second

    /**
     * What the host needs to *play* the title [details] last answered with, kept apart from
     * [DetailHostFacts] for one blunt reason: that class is in `app/api/classes-jar.api`, and a field
     * on it rewrites its `copy` and `<init>` lines. A changed line there stops for the owner, so a new
     * class it is.
     */
    @Volatile
    private var lastParts: Pair<String, DetailPlaybackParts>? = null

    internal fun playbackParts(id: String): DetailPlaybackParts? =
        lastParts?.takeIf { it.first == id }?.second

    override suspend fun details(id: String): TitleDetail = withContext(io) {
        val response = load(id)
        // The old TV page re-reads this preference before deciding whether to offer a trailer
        // (`ResultFragmentTv.kt:549`). `addTrailer` no-ops while it is false, so a stale `true`
        // would draw a button with nothing behind it.
        runCatching { context.updateHasTrailers() }
        val titleId = response.getId()
        val episodes = response.detailEpisodes(records.dub(titleId))
        val chosenDub = response.chosenDub(records.dub(titleId))
        val playData = response.playData()
        val detail = response.toTitleDetail(
            episodes = episodes,
            seasons = episodes.toSeasons(),
            resume = resumeOf(records, titleId, episodes, playData),
            watchStatus = records.watchState(titleId).toDetailStatus(),
            isFavourite = records.favourite(titleId) != null,
        )
        lastFacts = id to DetailHostFacts(
            apiName = response.apiName,
            titleId = titleId,
            resumeEpisodeId = records.lastWatched(titleId)?.episodeId
                ?: titleId.takeIf { playData != null },
            trailers = response.trailers.toList(),
            syncData = response.syncData.toMap(),
            posterHeaders = response.posterHeaders,
        )
        lastParts = id to DetailPlaybackParts(
            response = response,
            titleId = titleId,
            // One dub list, as the old page's generator is built from one
            // (`ResultViewModel2.kt:2119-2124`), so the player's next and previous stay inside the
            // list the page is showing instead of walking into the other one.
            slots = response.episodeSlots(titleId).filter { it.dub == chosenDub },
        )
        detail
    }

    /**
     * Every stream the provider has offered so far, each time there is one more.
     *
     * **Nothing on the TV path calls this yet.** Link loading is its own task and the TV screen hands
     * playback to `GeneratorPlayer`, which resolves links itself. It is implemented rather than
     * stubbed because the interface requires it and because `DesktopDetailSource.links`' contract —
     * each value is the whole list so far, append-only, completing when there is no more — is cheap to
     * honour over `APIRepository.loadLinks`, which is headless and does not throw.
     *
     * `conflate()`d: a collector that cannot keep up misses no link, because every value carries all
     * of them. The subtitle list belongs to the title rather than to one stream, so each snapshot
     * gives every link the subtitles known at that moment, as [StreamLink.subtitles] documents.
     */
    override fun links(id: String, data: String): Flow<List<StreamLink>> = callbackFlow {
        val api = provider(id)
        val found = ArrayList<ExtractorLink>()
        val subtitles = ArrayList<SubtitleFile>()
        // One lock around "record it and send the list", because a provider answers from several
        // coroutines at once and two emissions out of order would be a list that shrank.
        val lock = Any()
        fun sendSnapshot() {
            if (found.isEmpty()) return
            val tracks = subtitles.toTracks()
            trySend(found.map { link -> link.toStreamLink(tracks) })
        }
        APIRepository(api).loadLinks(
            data = data,
            isCasting = false,
            subtitleCallback = { file -> synchronized(lock) { subtitles.add(file); sendSnapshot() } },
            callback = { link -> synchronized(lock) { found.add(link); sendSnapshot() } },
        )
        close()
        awaitClose { }
    }.conflate().flowOn(io)

    /**
     * What `ResultViewModel2.updateWatchStatus` does, minus the dialog.
     *
     * `setResultWatchState` takes the `internalId` and deletes the row for [WatchType.NONE]; the full
     * `BookmarkedData` record is written for every other status, so the library and home screens have
     * the fields their cards read. Both events then fire, and only when the status really changed, as
     * `ResultViewModel2.kt:842-845` does.
     *
     * **`checkAndWarnDuplicates` is omitted**: it shows an `AlertDialog` and is `@MainThread`. The
     * duplicate-bookmark warning, and the de-duplication it performs on confirm, are therefore lost
     * on this screen. A write also no-ops silently when `CloudStreamApp`'s weak context reference is
     * empty — that is how `DataStoreHelper` works and there is no return value to check.
     */
    override suspend fun setWatchStatus(id: String, status: DetailWatchStatus): Unit = withContext(io) {
        val response = load(id)
        val titleId = response.getId()
        val previous = records.watchState(titleId)
        val next = status.toWatchType()
        records.writeWatchState(
            id = titleId,
            status = next,
            record = if (next == WatchType.NONE) null
            else response.bookmarkOf(titleId, records.bookmark(titleId)),
        )
        if (previous != next) records.announceBookmarks()
    }

    /** `ResultViewModel2.toggleFavoriteStatus`, minus the same dialog. Presence is the boolean. */
    override suspend fun setFavourite(id: String, favourite: Boolean): Unit = withContext(io) {
        val response = load(id)
        val titleId = response.getId()
        records.writeFavourite(
            id = titleId,
            record = if (favourite) response.favouriteOf(titleId, records.favourite(titleId)) else null,
        )
        records.announceLibrary()
    }

    /**
     * The provider for an id, by the name the hit was encoded with and by its URL as a fallback, the
     * two `ResultViewModel2.load` tries (`:2671`).
     */
    private fun provider(id: String) = decodeId(SearchResultId(id)).let { target ->
        if (target == null) throw IOException(BAD_ID)
        APIHolder.getApiFromNameNull(target.apiName)
            ?: APIHolder.getApiFromUrlNull(target.url)
            ?: throw IOException("$NOT_LOADED · ${target.apiName}")
    }

    /**
     * One title, from the provider's own `load`.
     *
     * Rebuilt per call rather than cached here, for the staleness reason
     * `ApiRepositorySearchSource` gives (`:63-70`), and affordable because `APIRepository` keeps a
     * shared ten-minute cache keyed on `(api.name, url)`: a write that follows a read of the same
     * page does not go back to the network.
     */
    private suspend fun load(id: String): LoadResponse {
        val target = decodeId(SearchResultId(id)) ?: throw IOException(BAD_ID)
        return when (val answer = APIRepository(provider(id)).load(target.url)) {
            is Resource.Success -> answer.value
            // A status line, not a sentence: `DetailLoad.Failed.detail` is drawn in the mono ramp and
            // is what someone pastes into a bug report. Capped, because a provider's own exception
            // message can be a whole stack trace.
            is Resource.Failure -> throw IOException(answer.errorString.firstLine().ifBlank { NO_ANSWER })
            is Resource.Loading -> throw IOException(NO_ANSWER)
        }
    }

    private companion object {
        const val BAD_ID = "BAD_ID"
        const val NOT_LOADED = "NOT_LOADED"
        const val NO_ANSWER = "NO_RESPONSE"
        const val MAX_REASON_CHARS = 60

        fun String.firstLine(): String =
            lineSequence().firstOrNull()?.trim()?.take(MAX_REASON_CHARS).orEmpty()
    }
}

/**
 * The keys this page reads and writes, one seam wide so the mapping can be tested off a device.
 *
 * `DataStoreHelper` needs no `Context` — `CloudStreamApp` holds a process-global weak reference — but
 * that also means a **write no-ops silently** when the reference is empty, with nothing to check.
 */
internal interface LocalDetailRecords {
    fun watchState(id: Int): WatchType
    fun bookmark(id: Int): DataStoreHelper.BookmarkedData?
    fun favourite(id: Int): DataStoreHelper.FavoritesData?
    fun lastWatched(id: Int): DownloadObjects.ResumeWatching?
    fun viewPosition(id: Int): DataStoreHelper.PosDur?

    /** The dub status the viewer last chose for this title, which anime episode lists are keyed on. */
    fun dub(id: Int): DubStatus?

    /** [record] is null for [WatchType.NONE], which `setResultWatchState` stores by deleting the row. */
    fun writeWatchState(id: Int, status: WatchType, record: DataStoreHelper.BookmarkedData?)
    fun writeFavourite(id: Int, record: DataStoreHelper.FavoritesData?)

    /** Both pushes `updateWatchStatus` fires, so the library and home screens reload as they do today. */
    fun announceBookmarks()

    /** The one push `toggleFavoriteStatus` fires. */
    fun announceLibrary()
}

/** The real keys, for the current account. Read again on every call. */
internal object DataStoreDetailRecords : LocalDetailRecords {
    override fun watchState(id: Int) = DataStoreHelper.getResultWatchState(id)
    override fun bookmark(id: Int) = DataStoreHelper.getBookmarkedData(id)
    override fun favourite(id: Int) = DataStoreHelper.getFavoritesData(id)
    override fun lastWatched(id: Int) = DataStoreHelper.getLastWatched(id)
    override fun viewPosition(id: Int) = DataStoreHelper.getViewPos(id)
    override fun dub(id: Int) = DataStoreHelper.getDub(id)

    override fun writeWatchState(id: Int, status: WatchType, record: DataStoreHelper.BookmarkedData?) {
        DataStoreHelper.setResultWatchState(id, status.internalId)
        record?.let { DataStoreHelper.setBookmarkedData(id, it) }
    }

    override fun writeFavourite(id: Int, record: DataStoreHelper.FavoritesData?) {
        if (record == null) DataStoreHelper.removeFavoritesData(id)
        else DataStoreHelper.setFavoritesData(id, record)
    }

    override fun announceBookmarks() {
        MainActivity.bookmarksUpdatedEvent(true)
        MainActivity.reloadLibraryEvent(true)
    }

    override fun announceLibrary() = MainActivity.reloadLibraryEvent(true)
}

/**
 * The full row `updateWatchStatus` writes (`ResultViewModel2.kt:826-841`), not a subset: the library
 * and home cards read every field off it, and `bookmarkedTime` is preserved so re-picking a status
 * does not look like a new bookmark.
 */
internal fun LoadResponse.bookmarkOf(
    titleId: Int,
    existing: DataStoreHelper.BookmarkedData?,
) = DataStoreHelper.BookmarkedData(
    bookmarkedTime = existing?.bookmarkedTime ?: APIHolder.unixTimeMS,
    id = titleId,
    latestUpdatedTime = APIHolder.unixTimeMS,
    name = name,
    url = url,
    apiName = apiName,
    type = type,
    posterUrl = posterUrl,
    year = year,
    syncData = syncData,
    posterHeaders = posterHeaders,
    plot = plot,
    score = score,
    tags = tags,
)

/** The same row for favourites, which `toggleFavoriteStatus` writes with `favoritesTime` in place. */
internal fun LoadResponse.favouriteOf(
    titleId: Int,
    existing: DataStoreHelper.FavoritesData?,
) = DataStoreHelper.FavoritesData(
    favoritesTime = existing?.favoritesTime ?: APIHolder.unixTimeMS,
    id = titleId,
    latestUpdatedTime = APIHolder.unixTimeMS,
    name = name,
    url = url,
    apiName = apiName,
    type = type,
    posterUrl = posterUrl,
    year = year,
    syncData = syncData,
    posterHeaders = posterHeaders,
    plot = plot,
    score = score,
    tags = tags,
)

/**
 * The facts about a loaded title that the host needs and `:feature:detail` must not see.
 *
 * [resumeEpisodeId] is the id `:app` really keeps the position under, taken from
 * `DataStoreHelper.getLastWatched` or, for a playable title, the title's own id — **never** derived.
 * `ResultViewModel2.postEpisodes` builds each episode's id with a per-response-type formula
 * (`:2227-2229`, `:2284-2285`) that is private to it, and an id that disagrees with it points every
 * stored position at nothing while writing new ones where no other screen looks. Null therefore means
 * "this host does not know an id for that part", and the player then keeps no position for it. Giving
 * every episode its real id needs either that formula lifted out of `ResultViewModel2` or an id on
 * the seam; both are the owner's call.
 *
 * [posterHeaders] is here because [TitleDetail] has no field for it and image loading needs it.
 */
internal data class DetailHostFacts(
    val apiName: String,
    val titleId: Int,
    val resumeEpisodeId: Int?,
    val trailers: List<TrailerData>,
    val syncData: Map<String, String>,
    val posterHeaders: Map<String, String>?,
)

/**
 * The title's playable parts, as the player needs them.
 *
 * The point of this class is the `Int` id on each part. `NoVideoGenerator` carries one id and an empty
 * `videos` list, so `PlayerGeneratorViewModel` had no meta to hand `setViewPosAndResume`
 * (`:396`), and that function writes the Continue Watching entry only for a `ResultEpisode`
 * (`DataStoreHelper.kt:719-742`). A series played from this page therefore kept no position and never
 * appeared in Continue Watching — the regression `KMP-38` reported and the owner ruled on.
 *
 * `RepoLinkGenerator` takes the real list, which also restores the two things that hung off it: the
 * player's next and previous episode, and the twenty-minute link cache.
 *
 * @param slots one dub list's parts, in `episodeSlots` order. See [AndroidDetailSource.playbackParts].
 */
internal data class DetailPlaybackParts(
    val response: LoadResponse,
    val titleId: Int,
    val slots: List<EpisodeSlot>,
) {

    /**
     * The part the screen means by [data], or null when nothing in this response matches.
     *
     * By `data` and not by index, because the screen's own episode list is sorted by a comparator of
     * its own and the two orders need not agree. Where a provider gives two parts the same `data` the
     * first wins; they resolve to one stream either way, so what is lost is only which of the two ids
     * the position lands under.
     */
    fun indexOf(data: String): Int? = slots.indexOfFirst { it.data == data }.takeIf { it >= 0 }

    /**
     * Built here and not at load time: `buildResultEpisode` reads the stored position and the watched
     * marker, and the player may have moved both since the page loaded.
     */
    fun episodes(): List<ResultEpisode> = slots.map { slot -> slot.toResultEpisode(response, titleId) }
}

/**
 * One slot as `:app`'s own episode, filled as `postEpisodes` fills it (`:2241-2320`).
 *
 * `isFiller` is the one field left null that the old page can set: it comes from the filler-list
 * request `updateFillers` makes, which this page does not make. The player's filler badge is off
 * until it does.
 */
private fun EpisodeSlot.toResultEpisode(response: LoadResponse, titleId: Int): ResultEpisode {
    val show = response as? EpisodeResponse
    // `ResultViewModel2.getSeason`'s body (`:622-625`), which is private to its companion.
    val seasonData = season?.let { value -> show?.seasonNames?.firstOrNull { it.season == value } }
    return buildResultEpisode(
        headerName = response.name,
        name = if (source == null) response.name else source.name?.takeIf { !it.matches(FILLER_NAME) },
        poster = source?.posterUrl,
        episode = episode,
        seasonIndex = season,
        season = if (seasonData != null) seasonData.displaySeason else season,
        data = data,
        apiName = response.apiName,
        id = id,
        index = index,
        rating = source?.score,
        description = source?.description,
        isFiller = null,
        tvType = response.type,
        parentId = titleId,
        totalEpisodeIndex = season?.let { value -> show?.getTotalEpisodeIndex(episode, value) },
        airDate = source?.date,
        runTime = source?.runTime,
        seasonData = seasonData,
    )
}

/**
 * The title's fields, straight across where they are data and decided where they are not.
 *
 * `providerName` is the response's own `apiName` rather than the `MainAPI`'s, because that is what
 * every other screen shows and what a bookmark stores.
 */
internal fun LoadResponse.toTitleDetail(
    episodes: List<DetailEpisode>,
    seasons: List<DetailSeason>,
    resume: DetailResume?,
    watchStatus: DetailWatchStatus,
    isFavourite: Boolean,
): TitleDetail = TitleDetail(
    title = name.cleanText(),
    providerName = apiName,
    // An identifier, not words: `DetailCopy` turns it into the language the page is in. The 18-arm
    // `when` in `toResultData` (`:350-371`) is an XML-layout decision and is not reused.
    typeName = type.name,
    year = year,
    plot = plot?.withoutMarkup()?.ifBlank { null },
    posterUrl = posterUrl,
    playData = playData(),
    episodes = episodes,
    backdropUrl = backgroundPosterUrl,
    score = score?.toFloat(SCORE_OUT_OF),
    tags = tags.orEmpty().map { it.cleanText() }.filter { it.isNotEmpty() },
    contentRating = contentRating?.cleanText()?.ifEmpty { null },
    statusName = (this as? EpisodeResponse)?.showStatus?.name,
    durationMinutes = duration?.takeIf { it > 0 },
    seasons = seasons,
    cast = actors.toCast(),
    recommendations = recommendations.toRecommendations(),
    // The user's "show trailers" preference, which `addTrailer` also honours, so a provider that
    // filled the list while it was off is not trusted over the current setting.
    hasTrailer = LoadResponse.isTrailersEnabled && trailers.isNotEmpty(),
    resume = resume,
    watchStatus = watchStatus,
    isFavourite = isFavourite,
)

/**
 * The play payload for a title that is itself playable, or null when there is nothing to play.
 *
 * `comingSoon` is not consulted: for a movie it *is* `dataUrl.isBlank()` (`MainAPI.kt:2454`) and for
 * a series it means something else to a provider. `isInvalidData` is the same guard `loadLinks`
 * applies before calling one, reused rather than restated.
 */
internal fun LoadResponse.playData(): String? = when (this) {
    is MovieLoadResponse -> dataUrl
    is LiveStreamLoadResponse -> dataUrl
    // `torrent` before `magnet`, which is the order `postEpisodes` has always had (`:2385`). It was
    // the other way round here, and that was a real fault once the player started storing positions:
    // the part the page plays has to be the part whose id `:app` keeps that position under, and for a
    // provider that sends both this page would have played the magnet and found no slot for it.
    is TorrentLoadResponse -> torrent ?: magnet
    else -> null
}?.takeIf { !APIRepository.isInvalidData(it) }

/**
 * The episodes, in the order the old page sorts them: by season then episode.
 *
 * Sorted with a comparator rather than `ResultViewModel2`'s `season * 10_000 + episode` key, which is
 * the same order without a stride that a provider numbering past 10,000 would break.
 *
 * Anime carries one list per dub status and this screen has no dub selector yet, so it shows the
 * status the viewer last chose on the old page and otherwise the **first non-empty list the provider
 * added**, rather than imposing dubbed over subbed. The old page reads a preference for that default
 * (`ResultFragment.getStoredData`, `:289-291`); offering no choice at all is a narrowing of today's
 * behaviour, and the selector is where that is fixed.
 */
internal fun LoadResponse.detailEpisodes(preferredDub: DubStatus?): List<DetailEpisode> = when (this) {
    is TvSeriesLoadResponse -> episodes.toDetailEpisodes()
    is AnimeLoadResponse -> episodes[chosenDub(preferredDub)].orEmpty().toDetailEpisodes()
    else -> emptyList()
}

/**
 * Which of an anime's dub lists this page is showing, and [DubStatus.None] for everything else.
 *
 * Its own function because two callers have to agree on it: the episodes the page draws and the parts
 * the player is given. Were they to disagree, the viewer would press an episode of the list they can
 * see and the player would walk forward through the other one.
 */
internal fun LoadResponse.chosenDub(preferredDub: DubStatus?): DubStatus = when (this) {
    is AnimeLoadResponse ->
        preferredDub?.takeIf { episodes[it]?.isNotEmpty() == true }
            ?: episodes.entries.firstOrNull { it.value.isNotEmpty() }?.key
            ?: DubStatus.None

    else -> DubStatus.None
}

private fun List<Episode>.toDetailEpisodes(): List<DetailEpisode> =
    sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        .mapIndexed { index, episode ->
            DetailEpisode(
                name = episode.name?.cleanText()?.ifEmpty { null }?.takeIf { !it.matches(FILLER_NAME) },
                data = episode.data,
                season = episode.season,
                // What `postEpisodes` falls back to when a provider numbered nothing (`:2226`).
                episode = episode.episode ?: (index + 1),
            )
        }

/** One row per season the mapped episodes mention, counted from them. */
internal fun List<DetailEpisode>.toSeasons(): List<DetailSeason> =
    mapNotNull { it.season }
        .groupingBy { it }
        .eachCount()
        .map { (number, count) -> DetailSeason(number, count) }
        .sortedBy { it.number }

/**
 * Where the viewer stopped, read through the seam rather than recomputed. See [DetailHostFacts].
 *
 * The raw position and duration, not `fixVisual()`, because that is what `ResultViewModel2.resume()`
 * reads (`:2461-2474`); the snap is a display decision and [DetailResume] makes its own, with the
 * zero guard `ResultEpisode.getWatchProgress()` is missing.
 */
internal fun resumeOf(
    records: LocalDetailRecords,
    titleId: Int,
    episodes: List<DetailEpisode>,
    playData: String?,
): DetailResume? {
    val stored = records.lastWatched(titleId)
    // For a movie the stored episode id is the title's own: `postEpisodes` gives its single episode
    // "HAS SAME ID" as the title (`:2344`), which is what makes a film's position the title's.
    val episodeId = stored?.episodeId ?: titleId.takeIf { playData != null } ?: return null
    val position = records.viewPosition(episodeId) ?: return null
    if (position.position <= 0L) return null
    val episode = stored?.let { watched ->
        episodes.firstOrNull { it.season == watched.season && it.episode == watched.episode }
    }
    val data = episode?.data ?: playData ?: return null
    return DetailResume(data, position.position, position.duration, episode)
}

/**
 * The cast, decided **per person**.
 *
 * `toResultData` reads only the first actor's image and nulls the whole list when it is blank
 * (`ResultViewModel2.kt:277`, `:339`), so a provider with 38 names and no photos draws one sentence.
 * Here a missing headshot removes that one person's image and nothing else.
 *
 * `roleString` wins over the `ActorRole` enum because it is what the provider wrote; the enum's three
 * names are a classification, and `Main` is the one it is usually set to.
 */
internal fun List<ActorData>?.toCast(): List<DetailCastMember> = orEmpty().mapNotNull { entry ->
    val name = entry.actor.name.cleanText().ifEmpty { return@mapNotNull null }
    DetailCastMember(
        name = name,
        imageUrl = entry.actor.image?.takeIf { it.isPlainHttp() },
        role = (entry.roleString ?: entry.role?.name)?.cleanText()?.ifEmpty { null },
        voiceActorName = entry.voiceActor?.name?.cleanText()?.ifEmpty { null },
    )
}

/** The same opaque id the search source writes, so a recommendation reopens by the one codec. */
internal fun List<SearchResponse>?.toRecommendations(): List<DetailRecommendation> = orEmpty().map { hit ->
    DetailRecommendation(
        id = encodeId(apiName = hit.apiName, url = hit.url).value,
        title = hit.name.cleanText(),
        posterUrl = hit.posterUrl?.takeIf { it.isPlainHttp() },
        year = hit.yearOrNull(),
    )
}

/** `SearchResponse` declares no year; three of its concrete types do (`MainAPI.kt:1564`, `:1664`, `:1736`). */
private fun SearchResponse.yearOrNull(): Int? = when (this) {
    is AnimeSearchResponse -> year
    is MovieSearchResponse -> year
    is TvSeriesSearchResponse -> year
    else -> null
}

/** `WatchType` -> the contract's six, which are the same six. `NONE` is stored as no key at all. */
internal fun WatchType.toDetailStatus(): DetailWatchStatus = when (this) {
    WatchType.WATCHING -> DetailWatchStatus.Watching
    WatchType.COMPLETED -> DetailWatchStatus.Completed
    WatchType.ONHOLD -> DetailWatchStatus.OnHold
    WatchType.DROPPED -> DetailWatchStatus.Dropped
    WatchType.PLANTOWATCH -> DetailWatchStatus.PlanToWatch
    WatchType.NONE -> DetailWatchStatus.None
}

/** Inverse of [toDetailStatus]. Not `WatchType.entries[ordinal]`: the two orders are not a contract. */
internal fun DetailWatchStatus.toWatchType(): WatchType = when (this) {
    DetailWatchStatus.Watching -> WatchType.WATCHING
    DetailWatchStatus.Completed -> WatchType.COMPLETED
    DetailWatchStatus.OnHold -> WatchType.ONHOLD
    DetailWatchStatus.Dropped -> WatchType.DROPPED
    DetailWatchStatus.PlanToWatch -> WatchType.PLANTOWATCH
    DetailWatchStatus.None -> WatchType.NONE
}

/** One stream, as the seam models it. A quality of zero or less is "the provider did not say". */
internal fun ExtractorLink.toStreamLink(subtitles: List<SubtitleTrack>) = StreamLink(
    label = name.cleanText(),
    qualityLabel = quality.takeIf { it > 0 }?.let { "${it}p" },
    url = url,
    referer = referer.ifBlank { null },
    userAgent = headers.entries.firstOrNull { it.key.equals(USER_AGENT_HEADER, ignoreCase = true) }?.value,
    audioUrl = audioTracks.firstOrNull()?.url,
    subtitles = subtitles,
)

/**
 * The provider's subtitles as tracks.
 *
 * `langTag` is already resolved by `:library`'s language table, so nothing is resolved twice here.
 * Two rows must not read the same, which a provider offering `deu` twice would otherwise produce.
 */
internal fun List<SubtitleFile>.toTracks(): List<SubtitleTrack> {
    val seen = HashMap<String, Int>()
    return map { file ->
        val label = file.lang.cleanText().ifEmpty { file.langTag.orEmpty() }
        val count = (seen[label] ?: 0) + 1
        seen[label] = count
        SubtitleTrack(
            source = SubtitleSource.Remote(url = file.url, headers = file.headers.orEmpty()),
            label = if (count == 1) label else "$label $count",
            languageTag = file.langTag,
        )
    }
}

/**
 * The plot as plain text.
 *
 * `DesktopDetailSource.withoutMarkup()`, restated because it is `private` to a class in `:desktopApp`
 * that `:app` cannot see. A `<br>` becomes a space, every other tag goes, runs of whitespace collapse.
 */
internal fun String.withoutMarkup(): String =
    replace(BREAK_TAG, " ").replace(ANY_TAG, "").replace(RUN_OF_SPACES, " ").trim()

/** Control and format characters out, bounded. The page's boxes are fixed-width on purpose. */
private fun String.cleanText(): String =
    filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(MAX_TEXT_CHARS)

private fun String.isPlainHttp(): Boolean =
    none { it.isISOControl() || it.isWhitespace() } &&
        (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true))

private const val MAX_TEXT_CHARS = 200
private const val SCORE_OUT_OF = 10
private const val USER_AGENT_HEADER = "User-Agent"
private val BREAK_TAG = Regex("(?i)<br\\s*/?>")
private val ANY_TAG = Regex("<[^>]*>")
private val RUN_OF_SPACES = Regex("\\s+")

/** `ResultViewModel2.filterName`'s regex (`:650-657`), which is private to it. */
private val FILLER_NAME = Regex("^[eE]pisode [0-9]*$")
