package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.detail.toTracks
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteSubtitle
import com.lagradost.cloudstream4.media.model.SubtitleSource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleTracksTest {
    private fun subtitle(language: String, tag: String? = null, url: String = "https://a/$language") =
        RemoteSubtitle(language, tag, url, null, null)

    @Test
    fun `a bare code becomes its language, and a name stays what the plugin called it`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            val labels = listOf(subtitle("deu", "de"), subtitle("Deutsch", "de"), subtitle("asr")).toTracks().map { it.label }
            assertEquals(listOf("German", "Deutsch", "asr"), labels)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `two rows never read the same`() {
        val labels = listOf(
            subtitle("English", "en", "https://a/1"),
            subtitle("English", "en", "https://a/2"),
            subtitle("English", "en", "https://a/3"),
        ).toTracks().map { it.label }
        assertEquals(listOf("English", "English 2", "English 3"), labels)
    }

    @Test
    fun `only the two headers travel`() {
        val track = listOf(RemoteSubtitle("x", null, "https://a/", "https://r/", "ua")).toTracks().single()
        assertEquals(mapOf("Referer" to "https://r/", "User-Agent" to "ua"), (track.source as SubtitleSource.Remote).headers)
    }
}
