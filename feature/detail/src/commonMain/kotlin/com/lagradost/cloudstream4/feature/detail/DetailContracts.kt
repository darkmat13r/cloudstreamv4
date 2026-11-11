package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import kotlinx.coroutines.flow.Flow

/**
 * One episode of a series. [data] is opaque, and goes back to [DetailSource.links] unread.
 *
 * @param runTimeMinutes how long the episode runs, as the provider said. Null where it said nothing,
 *   which is most providers; board 7i's `24 min` is drawn only where there is one.
 * @param airDateMs when the episode aired, as epoch milliseconds. **Carried but not yet drawn**, and
 *   that is deliberate: `Episode.addDate` stores this by resolving a date to start-of-day in
 *   `TimeZone.currentSystemDefault()`, so the value is already timezone-dependent before this module
 *   sees it — which is `BUG-08`, the reason `EpisodeDateTest` fails off UTC. Rendering it back needs a
 *   locale-aware formatter (`:core:strings` has no date one; `NumberSymbols` is the precedent for what
 *   one costs) *and* a ruling on which zone to render in. Both are their own task, so 7i's
 *   `12 Mar 2019` is not drawn here rather than drawn wrongly.
 * @param progress where the viewer stopped in **this** episode, or null where nothing usable is stored
 *   (`KMP-63`). Its [DetailResume.episode] is null, because the row is the episode, and its
 *   [DetailResume.data] is this episode's [data]. The host looks it up by its own key for the episode;
 *   no key of the host's crosses this seam.
 * @param markedWatched the host keeps a "watched" mark apart from any position: `:app`'s old page sets it
 *   from Mark watched with no position stored. False where the host has no such mark. See
 *   [watchReadout] for how the two combine.
 * @param stillUrl a still from the episode, which board E1 draws at the start of the Mac's row (`KMP-115`). A
 *   provider's address, so untrusted: each host lets through only an http(s) one, and the screen's host loads it
 *   with its own checks. Null where the provider gave none, or the host does not carry one yet.
 */
@Immutable
data class DetailEpisode(
    val name: String?,
    val data: String,
    val season: Int?,
    val episode: Int?,
    val runTimeMinutes: Int? = null,
    val airDateMs: Long? = null,
    val progress: DetailResume? = null,
    val markedWatched: Boolean = false,
    val stillUrl: String? = null,
)

/**
 * A season the provider named, and how many episodes it holds.
 *
 * [number] is the provider's season **integer**, never its free-text season name: the board draws
 * `Season 1` from a translated word plus a number, and a provider that calls a season
 * "Book Three: Fire" would otherwise put untranslated prose in a button of fixed width.
 */
@Immutable
data class DetailSeason(
    val number: Int,
    val episodeCount: Int,
)

/**
 * One name in the cast rail.
 *
 * ## [name] is not always the performer
 *
 * TMDB, Trakt and MyDramaList fill [name] with the performer and [role] with the character. AniList
 * inverts it: [name] is the character and the performer arrives as [voiceActorName]. On an anime
 * title the first line therefore reads the character and the second reads "Main". That is correct,
 * not a binding defect, and the screen must not try to normalise it.
 *
 * @param imageUrl the headshot, or null for this one person. Decided **per person**: the old screen
 *   read only the first actor's image and dropped the whole rail to a text line when it was missing.
 * @param role the character, or the role word the provider sent. Null removes the line.
 */
@Immutable
data class DetailCastMember(
    val name: String,
    val imageUrl: String?,
    val role: String?,
    val voiceActorName: String? = null,
    /**
     * `Main`, `Supporting` or `Background` (`ActorRole`), for when there is no [role] (`KMP-104`). An identifier: the
     * page turns it into words. Null where the host folds the kind into [role], as Android's does.
     */
    val roleKind: String? = null,
)

/**
 * A title the provider recommends after this one.
 *
 * [id] is opaque in exactly the way [DetailSource.details]'s own id is: the host encodes it and gets
 * it back unread, so this module needs no provider types.
 */
@Immutable
data class DetailRecommendation(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    /** The provider's type name, such as `TvSeries` (`KMP-103`). An identifier: the copy turns it into words. */
    val typeName: String? = null,
)

/**
 * How far through a title the viewer already is, and what Resume would play.
 *
 * Carries [episode] rather than a rendered label because the code (`S1:E4`) is built from translated
 * letters where it is drawn (`I18N-08`): a pre-rendered string here would be in the wrong language
 * after a language change.
 *
 * @param durationMs the stored duration. Zero or less means the position is unusable, which is why
 *   [fraction] and [remainingMinutes] are null rather than `NaN` — the defect
 *   `ResultEpisode.getWatchProgress()` has, divided with no guard.
 */
@Immutable
data class DetailResume(
    val data: String,
    val positionMs: Long,
    val durationMs: Long,
    val episode: DetailEpisode?,
) {
    /** `0f..1f`, or null when nothing usable is stored. */
    val fraction: Float?
        get() = if (durationMs <= 0L || positionMs <= 0L) null
        else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

    /** Whole minutes left, or null when nothing usable is stored. Rounded up: 30 s left is "1m". */
    val remainingMinutes: Int?
        get() = if (durationMs <= 0L || positionMs <= 0L) null
        else ((durationMs - positionMs).coerceAtLeast(0L) + MS_PER_MINUTE - 1).div(MS_PER_MINUTE).toInt()

    /**
     * Near enough to the end to read as watched: a whole percent of at least [FINISHED_PERCENT], in the integer
     * arithmetic `fixVisual` uses (`position * 100 / duration`), so the two agree on every millisecond. A float
     * `fraction >= 0.95f` does not: `0.95f` is just under 0.95, so a position a millisecond short of 95% of a long
     * enough duration rounds up into it.
     */
    val finished: Boolean
        get() = durationMs > 0L && positionMs > 0L && positionMs * PERCENT / durationMs >= FINISHED_PERCENT

    companion object {
        private const val MS_PER_MINUTE = 60_000L
        private const val PERCENT = 100L

        /**
         * 95%, the rule both hosts already draw by.
         *
         * `:app`'s old episode row snaps a position of 95% or more to the end and draws the check for it
         * (`DataStoreHelper.fixVisual`), and the Mac's library counts 95% as watched
         * (`WatchPosition.FINISHED_FRACTION`, which this module cannot see, as a float). `:app`'s other number, 90%
         * (`NEXT_WATCH_EPISODE_PERCENTAGE`), is when Resume moves on to the next episode, not when a row
         * reads watched: between the two, a row says what is left while Resume offers the next one, as
         * the old page does.
         */
        const val FINISHED_PERCENT = 95L
    }
}

/**
 * The watch lists a title can be on, as the screen names them.
 *
 * Restated here rather than imported so this module keeps no `:app` or `:library` edge, the same
 * reason `SearchResultItem` restates the half of `SearchResponse` a card reads. The host maps it to
 * its own storage enum; the order is the order the picker offers.
 */
enum class DetailWatchStatus {
    Watching,
    Completed,
    OnHold,
    Dropped,
    PlanToWatch,
    None,
}

/**
 * A title, as the screen needs it.
 *
 * Every field after [episodes] has a default, so a host that knows nothing about it constructs this
 * exactly as before and draws exactly what it drew before. `DesktopDetailSource` sets none of them.
 *
 * @param playData what to ask for links with when the title is itself playable. Null for a series,
 *   and also null for a title the provider gave nothing playable for.
 * @param watchStatus null where the host has no watch lists at all, which hides the control rather
 *   than drawing a dead one. [DetailWatchStatus.None] means "supported, and on no list".
 * @param isFavourite null where the host has no favourites, for the same reason.
 * @param hasTrailer whether a trailer exists to play. The URLs stay with the host: this module never
 *   sees a stream address.
 */
@Immutable
data class TitleDetail(
    val title: String,
    val providerName: String,
    /** The provider's type name, such as `TvSeries`. An identifier: the copy turns it into words. */
    val typeName: String?,
    val year: Int?,
    val plot: String?,
    val posterUrl: String?,
    val playData: String?,
    val episodes: List<DetailEpisode>,
    /** The wide artwork behind the hero. Falls back to [posterUrl] where a provider sent none. */
    val backdropUrl: String? = null,
    /** Out of ten, as the provider scored it. The copy formats it. */
    val score: Float? = null,
    val tags: List<String> = emptyList(),
    /** A certification such as `TV-MA`, as the provider spelled it. Never translated: it is a label. */
    val contentRating: String? = null,
    /** `Ongoing` or `Completed`. An identifier: the copy turns it into words. */
    val statusName: String? = null,
    val durationMinutes: Int? = null,
    val seasons: List<DetailSeason> = emptyList(),
    val cast: List<DetailCastMember> = emptyList(),
    val recommendations: List<DetailRecommendation> = emptyList(),
    val hasTrailer: Boolean = false,
    val resume: DetailResume? = null,
    val watchStatus: DetailWatchStatus? = null,
    val isFavourite: Boolean? = null,
    /** The title's ids on other sites, for the Mac player's skip stamps (`KMP-86`). Never drawn. */
    val ids: TitleIds = TitleIds(),
) {
    /**
     * What the primary button plays: where the viewer stopped, else the title, else where an unstarted show starts —
     * S1E1, not a special (`firstEpisodeToPlay`, `KMP-92`).
     */
    val primaryData: String? get() = resume?.data ?: playData ?: episodes.firstEpisodeToPlay()?.data
}

/**
 * One playable stream.
 *
 * @param subtitles what the provider had offered in the same call by the time this was read. A provider's
 *   subtitle list belongs to the title rather than to one stream, so every link of one answer carries the same
 *   list.
 * @param quality the plugin's own number for it, `ExtractorLink.quality`, unread except to rank it (see [bestFirst]).
 *   Null where nothing said.
 */
@Immutable
data class StreamLink(
    val label: String,
    val qualityLabel: String?,
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val audioUrl: String?,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val quality: Int? = null,
)

/**
 * Where a title and its streams come from. Both reads reach third-party code, so both are expected
 * to fail, and they report it by throwing.
 */
interface DetailSource {
    suspend fun details(id: String): TitleDetail

    /**
     * Every stream so far, each time there are more. A later value only adds to the end of an earlier one, so a
     * row that is on screen stays where it is. It completes when the source has nothing more to say, having
     * emitted nothing if it found nothing, and cancelling it stops the work behind it where that can be stopped.
     */
    fun links(id: String, data: String): Flow<List<StreamLink>>

    /**
     * Puts the title on a watch list, or takes it off with [DetailWatchStatus.None].
     *
     * The default does nothing, for a host with no watch lists. Such a host leaves
     * [TitleDetail.watchStatus] null, so no control that could call this is ever drawn.
     */
    suspend fun setWatchStatus(id: String, status: DetailWatchStatus) = Unit

    /** Adds or removes a favourite. The default does nothing, as [setWatchStatus] does. */
    suspend fun setFavourite(id: String, favourite: Boolean) = Unit
}

/**
 * A title's ids on other sites (`KMP-86`), each already in its exact shape: what the Mac player sends to the skip
 * services. Android's player reads its `LoadResponse` directly and leaves these empty.
 *
 * @param imdbId `tt` and 7 to 10 ASCII digits.
 */
@Immutable
data class TitleIds(val malId: Int? = null, val tmdbId: Int? = null, val imdbId: String? = null)
