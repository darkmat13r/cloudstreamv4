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
 * A title's facts on the wire (`KMP-76`). Most frames here are written by hand, as a lying host would write them, so
 * nothing the honest host does to cut them can make a test pass.
 */
class HostProtocolFactsTest {

    private val page = RemoteDetails("n", "u", "TvSeries", null, null, 2024, null, listOf(RemoteEpisode(null, "d", 1, 2)))

    /** What the app makes of a reply: the reader, then the checks, as `PluginHostClient.load` runs them. */
    private fun received(frame: ByteArray, dropped: MutableList<String> = ArrayList()): RemoteDetails =
        HostProtocol.decode(HostProtocol.readFrame(ByteArrayInputStream(frame))!!) {
            readDetails { dropped += it }.cleaned { dropped += it }
        }

    /** [page]'s fields as the format has always had them, then [rest] as written. */
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
        rest()
    }

    /** A version-1 section with [body] inside it, its length stated truthfully. */
    private fun facts(version: Int = HostProtocol.FACTS_V1, body: DataOutputStream.() -> Unit) = reply {
        val section = ByteArrayOutputStream()
        DataOutputStream(section).use(body)
        writeByte(version)
        writeInt(section.size())
        section.writeTo(this)
    }

    /** One section in field order. A tag or rating given as bytes is written as those bytes. */
    private fun section(
        score: Int = NO_NUMBER,
        tags: List<String> = emptyList(),
        rating: String? = null,
        status: Int = HostProtocol.STATUS_NONE,
        runtime: Int = NO_NUMBER,
    ) = facts {
        writeInt(score)
        writeInt(tags.size)
        tags.forEach { writeText(it) }
        writeOptional(rating)
        writeByte(status)
        writeInt(runtime)
    }

    private fun factsOf(frame: ByteArray) = received(frame).facts

    @Test
    fun `facts survive the round trip`() {
        val facts = RemoteFacts(84, listOf("Drama", "Crime"), "TV-MA", RemoteShowStatus.Completed, 48)
        val details = page.copy(facts = facts)
        assertEquals(details, received(HostProtocol.encode { writeDetails(details) }))
    }

    @Test
    fun `a reply from before facts opens the page as it was, with none`() {
        val dropped = ArrayList<String>()
        val read = received(reply { }, dropped)
        assertEquals(page, read)
        assertEquals(RemoteFacts.NONE, read.facts)
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `a version this app does not know is skipped whole, and the page opens`() {
        val dropped = ArrayList<String>()
        val read = received(facts(version = 2) { writeInt(84); write(ByteArray(100) { 7 }) }, dropped)
        assertEquals(page, read)
        assertEquals(listOf("facts: version 2"), dropped)
    }

    @Test
    fun `each number is kept at its bounds and dropped one past them`() {
        assertEquals(0, factsOf(section(score = 0)).scoreTenths)
        assertEquals(100, factsOf(section(score = 100)).scoreTenths)
        assertNull(factsOf(section(score = -1)).scoreTenths)
        assertNull(factsOf(section(score = 101)).scoreTenths)
        assertNull(factsOf(section(score = Int.MAX_VALUE)).scoreTenths)

        assertEquals(1, factsOf(section(runtime = 1)).runtimeMinutes)
        assertEquals(1440, factsOf(section(runtime = 1440)).runtimeMinutes)
        assertNull(factsOf(section(runtime = 0)).runtimeMinutes)
        assertNull(factsOf(section(runtime = -1)).runtimeMinutes)
        assertNull(factsOf(section(runtime = 1441)).runtimeMinutes)
    }

    @Test
    fun `a status is one of two codes, and any other is dropped`() {
        assertEquals(RemoteShowStatus.Ongoing, factsOf(section(status = HostProtocol.STATUS_ONGOING)).status)
        assertEquals(RemoteShowStatus.Completed, factsOf(section(status = HostProtocol.STATUS_COMPLETED)).status)
        assertNull(factsOf(section(status = HostProtocol.STATUS_NONE)).status)
        val dropped = ArrayList<String>()
        assertNull(received(section(status = 3, runtime = 90), dropped).facts.status)
        assertEquals(90, received(section(status = 255, runtime = 90)).facts.runtimeMinutes, "the reader stays in step")
        assertEquals(listOf("status: code 3"), dropped)
    }

    @Test
    fun `a certification is letters, digits and a few marks, up to its length`() {
        listOf("TV-MA", "PG-13", "12+", "FSK 16", "R", "15세", "a".repeat(16)).forEach { rating ->
            assertEquals(rating, factsOf(section(rating = rating)).contentRating, rating)
        }
        listOf("<script>", "PG\n13", "TV‮AM", "a".repeat(17), "", "   ", "PG;13", "http://x").forEach { rating ->
            assertNull(factsOf(section(rating = rating)).contentRating, rating)
        }
    }

    @Test
    fun `a certification past its bytes is skipped unread, and what follows is still read`() {
        val dropped = ArrayList<String>()
        val read = received(section(rating = "é".repeat(33), runtime = 90), dropped).facts
        assertNull(read.contentRating)
        assertEquals(90, read.runtimeMinutes)
        assertEquals(listOf("contentRating: past its bytes"), dropped)
    }

    @Test
    fun `tags are kept to their count and length, without blanks, repeats or invisible text`() {
        assertEquals(List(16) { "t$it" }, factsOf(section(tags = List(17) { "t$it" })).tags)
        assertEquals(listOf("a".repeat(64)), factsOf(section(tags = listOf("a".repeat(64)))).tags)
        assertEquals(listOf("ok"), factsOf(section(tags = listOf("a".repeat(65), "ok"))).tags)
        assertEquals(
            listOf("Drama", "evil[2Jline", "rtl", "zerowidth"),
            factsOf(section(tags = listOf("Drama", " ", "", "Drama", "evil\u001B[2J\nline", "‮rtl", "zero‍width"))).tags,
        )
    }

    @Test
    fun `a tag past its bytes is skipped unread, and the next tag and field are still read`() {
        val dropped = ArrayList<String>()
        val read = received(section(tags = listOf("x".repeat(257), "after"), runtime = 90), dropped).facts
        assertEquals(listOf("after"), read.tags)
        assertEquals(90, read.runtimeMinutes)
        assertEquals(listOf("tags: 1 past a bound"), dropped)
    }

    @Test
    fun `tags past the list's bytes are skipped, and many make one line of log`() {
        val dropped = ArrayList<String>()
        // 64 characters and 188 bytes each, every one a tag the checks would keep: past 16 KiB, about 300 times over.
        val tags = List(300) { "%02d".format(it % 100) + "€".repeat(62) }
        val read = received(section(tags = tags, runtime = 90), dropped).facts
        assertEquals(tags.take(16), read.tags)
        assertEquals(90, read.runtimeMinutes)
        assertEquals(1, dropped.count { it.startsWith("tags:") }, dropped.toString())
    }

    @Test
    fun `a hundred thousand tags cost one frame and keep sixteen`() {
        val read = factsOf(section(tags = List(100_000) { "t$it" }))
        assertEquals(List(16) { "t$it" }, read.tags)
    }

    @Test
    fun `a section claiming more than the frame holds fails the call, as any lying length does`() {
        assertFailsWith<IOException> { received(reply { writeByte(HostProtocol.FACTS_V1); writeInt(Int.MAX_VALUE) }) }
        assertFailsWith<IOException> { received(reply { writeByte(2); writeInt(1000) }) }
    }

    @Test
    fun `a lying length inside the section fails the call`() {
        assertFailsWith<IOException> { received(facts { writeInt(84); writeInt(Int.MAX_VALUE) }) }
        assertFailsWith<IOException> { received(facts { writeInt(84); writeInt(1); writeInt(Int.MAX_VALUE) }) }
        assertFailsWith<IOException> {
            received(facts { writeInt(84); writeInt(0); writeBoolean(true); writeInt(1_000_000) })
        }
    }

    @Test
    fun `a section cut short fails the call, and cannot read into what follows it`() {
        assertFailsWith<IOException> { received(facts { writeInt(84) }) }
        // Its length says 4, and the rest is outside it: a full section's worth of bytes after does not help.
        assertFailsWith<IOException> {
            received(
                reply {
                    writeByte(HostProtocol.FACTS_V1)
                    writeInt(Int.SIZE_BYTES)
                    writeInt(84)
                    writeInt(0); writeBoolean(false); writeByte(0); writeInt(90)
                },
            )
        }
    }

    @Test
    fun `nothing a dropped fact held reaches the log`() {
        val dropped = ArrayList<String>()
        received(
            section(score = 999, tags = listOf("https://secret.invalid/?token=abc", " "), rating = "https://secret.invalid", runtime = -5),
            dropped,
        )
        assertTrue(dropped.isNotEmpty())
        dropped.forEach { assertTrue("secret" !in it && "token" !in it && "999" !in it, it) }
    }

    private companion object {
        const val NO_NUMBER = Int.MIN_VALUE
    }
}
