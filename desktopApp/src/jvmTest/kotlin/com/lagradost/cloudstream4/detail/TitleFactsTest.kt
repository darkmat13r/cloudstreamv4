package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteDetails
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteFacts
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteShowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the sandbox says about a title, on the Mac's title page (`KMP-76`). */
class TitleFactsTest {

    private val details = RemoteDetails("Name", "u", "TvSeries", "a<br>plot", null, 2024, null, emptyList())

    @Test
    fun `each fact lands on the page, the score out of ten`() {
        val page = details.copy(
            facts = RemoteFacts(84, listOf("Drama", "Crime"), "TV-MA", RemoteShowStatus.Ongoing, 48),
        ).toTitleDetail("Provider")

        assertEquals(8.4f, page.score)
        assertEquals(listOf("Drama", "Crime"), page.tags)
        assertEquals("TV-MA", page.contentRating)
        assertEquals("Ongoing", page.statusName, "the key the page's words are found by")
        assertEquals(48, page.durationMinutes)
        assertEquals("Provider", page.providerName)
        assertEquals("a plot", page.plot)
    }

    @Test
    fun `a title with no facts has none on its page`() {
        val page = details.toTitleDetail("Provider")

        assertNull(page.score)
        assertEquals(emptyList(), page.tags)
        assertNull(page.contentRating)
        assertNull(page.statusName)
        assertNull(page.durationMinutes)
        assertEquals("Completed", details.copy(facts = RemoteFacts(status = RemoteShowStatus.Completed)).toTitleDetail("p").statusName)
    }

    @Test
    fun `the backdrop the sandbox kept is the page's, and none is none, not the poster`() {
        val withArt = details.copy(posterUrl = "https://img.example/p.jpg", backdropUrl = "https://img.example/b.jpg")
        assertEquals("https://img.example/b.jpg", withArt.toTitleDetail("p").backdropUrl)
        assertNull(withArt.copy(backdropUrl = null).toTitleDetail("p").backdropUrl)
    }
}
