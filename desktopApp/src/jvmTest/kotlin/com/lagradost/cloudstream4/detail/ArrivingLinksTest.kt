package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLinks
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * What a plugin's links become on the way to the screen, one answer at a time.
 *
 * The addresses are IP literals, so nothing here resolves a name.
 */
class ArrivingLinksTest {

    private fun link(url: String, audioUrl: String? = null, name: String = url) =
        RemoteLink("s", name, url, 1080, "VIDEO", null, null, audioUrl)

    private fun answer(vararg links: RemoteLink, subtitles: List<RemoteSubtitle> = emptyList()) =
        RemoteLinks(links.toList(), subtitles)

    private fun List<StreamLink>.urls() = map { it.url }

    @Test
    fun `a partial link with a bad URL is refused exactly as a whole answer's is`() = runBlocking<Unit> {
        val refused = listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "magnet:?xt=urn:btih:2849211f150e966ef7924f43aec22727a644e10d",
            "http://127.0.0.1/stream",
            "http://localhost/stream",
            "http://192.168.1.1/stream",
            "http://10.0.0.1/stream",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/stream",
            "http://user:password@8.8.8.8/stream",
            "ftp://8.8.8.8/stream",
        )
        for (url in refused) {
            val emitted = flowOf(answer(link(url))).toStreamLinks().toList()
            assertTrue(emitted.isEmpty(), "$url reached the screen")
        }
    }

    @Test
    fun `a partial link with a bad audio URL is refused too`() = runBlocking<Unit> {
        val emitted = flowOf(answer(link("https://8.8.8.8/v", audioUrl = "https://8.8.8.8/a#file:///etc/passwd"))).toStreamLinks().toList()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `a bad one among good ones goes alone, and the good ones stay in the order they came`() = runBlocking<Unit> {
        val good = link("https://8.8.8.8/one")
        val bad = link("http://127.0.0.1/two")
        val later = link("https://8.8.4.4/three")
        val emitted = flowOf(answer(good), answer(good, bad), answer(good, bad, later)).toStreamLinks().toList()
        assertEquals(listOf("https://8.8.8.8/one", "https://8.8.4.4/three"), emitted.last().urls())
        emitted.zipWithNext { earlier, next ->
            assertEquals(earlier.urls(), next.urls().take(earlier.size), "a row moved")
        }
    }

    @Test
    fun `each link is checked once, however many answers it is in`() = runBlocking<Unit> {
        val checked = mutableListOf<String>()
        val one = link("https://8.8.8.8/one")
        val two = link("https://8.8.8.8/two")
        flowOf(answer(one), answer(one), answer(one, two)).toStreamLinks { checked += it.url; true }.toList()
        assertEquals(listOf(one.url, two.url), checked)
    }

    @Test
    fun `subtitles that arrive later reach the links already there`() = runBlocking<Unit> {
        val one = link("https://8.8.8.8/one")
        val track = RemoteSubtitle("English", "en", "https://8.8.8.8/en.vtt", null, null)
        val emitted = flowOf(answer(one), answer(one, subtitles = listOf(track))).toStreamLinks().toList()
        assertEquals(listOf(0, 1), emitted.map { it.single().subtitles.size })
    }

    @Test
    fun `subtitles alone do not put anything on the screen`() = runBlocking<Unit> {
        val track = RemoteSubtitle("English", "en", "https://8.8.8.8/en.vtt", null, null)
        assertTrue(flowOf(answer(subtitles = listOf(track))).toStreamLinks().toList().isEmpty())
    }
}
