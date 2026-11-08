package com.lagradost.cloudstream4.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `KMP-71`: a plugin's title or episode name that reads as an address names nothing and is not kept. */
class DownloadPathsTest {
    @Test
    fun `an address is dropped from a title and an episode name`() {
        for (text in ADDRESSES) {
            assertNull(DownloadPaths.displayText(text), text)
            assertEquals("Download", DownloadPaths.titleText(text), text)
            assertEquals("Download", DownloadPaths.segment(DownloadPaths.titleText(text)), text)
        }
    }

    /** The same samples as the HUD's title rule keeps (`KMP-66`): dotted titles and punctuation stay. */
    @Test
    fun `dotted titles and punctuation stay`() {
        for (title in listOf("What If...?", "Dr.Stone", "Mr.Robot", "Vol.II")) {
            assertEquals(title, DownloadPaths.displayText(title))
            assertEquals(title, DownloadPaths.titleText(title))
        }
    }

    @Test
    fun `a blank title stays blank, a blank episode name stays null`() {
        assertEquals("", DownloadPaths.titleText("  "))
        assertEquals("", DownloadPaths.titleText(null))
        assertNull(DownloadPaths.displayText("​ "))
    }

    @Test
    fun `an episode whose name was an address is saved by its code`() {
        val name = DownloadPaths.fileName(
            titleName = "Show",
            season = 1,
            episode = 5,
            episodeName = DownloadPaths.displayText(ADDRESSES.first()),
            isFilm = false,
        ) { false }
        assertEquals("S01E05.mp4", name)
    }

    companion object {
        const val SECRET_PATH = "PATHSECRET42"
        const val SECRET_TOKEN = "TOKENSECRET42"

        /**
         * Copied from the HUD's tests (`TvHudFactsTest`, `KMP-64`), the ones its title rule drops. The rest there (a
         * bare host, `sig=`, a backslash path) are dropped only from a source's name, never from a title.
         */
        val ADDRESSES = listOf(
            "https://cdn.example.invalid/$SECRET_PATH",
            "/storage/emulated/0/$SECRET_PATH",
            "content://x/$SECRET_PATH",
            "cdn.host.to/v/$SECRET_PATH?token=$SECRET_TOKEN",
            "host.com:8443/$SECRET_PATH",
        )
    }
}
