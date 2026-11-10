package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A title's backdrop on the wire (`KMP-100`). Frames are written by hand, as a lying host would write them, after a
 * facts section with nothing in it.
 */
class HostProtocolArtTest {

    private val page = RemoteDetails("n", "u", "TvSeries", null, null, 2024, null, listOf(RemoteEpisode(null, "d", 1, 2)))

    private fun received(frame: ByteArray, dropped: MutableList<String> = ArrayList()): RemoteDetails =
        HostProtocol.decode(HostProtocol.readFrame(ByteArrayInputStream(frame))!!) {
            readDetails { dropped += it }.cleaned { dropped += it }
        }

    /** [page] as the format had it before `KMP-100`: its fields and an empty facts section, then [rest] as written. */
    private fun reply(rest: DataOutputStream.() -> Unit) = HostProtocol.encode {
        writeText(page.name)
        writeText(page.url)
        writeText(page.type)
        writeOptional(page.plot)
        writeOptional(page.posterUrl)
        writeInt(page.year!!)
        writeOptional(page.dataUrl)
        writeInt(page.episodes.size)
        page.episodes.forEach { episode ->
            writeOptional(episode.name)
            writeText(episode.data)
            writeInt(episode.season!!)
            writeInt(episode.episode!!)
        }
        val facts = ByteArrayOutputStream()
        DataOutputStream(facts).use { out ->
            out.writeInt(Int.MIN_VALUE)
            out.writeInt(0)
            out.writeBoolean(false)
            out.writeByte(HostProtocol.STATUS_NONE)
            out.writeInt(Int.MIN_VALUE)
        }
        writeByte(HostProtocol.FACTS_V1)
        writeInt(facts.size())
        facts.writeTo(this)
        rest()
    }

    /** A section of [tag] holding [body], its length stated truthfully. */
    private fun DataOutputStream.section(tag: Int, body: DataOutputStream.() -> Unit) {
        val section = ByteArrayOutputStream()
        DataOutputStream(section).use(body)
        writeByte(tag)
        writeInt(section.size())
        section.writeTo(this)
    }

    /** An art section, or one of [version] in its place, holding [body]. */
    private fun art(version: Int = HostProtocol.ART_V1, body: DataOutputStream.() -> Unit) = reply { section(version, body) }

    private fun backdrop(url: String?) = art { writeOptional(url) }

    private fun backdropOf(frame: ByteArray) = received(frame).backdropUrl

    @Test
    fun `a backdrop survives the round trip`() {
        val details = page.copy(backdropUrl = "https://img.example/b.jpg")
        assertEquals(details, received(HostProtocol.encode { writeDetails(details) }))
        assertEquals(page, received(HostProtocol.encode { writeDetails(page) }), "and none stays none")
    }

    @Test
    fun `a reply from before the art section opens the page with no backdrop`() {
        val dropped = ArrayList<String>()
        val read = received(reply { }, dropped)
        assertEquals(page, read)
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `a tag this app does not know is skipped whole`() {
        listOf(HostProtocol.FACTS_V1, 2, 0x22, 0x32, 0xFF).forEach { tag ->
            val dropped = ArrayList<String>()
            val read = received(art(version = tag) { writeOptional("https://img.example/b.jpg"); write(ByteArray(50) { 7 }) }, dropped)
            assertEquals(page, read, "tag $tag")
            assertEquals(listOf("a section this app does not read"), dropped, "tag $tag")
        }
    }

    @Test
    fun `the art section has a tag of its own, read past sections it does not know and after a second of its own`() {
        assertEquals(0x21, HostProtocol.ART_V1, "on the wire: a tag is never changed")
        val dropped = ArrayList<String>()
        val read = received(
            reply {
                section(0x32) { writeOptional("https://img.example/not-art.jpg"); writeInt(7) }
                section(HostProtocol.ART_V1) { writeOptional("https://img.example/b.jpg") }
                section(HostProtocol.ART_V1) { writeOptional("https://img.example/second.jpg") }
                section(0x71) { } // a tag no app reads yet; 0x41 is the cast now (KMP-103)
            },
            dropped,
        )
        assertEquals("https://img.example/b.jpg", read.backdropUrl)
        assertEquals(listOf("a section this app does not read"), dropped, "said once, whatever the count")
    }

    @Test
    fun `only a public https URL with no user info is kept`() {
        assertEquals("https://img.example/b.jpg", backdropOf(backdrop("https://img.example/b.jpg")))
        assertEquals("HTTPS://img.example/b.jpg", backdropOf(backdrop("HTTPS://img.example/b.jpg")))
        listOf(
            "http://img.example/b.jpg",
            "file:///etc/passwd",
            "https://localhost/b.jpg",
            "https://printer.local/b.jpg",
            "https://127.0.0.1/b.jpg",
            "https://10.0.0.8/b.jpg",
            "https://192.168.1.1/b.jpg",
            "https://169.254.169.254/latest",
            "https://[::1]/b.jpg",
            "data:image/png;base64,AAAA",
            "img.example/b.jpg",
            "",
            "https://user:pass@img.example/b.jpg",
            "https://user@img.example/b.jpg",
            "https://@img.example/b.jpg",
            "https://img.example@other.example/b.jpg",
            "https://user%40x:p@img.example/b.jpg",
        ).forEach { url -> assertNull(backdropOf(backdrop(url)), url) }
    }

    @Test
    fun `a URL at its bytes is read, and one past them is skipped unread with what follows still read`() {
        val prefix = "https://img.example/"
        val atCap = prefix + "a".repeat(HostProtocol.MAX_IMAGE_URL_BYTES - prefix.length)
        assertEquals(atCap, backdropOf(backdrop(atCap)))
        val dropped = ArrayList<String>()
        val read = received(backdrop(atCap + "a"), dropped)
        assertNull(read.backdropUrl)
        assertEquals(page, read)
        // `KMP-108`: a URL a byte past its cap makes the section a byte past its own, so the section is skipped whole.
        assertEquals(listOf("art: a section past its bytes"), dropped)
    }

    @Test
    fun `a lying length inside the section costs the backdrop and keeps the page`() {
        listOf<DataOutputStream.() -> Unit>(
            { writeBoolean(true); writeInt(Int.MAX_VALUE) },
            { writeBoolean(true); writeInt(10); write(ByteArray(3)) },
            { },
        ).forEach { body ->
            val dropped = ArrayList<String>()
            assertEquals(page, received(art(body = body), dropped))
            assertEquals(listOf("art: a section that could not be read"), dropped)
        }
    }

    @Test
    fun `a section claiming more than the frame holds fails the call, as any lying length does`() {
        assertFailsWith<IOException> { received(reply { writeByte(HostProtocol.ART_V1); writeInt(Int.MAX_VALUE) }) }
        assertFailsWith<IOException> { received(reply { writeByte(9); writeInt(1000) }) }
    }

    @Test
    fun `nothing the backdrop held reaches the log`() {
        val dropped = ArrayList<String>()
        received(backdrop("https://secret.invalid/?token=abc" + "x".repeat(HostProtocol.MAX_IMAGE_URL_BYTES)), dropped)
        received(art(version = 77) { writeOptional("https://secret.invalid/?token=abc") }, dropped)
        assertEquals(2, dropped.size)
        dropped.forEach { assertTrue("secret" !in it && "token" !in it && "77" !in it, it) }
    }
}
