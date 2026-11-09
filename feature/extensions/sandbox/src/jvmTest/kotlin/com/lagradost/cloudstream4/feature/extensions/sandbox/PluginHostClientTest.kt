package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * What the app does with replies that arrive after their call gave up.
 *
 * The fake host decides exactly which bytes arrive in which order, which a real child cannot do. The
 * real child is in SandboxEnforcementTest. A call made directly in `runTest` has its 60 s timeout on
 * virtual time, so it fails at once; one wrapped in [Dispatchers.Default] is on real time and waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginHostClientTest {
    private val fake = FakeHost()
    private val client = PluginHostClient(fake, "test")

    @AfterTest
    fun tearDown() = client.close()

    private suspend fun timedOutCall(): Int {
        val failure = assertFailsWith<IOException> { client.search("api", "query", 1) }
        assertTrue("did not answer" in failure.message.orEmpty(), failure.message)
        return fake.nextRequest()
    }

    /** A whole call on real time, which proves the host is alive and the stream is still in sync. */
    private suspend fun answeredCall(name: String): String = withContext(Dispatchers.Default) {
        val call = async { client.search("api", "query", 1) }
        fake.send(success(fake.nextRequest(), name))
        call.await().items.single().name
    }

    @Test
    fun `a reply in time reaches its call`() = runTest {
        assertEquals("first", answeredCall("first"))
        fake.assertAlive()
    }

    @Test
    fun `a late reply is dropped and the next call on the same host works`() = runTest {
        val late = timedOutCall()
        fake.send(success(late, "late"))

        assertEquals("next", answeredCall("next"))
        fake.assertAlive()
    }

    @Test
    fun `a late reply reporting a failure is dropped too`() = runTest {
        val late = timedOutCall()
        fake.send(HostProtocol.encode { writeInt(late); writeBoolean(false); writeText("provider failed") })

        assertEquals("next", answeredCall("next"))
    }

    @Test
    fun `a late reply is not delivered to the call waiting at the time`() = runTest {
        val late = timedOutCall()
        val name = withContext(Dispatchers.Default) {
            val waiting = async { client.search("api", "query", 1) }
            val id = fake.nextRequest()
            fake.send(success(late, "late"))
            fake.send(success(id, "mine"))
            waiting.await().items.single().name
        }
        assertEquals("mine", name)
    }

    @Test
    fun `a late reply of the largest legal size is skipped whole`() = runTest {
        val late = timedOutCall()
        val text = "x".repeat(HostProtocol.MAX_FRAME_BYTES - 2 * Int.SIZE_BYTES - 1)
        val frame = HostProtocol.encode { writeInt(late); writeBoolean(false); writeText(text) }
        assertEquals(HostProtocol.MAX_FRAME_BYTES + Int.SIZE_BYTES, frame.size)
        fake.send(frame)

        assertEquals("next", answeredCall("next"))
    }

    @Test
    fun `an id never issued still ends the host`() = runTest {
        fake.send(success(NEVER_ISSUED, "forged"))
        fake.assertEnded()
        assertFailsWith<IOException> { client.search("api", "query", 1) }
    }

    @Test
    fun `the next id, not yet issued, is not excused by a timed-out one`() = runTest {
        val late = timedOutCall()
        fake.send(success(late + 1, "forged"))
        fake.assertEnded()
    }

    @Test
    fun `a second reply to a timed-out id ends the host`() = runTest {
        val late = timedOutCall()
        fake.send(success(late, "late"))
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        fake.send(success(late, "again"))
        fake.assertEnded()
    }

    @Test
    fun `a second reply to an answered call ends the host`() = runTest {
        val call = withContext(Dispatchers.Default) {
            val answered = async { client.search("api", "query", 1) }
            val id = fake.nextRequest()
            fake.send(success(id, "first"))
            answered.await()
            id
        }
        fake.send(success(call, "again"))
        fake.assertEnded()
    }

    @Test
    fun `a late reply that does not parse ends the host`() = runTest {
        val late = timedOutCall()
        val claimedLength = Int.MAX_VALUE
        fake.send(HostProtocol.encode { writeInt(late); writeBoolean(true); writeBoolean(false); writeInt(claimedLength) })
        fake.assertEnded()
    }

    @Test
    fun `a late reply claiming a frame past the limit ends the host`() = runTest {
        timedOutCall()
        fake.send(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(HostProtocol.MAX_FRAME_BYTES + 1).array())
        fake.assertEnded()
    }

    @Test
    fun `at the bound the host survives, one past it the host ends`() = runTest {
        val ids = List(PluginHostClient.MAX_TIMED_OUT_CALLS) { timedOutCall() }
        assertEquals(ids.size, ids.toSet().size, "an id was issued twice")
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        timedOutCall()
        fake.assertEnded()
        val refused = assertFailsWith<IOException> { client.search("api", "query", 1) }
        assertTrue("not running" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a call waiting when the bound is passed fails rather than hangs`() = runTest {
        repeat(PluginHostClient.MAX_TIMED_OUT_CALLS) { timedOutCall() }
        val waiting = async(Dispatchers.Default) { runCatching { client.search("api", "query", 1) } }
        fake.nextRequest()
        timedOutCall()
        fake.assertEnded()
        val message = waiting.await().exceptionOrNull()?.message.orEmpty()
        assertTrue("unanswered past their timeout" in message, message)
    }

    @Test
    fun `an answered late reply gives its place back`() = runTest {
        val ids = List(PluginHostClient.MAX_TIMED_OUT_CALLS) { timedOutCall() }
        fake.send(success(ids.first(), "late"))
        assertEquals("next", answeredCall("next"))

        timedOutCall()
        assertEquals("still", answeredCall("still"))
        fake.assertAlive()
    }

    private fun success(id: Int, name: String) = HostProtocol.encode {
        writeInt(id)
        writeBoolean(true)
        writeSearchPage(RemoteSearchPage(listOf(RemoteSearchItem(name, "u", null, null)), false))
    }

    // ---- KMP-76: a title's facts from a host that lies

    /** A load reply written by hand, as a host whose plugin bypassed every cap would write it. */
    private fun loadReply(id: Int, section: java.io.DataOutputStream.() -> Unit) = HostProtocol.encode {
        writeInt(id)
        writeByte(HostProtocol.REPLY_DONE)
        writeText("Title")
        writeText("u")
        writeText("TvSeries")
        writeOptional(null)
        writeOptional(null)
        writeInt(Int.MIN_VALUE)
        writeOptional(null)
        writeInt(0)
        section()
    }

    private suspend fun loaded(reply: (Int) -> ByteArray): RemoteDetails = withContext(Dispatchers.Default) {
        val call = async { client.load("api", "u") }
        fake.send(reply(fake.nextRequest()))
        call.await()
    }

    @Test
    fun `facts past every bound from a lying host cost only themselves, and keep the host`() = runTest {
        val page = loaded { id ->
            loadReply(id) {
                val body = java.io.ByteArrayOutputStream()
                java.io.DataOutputStream(body).use { out ->
                    out.writeInt(Int.MAX_VALUE)
                    out.writeInt(100_002)
                    out.writeText("x".repeat(1024 * 1024))
                    out.writeText("Drama\u202E")
                    repeat(100_000) { out.writeText("") }
                    out.writeOptional("<script>")
                    out.writeByte(9)
                    out.writeInt(-1)
                }
                writeByte(HostProtocol.FACTS_V1)
                writeInt(body.size())
                body.writeTo(this)
            }
        }
        assertEquals("Title", page.name)
        assertEquals(RemoteFacts(tags = listOf("Drama")), page.facts)
        fake.assertAlive()
        assertEquals("still", answeredCall("still"))
    }

    @Test
    fun `a facts section cut short inside its length costs the facts, and keeps the page and the host`() = runTest {
        val page = loaded { id -> loadReply(id) { writeByte(HostProtocol.FACTS_V1); writeInt(Int.SIZE_BYTES); writeInt(84) } }
        assertEquals("Title", page.name)
        assertEquals(RemoteFacts.NONE, page.facts)
        fake.assertAlive()
        assertEquals("still", answeredCall("still"))
    }

    @Test
    fun `a facts section lying about its length ends the host and fails the call`() = runTest {
        assertFailsWith<IOException> {
            loaded { id -> loadReply(id) { writeByte(HostProtocol.FACTS_V1); writeInt(Int.MAX_VALUE) } }
        }
        fake.assertEnded()
    }

    // ---- KMP-103: a title's cast and recommendations from a host that lies

    private fun java.io.DataOutputStream.emptyFactsSection() = with(TitleSectionFrames) { emptyFacts() }

    @Test
    fun `cast and recommendations past every bound from a lying host cost only themselves, and keep the host`() = runTest {
        val page = loaded { id ->
            loadReply(id) {
                emptyFactsSection()
                with(TitleSectionFrames) {
                    section(HostProtocol.CAST_V1) {
                        writeInt(30)
                        person("x".repeat(HostProtocol.MAX_PERSON_BYTES + 1))
                        person("\u202EIn\u200Des", kind = 7, role = "\u001B\u200B", image = "https://169.254.169.254/latest/meta-data")
                        repeat(28) { person("Person $it", image = "https://u:p@img.example/$it.jpg") }
                    }
                    section(HostProtocol.RECOMMENDATIONS_V1) {
                        writeInt(1_000)
                        suggestion("Itself", "u")
                        suggestion("Saltwind", "k\u0000ey")
                        repeat(998) { suggestion("T$it", "k$it", type = 200, year = Int.MAX_VALUE, poster = "file:///etc/passwd") }
                    }
                }
            }
        }
        assertEquals("Title", page.name)
        assertEquals(HostProtocol.MAX_CAST - 1, page.cast.size, "a name past its bytes is skipped unread; the rest of 30 is past the count")
        assertEquals(RemoteCastMember("Ines"), page.cast.first())
        assertTrue(page.cast.all { it.imageUrl == null })
        assertEquals(HostProtocol.MAX_RECOMMENDATIONS - 2, page.recommendations.size, "itself and the bad key were among the 20 read")
        assertTrue(page.recommendations.all { it.type == null && it.year == null && it.posterUrl == null })
        fake.assertAlive()
        assertEquals("still", answeredCall("still"))
    }

    @Test
    fun `a cast section past 64 KiB from a lying host costs the cast, and keeps the page and the host`() = runTest {
        val page = loaded { id ->
            loadReply(id) {
                emptyFactsSection()
                with(TitleSectionFrames) {
                    section(HostProtocol.CAST_V1) { writeInt(1); person("x".repeat(1024 * 1024)) }
                    section(HostProtocol.RECOMMENDATIONS_V1) { writeInt(1); suggestion("Saltwind", "s") }
                }
            }
        }
        assertEquals(emptyList(), page.cast)
        assertEquals(listOf("Saltwind"), page.recommendations.map { it.title })
        fake.assertAlive()
        assertEquals("still", answeredCall("still"))
    }

    @Test
    fun `a cast section cut short inside its length costs the cast, and keeps the page and the host`() = runTest {
        val page = loaded { id ->
            loadReply(id) {
                emptyFactsSection()
                writeByte(HostProtocol.CAST_V1)
                writeInt(Int.SIZE_BYTES)
                writeInt(5)
            }
        }
        assertEquals("Title", page.name)
        assertEquals(emptyList(), page.cast)
        fake.assertAlive()
        assertEquals("still", answeredCall("still"))
    }

    @Test
    fun `a recommendations section lying about its length ends the host and fails the call`() = runTest {
        assertFailsWith<IOException> {
            loaded { id ->
                loadReply(id) {
                    emptyFactsSection()
                    writeByte(HostProtocol.RECOMMENDATIONS_V1)
                    writeInt(Int.MAX_VALUE)
                }
            }
        }
        fake.assertEnded()
    }

    // ---- BUG-19: links as they arrive

    private fun link(name: String) = RemoteLink("s", name, "https://a.invalid/$name", 720, "VIDEO", null, null, null)

    private fun subtitle(name: String) = RemoteSubtitle(name, null, "https://a.invalid/$name.vtt", null, null)

    private fun partial(id: Int, vararg links: RemoteLink) = partial(id, RemoteLinks(links.toList(), emptyList()))

    private fun partial(id: Int, part: RemoteLinks) = HostProtocol.encode {
        writeInt(id)
        writeByte(HostProtocol.REPLY_PARTIAL)
        writeLinks(part)
    }

    private fun done(id: Int) = HostProtocol.encode {
        writeInt(id)
        writeByte(HostProtocol.REPLY_DONE)
        writeLinks(RemoteLinks(emptyList(), emptyList()))
    }

    private fun failed(id: Int, reason: String) = HostProtocol.encode {
        writeInt(id)
        writeByte(HostProtocol.REPLY_FAILED)
        writeText(reason)
    }

    /** A links call collected on real time, with each answer readable as it comes. */
    private inner class LinksCall(scope: CoroutineScope) {
        val answers = Channel<RemoteLinks>(Channel.UNLIMITED)
        val ending: Deferred<Result<Unit>> = scope.async {
            runCatching { client.linksAsTheyArrive("api", "data").collect { answers.send(it) } }
        }
        val id = fake.nextRequest()

        suspend fun names() = answers.receive().links.map { it.name }
    }

    private suspend fun <T> linksCall(block: suspend CoroutineScope.(LinksCall) -> T): T =
        withContext(Dispatchers.Default) { block(LinksCall(this)) }

    /** Past the ceiling with nothing offered: on virtual time, as [timedOutCall] is. */
    private suspend fun timedOutLinksCall(): Int {
        val failure = assertFailsWith<IOException> { client.loadLinks("api", "data") }
        assertTrue("did not answer within ${PluginHostClient.LINKS_CEILING_MILLIS}ms" in failure.message.orEmpty(), failure.message)
        return fake.nextRequest()
    }

    /** Lets the read loop and an IO hop on real threads get done, while virtual time stands still. */
    private fun TestScope.pumpUntil(reached: () -> Boolean) {
        repeat(PUMP_ATTEMPTS) {
            runCurrent()
            if (reached()) return
            Thread.sleep(PUMP_SLEEP_MILLIS)
        }
        throw AssertionError("not reached")
    }

    @Test
    fun `a link reaches its call while the plugin is still working, and the list only grows`() = runTest {
        linksCall { call ->
            fake.send(partial(call.id, link("one")))
            assertEquals(listOf("one"), call.names())
            fake.send(partial(call.id, link("two")))
            assertEquals(listOf("one", "two"), call.names())
            assertFalse(call.ending.isCompleted, "the plugin has not finished")

            fake.send(done(call.id))
            assertTrue(call.ending.await().isSuccess)
        }
        fake.assertAlive()
        assertEquals("next", answeredCall("next"))
    }

    @Test
    fun `what arrives in parts is cleaned as a whole answer would be`() = runTest {
        val answer = linksCall { call ->
            val evil = link("evil\u001B[2J\nforged")
            val track = subtitle("en")
            val sameTrack = track.copy(referer = "elsewhere")
            val badTrack = subtitle("bad").copy(url = "file:///etc/passwd")
            fake.send(partial(call.id, evil))
            fake.send(partial(call.id, RemoteLinks(emptyList(), listOf(track, badTrack))))
            fake.send(partial(call.id, RemoteLinks(emptyList(), listOf(sameTrack))))
            fake.send(done(call.id))
            assertTrue(call.ending.await().isSuccess)
            call.answers.close()
            var last: RemoteLinks? = null
            for (answer in call.answers) last = answer
            assertNotNull(last)
        }
        assertEquals(listOf("evil[2Jforged"), answer.links.map { it.name })
        assertEquals(listOf("en"), answer.subtitles.map { it.language }, "the file: track goes, and the repeat")
    }

    @Test
    fun `links up to the bound are fine, and one past it ends the host and fails the call`() = runTest {
        linksCall { call ->
            fake.send(partial(call.id, *Array(HostProtocol.MAX_LINKS) { link("l$it") }))
            assertEquals(HostProtocol.MAX_LINKS, call.names().size)
            fake.assertAlive()

            fake.send(partial(call.id, link("flood")))
            fake.assertEnded()
            assertTrue(call.ending.await().isFailure, "a host that ended fails the call, whatever had arrived")
        }
    }

    @Test
    fun `subtitles past their bound end the host, one frame at a time or not`() = runTest {
        linksCall { call ->
            val tracks = List(HostProtocol.MAX_SUBTITLES) { subtitle("t$it") }
            fake.send(partial(call.id, RemoteLinks(emptyList(), tracks)))
            fake.send(partial(call.id, link("marker")))
            assertEquals(listOf("marker"), call.names())
            fake.assertAlive()

            fake.send(partial(call.id, RemoteLinks(emptyList(), listOf(subtitle("flood")))))
            fake.assertEnded()
        }
    }

    @Test
    fun `frames within the limit one at a time still cannot pass the byte budget for a call`() = runTest {
        linksCall { call ->
            val carrying = { text: String -> partial(call.id, link("").copy(source = text)) }
            val half = "x".repeat(HostProtocol.MAX_LINK_BYTES / 2 - (carrying("").size - Int.SIZE_BYTES))
            assertEquals(HostProtocol.MAX_LINK_BYTES / 2, carrying(half).size - Int.SIZE_BYTES)
            fake.send(carrying(half))
            fake.send(carrying(half))
            while (fake.isQueued()) Thread.sleep(PUMP_SLEEP_MILLIS)
            Thread.sleep(REPLY_SETTLE_MILLIS)
            fake.assertAlive()

            fake.send(partial(call.id, link("one more")))
            fake.assertEnded()
        }
    }

    @Test
    fun `a partial with nothing in it ends the host, so frames cannot outnumber what they carry`() = runTest {
        linksCall { call ->
            fake.send(partial(call.id, RemoteLinks(emptyList(), emptyList())))
            fake.assertEnded()
        }
    }

    @Test
    fun `a partial claiming a frame past the limit ends the host`() = runTest {
        linksCall { call ->
            fake.send(partial(call.id, link("one")))
            assertEquals(listOf("one"), call.names())
            fake.send(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(HostProtocol.MAX_FRAME_BYTES + 1).array())
            fake.assertEnded()
            assertTrue(call.ending.await().isFailure)
        }
    }

    @Test
    fun `a partial that does not parse ends the host`() = runTest {
        linksCall { call ->
            val claimedCount = Int.MAX_VALUE
            fake.send(HostProtocol.encode { writeInt(call.id); writeByte(HostProtocol.REPLY_PARTIAL); writeInt(claimedCount) })
            fake.assertEnded()
        }
    }

    @Test
    fun `a partial after the final reply ends the host`() = runTest {
        val id = linksCall { call ->
            fake.send(done(call.id))
            assertTrue(call.ending.await().isSuccess)
            call.id
        }
        fake.assertAlive()
        fake.send(partial(id, link("after")))
        fake.assertEnded()
    }

    @Test
    fun `a partial to an id never issued ends the host`() = runTest {
        fake.send(partial(NEVER_ISSUED, link("forged")))
        fake.assertEnded()
    }

    @Test
    fun `a partial to a call that may not have one ends the host`() = runTest {
        withContext(Dispatchers.Default) {
            val searching = async { runCatching { client.search("api", "query", 1) } }
            fake.send(partial(fake.nextRequest(), link("forged")))
            fake.assertEnded()
            assertTrue(searching.await().isFailure)
        }
    }

    @Test
    fun `a partial to the ready id ends the host`() = runTest {
        fake.send(partial(HostProtocol.READY_ID, link("forged")))
        fake.assertEnded()
    }

    @Test
    fun `a reply of a kind the protocol does not have ends the host`() = runTest {
        linksCall { call ->
            fake.send(HostProtocol.encode { writeInt(call.id); writeByte(UNKNOWN_REPLY_KIND); writeLinks(RemoteLinks(emptyList(), emptyList())) })
            fake.assertEnded()
        }
    }

    @Test
    fun `a final reply over the bound ends the host too`() = runTest {
        linksCall { call ->
            fake.send(partial(call.id, *Array(HostProtocol.MAX_LINKS) { link("l$it") }))
            assertEquals(HostProtocol.MAX_LINKS, call.names().size)
            fake.send(HostProtocol.encode { writeInt(call.id); writeByte(HostProtocol.REPLY_DONE); writeLinks(RemoteLinks(listOf(link("flood")), emptyList())) })
            fake.assertEnded()
            assertTrue(call.ending.await().isFailure, "and the call fails now, not at the ceiling")
        }
    }

    @Test
    fun `a call with nothing at the ceiling fails as an unanswered call does, and asks the host to stop`() = runTest {
        val id = timedOutLinksCall()
        assertEquals(id, fake.nextCancel())
    }

    @Test
    fun `a partial to a timed-out id is dropped, and the next call on the host works`() = runTest {
        val late = timedOutLinksCall()
        fake.send(partial(late, link("late")))
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        fake.send(done(late))
        assertEquals("still", answeredCall("still"))
        fake.assertAlive()
    }

    @Test
    fun `a partial to a timed-out id reaches nobody, not even the call waiting at the time`() = runTest {
        val late = timedOutLinksCall()
        val names = linksCall { call ->
            fake.send(partial(late, link("late")))
            fake.send(partial(call.id, link("mine")))
            call.names().also { fake.send(done(call.id)) }
        }
        assertEquals(listOf("mine"), names)
    }

    @Test
    fun `a timed-out id still has one final reply, and a second ends the host`() = runTest {
        val late = timedOutLinksCall()
        fake.send(done(late))
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        fake.send(done(late))
        fake.assertEnded()
    }

    @Test
    fun `a timed-out id cannot flood either`() = runTest {
        val late = timedOutLinksCall()
        fake.send(partial(late, *Array(HostProtocol.MAX_LINKS) { link("l$it") }))
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        fake.send(partial(late, link("flood")))
        fake.assertEnded()
    }

    @Test
    fun `links calls left unanswered count toward the timed-out bound`() = runTest {
        repeat(PluginHostClient.MAX_TIMED_OUT_CALLS) { timedOutLinksCall() }
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        timedOutLinksCall()
        fake.assertEnded()
    }

    @Test
    fun `a call with links at the ceiling succeeds with them, and a partial just before it does not extend it`() = runTest {
        val answers = mutableListOf<RemoteLinks>()
        val call = launch { client.linksAsTheyArrive("api", "data").collect { answers += it } }
        runCurrent()
        val id = fake.nextRequest()
        fake.send(partial(id, link("early")))
        pumpUntil { answers.isNotEmpty() }

        advanceTimeBy(PluginHostClient.LINKS_CEILING_MILLIS - 1)
        fake.send(partial(id, link("just in time")))
        pumpUntil { answers.last().links.size == 2 }
        assertFalse(call.isCompleted, "the ceiling has not passed")

        advanceTimeBy(2)
        pumpUntil { call.isCompleted }
        assertEquals(listOf("early", "just in time"), answers.last().links.map { it.name })
        assertTrue("did not finish" in answers.last().stoppedEarly.orEmpty(), answers.last().stoppedEarly)
        assertEquals(id, fake.nextCancel())
    }

    @Test
    fun `leaving stops the plugin, and what crosses on the way does no harm`() = runTest {
        val id = withContext(Dispatchers.Default) {
            val first = async { client.linksAsTheyArrive("api", "data").first() }
            val id = fake.nextRequest()
            fake.send(partial(id, link("one")))
            assertEquals(listOf("one"), first.await().links.map { it.name })
            id
        }
        assertEquals(id, fake.nextCancel())
        fake.send(partial(id, link("crossed")))
        fake.send(failed(id, "CancellationException: cancelled"))
        assertEquals("next", answeredCall("next"))
        fake.assertAlive()

        fake.send(done(id))
        fake.assertEnded()
    }

    @Test
    fun `a finished call asks nothing to stop`() = runTest {
        linksCall { call ->
            fake.send(done(call.id))
            assertTrue(call.ending.await().isSuccess)
        }
        assertEquals("next", answeredCall("next"))
        assertEquals(null, fake.nextCancel(wait = 0))
    }

    @Test
    fun `a plugin failing after offering links has answered with them`() = runTest {
        val answer = withContext(Dispatchers.Default) {
            val call = async { client.loadLinks("api", "data") }
            val id = fake.nextRequest()
            fake.send(partial(id, link("one")))
            fake.send(failed(id, "NotImplementedError: a source gave up"))
            call.await()
        }
        assertEquals(listOf("one"), answer.links.map { it.name })
        assertEquals("NotImplementedError: a source gave up", answer.stoppedEarly)
        fake.assertAlive()
    }

    @Test
    fun `a plugin failing with nothing offered fails the call with its reason`() = runTest {
        val failure = withContext(Dispatchers.Default) {
            val call = async { runCatching { client.loadLinks("api", "data") } }
            fake.send(failed(fake.nextRequest(), "NotImplementedError: nothing"))
            call.await().exceptionOrNull()
        }
        assertEquals("NotImplementedError: nothing", failure?.message)
    }

    @Test
    fun `a plugin that finishes with only subtitles has an answer with no links`() = runTest {
        val answer = withContext(Dispatchers.Default) {
            val call = async { client.loadLinks("api", "data") }
            val id = fake.nextRequest()
            fake.send(partial(id, RemoteLinks(emptyList(), listOf(subtitle("en")))))
            fake.send(done(id))
            call.await()
        }
        assertTrue(answer.links.isEmpty())
        assertEquals(listOf("en"), answer.subtitles.map { it.language })
    }

    /**
     * Not piped streams: those report a broken pipe when the last thread to use them has ended, and a
     * coroutine test does not promise which thread that was.
     */
    private class FakeHost : Process() {
        private val replies = LinkedBlockingQueue<ByteArray>()
        private val requests = LinkedBlockingQueue<Int>()
        private val cancels = LinkedBlockingQueue<Int>()
        private val ended = CountDownLatch(1)
        private val killed = AtomicBoolean(false)

        private val toClient = object : InputStream() {
            private var chunk = ByteArray(0)
            private var at = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
            }

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (at == chunk.size) {
                    chunk = replies.take()
                    at = 0
                }
                if (chunk.isEmpty()) {
                    replies.add(chunk)
                    return -1
                }
                val count = minOf(length, chunk.size - at)
                chunk.copyInto(target, offset, at, at + count)
                at += count
                return count
            }
        }

        /** The client writes each request as one array, so it can be read back here as one. */
        private val fromClient = object : OutputStream() {
            override fun write(value: Int) = throw AssertionError("a request was split")

            override fun write(frame: ByteArray, offset: Int, length: Int) {
                val body = ByteBuffer.wrap(frame, offset, length)
                val id = body.getInt(offset + Int.SIZE_BYTES)
                val op = body.getInt(offset + 2 * Int.SIZE_BYTES)
                (if (op == HostProtocol.OP_CANCEL) cancels else requests).add(id)
            }
        }

        fun send(bytes: ByteArray) = replies.add(bytes)

        /** True until the reader has taken everything sent. It may still be reading the last of it. */
        fun isQueued() = replies.isNotEmpty()

        fun nextCancel(wait: Long = WAIT_SECONDS): Int? = cancels.poll(wait, TimeUnit.SECONDS)

        fun nextRequest(): Int = assertNotNull(requests.poll(WAIT_SECONDS, TimeUnit.SECONDS), "no request arrived")

        fun assertEnded() = assertTrue(ended.await(WAIT_SECONDS, TimeUnit.SECONDS), "the host was not ended")

        fun assertAlive() = assertFalse(ended.await(0, TimeUnit.SECONDS), "the host was ended")

        override fun getInputStream(): InputStream = toClient
        override fun getOutputStream(): OutputStream = fromClient
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0.also { ended.await() }
        override fun exitValue(): Int = 0

        override fun destroy() {
            ended.countDown()
            replies.add(ByteArray(0))
        }

        /**
         * A real process's streams close before this call returns, so the reader gets to run first. Only
         * the first call waits: the reader's own abort comes here too, and must not be held back.
         */
        override fun destroyForcibly(): Process = apply {
            destroy()
            if (killed.compareAndSet(false, true)) Thread.sleep(READER_HEAD_START_MILLIS)
        }
    }

    private companion object {
        const val NEVER_ISSUED = 1_000_000
        const val WAIT_SECONDS = 10L
        const val READER_HEAD_START_MILLIS = 50L
        const val BYTE_MASK = 0xff
        const val UNKNOWN_REPLY_KIND = 3
        const val PUMP_ATTEMPTS = 2000
        const val PUMP_SLEEP_MILLIS = 5L
        const val REPLY_SETTLE_MILLIS = 200L
    }
}
