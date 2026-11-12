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
 * A title's ids on the wire (`KMP-86`), and the shapes the app keeps. Frames are written by hand, as a lying host would
 * write them: the page's fields and an empty facts section, then the sections after the facts, each by its tag.
 */
class HostProtocolIdsTest {

    private val page = RemoteDetails("n", "u", "Anime", null, null, 2024, null, listOf(RemoteEpisode(null, "d", 1, 2)))

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
        section(HostProtocol.FACTS_V1) {
            writeInt(Int.MIN_VALUE)
            writeInt(0)
            writeBoolean(false)
            writeByte(HostProtocol.STATUS_NONE)
            writeInt(Int.MIN_VALUE)
        }
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

    /** An empty art section, as a host with `KMP-100` always writes one, then the ids section holding [body]. */
    private fun ids(body: DataOutputStream.() -> Unit) = reply {
        section(HostProtocol.ART_V1) { writeOptional(null) }
        section(HostProtocol.IDS_V1, body)
    }

    private fun idsOf(mal: String?, tmdb: String?, imdb: String?, dropped: MutableList<String> = ArrayList()) =
        received(ids { writeOptional(mal); writeOptional(tmdb); writeOptional(imdb) }, dropped).ids

    @Test
    fun `ids survive the round trip, after the art`() {
        assertEquals(0x31, HostProtocol.IDS_V1, "on the wire: a tag is never changed")
        val details = page.copy(backdropUrl = "https://img.example/b.jpg", ids = RemoteIds("21", "37854", "tt0388629"))
        assertEquals(details, received(HostProtocol.encode { writeDetails(details) }))
        assertEquals(page, received(HostProtocol.encode { writeDetails(page) }), "and none stays none")
    }

    @Test
    fun `a reply from before the ids section opens the page with none`() {
        val dropped = ArrayList<String>()
        val read = received(reply { section(HostProtocol.ART_V1) { writeOptional("https://img.example/b.jpg") } }, dropped)
        assertEquals(page.copy(backdropUrl = "https://img.example/b.jpg"), read)
        assertEquals(emptyList(), dropped)
        assertEquals(page, received(reply { }), "and from before the art, neither")
    }

    @Test
    fun `art missing and ids present is no backdrop, and the ids kept`() {
        val dropped = ArrayList<String>()
        val read = received(
            reply { section(HostProtocol.IDS_V1) { writeOptional("21"); writeOptional(null); writeOptional("tt0388629") } },
            dropped,
        )
        assertNull(read.backdropUrl)
        assertEquals(RemoteIds("21", null, "tt0388629"), read.ids)
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `ids are read past sections this app does not know, and a second ids section is ignored`() {
        val dropped = ArrayList<String>()
        val read = received(
            reply {
                section(0x32) { writeOptional("99"); writeOptional("99"); writeOptional("tt9999999") }
                section(HostProtocol.IDS_V1) { writeOptional("21"); writeOptional("5"); writeOptional(null) }
                section(HostProtocol.ART_V1) { writeOptional("https://img.example/b.jpg") }
                section(HostProtocol.IDS_V1) { writeOptional("22"); writeOptional("6"); writeOptional("tt0388629") }
                section(0x7F) { }
            },
            dropped,
        )
        assertEquals(RemoteIds("21", "5", null), read.ids)
        assertEquals("https://img.example/b.jpg", read.backdropUrl, "the art is still read by its tag")
        assertEquals(listOf("a section this app does not read"), dropped, "said once, whatever the count")
    }

    @Test
    fun `only the exact shapes are kept, in ASCII`() {
        // Kept: digits, re-formatted; IMDb's tt and 7 to 10 digits.
        assertEquals(RemoteIds("21", "7", "tt0388629"), idsOf("21", "007", "tt0388629"))
        assertEquals(RemoteIds("999999999", null, "tt1234567890"), idsOf("999999999", null, "tt1234567890"))
        val refused = listOf(
            "0", "", " 21", "21 ", "21\n", "+21", "-21", "2.1", "1e3", "1234567890", "٢١", "２１", "0x15", "21/../x",
        )
        refused.forEach { bad ->
            val dropped = ArrayList<String>()
            assertEquals(RemoteIds.NONE, idsOf(bad, bad, null, dropped), "MAL and TMDb refuse <$bad>")
            assertEquals(listOf("malId: not an id", "tmdbId: not an id"), dropped)
        }
        listOf("tt038862", "tt12345678901", "TT0388629", "tt0388629\n", "tt٠٣٨٨٦٢٩", "nm0388629", "tt0388629&x=1").forEach { bad ->
            assertEquals(RemoteIds.NONE, idsOf(null, null, bad), "IMDb refuses <$bad>")
        }
    }

    @Test
    fun `an id past its bytes is skipped unread, and the others kept`() {
        val dropped = ArrayList<String>()
        val long = "1".repeat(HostProtocol.MAX_ID_BYTES + 1)
        assertEquals(RemoteIds(null, "5", "tt0388629"), idsOf(long, "5", "tt0388629", dropped))
        assertEquals(listOf("malId: past its bytes"), dropped)
    }

    @Test
    fun `a slice that cannot be read costs the ids, not the page or the art`() {
        val dropped = ArrayList<String>()
        val read = received(
            reply {
                section(HostProtocol.IDS_V1) { writeBoolean(true); writeInt(1_000) }
                section(HostProtocol.ART_V1) { writeOptional("https://img.example/b.jpg") }
            },
            dropped,
        )
        assertEquals(page.copy(backdropUrl = "https://img.example/b.jpg"), read)
        assertEquals(listOf("ids: a section that could not be read"), dropped)
    }

    @Test
    fun `a slice longer than the frame fails the call`() {
        val frame = reply {
            writeByte(HostProtocol.IDS_V1)
            writeInt(1_000)
        }
        assertFailsWith<IOException> { received(frame) }
    }

    @Test
    fun `nothing an id held reaches the log`() {
        val dropped = ArrayList<String>()
        idsOf("SECRET1", "SECRET2", "SECRET3", dropped)
        idsOf("1".repeat(40) + "SECRET4", null, null, dropped)
        assertTrue(dropped.isNotEmpty())
        assertTrue(dropped.none { "SECRET" in it || "1111" in it }, dropped.toString())
    }
}
