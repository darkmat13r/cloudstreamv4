package com.lagradost.cloudstream4.core.http

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import platform.posix.pthread_self
import kotlinx.cinterop.toLong
import kotlin.test.assertFailsWith

/**
 * `BUG-128`: when a request through the gate fails, it is sent once more if, and only if, the gate was found dead and
 * listens again. Counted at an engine in this file, which fails the first [failures] sends and answers the rest, so
 * `NSURLSession`'s own retries (it sends some failed requests again by itself) do not enter the count.
 */
@OptIn(ExperimentalAtomicApi::class)
class GateRetryTest {

    @OptIn(InternalAPI::class)
    private class Engine(private val failures: Int, private val failure: () -> Throwable = { IllegalStateException("refused") }) :
        HttpClientEngineBase("gate-retry") {
        override val config = HttpClientEngineConfig()
        val sends = AtomicInt(0)

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            if (sends.incrementAndFetch() <= failures) throw failure()
            return HttpResponseData(
                HttpStatusCode.OK, GMTDate(), Headers.Empty, HttpProtocolVersion.HTTP_1_1, ByteReadChannel("ok"), callContext(),
            )
        }
    }

    /** The case the owner hit: the first send fails because the gate is dead, it is revived, and the second goes. */
    @Test
    fun aFailureWithTheGateRevivedIsSentOnceMore() = runBlocking {
        val engine = Engine(failures = 1)
        val revives = AtomicInt(0)
        val client = DarwinClientFactory(engine, owned = { true }, revive = { revives.incrementAndFetch(); true }).create()
        assertEquals("ok", client.post("https://example.com/") { setBody("body") }.bodyAsText())
        assertEquals(2, engine.sends.load())
        assertEquals(1, revives.load())
    }

    /** Once more at most: a second failure after a revival is the caller's. */
    @Test
    fun itIsSentOnceMoreAndNoMore() = runBlocking {
        val engine = Engine(failures = 5)
        val client = DarwinClientFactory(engine, owned = { true }, revive = { true }).create()
        assertFailsWith<IllegalStateException> { client.get("https://example.com/") }
        assertEquals(2, engine.sends.load())
    }

    /** A gate that was alive means the failure was the network's or the server's: it is not sent again. */
    @Test
    fun aFailureWithTheGateAliveIsNotSentAgain() = runBlocking {
        val engine = Engine(failures = 1)
        val client = DarwinClientFactory(engine, owned = { true }, revive = { false }).create()
        assertFailsWith<IllegalStateException> { client.post("https://example.com/") { setBody("body") } }
        assertEquals(1, engine.sends.load())
    }

    /** A body that may already be spent is never sent twice, and the gate is not even checked for it. */
    @Test
    fun aBodyThatCannotBeSentTwiceIsNotRetried() = runBlocking {
        val engine = Engine(failures = 1)
        val revives = AtomicInt(0)
        val client = DarwinClientFactory(engine, owned = { true }, revive = { revives.incrementAndFetch(); true }).create()
        val streamed = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.Plain
            override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeStringUtf8("once")
        }
        assertFailsWith<IllegalStateException> { client.post("https://example.com/") { setBody(streamed) } }
        assertEquals(1, engine.sends.load())
        assertEquals(0, revives.load())
    }

    /**
     * The gate is checked off the caller's thread: checking can wait for the accept loop, up to seconds when the port
     * cannot be had, and a request made on the main thread must not hold it that long (review S2).
     */
    @Test
    fun theGateIsCheckedOffTheCallersThread() = runBlocking {
        val caller = thread()
        var checkedOn = caller
        val client = DarwinClientFactory(Engine(failures = 1), owned = { true }, revive = { checkedOn = thread(); true }).create()
        client.get("https://example.com/")
        assertNotEquals(caller, checkedOn, "the gate was checked on the caller's thread")
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun thread(): Long = pthread_self().toLong()

    /** `BUG-129`: a gate that cannot prove it holds its port sends nothing; the request is refused before the engine. */
    @Test
    fun nothingIsSentWhileTheGateDoesNotHoldItsPort() = runBlocking {
        val engine = Engine(failures = 0)
        val client = DarwinClientFactory(engine, owned = { false }, revive = { true }).create()
        assertFailsWith<GateNotOwned> { client.get("https://example.com/") }
        assertEquals(0, engine.sends.load())
    }

    /** The second send is checked as the first was: revived, but not proven, is not sent. */
    @Test
    fun theRetryIsCheckedToo() = runBlocking {
        val engine = Engine(failures = 1)
        val asked = AtomicInt(0)
        val client = DarwinClientFactory(engine, owned = { asked.incrementAndFetch() == 1 }, revive = { true }).create()
        assertFailsWith<GateNotOwned> { client.get("https://example.com/") }
        assertEquals(1, engine.sends.load())
        assertEquals(2, asked.load())
    }

    /** Each redirect hop is a send of its own, and is checked as the first was: refused here at the second check. */
    @Test
    fun eachRedirectHopIsChecked() = runBlocking {
        val engine = RedirectingEngine()
        val asked = AtomicInt(0)
        val client = DarwinClientFactory(engine, owned = { asked.incrementAndFetch() == 1 }).create()
        assertFailsWith<GateNotOwned> { client.get("https://example.com/") }
        assertEquals(1, engine.sends.load(), "the redirect's target was sent without a check")
        assertEquals(2, asked.load())
    }

    /** Answers its first send with a redirect to another https URL, and the rest with 200. */
    @OptIn(InternalAPI::class)
    private class RedirectingEngine : HttpClientEngineBase("gate-redirect") {
        override val config = HttpClientEngineConfig()
        val sends = AtomicInt(0)

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val first = sends.incrementAndFetch() == 1
            return HttpResponseData(
                if (first) HttpStatusCode.Found else HttpStatusCode.OK,
                GMTDate(),
                if (first) Headers.build { append("Location", "https://example.org/next") } else Headers.Empty,
                HttpProtocolVersion.HTTP_1_1,
                ByteReadChannel(""),
                callContext(),
            )
        }
    }

    /** The ownership check, like the revive, is off the caller's thread: it can wait for the accept loop. */
    @Test
    fun ownershipIsCheckedOffTheCallersThread() = runBlocking {
        val caller = thread()
        var checkedOn = caller
        val client = DarwinClientFactory(Engine(failures = 0), owned = { checkedOn = thread(); true }).create()
        client.get("https://example.com/")
        assertNotEquals(caller, checkedOn, "ownership was checked on the caller's thread")
    }

    /** A cancelled request stays cancelled: no gate check, no second send. */
    @Test
    fun aCancellationIsNotRetried() = runBlocking {
        val engine = Engine(failures = 1, failure = { CancellationException("gone") })
        val revives = AtomicInt(0)
        val client = DarwinClientFactory(engine, owned = { true }, revive = { revives.incrementAndFetch(); true }).create()
        assertFailsWith<CancellationException> { client.get("https://example.com/") }
        assertEquals(1, engine.sends.load())
        assertEquals(0, revives.load())
    }

    /**
     * `BUG-132`: a copy made with `config { }`, as the download engine makes one for every run, is checked as the client
     * it was copied from is. Before, the check was added to the built client and the copy sent without it.
     */
    @Test
    fun aCopyOfTheClientSendsNothingWhileTheGateDoesNotHoldItsPort() = runBlocking {
        val engine = Engine(failures = 0)
        val copy = DarwinClientFactory(engine, owned = { false }, revive = { true }).create()
            .config { followRedirects = false; expectSuccess = false }
        assertFailsWith<GateNotOwned> { copy.get("https://example.com/") }
        assertEquals(0, engine.sends.load())
    }

    /** `BUG-132`: and a copy's failure with the gate revived is sent once more, as the client's is. */
    @Test
    fun aCopyOfTheClientIsSentOnceMoreAfterARevival() = runBlocking {
        val engine = Engine(failures = 1)
        val revives = AtomicInt(0)
        val copy = DarwinClientFactory(engine, owned = { true }, revive = { revives.incrementAndFetch(); true }).create()
            .config { expectSuccess = false }
        assertEquals("ok", copy.get("https://example.com/").bodyAsText())
        assertEquals(2, engine.sends.load())
        assertEquals(1, revives.load())
    }
}
