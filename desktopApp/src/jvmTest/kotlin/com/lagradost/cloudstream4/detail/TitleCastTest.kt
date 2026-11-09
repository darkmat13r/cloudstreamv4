package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteActorRole
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteCastMember
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteDetails
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteRecommendation
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.DesktopSearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `KMP-103` on the Mac: the cast and recommendations the sandbox kept, onto the page, and the key kept out of logs. */
class TitleCastTest {

    private val details = RemoteDetails("Title", "https://site.example/t", "TvSeries", null, null, null, null, emptyList())

    @Test
    fun `the id round-trips any key, the codec's own separator, other scripts and 2048 characters included`() {
        val keys = listOf(
            "https://site.example/a",
            "a\u0000b",
            "\u0000",
            "\u0000\u0000x\u0000",
            "",
            "日本語のキー",
            "é".repeat(2048),
            "k".repeat(2048),
            """{"id":42,"path":"/a?b=c#d"}""",
        )
        keys.forEach { key ->
            val id = DesktopSearchSource.encodeId("Provider", key)
            assertEquals("Provider" to key, DesktopSearchSource.decodeId(SearchResultId(id)), "key ${key.take(16)}")
        }
        assertEquals(null, DesktopSearchSource.decodeId(SearchResultId("no separator at all")))
    }

    @Test
    fun `a recommendation opens with the provider that sent it, whatever it holds`() {
        val page = details.copy(
            recommendations = listOf(
                RemoteRecommendation("Saltwind", "https://site.example/s", "TvSeries", 2021, "https://img.example/s.jpg"),
                RemoteRecommendation("Elsewhere", "Other\u0000https://other.example/x"),
            ),
        ).toTitleDetail("Provider")
        val (first, second) = page.recommendations
        assertEquals("Provider" to "https://site.example/s", DesktopSearchSource.decodeId(SearchResultId(first.id)))
        assertEquals("Saltwind", first.title)
        assertEquals("TvSeries", first.typeName)
        assertEquals(2021, first.year)
        assertEquals("https://img.example/s.jpg", first.posterUrl)
        assertEquals(
            "Provider" to "Other\u0000https://other.example/x",
            DesktopSearchSource.decodeId(SearchResultId(second.id)),
            "a key that names another provider still opens with this one",
        )
    }

    @Test
    fun `a person's words win over the kind, which is the kind's name until the page puts it in words`() {
        val page = details.copy(
            cast = listOf(
                RemoteCastMember("Ines", RemoteActorRole.Main, "Cdr. Voss", null, "https://img.example/i.jpg"),
                RemoteCastMember("Ren", RemoteActorRole.Supporting, null, "Hana Sato"),
                RemoteCastMember("Noor"),
            ),
        ).toTitleDetail("p")
        assertEquals(
            listOf(
                DetailCastMember("Ines", "https://img.example/i.jpg", "Cdr. Voss"),
                DetailCastMember("Ren", null, "Supporting", "Hana Sato"),
                DetailCastMember("Noor", null, null),
            ),
            page.cast,
        )
    }

    @Test
    fun `none is none`() {
        val page = details.toTitleDetail("p")
        assertEquals(emptyList(), page.cast)
        assertEquals(emptyList(), page.recommendations)
    }

    @Test
    fun `a failure's log line holds neither the key nor a URL, however the plugin repeats them`() {
        val key = """{"token":"SECRET-123","id":7}"""
        val line = detailFailureLine("Provider", "IOException: no title for $key at https://site.example/x?token=SECRET", key)
        assertTrue("SECRET" !in line, line)
        assertTrue(key !in line, line)
        assertTrue(line.startsWith("detail: Provider could not open a title: IOException: no title for <key> at "), line)
        assertTrue("site.example" in line, "the host stays, for triage: $line")
        assertEquals("detail: p could not open a title: words", detailFailureLine("p", "words", ""), "an empty key changes nothing")
    }
}
