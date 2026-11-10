@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.result.episodeSlots
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailSeason
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.EpisodeWatchReadout
import com.lagradost.cloudstream4.feature.detail.watchReadout
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.decodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from what a provider returns to what the screen reads, and the two id decisions.
 *
 * Plain JUnit: everything under test is either a pure function over `LoadResponse` or takes
 * [LocalDetailRecords], so the only layer not covered here is [DataStoreDetailRecords] itself, which
 * bottoms out in `SharedPreferences`.
 */
class AndroidDetailSourceTest {

    private class Records(
        val states: Map<Int, WatchType> = emptyMap(),
        val resume: Map<Int, DownloadObjects.ResumeWatching> = emptyMap(),
        val positions: Map<Int, DataStoreHelper.PosDur> = emptyMap(),
        val marks: Map<Int, VideoWatchState> = emptyMap(),
    ) : LocalDetailRecords {
        override fun watchState(id: Int) = states[id] ?: WatchType.NONE
        override fun bookmark(id: Int): DataStoreHelper.BookmarkedData? = null
        override fun favourite(id: Int): DataStoreHelper.FavoritesData? = null
        override fun lastWatched(id: Int) = resume[id]
        override fun viewPosition(id: Int) = positions[id]
        override fun videoWatchState(id: Int) = marks[id]
        override fun dub(id: Int): DubStatus? = null

        // The write path needs a registered provider to reach, so only the records it builds are
        // covered here — see `aBookmarkCarriesEveryFieldTheLibraryReads`.
        override fun writeWatchState(id: Int, status: WatchType, record: DataStoreHelper.BookmarkedData?) = Unit
        override fun writeFavourite(id: Int, record: DataStoreHelper.FavoritesData?) = Unit
        override fun announceBookmarks() = Unit
        override fun announceLibrary() = Unit
    }

    private fun episode(season: Int?, number: Int?, name: String? = null, data: String = "s$season-e$number") =
        Episode(data = data, name = name, season = season, episode = number)

    private fun series(
        episodes: List<Episode>,
        name: String = "Andor",
        plot: String? = null,
        status: ShowStatus? = null,
    ) = TvSeriesLoadResponse(
        name = name,
        url = URL,
        apiName = API,
        type = TvType.TvSeries,
        episodes = episodes,
        plot = plot,
        showStatus = status,
    )

    private fun movie(data: String = "movie-payload", plot: String? = null) = MovieLoadResponse(
        name = "Arrival",
        url = URL,
        apiName = API,
        type = TvType.Movie,
        dataUrl = data,
    ).also { it.plot = plot }

    private fun LoadResponse.map(
        records: LocalDetailRecords = Records(),
        titleId: Int = TITLE_ID,
        dub: DubStatus? = null,
    ) = detailEpisodes(dub).withProgress(records, episodeSlots(titleId).filter { it.dub == chosenDub(dub) }).let { episodes ->
        toTitleDetail(
            episodes = episodes,
            seasons = episodes.toSeasons(),
            resume = resumeOf(records, titleId, episodes, playData()),
            watchStatus = records.watchState(titleId).toDetailStatus(),
            isFavourite = records.favourite(titleId) != null,
        )
    }

    // -- episodes and seasons ------------------------------------------------------------------

    @Test
    fun episodesAreSortedBySeasonThenEpisodeAndNumberedWhenAProviderDidNot() {
        val detail = series(
            listOf(episode(2, 1), episode(1, 2), episode(1, 1), episode(null, null)),
        ).map()
        assertEquals(
            // The unnumbered one is numbered from its place in the sorted list, as `postEpisodes` does.
            listOf(null to 1, 1 to 1, 1 to 2, 2 to 1),
            detail.episodes.map { it.season to it.episode },
        )
    }

    /** A season button draws a translated word plus the integer, so the count comes from the parts. */
    @Test
    fun seasonsAreTheProvidersIntegersWithTheirOwnCounts() {
        val detail = series(listOf(episode(1, 1), episode(1, 2), episode(2, 1))).map()
        assertEquals(listOf(DetailSeason(1, 2), DetailSeason(2, 1)), detail.seasons)
    }

    /** `Episode 12` is what the screen's own copy already says, so the provider's copy of it goes. */
    @Test
    fun aNameThatIsJustTheEpisodeNumberIsDropped() {
        val detail = series(listOf(episode(1, 12, "Episode 12"), episode(1, 13, "Rix Road"))).map()
        assertEquals(listOf(null, "Rix Road"), detail.episodes.map { it.name })
    }

    @Test
    fun aMovieHasNoEpisodesAndItsPayloadIsThePlayData() {
        val detail = movie().map()
        assertEquals(emptyList<DetailEpisode>(), detail.episodes)
        assertEquals("movie-payload", detail.playData)
    }

    /** `isInvalidData`'s three junk values, which `loadLinks` refuses anyway. */
    @Test
    fun aMovieWithNothingPlayableHasNoPlayData() {
        listOf("", "[]", "about:blank").forEach { junk ->
            assertNull(junk, movie(data = junk).map().playData)
        }
    }

    @Test
    fun animeShowsThePreferredDubAndFallsBackToTheFirstNonEmptyList() {
        val response = AnimeLoadResponse(
            name = "Frieren",
            url = URL,
            apiName = API,
            type = TvType.Anime,
            episodes = mutableMapOf(
                DubStatus.Subbed to listOf(episode(1, 1, data = "sub-1")),
                DubStatus.Dubbed to listOf(episode(1, 1, data = "dub-1"), episode(1, 2, data = "dub-2")),
            ),
        )
        assertEquals(listOf("dub-1", "dub-2"), response.map(dub = DubStatus.Dubbed).episodes.map { it.data })
        assertEquals(listOf("sub-1"), response.map(dub = DubStatus.Subbed).episodes.map { it.data })
        // No stored choice: the first non-empty list the provider added, not dubbed over subbed.
        assertEquals(listOf("sub-1"), response.map(dub = null).episodes.map { it.data })
        // And a stored choice the provider sent nothing for falls back the same way.
        assertEquals(listOf("sub-1"), response.map(dub = DubStatus.None).episodes.map { it.data })
    }

    // -- the rest of the title ------------------------------------------------------------------

    @Test
    fun theTitleCarriesEveryFieldTheBoardDraws() {
        val response = series(listOf(episode(1, 1)), status = ShowStatus.Ongoing).also {
            it.year = 2022
            it.posterUrl = "https://example.com/p.jpg"
            it.backgroundPosterUrl = "https://example.com/b.jpg"
            it.score = Score.from10(8.5)
            it.tags = listOf("Drama", " ", "Sci-Fi")
            it.contentRating = "TV-14"
            it.duration = 45
        }
        val detail = response.map()
        assertEquals("Andor", detail.title)
        assertEquals(API, detail.providerName)
        assertEquals(TvType.TvSeries.name, detail.typeName)
        assertEquals(2022, detail.year)
        assertEquals("https://example.com/b.jpg", detail.backdropUrl)
        assertEquals(8.5f, detail.score!!, SCORE_TOLERANCE)
        assertEquals(listOf("Drama", "Sci-Fi"), detail.tags)
        assertEquals("TV-14", detail.contentRating)
        assertEquals(ShowStatus.Ongoing.name, detail.statusName)
        assertEquals(45, detail.durationMinutes)
    }

    /** A `<br>` in a YouTube description is the known case. Nothing downstream parses HTML. */
    @Test
    fun markupIsStrippedFromThePlot() {
        val detail = series(
            listOf(episode(1, 1)),
            plot = "<p>One line.<br/>Another   <b>line</b>.</p>",
        ).map()
        assertEquals("One line. Another line.", detail.plot)
    }

    @Test
    fun aBlankPlotIsNullRatherThanAnEmptyBox() {
        assertNull(series(listOf(episode(1, 1)), plot = "<p> </p>").map().plot)
    }

    /**
     * The defect this mapping exists to fix: `toResultData` reads only the first actor's image and
     * nulls the whole list when it is blank.
     */
    @Test
    fun aMissingHeadshotRemovesOnePersonsImageAndNotTheRail() {
        val response = series(listOf(episode(1, 1))).also {
            it.actors = listOf(
                ActorData(Actor("Diego Luna", image = null), roleString = "Cassian"),
                ActorData(Actor("Stellan Skarsgård", image = "https://example.com/a.jpg"), ActorRole.Main),
                ActorData(Actor("  "), roleString = "Nobody"),
            )
        }
        val cast = response.map().cast
        assertEquals(listOf("Diego Luna", "Stellan Skarsgård"), cast.map { it.name })
        assertEquals(listOf(null, "https://example.com/a.jpg"), cast.map { it.imageUrl })
        assertEquals(listOf("Cassian", ActorRole.Main.name), cast.map { it.role })
    }

    /** A recommendation must reopen by the same codec a search hit does. */
    @Test
    fun aRecommendationsIdRoundTripsThroughTheSearchCodec() {
        val response = series(listOf(episode(1, 1))).also {
            it.recommendations = listOf(
                MovieSearchResponse(name = "Rogue One", url = "https://x.test/a:b|c", apiName = "Other").also { hit ->
                    hit.year = 2016
                },
            )
        }
        val recommendation = response.map().recommendations.single()
        assertEquals("Rogue One", recommendation.title)
        assertEquals(2016, recommendation.year)
        val target = decodeId(SearchResultId(recommendation.id))
        assertEquals("Other", target?.apiName)
        assertEquals("https://x.test/a:b|c", target?.url)
    }

    @Test
    fun aTrailerIsOfferedOnlyWhenOneExistsAndThePreferenceAllowsIt() {
        val withTrailer = series(listOf(episode(1, 1))).also {
            it.trailers = mutableListOf(TrailerData("https://t.test/x", null, false))
        }
        assertTrue(withTrailer.map().hasTrailer)
        LoadResponse.isTrailersEnabled = false
        try {
            assertFalse(withTrailer.map().hasTrailer)
        } finally {
            LoadResponse.isTrailersEnabled = true
        }
        assertFalse(series(listOf(episode(1, 1))).map().hasTrailer)
    }

    // -- links, which nothing on the TV path calls yet ------------------------------------------

    /**
     * The flow itself needs a registered provider, so only its mapping is covered here. The screen
     * draws these three fields and a quality a provider did not state must not read as `0p`.
     */
    @Test
    fun aStreamCarriesItsQualityItsRefererAndTheSubtitlesKnownSoFar() {
        val subtitles = listOf(
            subtitle("English", "https://s.test/en.vtt", mapOf("Referer" to "https://r.test")),
        ).toTracks()
        val link = ExtractorLink(
            source = "Src",
            name = "Mirror 1",
            url = "https://v.test/a.m3u8",
            referer = "https://r.test",
            quality = 1080,
            type = ExtractorLinkType.M3U8,
            headers = mapOf("User-Agent" to "UA/1"),
        )
        val stream = link.toStreamLink(subtitles)
        assertEquals("Mirror 1", stream.label)
        assertEquals("1080p", stream.qualityLabel)
        assertEquals("https://r.test", stream.referer)
        assertEquals("UA/1", stream.userAgent)
        assertEquals(listOf("English"), stream.subtitles.map { it.label })
        assertNull(link.copyLinkWith(quality = 0).toStreamLink(emptyList()).qualityLabel)
    }

    /** A provider offering the same words twice must not draw two rows that read the same. */
    @Test
    fun twoSubtitlesWithOneNameAreToldApart() {
        val tracks = listOf(
            subtitle("German", "https://s.test/1.vtt"),
            subtitle("German", "https://s.test/2.vtt"),
        ).toTracks()
        assertEquals(listOf("German", "German 2"), tracks.map { it.label })
    }

    private fun subtitle(lang: String, url: String, headers: Map<String, String>? = null) =
        SubtitleFile(lang, url).also { it.headers = headers }

    private fun ExtractorLink.copyLinkWith(quality: Int) = ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        type = type,
        headers = headers,
    )

    // -- resume, which is the id question ------------------------------------------------------

    /** The seam hands back the episode id; the part is found by matching season and episode. */
    @Test
    fun resumeComesFromTheStoredEpisodeIdAndNotFromArithmetic() {
        val records = Records(
            resume = mapOf(TITLE_ID to DownloadObjects.ResumeWatching(TITLE_ID, EPISODE_ID, 2, 1, 0L, false)),
            positions = mapOf(EPISODE_ID to DataStoreHelper.PosDur(60_000L, 240_000L)),
        )
        val detail = series(listOf(episode(1, 1), episode(1, 2))).map(records)
        val resume = detail.resume!!
        assertEquals("s1-e2", resume.data)
        assertEquals(60_000L, resume.positionMs)
        assertEquals(DetailEpisode(name = null, data = "s1-e2", season = 1, episode = 2), resume.episode)
        assertEquals(0.25f, resume.fraction!!, SCORE_TOLERANCE)
        assertEquals(3, resume.remainingMinutes)
        // `primaryData` is what the one button plays: where the viewer stopped.
        assertEquals("s1-e2", detail.primaryData)
    }

    /** A film's position is kept under the title's own id, which is why no episode is needed. */
    @Test
    fun aMoviesResumeIsReadUnderTheTitlesOwnId() {
        val records = Records(positions = mapOf(TITLE_ID to DataStoreHelper.PosDur(90_000L, 300_000L)))
        val resume = movie().map(records).resume!!
        assertEquals("movie-payload", resume.data)
        assertNull(resume.episode)
        assertEquals(90_000L, resume.positionMs)
    }

    @Test
    fun nothingStoredAndNothingUsableBothMeanNoResume() {
        val episodes = listOf(episode(1, 1))
        assertNull(series(episodes).map(Records()).resume)
        // A stored record whose episode the provider no longer sends, on a series with no play data.
        val stale = Records(
            resume = mapOf(TITLE_ID to DownloadObjects.ResumeWatching(TITLE_ID, EPISODE_ID, 9, 9, 0L, false)),
            positions = mapOf(EPISODE_ID to DataStoreHelper.PosDur(60_000L, 240_000L)),
        )
        assertNull(series(episodes).map(stale).resume)
        // A zero position is "never started", not "start from zero".
        val zero = Records(positions = mapOf(TITLE_ID to DataStoreHelper.PosDur(0L, 240_000L)))
        assertNull(movie().map(zero).resume)
    }

    /** `getWatchProgress()` divides with no guard and returns `NaN`; the contract returns null. */
    @Test
    fun aStoredDurationOfZeroIsNullRatherThanNaN() {
        val records = Records(positions = mapOf(TITLE_ID to DataStoreHelper.PosDur(60_000L, 0L)))
        val resume = movie().map(records).resume!!
        assertNull(resume.fraction)
        assertNull(resume.remainingMinutes)
    }

    // -- per-episode progress (`KMP-63`) ---------------------------------------------------------

    /** The id each row is read under, as `episodeSlots` gives it: the only derivation of it there is. */
    private fun LoadResponse.slotIds(): Map<String, Int> = episodeSlots(TITLE_ID).associate { it.data to it.id }

    /**
     * Each row reads its own position under its slot's id. A position kept under any other id — here the
     * naive `title + episode` a screen might re-derive — is one no row reads.
     */
    @Test
    fun eachRowReadsItsOwnPositionUnderItsSlotsId() {
        val show = series(listOf(episode(1, 1), episode(1, 2), episode(1, 3)))
        val ids = show.slotIds()
        val records = Records(
            positions = mapOf(
                ids.getValue("s1-e1") to DataStoreHelper.PosDur(2_300_000L, 2_400_000L),
                ids.getValue("s1-e2") to DataStoreHelper.PosDur(60_000L, 1_500_000L),
                TITLE_ID + 3 to DataStoreHelper.PosDur(60_000L, 1_500_000L),
            ),
        )
        val rows = show.map(records).episodes
        assertEquals(listOf("s1-e1", "s1-e2", "s1-e3"), rows.map { it.data })
        assertFalse("a slot id is not the naive sum", TITLE_ID + 3 in ids.values)

        val second = rows[1].progress!!
        assertEquals("s1-e2", second.data)
        assertEquals(60_000L, second.positionMs)
        assertEquals(1_500_000L, second.durationMs)
        assertNull("the row is the episode", second.episode)

        assertEquals(
            listOf(EpisodeWatchReadout.Watched, EpisodeWatchReadout.Left(24), EpisodeWatchReadout.None),
            rows.map { it.watchReadout() },
        )
        assertNull(rows[2].progress)
    }

    /** The old page's Mark watched stores a mark and no position; the row still reads watched. */
    @Test
    fun aWatchedMarkWithNoPositionIsCarried() {
        val show = series(listOf(episode(1, 1), episode(1, 2)))
        val records = Records(marks = mapOf(show.slotIds().getValue("s1-e2") to VideoWatchState.Watched))
        val rows = show.map(records).episodes
        assertEquals(listOf(false, true), rows.map { it.markedWatched })
        assertNull(rows[1].progress)
        assertEquals(listOf(EpisodeWatchReadout.None, EpisodeWatchReadout.Watched), rows.map { it.watchReadout() })
    }

    /** Anime keeps one id per dub list, so a position stored while watching dubbed is the dubbed row's. */
    @Test
    fun animeRowsReadTheChosenDubsIds() {
        val response = AnimeLoadResponse(
            name = "Frieren",
            url = URL,
            apiName = API,
            type = TvType.Anime,
            episodes = mutableMapOf(
                DubStatus.Subbed to listOf(episode(1, 1, data = "sub-1")),
                DubStatus.Dubbed to listOf(episode(1, 1, data = "dub-1")),
            ),
        )
        val dubbedId = response.slotIds().getValue("dub-1")
        assertFalse(dubbedId == response.slotIds().getValue("sub-1"))
        val records = Records(positions = mapOf(dubbedId to DataStoreHelper.PosDur(60_000L, 1_440_000L)))
        assertEquals(60_000L, response.map(records, dub = DubStatus.Dubbed).episodes.single().progress?.positionMs)
        assertNull(response.map(records, dub = DubStatus.Subbed).episodes.single().progress)
    }

    /** A zero position is "never started", as it is for the title's Resume. */
    @Test
    fun aZeroPositionIsNoProgress() {
        val show = series(listOf(episode(1, 1)))
        val records = Records(positions = mapOf(show.slotIds().getValue("s1-e1") to DataStoreHelper.PosDur(0L, 1_440_000L)))
        val row = show.map(records).episodes.single()
        assertNull(row.progress)
        assertEquals(EpisodeWatchReadout.None, row.watchReadout())
    }

    /**
     * `LocalDetailRecords` is in `classes.jar`, so the method this task adds has a body: an implementation
     * written before it, which this one stands in for, reads "no mark" rather than failing.
     */
    @Test
    fun recordsWrittenBeforeTheMarkExistedReadNoMark() {
        val older = object : LocalDetailRecords {
            override fun watchState(id: Int) = WatchType.NONE
            override fun bookmark(id: Int): DataStoreHelper.BookmarkedData? = null
            override fun favourite(id: Int): DataStoreHelper.FavoritesData? = null
            override fun lastWatched(id: Int): DownloadObjects.ResumeWatching? = null
            override fun viewPosition(id: Int): DataStoreHelper.PosDur? = null
            override fun dub(id: Int): DubStatus? = null
            override fun writeWatchState(id: Int, status: WatchType, record: DataStoreHelper.BookmarkedData?) = Unit
            override fun writeFavourite(id: Int, record: DataStoreHelper.FavoritesData?) = Unit
            override fun announceBookmarks() = Unit
            override fun announceLibrary() = Unit
        }
        assertNull(older.videoWatchState(TITLE_ID))
        assertEquals(listOf(false), series(listOf(episode(1, 1))).map(older).episodes.map { it.markedWatched })
    }

    // -- watch status and favourite -------------------------------------------------------------

    @Test
    fun everyWatchTypeMapsBothWaysWithNoOrdinalAssumption() {
        WatchType.entries.forEach { type ->
            assertEquals(type.name, type, type.toDetailStatus().toWatchType())
        }
        DetailWatchStatus.entries.forEach { status ->
            assertEquals(status.name, status, status.toWatchType().toDetailStatus())
        }
        assertEquals(DetailWatchStatus.None, WatchType.NONE.toDetailStatus())
    }

    /** The full record, so the library and home cards have the fields they read. */
    @Test
    fun aBookmarkCarriesEveryFieldTheLibraryReads() {
        val response = series(listOf(episode(1, 1))).also {
            it.year = 2022
            it.posterUrl = "https://example.com/p.jpg"
            it.plot = "Plot."
            it.tags = listOf("Drama")
            it.score = Score.from10(8.0)
        }
        val record = response.bookmarkOf(TITLE_ID, existing = null)
        assertEquals(TITLE_ID, record.id)
        assertEquals("Andor", record.name)
        assertEquals(URL, record.url)
        assertEquals(API, record.apiName)
        assertEquals(TvType.TvSeries, record.type)
        assertEquals(2022, record.year)
        assertEquals("Plot.", record.plot)
        assertEquals(listOf("Drama"), record.tags)
    }

    /** Re-picking a status must not look like a new bookmark; the library's default sort reads it. */
    @Test
    fun anExistingBookmarkKeepsItsOriginalTime() {
        val existing = series(listOf(episode(1, 1))).bookmarkOf(TITLE_ID, existing = null)
        val again = series(listOf(episode(1, 1))).bookmarkOf(TITLE_ID, existing = existing)
        assertEquals(existing.bookmarkedTime, again.bookmarkedTime)
    }

    private companion object {
        const val API = "Filmxy"
        const val URL = "https://example.com/title"
        const val TITLE_ID = 4242
        const val EPISODE_ID = 777
        const val SCORE_TOLERANCE = 0.001f
    }
}
