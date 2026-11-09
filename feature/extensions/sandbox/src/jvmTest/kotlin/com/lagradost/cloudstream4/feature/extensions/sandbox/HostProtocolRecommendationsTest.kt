package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.page
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.person
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.rawText
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.received
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.refusedImages
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.reply
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.section
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.suggestion
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A title's recommendations on the wire (`KMP-103`), read as hostile. Frames are written by hand. */
class HostProtocolRecommendationsTest {

    private fun suggestions(vararg write: DataOutputStream.() -> Unit) = reply {
        section(HostProtocol.CAST_V1) { writeInt(0) }
        section(HostProtocol.RECOMMENDATIONS_V1) {
            writeInt(write.size)
            write.forEach { it() }
        }
    }

    private fun of(frame: ByteArray, dropped: MutableList<String> = ArrayList()) = received(frame, dropped).recommendations

    @Test
    fun `recommendations survive the round trip`() {
        val details = page.copy(
            recommendations = listOf(
                RemoteRecommendation("Saltwind", "https://site.example/saltwind", "TvSeries", 2021, "https://img.example/s.jpg"),
                RemoteRecommendation("The Quiet Orbit", """{"id":42,"kind":"movie"}""", "Movie", null, null),
                RemoteRecommendation("Untyped", "id-7"),
            ),
        )
        assertEquals(details, received(HostProtocol.encode { writeDetails(details) }))
    }

    @Test
    fun `a reply with a cast and no recommendations section has none`() {
        val details = received(reply { section(HostProtocol.CAST_V1) { writeInt(1); person("A") } })
        assertEquals(emptyList(), details.recommendations)
        assertEquals(listOf("A"), details.cast.map { it.name })
    }

    @Test
    fun `twenty are kept and a twenty-first is not read`() {
        val dropped = ArrayList<String>()
        val frame = suggestions(*Array(HostProtocol.MAX_RECOMMENDATIONS + 1) { i -> { suggestion("T$i", "k$i") } })
        assertEquals((0 until HostProtocol.MAX_RECOMMENDATIONS).map { "T$it" }, of(frame, dropped).map { it.title })
        assertEquals(listOf("recommendations: entries past the count"), dropped)
    }

    @Test
    fun `a title of 128 characters is kept and 129 is dropped, and past 512 bytes it is skipped unread, still in step`() {
        val frame = suggestions(
            { suggestion("t".repeat(HostProtocol.MAX_RECOMMENDATION_TITLE_CHARS), "a") },
            { suggestion("t".repeat(HostProtocol.MAX_RECOMMENDATION_TITLE_CHARS + 1), "b") },
            { rawText(ByteArray(HostProtocol.MAX_RECOMMENDATION_TITLE_BYTES + 1) { 'x'.code.toByte() }); writeByte(0); writeInt(Int.MIN_VALUE); writeOptional(null); writeText("c") },
            { suggestion("After", "d") },
        )
        assertEquals(listOf("a", "d"), of(frame).map { it.key })
    }

    @Test
    fun `a key is opaque, 2048 characters of any script kept as sent, and one past it, blank or with a control refused`() {
        val wide = "é".repeat(HostProtocol.MAX_KEY_CHARS)
        val frame = suggestions(
            { suggestion("Wide", wide) },
            { suggestion("Ascii", " " + "k".repeat(HostProtocol.MAX_KEY_CHARS - 2) + " ") },
            { suggestion("Long", "k".repeat(HostProtocol.MAX_KEY_CHARS + 1)) },
            { suggestion("Blank", "") },
            { suggestion("Nul", "a\u0000b") },
            { suggestion("Line", "a\nb") },
        )
        val kept = of(frame)
        assertEquals(listOf("Wide", "Ascii"), kept.map { it.title })
        assertEquals(wide, kept[0].key)
        assertEquals(" " + "k".repeat(HostProtocol.MAX_KEY_CHARS - 2) + " ", kept[1].key, "never trimmed or repaired")
    }

    @Test
    fun `the title itself and repeats are left out`() {
        val frame = suggestions(
            { suggestion("Itself", page.url) },
            { suggestion("Saltwind", "s") },
            { suggestion("Saltwind again", "s") },
        )
        assertEquals(listOf("Saltwind"), of(frame).map { it.title })
    }

    @Test
    fun `a type is a code from the protocol's table, and an unknown one costs only the type`() {
        val frame = suggestions(
            { suggestion("None", "a", type = 0) },
            { suggestion("Movie", "b", type = 1) },
            { suggestion("Series", "c", type = 3) },
            { suggestion("Video", "d", type = HostProtocol.TYPE_CODES.size) },
            { suggestion("Unknown", "e", type = HostProtocol.TYPE_CODES.size + 1) },
        )
        assertEquals(listOf(null, "Movie", "TvSeries", "Video", null), of(frame).map { it.type })
    }

    @Test
    fun `a year outside 1870 to 2100 costs only the year`() {
        val frame = suggestions(
            { suggestion("a", "a", year = HostProtocol.MIN_YEAR - 1) },
            { suggestion("b", "b", year = HostProtocol.MIN_YEAR) },
            { suggestion("c", "c", year = HostProtocol.MAX_YEAR) },
            { suggestion("d", "d", year = HostProtocol.MAX_YEAR + 1) },
        )
        assertEquals(listOf(null, 1870, 2100, null), of(frame).map { it.year })
    }

    @Test
    fun `only a public https poster is kept, and a refused one costs only the poster`() {
        assertEquals("https://img.example/p.jpg", of(suggestions({ suggestion("A", "a", poster = "https://img.example/p.jpg") })).single().posterUrl)
        refusedImages.forEach { (url, what) ->
            val title = of(suggestions({ suggestion("A", "a", poster = url) })).singleOrNull()
            assertEquals("A", title?.title, "$what: the recommendation stays")
            assertNull(title?.posterUrl, "$what must not reach the image loader")
        }
    }

    @Test
    fun `a title loses invisible characters, and repeats of keys are compared as sent`() {
        val kept = of(suggestions({ suggestion("‮Salt‍wind\u001B ", "k") }))
        assertEquals("Saltwind", kept.single().title)
    }

    @Test
    fun `a negative count, a lying field length and a slice cut short each cost the recommendations, and keep the page`() {
        listOf<DataOutputStream.() -> Unit>(
            { writeInt(-5) },
            { writeInt(2); suggestion("a", "a"); writeInt(Int.MAX_VALUE) },
            { writeInt(2); suggestion("a", "a") },
        ).forEach { body ->
            val dropped = ArrayList<String>()
            val details = received(reply { section(HostProtocol.RECOMMENDATIONS_V1, body) }, dropped)
            assertEquals(page, details)
            assertEquals(listOf("recommendations: a section that could not be read"), dropped)
        }
    }

    @Test
    fun `a section length lying past the frame fails the call`() {
        assertFailsWith<IOException> {
            received(reply { writeByte(HostProtocol.RECOMMENDATIONS_V1); writeInt(1 shl 20) })
        }
    }

    @Test
    fun `nothing the plugin sent reaches the log`() {
        val dropped = ArrayList<String>()
        of(
            suggestions(
                { suggestion("SECRET-TITLE".repeat(20), "SECRET-KEY-1", type = 77, year = 9999, poster = "https://u:SECRET@img.example/") },
                { suggestion("ok", "SECRET-KEY-2\u0000") },
                { suggestion("self", page.url) },
                { rawText(byteArrayOf(0xC3.toByte(), 0x28)); writeByte(0); writeInt(Int.MIN_VALUE); writeOptional(null); writeText("SECRET-KEY-3") },
            ),
            dropped,
        )
        assertTrue(dropped.isNotEmpty())
        dropped.forEach { line ->
            assertTrue("SECRET" !in line && "site.example" !in line && "77" !in line && "9999" !in line, "a plugin's value in the log: $line")
        }
        assertEquals(dropped.distinct(), dropped, "each reason once")
    }
}
