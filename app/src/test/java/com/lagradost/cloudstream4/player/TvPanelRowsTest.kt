package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [TvPanelRows] is 7e's security boundary (`KMP-66`): a source's and a subtitle's row words come from objects that carry
 * their URL and headers, and only a display name and a quality may come out.
 */
class TvPanelRowsTest {

    @Suppress("DEPRECATION")
    private fun link(name: String, quality: Int = 1080) = ExtractorLink(
        source = "sourceone",
        name = name,
        url = "https://stream.example.invalid/$SECRET/master.m3u8?token=$TOKEN",
        referer = "https://referer.example.invalid/$SECRET",
        quality = quality,
        headers = mapOf("Cookie" to "session=$TOKEN"),
        type = ExtractorLinkType.M3U8,
    )

    private fun subtitle(name: String, suffix: String = "") = SubtitleData(
        originalName = name,
        nameSuffix = suffix,
        url = "https://subs.example.invalid/$SECRET.srt?token=$TOKEN",
        origin = SubtitleOrigin.URL,
        mimeType = "application/x-subrip",
        headers = mapOf("Authorization" to "Bearer $TOKEN"),
        languageCode = "en",
    )

    @Test
    fun aSourceRowIsItsNameAndQuality() {
        assertEquals("Source One · 1080p", TvPanelRows.sourceTitle(link("Source One"), null, "Source 1"))
        assertEquals("Source One · 4K", TvPanelRows.sourceTitle(link("Source One", Qualities.P2160.value), null, "Source 1"))
        assertEquals("Source Three", TvPanelRows.sourceTitle(link("Source Three", Qualities.Unknown.value), null, "Source 1"))
    }

    @Test
    fun noUrlTokenOrHeaderReachesASourceRow() {
        for (name in listOf(
            "Source One",
            "https://cdn.example.invalid/$SECRET",
            "cdn.host.to/v/$SECRET?token=$TOKEN",
            "host.com:8443/$SECRET",
            "Mirror ?sig=$TOKEN",
            "stream.example.invalid",
        )) {
            val title = TvPanelRows.sourceTitle(link(name), null, "Source 2")
            for (secret in listOf(SECRET, TOKEN, "example.invalid", "http")) {
                assertFalse("`$secret` reached a source row: $title", secret in title)
            }
        }
        assertEquals("Source 2 · 1080p", TvPanelRows.sourceTitle(link("cdn.host.to/v/$SECRET"), null, "Source 2"))
    }

    @Test
    fun noUrlOrHeaderReachesASubtitleRow() {
        val group = TvPanelRows.subtitleGroupTitle(subtitle("English"), "Subtitles 1")
        val variant = TvPanelRows.subtitleVariantTitle(subtitle("English", "<b>Forced</b>"), "Online")
        assertEquals("English", group)
        assertEquals("Forced", variant)
        assertEquals("Subtitles 1", TvPanelRows.subtitleGroupTitle(subtitle("https://subs.example.invalid/$SECRET"), "Subtitles 1"))
        assertEquals("Online", TvPanelRows.subtitleVariantTitle(subtitle("English", "subs.example.invalid/$SECRET"), "Online"))
        for (text in listOf(group, variant)) assertFalse(text, SECRET in text || TOKEN in text)
    }

    private companion object {
        const val SECRET = "PANELSECRET42"
        const val TOKEN = "PANELTOKEN42"
    }
}
