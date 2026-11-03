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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * What the app does with replies that arrive after their call gave up.
 *
 * The fake host decides exactly which bytes arrive in which order, which a real child cannot do. The
 * real child is in SandboxEnforcementTest. A call made directly in `runTest` has its 60 s timeout on
 * virtual time, so it fails at once; one wrapped in [Dispatchers.Default] is on real time and waits.
 */
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

    /**
     * Not piped streams: those report a broken pipe when the last thread to use them has ended, and a
     * coroutine test does not promise which thread that was.
     */
    private class FakeHost : Process() {
        private val replies = LinkedBlockingQueue<ByteArray>()
        private val requests = LinkedBlockingQueue<Int>()
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
                requests.add(ByteBuffer.wrap(frame, offset, length).getInt(offset + Int.SIZE_BYTES))
            }
        }

        fun send(bytes: ByteArray) = replies.add(bytes)

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
    }
}
