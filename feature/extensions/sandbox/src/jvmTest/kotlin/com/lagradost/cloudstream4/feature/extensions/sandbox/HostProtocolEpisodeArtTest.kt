package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.emptyFacts
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.person
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.refusedImages
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.section
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A title's episode stills on the wire (`KMP-116`), read as hostile. Each still names its episode by its place in the
 * frame's own episode list, so it can belong to no episode but one of this title's. Frames are written by hand, as a
 * lying host would write them.
 */
class HostProtocolEpisodeArtTest {

    private fun episodes(count: Int) = List(count) { RemoteEpisode("Episode ${it + 1}", "data-$it", 1, it + 1) }

    private fun page(count: Int) = RemoteDetails(
        "A title", "https://site.example/title", "TvSeries", null, null, 2024, null, episodes(count),
    )

    /** A reply for [page] of [count] episodes, its empty facts, then [rest] as written. */
    private fun reply(count: Int, rest: DataOutputStream.() -> Unit) = HostProtocol.encode {
        val details = page(count)
        writeText(details.name)
        writeText(details.url)
        writeText(details.type)
        writeOptional(details.plot)
        writeOptional(details.posterUrl)
        writeInt(details.year!!)
        writeOptional(details.dataUrl)
        writeInt(details.episodes.size)
        details.episodes.forEach { episode ->
            writeOptional(episode.name)
            writeText(episode.data)
            writeInt(episode.season!!)
            writeInt(episode.episode!!)
        }
        emptyFacts()
        rest()
    }

    /** An episode art section of [stills], each the place of its episode and its URL, the count stated as [count]. */
    private fun DataOutputStream.art(vararg stills: Pair<Int, String>, count: Int = stills.size) =
        section(HostProtocol.EPISODE_ART_V1) {
            writeInt(count)
            stills.forEach { (place, url) ->
                writeInt(place)
                writeText(url)
            }
        }

    private fun received(frame: ByteArray, dropped: MutableList<String> = ArrayList()): RemoteDetails =
        HostProtocol.decode(HostProtocol.readFrame(ByteArrayInputStream(frame))!!) {
            readDetails { dropped += it }.cleaned { dropped += it }
        }

    private fun stillsOf(details: RemoteDetails) = details.episodes.map { it.stillUrl }

    @Test
    fun `stills survive the round trip, each on its own episode, and episodes with none keep none`() {
        val details = page(3).let { title ->
            title.copy(
                episodes = title.episodes.mapIndexed { index, episode ->
                    if (index == 1) episode else episode.copy(stillUrl = "https://img.example/e$index.jpg")
                },
            )
        }
        val arrived = received(HostProtocol.encode { writeDetails(details) })
        assertEquals(details, arrived)
        assertEquals(listOf("https://img.example/e0.jpg", null, "https://img.example/e2.jpg"), stillsOf(arrived))
    }

    @Test
    fun `a reply from before the section opens every episode with no still, and says nothing`() {
        val dropped = ArrayList<String>()
        val arrived = received(reply(2) { }, dropped)
        assertEquals(page(2), arrived)
        assertEquals(emptyList(), dropped)
    }

    /**
     * True on any reader, this one and every one before `KMP-116`: the section after the cast is stepped over or read,
     * and the page, its episodes and its cast arrive either way. Run on master before this change, it is the proof that
     * an older app opens a page from a newer host.
     */
    @Test
    fun `a frame with episode art opens its page and its other sections on any reader`() {
        val frame = reply(2) {
            section(HostProtocol.CAST_V1) { writeInt(1); person("Ines Marlowe") }
            section(HostProtocol.RECOMMENDATIONS_V1) { writeInt(0) }
            art(0 to "https://img.example/a.jpg")
        }
        val arrived = received(frame)
        assertEquals(listOf("data-0", "data-1"), arrived.episodes.map { it.data })
        assertEquals(listOf("Ines Marlowe"), arrived.cast.map { it.name })
    }

    @Test
    fun `a still the image checks refuse is dropped, said once, and its episode is kept`() {
        refusedImages.forEach { (url, what) ->
            val dropped = ArrayList<String>()
            val arrived = received(reply(2) { art(0 to url, 1 to "https://img.example/kept.jpg") }, dropped)
            assertEquals(listOf(null, "https://img.example/kept.jpg"), stillsOf(arrived), what)
            assertEquals(2, arrived.episodes.size, "$what: the episode is kept")
            // One past its bytes is stepped over by the reader before the image checks see it.
            val reason = if (url.toByteArray().size > HostProtocol.MAX_IMAGE_URL_BYTES) "episode art: still past its bytes"
            else "episodes: a still refused"
            assertEquals(listOf(reason), dropped, what)
        }
    }

    @Test
    fun `a still naming no episode of this title is dropped, and none lands on another`() {
        val dropped = ArrayList<String>()
        val arrived = received(
            reply(2) {
                art(
                    -1 to "https://img.example/before.jpg",
                    2 to "https://img.example/past.jpg",
                    Int.MAX_VALUE to "https://img.example/far.jpg",
                    Int.MIN_VALUE to "https://img.example/negative.jpg",
                    1 to "https://img.example/one.jpg",
                )
            },
            dropped,
        )
        assertEquals(listOf(null, "https://img.example/one.jpg"), stillsOf(arrived))
        assertEquals(listOf("episode art: a still for no episode of this title"), dropped)
    }

    @Test
    fun `a second still for one episode is dropped, and the first is kept`() {
        val dropped = ArrayList<String>()
        val arrived = received(
            reply(1) { art(0 to "https://img.example/first.jpg", 0 to "https://img.example/second.jpg") },
            dropped,
        )
        assertEquals(listOf("https://img.example/first.jpg"), stillsOf(arrived))
        assertEquals(listOf("episode art: a second still for one episode"), dropped)
    }

    @Test
    fun `stills past the count are left unread, and the first of them are kept`() {
        val count = HostProtocol.MAX_EPISODE_STILLS + 5
        val stills = Array(count) { it to "https://img.example/e$it.jpg" }
        val dropped = ArrayList<String>()
        val arrived = received(reply(count) { art(*stills) }, dropped)
        val kept = stillsOf(arrived)
        assertEquals(HostProtocol.MAX_EPISODE_STILLS, kept.count { it != null })
        assertNull(kept.getOrNull(HostProtocol.MAX_EPISODE_STILLS), "the first past the count")
        assertTrue("episode art: entries past the count" in dropped, "$dropped")
    }

    @Test
    fun `a section that lies about its count or a field costs only the stills, and the page opens`() {
        listOf<DataOutputStream.() -> Unit>(
            { section(HostProtocol.EPISODE_ART_V1) { writeInt(-1) } },
            { section(HostProtocol.EPISODE_ART_V1) { writeInt(2); writeInt(0) } },
            { section(HostProtocol.EPISODE_ART_V1) { writeInt(1); writeInt(0); writeInt(Int.MAX_VALUE) } },
        ).forEach { write ->
            val dropped = ArrayList<String>()
            val arrived = received(reply(2) { write() }, dropped)
            assertEquals(listOf(null, null), stillsOf(arrived))
            assertEquals(listOf("data-0", "data-1"), arrived.episodes.map { it.data })
            assertEquals(listOf("episode art: a section that could not be read"), dropped)
        }
    }

    @Test
    fun `a still past its bytes is stepped over unread, and the next is kept`() {
        val long = "https://img.example/" + "a".repeat(HostProtocol.MAX_IMAGE_URL_BYTES)
        val dropped = ArrayList<String>()
        val arrived = received(reply(2) { art(0 to long, 1 to "https://img.example/b.jpg") }, dropped)
        assertEquals(listOf(null, "https://img.example/b.jpg"), stillsOf(arrived))
        assertEquals(listOf("episode art: still past its bytes"), dropped)
    }

    @Test
    fun `a later episode art version and a second section are stepped over whole`() {
        val dropped = ArrayList<String>()
        val frame = reply(1) {
            section(HostProtocol.EPISODE_ART_V1 + 1) { writeInt(1); writeInt(0); writeText("https://img.example/later.jpg") }
            art(0 to "https://img.example/first.jpg")
            art(0 to "https://img.example/second.jpg")
        }
        assertEquals(listOf("https://img.example/first.jpg"), stillsOf(received(frame, dropped)))
        assertEquals(listOf("a section this app does not read"), dropped)
    }

    /** `KMP-116`: the host sends what fits, so an honest provider's stills are cut, never refused whole. */
    @Test
    fun `the host sends stills for the first episodes only, and cuts trailing ones until the section fits`() {
        val many = List(HostProtocol.MAX_EPISODE_STILLS + 100) {
            RemoteEpisode(null, "d$it", 1, it + 1, "https://img.example/" + "s".repeat(400) + "/$it.jpg")
        }
        val sent = many.withStillsThatFit()
        assertEquals(many.map { it.copy(stillUrl = null) }, sent.map { it.copy(stillUrl = null) }, "every episode kept")
        val kept = sent.indexOfLast { it.stillUrl != null }
        assertTrue(kept < HostProtocol.MAX_EPISODE_STILLS, "none past the count: $kept")
        assertTrue(episodeArtBytes(sent).size <= HostProtocol.MAX_SECTION_BYTES, "the section fits")
        assertEquals(sent.take(kept + 1), many.take(kept + 1), "the stills kept are the first, unchanged")

        val tooLong = RemoteEpisode(null, "d", 1, 1, "https://img.example/" + "a".repeat(HostProtocol.MAX_IMAGE_URL_CHARS))
        assertNull(listOf(tooLong).withStillsThatFit().single().stillUrl, "one the app would skip is not sent")
    }
}
