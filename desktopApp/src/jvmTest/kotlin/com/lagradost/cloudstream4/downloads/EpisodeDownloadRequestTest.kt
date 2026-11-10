package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.search.DesktopSearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** `KMP-109`: a title page's episode as a download request, and the ids it is filed under. */
class EpisodeDownloadRequestTest {
    private val titleKey = DesktopSearchSource.encodeId(PROVIDER, "https://fixture.example/show")
    private val detail = TitleDetail(
        title = "Fixture Show",
        providerName = PROVIDER,
        typeName = "TvSeries",
        year = 2020,
        plot = null,
        posterUrl = "https://fixture.example/poster.jpg",
        playData = null,
        episodes = emptyList(),
    )
    private val episode = DetailEpisode(name = "The Second", data = EPISODE_DATA, season = 1, episode = 2)

    @Test
    fun `the ids are the same on every run, written down once from SHA-256 outside the app`() {
        // Computed with Python's hashlib over the same length-prefixed bytes, not by this code. A change to the
        // encoding moves every saved download to a new id, so these are fixed numbers, not a second call.
        assertEquals(1_270_117_470, EpisodeDownloadIds.episodeId(PROVIDER, EPISODE_DATA))
        assertEquals(-361_870_211, EpisodeDownloadIds.titleId(titleKey))
    }

    @Test
    fun `Aa and BB, which String hashCode gives one value, get different ids`() {
        assertEquals("Aa".hashCode(), "BB".hashCode(), "the collision this replaces")
        assertEquals(-680_888_560, EpisodeDownloadIds.titleId("Aa"))
        assertEquals(-1_783_088_319, EpisodeDownloadIds.titleId("BB"))
        assertNotEquals(EpisodeDownloadIds.episodeId("Aa", "x"), EpisodeDownloadIds.episodeId("BB", "x"))
    }

    @Test
    fun `the parts are length-prefixed, so moving a character between them changes the id`() {
        assertEquals(-225_206_384, EpisodeDownloadIds.idOf("ab", "c"))
        assertEquals(-1_254_830_570, EpisodeDownloadIds.idOf("a", "bc"))
    }

    @Test
    fun `the request carries the episode and how to ask for it again, and no link`() {
        val request = episodeDownloadRequest(titleKey, detail, episode)
        assertEquals(EpisodeDownloadIds.episodeId(PROVIDER, EPISODE_DATA), request.episodeId)
        assertEquals(EpisodeDownloadIds.titleId(titleKey), request.titleId)
        assertEquals("Fixture Show", request.titleName)
        assertEquals(DownloadTitleKind.Series, request.kind)
        assertEquals(1, request.season)
        assertEquals(2, request.episode)
        assertEquals("The Second", request.episodeName)
        assertEquals(DownloadSourceRef(PROVIDER, EPISODE_DATA), request.source)
        assertEquals("https://fixture.example/poster.jpg", request.posterUrl)
        assertNull(request.link, "the downloads service asks the extension, through its own checks")
    }

    @Test
    fun `two episodes of one title share its id and not their own`() {
        val other = episodeDownloadRequest(titleKey, detail, episode.copy(data = "https://fixture.example/show/1/3", episode = 3))
        val first = episodeDownloadRequest(titleKey, detail, episode)
        assertEquals(first.titleId, other.titleId)
        assertNotEquals(first.episodeId, other.episodeId)
    }

    private companion object {
        const val PROVIDER = "Fixture Provider"
        const val EPISODE_DATA = "https://fixture.example/show/1/2"
    }
}
