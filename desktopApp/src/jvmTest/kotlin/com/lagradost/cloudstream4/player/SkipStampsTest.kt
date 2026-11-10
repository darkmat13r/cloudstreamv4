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

    private val length = 1_440_500L

    @Test
    fun `the playhead is in a stamp from its start until a seek's slack before its end`() {
        val stamps = listOf(opening, ending)
        assertNull(stamps.at(89_999, length))
        assertEquals(opening, stamps.at(90_000, length))
        assertEquals(opening, stamps.at(179_698, length))
        assertNull(stamps.at(179_699, length), "a seek's slack before the end")
        assertNull(stamps.at(180_001, length), "where Skip lands")
        assertEquals(ending, stamps.at(1_420_000, length))
    }

    @Test
    fun `a stamp past the end of the stream playing is not offered, nor any before its length is known`() {
        val stamps = listOf(opening, ending)
        assertEquals(ending, stamps.at(1_420_000, 1_440_000), "one ending at the end is inside")
        assertNull(stamps.at(1_420_000, 1_439_999), "a shorter cut: past its end")
        assertEquals(opening, stamps.at(100_000, 1_439_999), "the others still are")
        assertNull(stamps.at(100_000, 0))
    }

    @Test
    fun `Skip seeks just past the end, held inside the stream`() {
        assertEquals(180_001f / 1_000_000, skipTarget(opening, 1_000_000))
        assertEquals(1_439_999f / 1_440_000, skipTarget(ending, 1_440_000), "a stamp ending at the end: just before it")
        assertTrue(skipTarget(SkipStamp(SkipKind.Ending, 0, 5_000_000), 1_440_000) < 1f, "never the end itself")
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
