package com.lagradost.cloudstream4.feature.extensions.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals

/** A poster from an extension reaches the app only on https (`KMP-102`), on every page that carries one. */
class PosterUrlTest {

    private val kept = listOf("https://img.example/p.jpg", "HTTPS://img.example/p.jpg")

    private val dropped = listOf(
        "http://img.example/p.jpg",
        "file:///etc/passwd",
        "https://localhost/p.jpg",
        "https://10.0.0.8/p.jpg",
        "https://169.254.169.254/latest",
        "data:image/png;base64,AAAA",
        "",
        "https://user:pass@img.example/p.jpg",
        "https://img.example@other.example/p.jpg",
    )

    private fun item(poster: String?) = RemoteSearchItem("n", "u", "Movie", poster)

    /** What each page keeps of [poster]: home, search and the title page, in that order. */
    private fun keptOn(poster: String): List<String?> = listOf(
        RemoteHomePage(listOf(RemoteHomeRow("r", false, listOf(item(poster)))), false).cleaned().rows.single().items.single().posterUrl,
        RemoteSearchPage(listOf(item(poster)), false).cleaned().items.single().posterUrl,
        RemoteDetails("n", "u", "Movie", null, poster, null, null, emptyList()).cleaned().posterUrl,
    )

    @Test
    fun `a public https poster is kept on every page`() {
        kept.forEach { poster -> assertEquals(listOf<String?>(poster, poster, poster), keptOn(poster), poster) }
    }

    @Test
    fun `a poster that is not public https, or carries user info, is dropped on every page`() {
        dropped.forEach { poster -> assertEquals(listOf<String?>(null, null, null), keptOn(poster), poster) }
    }
}
