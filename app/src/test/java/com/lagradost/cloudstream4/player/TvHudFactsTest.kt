package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.feature.player.HudBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [toHudState] is the TV HUD's security boundary (`KMP-64`): a stream's URL, its headers and its referer go in with the
 * link, and none of them may come out in the HUD's state, which is what the screen and its semantics tree are drawn
 * from.
 */
class TvHudFactsTest {

    @Suppress("DEPRECATION")
    private fun link(name: String = "Source One") = ExtractorLink(
        source = "sourceone",
        name = name,
        url = "https://stream.example.invalid/$SECRET_PATH/master.m3u8?token=$SECRET_TOKEN",
        referer = "https://referer.example.invalid/$SECRET_REFERER",
        quality = 1080,
        headers = mapOf("Cookie" to "session=$SECRET_COOKIE", "Authorization" to "Bearer $SECRET_TOKEN"),
        type = ExtractorLinkType.M3U8,
    )

    private fun facts(
        title: String = "Quiet Harbor",
        link: ExtractorLink? = link(),
        downloaded: Boolean = false,
        subtitle: String? = "English (SRT)",
        offline: Boolean = false,
        failed: Boolean = false,
    ) = TvHudFacts(
        title = title,
        season = 1,
        episode = 4,
        episodeName = "Low Tide",
        videoHeight = 1080,
        videoCodec = "AVC",
        audioLanguage = "English",
        audioChannels = "5.1",
        audioCodec = "EAC3",
        link = link,
        downloaded = downloaded,
        sourceFallback = "Downloaded",
        subtitle = subtitle,
        playing = true,
        buffering = false,
        positionMs = 2_533_000,
        durationMs = 3_704_000,
        bufferedMs = 3_100_000,
        speed = 1f,
        marks = listOf(1_200_000, 3_200_000),
        skipLabel = "Skip opening",
        hasPrevious = true,
        hasNext = true,
        nextSeason = 1,
        nextEpisode = 5,
        nextName = "The Night the Lighthouse Keeper Stayed",
        failed = failed,
        offline = offline,
    )

    @Test
    fun theBoardsLinesComeOutOfTheBoardsFacts() {
        val state = facts().toHudState()
        assertEquals("Quiet Harbor", state.title)
        assertEquals("S1:E4 · Low Tide · 1080p · AVC · English 5.1", state.meta)
        assertEquals("Source One · 1080p", state.source)
        assertEquals("English · 5.1 · EAC3", state.audio)
        assertEquals("English (SRT)", state.subtitles)
        assertEquals("S1:E5 The Night the Lighthouse Keeper Stayed", state.upNext)
        assertEquals("Skip opening", state.skip?.label)
        assertEquals(listOf(1_200_000L, 3_200_000L), state.marks)
    }

    @Test
    fun noUrlHeaderOrRefererReachesTheState() {
        val everything = facts().toHudState().toString()
        for (secret in listOf(SECRET_PATH, SECRET_TOKEN, SECRET_REFERER, SECRET_COOKIE, "example.invalid", "http")) {
            assertFalse("`$secret` reached the HUD: $everything", secret in everything)
        }
    }

    /** A provider can put anything in a link's name. One that reads as an address is dropped, not shown. */
    @Test
    fun aLinkNameThatIsAnAddressIsDropped() {
        for (name in listOf("https://cdn.example.invalid/$SECRET_PATH", "/storage/emulated/0/$SECRET_PATH", "content://x/$SECRET_PATH")) {
            val state = facts(link = link(name = name), subtitle = name).toHudState()
            assertEquals("1080p", state.source)
            assertNull(state.subtitles)
            assertFalse(state.toString(), SECRET_PATH in state.toString())
        }
    }

    /**
     * Addresses with no scheme and token-bearing names (review of `KMP-64`): a bare host with a path, a port or a query,
     * a name carrying a signature, a backslash path. Each is dropped from a source's and a subtitle's name.
     */
    @Test
    fun schemelessAddressesAndTokenNamesAreDroppedToo() {
        for (name in listOf(
            "cdn.host.to/v/$SECRET_PATH?token=$SECRET_TOKEN",
            "host.com:8443/$SECRET_PATH",
            "stream.example.invalid",
            "Mirror ?sig=$SECRET_TOKEN",
            "Mirror sig=$SECRET_TOKEN",
            "C:\\$SECRET_PATH\\movie.mkv",
        )) {
            val state = facts(link = link(name = name), subtitle = name).toHudState()
            assertEquals(name, "1080p", state.source)
            assertNull(name, state.subtitles)
            assertFalse(state.toString(), SECRET_PATH in state.toString() || SECRET_TOKEN in state.toString())
        }
    }

    /** The stricter rule costs a real name with a slash its label, and the row says the quality alone. */
    @Test
    fun aNameWithASlashFallsBackToTheQuality() {
        assertEquals("1080p", facts(link = link(name = "Server 1/2")).toHudState().source)
    }

    /** Titles keep their punctuation and dotted words (review of `KMP-64`): only an address is dropped from them. */
    @Test
    fun aTitleWithAQuestionMarkStays() {
        val state = facts(title = "Harbor Lights...?").toHudState()
        assertEquals("Harbor Lights...?", state.title)
        for (title in listOf("Harbor Lights...?", "Dr.Harbor", "Mr.Lantern", "Vol.II")) {
            assertEquals(title, facts(title = title).toHudState().title)
        }
        // A title that is an address still goes.
        assertEquals("", facts(title = "cdn.host.to/v/$SECRET_PATH").toHudState().title)
    }

    @Test
    fun providerTextLosesControlCharacters() {
        val state = facts(link = link(name = "Sou\nrce\u0007 One")).toHudState()
        assertEquals("Source One · 1080p", state.source)
    }

    @Test
    fun aDownloadSaysSoAndItsOfflineBannerNeedsTheDownload() {
        val local = facts(link = null, downloaded = true, offline = true).toHudState()
        assertTrue(local.downloaded)
        assertEquals("Downloaded · 1080p", local.source)
        assertEquals(HudBanner.OfflineCopy, local.banner)

        assertNull("streaming offline is the player's error, not this banner", facts(offline = true).toHudState().banner)
        assertEquals(HudBanner.Failed, facts(failed = true).toHudState().banner)
    }

    private companion object {
        const val SECRET_PATH = "PATHSECRET42"
        const val SECRET_TOKEN = "TOKENSECRET42"
        const val SECRET_REFERER = "REFERERSECRET42"
        const val SECRET_COOKIE = "COOKIESECRET42"
    }
}
