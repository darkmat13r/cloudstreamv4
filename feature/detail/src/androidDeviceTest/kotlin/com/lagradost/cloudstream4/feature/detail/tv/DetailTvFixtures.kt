package com.lagradost.cloudstream4.feature.detail.tv

import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailRecommendation
import com.lagradost.cloudstream4.feature.detail.DetailResume
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fake at this module's one seam, driven from a test. `NoHistory` in `SearchScreenTvDeviceTest` is
 * the precedent; this one needs state because four of the cases are about *when* the source answers.
 *
 * Three modes, and they are checked in this order:
 *
 * - [answers] false — [details] never returns, which is the page's LOADING state. It suspends on a
 *   [CompletableDeferred] nobody completes, so cancelling the ViewModel's scope releases it.
 * - [failure] non-null — [details] throws it, which is the page's ERROR state.
 * - otherwise [detail] is returned, and it must have been set.
 *
 * [reads] is what makes the retry case falsifiable: a Retry that is drawn but wired to nothing leaves
 * this at 1.
 */
internal class FakeDetailSource : DetailSource {

    @Volatile
    var detail: TitleDetail? = null

    @Volatile
    var failure: Exception? = null

    /** False means [details] never answers. */
    @Volatile
    var answers: Boolean = true

    /** How many times the page has asked for the title. One per load, one per Retry, one per Reappeared. */
    val reads = AtomicInteger()

    val watchStatuses = CopyOnWriteArrayList<DetailWatchStatus>()
    val favourites = CopyOnWriteArrayList<Boolean>()

    private val never = CompletableDeferred<Unit>()

    override suspend fun details(id: String): TitleDetail {
        reads.incrementAndGet()
        if (!answers) never.await()
        failure?.let { throw it }
        return checkNotNull(detail) { "No TitleDetail was set on the fake" }
    }

    /**
     * Nothing on the TV path asks for links: `DetailScreenTv` sends `PlaybackRequested` and the host's
     * own player resolves them. An empty flow rather than a throw, so a stray request is not a failure.
     */
    override fun links(id: String, data: String): Flow<List<StreamLink>> = emptyFlow()

    override suspend fun setWatchStatus(id: String, status: DetailWatchStatus) {
        watchStatuses += status
    }

    override suspend fun setFavourite(id: String, favourite: Boolean) {
        favourites += favourite
    }
}

/**
 * The titles the device tests drive.
 *
 * **Every stop has a distinct label.** [DpadDriver][com.lagradost.cloudstream4.core.devicetest.DpadDriver]
 * names a stop by its text, then its content description, then its class and bounds, so two stops
 * sharing a label read as one and `FocusGraph` would report a screen smaller than it is. That is why no
 * two cast members, recommendations or episodes here share a name, and why none of them collides with a
 * button label ("Resume", "Play Movie", "Play Episode", "Trailer", "Season 1", "Episodes · N",
 * "Synopsis") or with a chrome cell's description ("Go Back", "Set watch status", "Favorite").
 *
 * Every cast member has an image URL. [com.lagradost.cloudstream4.core.designsystem.primitive.Avatar]
 * draws initials **instead of** the image when there is none, and those initials are then the first text
 * on the cast item's merged node — so an imageless member would be named `IM` rather than `Ines
 * Marlowe`. The artwork slot draws nothing in a test, so the URL costs nothing and keeps the label the
 * name. The board's initials variant is therefore not covered here.
 */
internal object DetailFixtures {

    private const val PAD = 2
    private const val FORTY = 40

    /** Board 7i's own runtime, the one it writes as `24 min`. */
    const val EPISODE_MINUTES = 24

    /** Mirrors `DetailSeasonGroups.SPECIALS_SEASON`, which is internal to the feature module. */
    const val SPECIALS_SEASON_FIXTURE = 0
    private const val PLOT_REPEATS = 4

    /** Opaque to this module; the fake never reads it. */
    const val Id = "7:FakeApihttps://example.invalid/the-last-signal"

    private const val Headshot = "https://example.invalid/headshot"

    /** Exposed so a screenshot host can tell a headshot from the hero when it fills `artwork`. */
    const val HeadshotUrl = Headshot
    private const val Poster = "https://example.invalid/poster"

    /** The third one is the only member with a voice actor, which is the only press a cast item has. */
    val Cast = listOf(
        DetailCastMember("Ines Marlowe", Headshot, "Cdr. Rhea Voss"),
        DetailCastMember("Tobias Kerr", Headshot, "Idris Pell"),
        DetailCastMember("Ren Takeda", Headshot, "Main", voiceActorName = "Hana Sato"),
    )

    val Recommendations = listOf(
        DetailRecommendation("r1", "Ashen Field", Poster, 2021),
        DetailRecommendation("r2", "Deep Signal", Poster, 2019),
        DetailRecommendation("r3", "Quiet Harbor", Poster, 2023),
    )

    /** Zero-padded, so `Chapter 04` and `Chapter 40` are never prefixes of one another. */
    fun episodeName(index: Int): String = "Chapter ${(index + 1).toString().padStart(PAD, '0')}"

    fun episodes(count: Int, runTimeMinutes: Int? = null): List<DetailEpisode> =
        List(count) { index ->
            DetailEpisode(episodeName(index), "e$index", 1, index + 1, runTimeMinutes)
        }

    /**
     * 7a: a series, three episodes in one season, two thirds of the way through episode 3.
     *
     * `playData` is null, so the primary plays `resume.data` and reads "Resume" — and **no "start
     * over" control is drawn**, because `KMP-38` makes that one a playable title's: a series' only
     * candidate for it is episode 1, which beside a Resume of a later episode is a different action.
     * A show's row therefore tops out at five controls.
     */
    val Show = TitleDetail(
        title = "The Last Signal",
        providerName = "Sylvan",
        typeName = "TvSeries",
        year = 2019,
        plot = "Six months after the broadcast went dark, a salvage crew picks up a repeating signal " +
            "from a station decommissioned forty years ago.",
        posterUrl = Poster,
        playData = null,
        episodes = episodes(3),
        backdropUrl = Poster,
        score = 8.4f,
        tags = listOf("Drama", "Thriller"),
        contentRating = "TV-MA",
        statusName = "Ongoing",
        seasons = listOf(DetailSeason(number = 1, episodeCount = 3)),
        cast = Cast,
        recommendations = Recommendations,
        hasTrailer = true,
        resume = DetailResume("e2", positionMs = 1_000_000L, durationMs = 2_620_000L, episode = episodes(3)[2]),
        watchStatus = DetailWatchStatus.Watching,
        isFavourite = false,
    )

    /** 7b: a film. `playData` set, no episodes, so the row's own label is the movie's. */
    val Movie = TitleDetail(
        title = "The Last Expedition",
        providerName = "Sylvan",
        typeName = "Movie",
        year = 2024,
        plot = "A salvage crew answers a distress call from a ship that vanished forty years ago, with " +
            "one more name on its manifest than there should be.",
        posterUrl = Poster,
        playData = "movie",
        episodes = emptyList(),
        backdropUrl = Poster,
        score = 7.5f,
        tags = listOf("Science Fiction"),
        contentRating = "PG-13",
        durationMinutes = 136,
        cast = Cast,
        recommendations = Recommendations,
        hasTrailer = true,
        resume = DetailResume("movie", positionMs = 5_047_000L, durationMs = 8_160_000L, episode = null),
        watchStatus = DetailWatchStatus.None,
        isFavourite = true,
    )

    /**
     * The fixture the D-pad walk maps: every kind of stop, as few of each as the screen still means.
     *
     * It keeps [Show]'s whole action row — five controls, 748dp of the band's 833dp — and cuts only the
     * repetition: two episodes rather than three, which is all a list needs to be a list. Every one of
     * the walk's 16 stops is named by a distinct label, which is the condition `FocusGraph` depends on.
     */
    val WalkShow = Show.copy(
        episodes = episodes(2),
        seasons = listOf(DetailSeason(number = 1, episodeCount = 2)),
        resume = DetailResume("e1", positionMs = 1_000_000L, durationMs = 2_620_000L, episode = episodes(2)[1]),
    )

    /** A series whose provider returned no episodes, and nothing playable with them. */
    val NoEpisodes = Show.copy(
        episodes = emptyList(),
        seasons = emptyList(),
        resume = null,
        playData = null,
    )

    /** One episode: the singular of `episodes`, and a list that must still be a list. */
    val OneEpisode = Show.copy(
        episodes = episodes(1),
        seasons = listOf(DetailSeason(number = 1, episodeCount = 1)),
        resume = null,
    )

    /**
     * 7i's rows: a provider that sent runtimes, so a row's second line reads `S1:E2 · 24 min`.
     *
     * Resume is **episode 2**, the middle one, so the pill marks a row that is neither the first nor
     * the last. A fixture resuming nothing could not tell "marks the primary's target" apart from
     * "always marks row 1", and one resuming the last could not tell it from "marks the last".
     */
    val RuntimeEpisodes = Show.copy(
        episodes = episodes(3, runTimeMinutes = EPISODE_MINUTES),
        resume = DetailResume(
            "e1",
            positionMs = 1_000_000L,
            durationMs = 2_620_000L,
            episode = episodes(3)[1],
        ),
    )

    /**
     * Board 7i's chip row with all four kinds of group at once: two numbered seasons, a specials
     * season, and episodes the provider never gave a season.
     *
     * Named per group so a filtered list is identifiable by text alone, and deliberately **not** in
     * chip order — season 2 is declared before season 1 and Specials before both — so the ordering
     * the chips draw is the code's doing and not the fixture's.
     */
    val SeasonGroups = Show.copy(
        episodes = listOf(
            DetailEpisode("Specials One", "sp0", SPECIALS_SEASON_FIXTURE, 1, EPISODE_MINUTES),
            DetailEpisode("Two One", "s2e0", 2, 1, EPISODE_MINUTES),
            DetailEpisode("Two Two", "s2e1", 2, 2, EPISODE_MINUTES),
            DetailEpisode("One One", "s1e0", 1, 1, EPISODE_MINUTES),
            DetailEpisode("Unnumbered One", "x0", null, null, EPISODE_MINUTES),
        ),
        seasons = emptyList(),
        resume = null,
        playData = null,
    )

    /** One season only, so 7i draws no chip row at all: a lone chip filters nothing. */
    val OneSeasonOnly = Show.copy(
        episodes = episodes(3, runTimeMinutes = EPISODE_MINUTES),
        resume = null,
        playData = null,
    )

    /** Forty, so the list has to scroll and the last row still has to be reachable. */
    val FortyEpisodes = Show.copy(
        episodes = episodes(FORTY),
        seasons = listOf(DetailSeason(number = 1, episodeCount = FORTY)),
        resume = null,
    )

    val LongTitle = "The Unreasonably Long Title Of A Station Decommissioned Forty Years Ago And Still Broadcasting"

    val LongPlot = Show.plot.orEmpty().repeat(PLOT_REPEATS)

    /** Both boxes are fixed, so neither of these may grow one. */
    val LongText = Show.copy(title = LongTitle, plot = LongPlot)

    /**
     * A stored position of zero. [DetailResume.fraction] is null for it, which is the guard
     * `ResultEpisode.getWatchProgress()` is missing, so the primary must read Play and the caption slot
     * must stay empty.
     */
    val ResumeAtZero = Show.copy(
        resume = DetailResume("e0", positionMs = 0L, durationMs = 2_620_000L, episode = null),
    )
}
