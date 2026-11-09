package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.TitleIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which stamp the playhead is in, and what Skip does there (`KMP-86`): Android's rules. */
class SkipStampsTest {
    private val opening = SkipStamp(SkipKind.Opening, 90_000, 180_000)
    private val ending = SkipStamp(SkipKind.Ending, 1_400_000, 1_440_000)

    @Test
    fun `the playhead is in a stamp from its start until a seek's slack before its end`() {
        val stamps = listOf(opening, ending)
        assertNull(stamps.at(89_999))
        assertEquals(opening, stamps.at(90_000))
        assertEquals(opening, stamps.at(179_698))
        assertNull(stamps.at(179_699), "a seek's slack before the end")
        assertNull(stamps.at(180_001), "where Skip lands")
        assertEquals(ending, stamps.at(1_420_000))
    }

    @Test
    fun `Skip plays the next episode only for a stamp ending in the last 20 s, and only with one to play`() {
        assertTrue(ending.skipsToNext(1_440_500, hasNext = true))
        assertFalse(ending.skipsToNext(1_440_500, hasNext = false))
        assertFalse(opening.skipsToNext(1_440_500, hasNext = true))
        assertFalse(ending.skipsToNext(1_460_000, hasNext = true), "20 s from the end is not within it")
    }

    @Test
    fun `the query is the playing episode's numbers, and a title played whole is a film`() {
        val detail = TitleDetail(
            title = "Harbor Lights",
            providerName = "Source One",
            typeName = "TvSeries",
            year = null,
            plot = null,
            posterUrl = null,
            playData = null,
            episodes = listOf(DetailEpisode("One", "e1", 1, 1), DetailEpisode("Two", "e2", 2, 7)),
            ids = TitleIds(imdbId = "tt0388629"),
        )
        assertEquals(SkipQuery("TvSeries", TitleIds(imdbId = "tt0388629"), 2, 7, movie = false), skipQuery(detail, "e2"))
        assertEquals(SkipQuery("TvSeries", TitleIds(imdbId = "tt0388629"), null, null, movie = true), skipQuery(detail, null))
        assertTrue(skipQuery(detail.copy(typeName = "Movie"), "e1").movie)
    }
}
