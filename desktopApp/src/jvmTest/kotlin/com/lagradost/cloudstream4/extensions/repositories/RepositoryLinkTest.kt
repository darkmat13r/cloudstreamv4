package com.lagradost.cloudstream4.extensions.repositories

import kotlin.test.Test
import kotlin.test.assertEquals

/** `KMP-110`: what someone types into "Add repository". Nothing here touches the network. */
class RepositoryLinkTest {
    @Test
    fun `an https URL is used as typed, trimmed`() {
        assertEquals(
            RepositoryLink.Https("https://files.example/harbour/repo.json"),
            parseRepositoryLink("  https://files.example/harbour/repo.json \n"),
        )
    }

    @Test
    fun `app links and share links point at the repository they carry`() {
        val expected = RepositoryLink.Https("https://files.example/repo.json")
        assertEquals(expected, parseRepositoryLink("cloudstreamrepo://files.example/repo.json"))
        assertEquals(expected, parseRepositoryLink("cloudstreamrepo://https://files.example/repo.json"))
        assertEquals(expected, parseRepositoryLink("https://cs.repo/?https://files.example/repo.json"))
        assertEquals(expected, parseRepositoryLink("https://cs.repo/files.example/repo.json"))
    }

    @Test
    fun `a short code is not sent anywhere, and says so`() {
        listOf("harbour", "!harbour", "Salt_Road-2").forEach {
            assertEquals(RepositoryLink.Shortcode, parseRepositoryLink(it), it)
        }
    }

    @Test
    fun `http, however it arrives, is not https`() {
        listOf(
            "http://files.example/repo.json",
            "HTTP://files.example/repo.json",
            "cloudstreamrepo://http://files.example/repo.json",
            "https://cs.repo/?http://files.example/repo.json",
        ).forEach { assertEquals(RepositoryLink.NotHttps, parseRepositoryLink(it), it) }
    }

    @Test
    fun `anything else is not a link`() {
        listOf(
            "", "   ", "file:///etc/hosts", "javascript:alert(1)", "ftp://files.example/repo.json",
            "https://user:secret@files.example/repo.json", "https://", "https://files.example/" + "a".repeat(MAX_LINK_CHARS),
            "two words", "files.example/repo.json",
        ).forEach { assertEquals(RepositoryLink.Invalid, parseRepositoryLink(it), it) }
    }
}
