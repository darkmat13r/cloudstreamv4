@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.core.http.HttpClientFactory
import com.lagradost.cloudstream4.core.http.RefusedDestination
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The iPad's media gateway (`BUG-114`): the only way libVLC reaches the network.
 *
 * libVLC (MobileVLCKit) runs in the app's own process, where nothing refuses a connection it makes. So it is given
 * gateway urls only (`http://127.0.0.1:<port>/<token>/…`, [GatewayUrl]), and this fetches each one upstream through
 * the checked client: `:core:http`'s `HttpClientFactory()`, which judges every name by the address it resolves to and
 * refuses address urls and anything but https (`BUG-111`). What comes back is decided by its content ([MediaContent]):
 * - an HLS playlist is rewritten `strict` ([HlsRewriter]), so every url in it comes back here, or is refused whole;
 * - a manifest or another playlist format is refused: the player would follow what it names;
 * - the stream's first url passes only as HLS or a container the player plays from its own bytes;
 * - media is fetched in chunks ([ByteRanges]), each a short checked request, and served as one response.
 *
 * It follows redirects itself, so the player never sees a `Location`. It sends the stream's own `Referer` and
 * `User-Agent` and nothing of the player's but `Range` and `Accept`. It logs nothing, and a failure is a [Failure] kind
 * only: an exception from the client can hold the url, so none is kept.
 */
class IosMediaGateway internal constructor(
    private val listener: Loopback.Listener,
    private val token: String,
    private val client: HttpClient,
    referer: String?,
    userAgent: String?,
    private val limits: Limits,
) {
    /** Why the gateway answered with a failure. A kind and nothing else: no url, header or body goes with it. */
    enum class Failure {
        RefusedAddress, TooManyRedirects, BadRedirect, NotMedia, Refused, PlaylistTooLarge, PlaylistUnreadable,
        UnrangedTooLarge, RangeIgnored, BadRange, ObjectChanged, UnknownLength, UpstreamTimeout, UpstreamUnreachable, UpstreamStatus4xx, UpstreamStatus5xx, BadRequest, NoToken,
        Internal,
    }

    data class Limits(
        val maxConnections: Int = 16,
        val maxHeadBytes: Int = 16 * 1024,
        val maxHeaders: Int = 100,
        val idleSeconds: Int = 30,
        val maxRedirects: Int = 5,
        /** Each upstream media request asks for at most this many bytes. */
        val chunkBytes: Long = 4L * 1024 * 1024,
        /** An upstream that ignores `Range` is served whole only up to this size. */
        val maxUnrangedBytes: Long = 16L * 1024 * 1024,
        val playlistMillis: Long = 5_000,
        val rewriter: HlsRewriter.Limits = HlsRewriter.Limits(),
    )

    /** The port of the gateway's listener on 127.0.0.1. */
    val port: Int get() = listener.port

    private val urls = GatewayUrl(port, token)
    private val rewriter = HlsRewriter(urls::encode, limits.rewriter, strict = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(limits.maxConnections + 1))
    private val open = AtomicInt(0)
    private val closed = AtomicInt(0)
    private val counts = mutableMapOf<Failure, Int>()
    private val lock = NSLock()

    /** What each first url turned out to be, so a seek does not probe it again. Keyed by the upstream url. */
    private val verdicts = mutableMapOf<String, MediaContent>()

    @Volatile private var referer: String? = headerValue(referer)
    @Volatile private var userAgent: String? = headerValue(userAgent)

    /** Failures so far, by kind. For tests; nothing in it came from a stream. */
    val failures: Map<Failure, Int> get() = locked { counts.toMap() }

    init {
        scope.launch { acceptLoop() }
    }

    /** The url libVLC is given for a stream's first url, or null when the gateway's rules refuse it. */
    fun urlFor(streamUrl: String): String? = urls.encode(streamUrl, GatewayRole.Unknown)

    /** [url] as this gateway's url for [role]: what a rewritten playlist would name. Internal, for tests. */
    internal fun urlFor(url: String, role: GatewayRole): String? = urls.encode(url, role)

    /** Whether [url] is one of this gateway's: what the player may be handed, and nothing else. */
    fun isGatewayUrl(url: String): Boolean = url.startsWith("http://127.0.0.1:$port/$token/")

    /** The stream's own `Referer` and `User-Agent`, for every upstream request from now on. */
    fun useHeaders(referer: String?, userAgent: String?) {
        this.referer = headerValue(referer)
        this.userAgent = headerValue(userAgent)
    }

    fun close() {
        if (closed.incrementAndFetch() != 1) return
        listener.close()
        scope.cancel()
    }

    private fun fail(kind: Failure) = locked { counts[kind] = (counts[kind] ?: 0) + 1 }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // ---- Connections ------------------------------------------------------------------------------------------

    private fun acceptLoop() {
        while (closed.load() == 0) {
            val fd = listener.accept() ?: continue
            if (open.incrementAndFetch() > limits.maxConnections) {
                open.decrementAndFetch()
                Loopback.close(fd)
                continue
            }
            scope.launch {
                try {
                    serve(fd)
                } catch (_: Throwable) {
                    // Nothing escapes: a message could hold a url.
                    fail(Failure.Internal)
                } finally {
                    Loopback.close(fd)
                    open.decrementAndFetch()
                }
            }
        }
    }

    private class Head(val method: String, val target: String, val http10: Boolean, val headers: List<Pair<String, String>>) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private class BadHead : Exception()

    private suspend fun serve(fd: Int) {
        Loopback.prepare(fd, limits.idleSeconds)
        val reader = Loopback.Reader(fd)
        while (closed.load() == 0) {
            val head = try {
                readHead(reader) ?: return
            } catch (_: BadHead) {
                fail(Failure.BadRequest)
                respond(fd, 400, closing = true)
                return
            }
            val keepAlive = handle(head, fd)
            if (!keepAlive || head.http10 || head.header("Connection").equals("close", ignoreCase = true)) return
        }
    }

    /** Answers [head]; true when the connection can carry another request. */
    private suspend fun handle(head: Head, fd: Int): Boolean {
        if (head.method == "CONNECT" || !head.target.startsWith("/")) {
            fail(Failure.BadRequest)
            return respond(fd, 400, closing = true)
        }
        if (head.method != "GET" && head.method != "HEAD") return respond(fd, 405, closing = true)
        if (head.header("Content-Length") != null || head.header("Transfer-Encoding") != null) {
            fail(Failure.BadRequest)
            return respond(fd, 400, closing = true)
        }
        // A wrong or missing token, and any target this scheme does not make, get the same answer.
        val target = urls.decode(head.target) ?: run {
            fail(Failure.NoToken)
            return respond(fd, 404)
        }
        return try {
            when (target.role) {
                GatewayRole.Playlist -> servePlaylist(target.upstream, head, fd)
                GatewayRole.Unknown -> serveFirst(target.upstream, head, fd)
                GatewayRole.Media -> serveMedia(target.upstream, head, fd, firstUrl = false)
            }
        } catch (failure: Stop) {
            fail(failure.kind)
            respond(fd, failure.status, closing = failure.status != 404)
        }
    }

    /** A request that ends in a fixed answer, counted as [kind]. */
    private class Stop(val kind: Failure, val status: Int = BAD_GATEWAY) : Exception()

    // ---- What each role gets ----------------------------------------------------------------------------------

    /** A variant or rendition: must be an HLS playlist, and is rewritten. */
    private suspend fun servePlaylist(upstream: String, head: Head, fd: Int): Boolean {
        val (body, finalUrl) = fetchWhole(upstream)
        if (MediaContent.of(body) != MediaContent.Hls) throw Stop(Failure.NotMedia)
        return writePlaylist(body, finalUrl, head, fd)
    }

    /** The stream's first url: HLS (rewritten) or a container (served as media). Anything else is refused. */
    private suspend fun serveFirst(upstream: String, head: Head, fd: Int): Boolean =
        when (verdictFor(upstream)) {
            MediaContent.Hls -> {
                val (body, finalUrl) = fetchWhole(upstream)
                if (MediaContent.of(body) != MediaContent.Hls) throw Stop(Failure.NotMedia)
                writePlaylist(body, finalUrl, head, fd)
            }
            MediaContent.Container -> serveMedia(upstream, head, fd, firstUrl = true)
            MediaContent.Manifest, MediaContent.OtherPlaylist -> throw Stop(Failure.Refused)
            MediaContent.Opaque -> throw Stop(Failure.NotMedia)
        }

    /**
     * The first url's kind, from its first bytes: a checked request for bytes 0 to 4095, made once per upstream url and
     * kept, so a seek (a first request that does not start at 0) is judged by the start of the file too.
     */
    private suspend fun verdictFor(upstream: String): MediaContent {
        locked { verdicts[upstream] }?.let { return it }
        val verdict = fetch(upstream, 0L until MediaContent.SNIFF_BYTES.toLong(), ifRange = null) { probe ->
            checkStatus(probe.status)
            MediaContent.of(probe.channel.readCapped(MediaContent.SNIFF_BYTES.toLong()))
        }
        locked { verdicts[upstream] = verdict }
        return verdict
    }

    /**
     * Media: the range the player asked for, fetched upstream in chunks of at most [Limits.chunkBytes], each a checked
     * request, and written as one response. What upstream sends must be exactly what was asked:
     * - a 206 whose range starts where asked and ends no later, and a body of exactly that many bytes (read no further);
     * - the same total and the same `ETag` (or `Last-Modified`) on every chunk, each asked with `If-Range`: a changed
     *   object fails the stream rather than be spliced into it;
     * - a 200 to a range that does not start at 0 is a failure: those bytes do not start where the player was told.
     * It stops at the first short chunk, a 416, the total or a player that went away, and fetches nothing ahead.
     */
    private suspend fun serveMedia(upstream: String, head: Head, fd: Int, firstUrl: Boolean): Boolean {
        val asked = ByteRanges.parse(head.header("Range"))
        val request = asked ?: ByteRanges.Request.WHOLE
        val start = ByteRanges.probeStart(request)
        val firstWant = start..(start + limits.chunkBytes - 1)
        return fetch(upstream, firstWant, ifRange = null) { first ->
            when (first.status) {
                416 -> writeHead(fd, 416, listOf("Content-Range" to "bytes */${unsatisfiedTotal(first.contentRange)}"), length = 0)
                200 -> if (start != 0L) throw Stop(Failure.RangeIgnored) else serveUnranged(first, asked, head, fd, firstUrl)
                206 -> serveRanged(first, firstWant, request, asked != null, upstream, head, fd, firstUrl)
                else -> {
                    checkStatus(first.status)
                    throw Stop(Failure.UpstreamUnreachable)
                }
            }
        }
    }

    private suspend fun serveRanged(
        first: Fetched, firstWant: LongRange, request: ByteRanges.Request, ranged: Boolean, upstream: String, head: Head,
        fd: Int, firstUrl: Boolean,
    ): Boolean {
        val got = ByteRanges.parseContentRange(first.contentRange) ?: throw Stop(Failure.BadRange)
        if (got.first != firstWant.first || got.last > firstWant.last) throw Stop(Failure.BadRange)
        val validator = first.validator
        val total = got.total ?: return serveUnknownLength(first, got, firstWant, upstream, head, fd, firstUrl)
        val plan = when (val p = ByteRanges.plan(request, total, ranged)) {
            is ByteRanges.Plan.Unsatisfiable -> return writeHead(fd, 416, listOf("Content-Range" to p.contentRange), length = 0)
            is ByteRanges.Plan.Serve -> p
        }
        // A suffix range starts where only the total could say: fetched again from there, now as an explicit range.
        if (plan.first != got.first) {
            val want = ByteRanges.chunk(plan.first, plan.last, limits.chunkBytes)
            return fetch(upstream, want, ifRange = null) { again ->
                if (again.status != 206) throw Stop(Failure.BadRange)
                serveRanged(again, want, ByteRanges.Request(plan.first, plan.last, null), ranged = true, upstream, head, fd, firstUrl)
            }
        }
        // The bytes actually served are judged before anything is promised, not the probe's: an origin may answer the
        // probe with media and the fetch with a playlist.
        val sniff = first.channel.readCapped(minOf(MediaContent.SNIFF_BYTES.toLong(), got.last - got.first + 1, plan.length))
        if (sniff.size.toLong() != minOf(MediaContent.SNIFF_BYTES.toLong(), got.last - got.first + 1, plan.length)) throw Stop(Failure.UpstreamUnreachable)
        judgeServed(sniff, fromStart = got.first == 0L, firstUrl = firstUrl)
        val headers = mutableListOf("Content-Type" to contentType(firstUrl), "Accept-Ranges" to "bytes")
        if (plan.partial) headers += "Content-Range" to plan.contentRange
        writeHead(fd, if (plan.partial) 206 else 200, headers, length = plan.length, headOnly = head.method == "HEAD")
        if (head.method == "HEAD" || plan.length == 0L) return true
        if (!Loopback.writeAll(fd, sniff)) return false
        var next = got.first + sniff.size
        if (next <= minOf(got.last, plan.last)) next = copyExact(first, fd, next, got.last, plan.last) ?: return false
        if (next <= plan.last && got.last < firstWant.last) return false // a short chunk before the end
        while (next <= plan.last) {
            val want = ByteRanges.chunk(next, plan.last, limits.chunkBytes)
            val from = next
            next = fetch(upstream, want, ifRange = validator) { chunk ->
                val part = ByteRanges.parseContentRange(chunk.contentRange)
                // The body is already promised to the player: a mismatch ends the connection, and it asks again.
                if (chunk.status != 206 || part == null || part.first != from || part.last > want.last ||
                    part.total != total || (validator != null && chunk.validator != validator)
                ) {
                    fail(Failure.ObjectChanged)
                    return@fetch null
                }
                val after = copyExact(chunk, fd, part.first, part.last, plan.last)
                if (after != null && after <= plan.last && part.last < want.last) null else after
            } ?: return false
        }
        return true
    }

    /**
     * A 206 whose total is `*`: served from offset 0 only, as a 200 with no length that ends when upstream does (at the
     * first short chunk). A range anywhere else cannot be told to the player without the total, so it fails.
     */
    private suspend fun serveUnknownLength(
        first: Fetched, got: ByteRanges.Upstream, firstWant: LongRange, upstream: String, head: Head, fd: Int, firstUrl: Boolean,
    ): Boolean {
        if (firstWant.first != 0L) throw Stop(Failure.UnknownLength)
        val sniff = first.channel.readCapped(minOf(MediaContent.SNIFF_BYTES.toLong(), got.last - got.first + 1))
        judgeServed(sniff, fromStart = true, firstUrl = firstUrl)
        writeHead(fd, 200, listOf("Content-Type" to contentType(firstUrl)), length = null, headOnly = head.method == "HEAD")
        if (head.method == "HEAD") return false
        if (!Loopback.writeAll(fd, sniff)) return false
        val validator = first.validator
        var next = got.first + sniff.size
        if (next <= got.last) next = copyExact(first, fd, next, got.last, Long.MAX_VALUE) ?: return false
        if (got.last < firstWant.last) return false
        while (true) {
            val want = next..(next + limits.chunkBytes - 1)
            val from = next
            next = fetch(upstream, want, ifRange = validator) { chunk ->
                if (chunk.status == 416) return@fetch null
                val part = ByteRanges.parseContentRange(chunk.contentRange)
                if (chunk.status != 206 || part == null || part.first != from || part.last > want.last ||
                    (validator != null && chunk.validator != validator)
                ) {
                    fail(Failure.ObjectChanged)
                    return@fetch null
                }
                val after = copyExact(chunk, fd, part.first, part.last, Long.MAX_VALUE)
                if (after != null && part.last < want.last) null else after
            } ?: return false
        }
    }

    /** An upstream that ignored `Range` (a 200 to a range from 0): served whole, if small enough for one request. */
    private suspend fun serveUnranged(first: Fetched, asked: ByteRanges.Request?, head: Head, fd: Int, firstUrl: Boolean): Boolean {
        val length = first.contentLength
        // Unknown or large: one request would run into the session's 60 s cap mid-body. A fixed failure, not a cut.
        if (length == null || length > limits.maxUnrangedBytes) throw Stop(Failure.UnrangedTooLarge)
        // Exactly the stated length, read no further: a server sending more cannot grow the buffer.
        val body = first.channel.readCapped(length)
        if (body.size.toLong() != length) throw Stop(Failure.UpstreamUnreachable)
        return when (val plan = ByteRanges.plan(asked ?: ByteRanges.Request.WHOLE, length, ranged = asked != null)) {
            is ByteRanges.Plan.Unsatisfiable -> writeHead(fd, 416, listOf("Content-Range" to plan.contentRange), length = 0)
            is ByteRanges.Plan.Serve -> {
                if (plan.length > 0) {
                    val from = plan.first.toInt()
                    judgeServed(body.copyOfRange(from, minOf(body.size, from + MediaContent.SNIFF_BYTES)), fromStart = from == 0, firstUrl = firstUrl)
                }
                val headers = mutableListOf("Content-Type" to contentType(firstUrl), "Accept-Ranges" to "bytes")
                if (plan.partial) headers += "Content-Range" to plan.contentRange
                writeHead(fd, if (plan.partial) 206 else 200, headers, length = plan.length, headOnly = head.method == "HEAD")
                if (head.method == "HEAD" || plan.length == 0L) true
                else Loopback.writeAll(fd, body.copyOfRange(plan.first.toInt(), (plan.last + 1).toInt()))
            }
        }
    }

    /**
     * The first bytes of what is about to be served, judged before any head is written, whatever offset they are at:
     * never a playlist or a manifest, and, for the first url from offset 0, a container. These are the bytes the player
     * probes, so they are judged rather than the probe's (an origin can answer the two differently).
     */
    private fun judgeServed(sniff: ByteArray, fromStart: Boolean, firstUrl: Boolean) {
        if (sniff.isEmpty()) return
        val kind = MediaContent.of(sniff)
        if (kind in REFUSED_AT_START) throw Stop(Failure.Refused)
        if (firstUrl && fromStart && kind != MediaContent.Container) throw Stop(Failure.NotMedia)
    }

    /**
     * Reads exactly bytes [from]..[upTo] of [fetched]'s body, writing those up to [stop] to the player; the next offset,
     * or null when upstream sent fewer bytes than it said, or the player went away. Never reads past [upTo]: a server
     * that sends more cannot grow anything.
     */
    private suspend fun copyExact(fetched: Fetched, fd: Int, from: Long, upTo: Long, stop: Long): Long? {
        val end = minOf(upTo, stop)
        var position = from
        val buffer = ByteArray(BUFFER)
        while (position <= end) {
            val want = minOf(BUFFER.toLong(), end - position + 1).toInt()
            val read = fetched.channel.readAvailable(buffer, 0, want)
            if (read <= 0) return null
            if (!Loopback.writeAll(fd, buffer.copyOf(read))) return null
            position += read
        }
        return position
    }

    /** The total of an upstream 416's `Content-Range` (`bytes`, a star, `/total`), as digits; 0 when it gave none. */
    private fun unsatisfiedTotal(contentRange: String?): Long =
        contentRange?.substringAfter('/', "")?.takeIf { it.isNotEmpty() && it.length <= 18 && it.all { c -> c in '0'..'9' } }?.toLong() ?: 0

    private fun contentType(firstUrl: Boolean) = if (firstUrl) "video/mp2t" else "application/octet-stream"

    private fun writePlaylist(body: ByteArray, finalUrl: String, head: Head, fd: Int): Boolean =
        when (val result = rewriter.rewrite(body, finalUrl)) {
            is Rewrite.Failed -> throw Stop(
                when (result.kind) {
                    RewriteFailure.Unreadable -> Failure.PlaylistUnreadable
                    RewriteFailure.Unrewritable -> Failure.Refused
                    else -> Failure.PlaylistTooLarge
                },
            )
            is Rewrite.Done -> {
                val bytes = result.text.encodeToByteArray()
                writeHead(fd, 200, listOf("Content-Type" to HLS_TYPE), bytes.size.toLong(), headOnly = head.method == "HEAD")
                head.method == "HEAD" || Loopback.writeAll(fd, bytes)
            }
        }

    // ---- Upstream ---------------------------------------------------------------------------------------------

    /** One upstream response, open while the block given to [fetch] runs. */
    private class Fetched(
        val status: Int,
        val contentRange: String?,
        val contentLength: Long?,
        /** `ETag`, else `Last-Modified`: what says it is still the same object. */
        val validator: String?,
        val channel: ByteReadChannel,
        /** The url this response came from, after redirects: what a playlist's relative urls resolve against. */
        val url: String,
    )

    private sealed interface Hop<out T> {
        class Redirect(val url: String) : Hop<Nothing>
        class Done<T>(val value: T) : Hop<T>
    }

    /**
     * [upstream] (bytes [range], or whole), redirects followed here, each hop a request through the checked client;
     * [block] reads the final response while it is open. Nothing of the player's request goes upstream.
     */
    private suspend fun <T> fetch(upstream: String, range: LongRange?, ifRange: String?, block: suspend (Fetched) -> T): T {
        var current = upstream
        repeat(limits.maxRedirects + 1) {
            val hop: Hop<T> = try {
                client.prepareRequest { build(current, range, ifRange) }.execute { response ->
                    val next = redirect(current, response)
                    if (next != null) {
                        Hop.Redirect(next)
                    } else {
                        Hop.Done(
                            block(
                                Fetched(
                                    response.status.value, response.headers["Content-Range"],
                                    response.headers["Content-Length"]?.toLongOrNull(), validatorOf(response),
                                    response.bodyAsChannel(), current,
                                ),
                            ),
                        )
                    }
                }
            } catch (stop: Stop) {
                throw stop
            } catch (e: Throwable) {
                throw mapped(e)
            }
            when (hop) {
                is Hop.Redirect -> current = hop.url
                is Hop.Done -> return hop.value
            }
        }
        throw Stop(Failure.TooManyRedirects)
    }

    /** A playlist, whole: no `Range`, at most the rewriter's size, within [Limits.playlistMillis]. And its final url. */
    private suspend fun fetchWhole(upstream: String): Pair<ByteArray, String> = try {
        withTimeout(limits.playlistMillis) {
            fetch(upstream, range = null, ifRange = null) { response ->
                checkStatus(response.status)
                val body = response.channel.readCapped(limits.rewriter.maxBytes + 1L)
                if (body.size > limits.rewriter.maxBytes) throw Stop(Failure.PlaylistTooLarge)
                body to response.url
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw Stop(Failure.UpstreamTimeout)
    }

    /**
     * An upstream request: the stream's `Referer` and `User-Agent`, and for media the gateway's own chunk as `Range`
     * with the first chunk's validator as `If-Range` (a changed object then answers 200, which fails the stream).
     */
    private fun HttpRequestBuilder.build(target: String, range: LongRange?, ifRange: String?) {
        method = HttpMethod.Get
        url(target)
        referer?.let { header("Referer", it) }
        userAgent?.let { header("User-Agent", it) }
        if (range != null) {
            header("Range", ByteRanges.header(range))
            header("Accept-Encoding", "identity")
            ifRange?.let { header("If-Range", it) }
        }
    }

    private fun validatorOf(response: HttpResponse): String? =
        (response.headers["ETag"] ?: response.headers["Last-Modified"])?.let(::headerValue)

    /**
     * The next hop of a redirect, or null when [response] is not one. Refused: missing, not http(s), a step down from
     * https to http, or with user info. The checked client refuses anything but https on every hop besides (`BUG-111`),
     * so in the app every hop is https; the player is never told where it went.
     */
    private fun redirect(current: String, response: HttpResponse): String? {
        val status = response.status.value
        if (status !in 300..399 || status == 304) return null
        val location = response.headers["Location"] ?: throw Stop(Failure.BadRedirect)
        val resolved = UriOps.resolve(current, HlsRewriter.lenient(location)) ?: throw Stop(Failure.BadRedirect)
        val scheme = resolved.scheme?.lowercase()
        val wasHttps = current.startsWith("https:", ignoreCase = true)
        if (scheme !in HTTP_SCHEMES || (wasHttps && scheme != "https") || resolved.rawUserInfo != null || resolved.rawAuthority.isNullOrEmpty()) {
            throw Stop(Failure.BadRedirect)
        }
        return "$scheme://${resolved.rawAuthority}${resolved.rawPath?.ifEmpty { "/" } ?: "/"}" + resolved.rawQuery?.let { "?$it" }.orEmpty()
    }

    private fun checkStatus(code: Int) {
        if (code in 200..299) return
        throw Stop(if (code in 400..499) Failure.UpstreamStatus4xx else Failure.UpstreamStatus5xx)
    }

    /** A client failure as a kind. Its message is dropped: it can hold the url. */
    private fun mapped(e: Throwable): Stop = when {
        e is Stop -> e
        generateSequence(e) { it.cause }.any { it is RefusedDestination } -> Stop(Failure.RefusedAddress)
        generateSequence(e) { it.cause }.any { it is TimeoutCancellationException || it::class.simpleName?.contains("Timeout") == true } ->
            Stop(Failure.UpstreamTimeout)
        else -> Stop(Failure.UpstreamUnreachable)
    }

    // ---- Answers ----------------------------------------------------------------------------------------------

    private fun respond(fd: Int, code: Int, closing: Boolean = false): Boolean {
        writeHead(fd, code, listOf("Content-Type" to "text/plain"), FIXED_BODY.size.toLong(), closing = closing)
        Loopback.writeAll(fd, FIXED_BODY)
        return !closing
    }

    /**
     * A response head. Only what is passed here goes to the player: `Content-Type`, `Content-Range`, `Accept-Ranges` and
     * the length. Never `Location`, `Set-Cookie` or anything upstream sent. [length] null means close-delimited.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun writeHead(fd: Int, code: Int, headers: List<Pair<String, String>>, length: Long?, closing: Boolean = false, headOnly: Boolean = false): Boolean {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(REASONS[code] ?: "Status").append("\r\n")
            headers.forEach { (name, value) -> if (value.none { it == '\r' || it == '\n' }) append(name).append(": ").append(value).append("\r\n") }
            if (length != null) append("Content-Length: ").append(length).append("\r\n")
            if (closing || length == null) append("Connection: close\r\n")
            append("\r\n")
        }
        Loopback.writeAll(fd, text.encodeToByteArray())
        return true
    }

    // ---- A request head -----------------------------------------------------------------------------------------

    /** One request head, bounded. Null at a clean end of the stream; [BadHead] for anything malformed or too big. */
    private fun readHead(reader: Loopback.Reader): Head? {
        val bytes = ArrayList<Byte>()
        var matched = 0
        while (true) {
            val b = reader.read()
            if (b < 0) {
                if (bytes.isEmpty()) return null
                throw BadHead()
            }
            bytes += b.toByte()
            if (bytes.size > limits.maxHeadBytes) throw BadHead()
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (matched == 4) break
        }
        val text = bytes.toByteArray().let { raw -> CharArray(raw.size) { (raw[it].toInt() and 0xFF).toChar() }.concatToString() }
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3 || !request[2].startsWith("HTTP/1.")) throw BadHead()
        val headers = lines.drop(1)
        if (headers.size > limits.maxHeaders) throw BadHead()
        val parsed = headers.map { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadHead()
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        return Head(request[0], request[1], http10 = request[2] == "HTTP/1.0", headers = parsed)
    }

    companion object {
        private const val BAD_GATEWAY = 502
        private const val BUFFER = 64 * 1024
        private const val HLS_TYPE = "application/vnd.apple.mpegurl"
        private val HTTP_SCHEMES = setOf("http", "https")
        private val REFUSED_AT_START = setOf(MediaContent.Hls, MediaContent.Manifest, MediaContent.OtherPlaylist)
        private val FIXED_BODY = "media gateway\n".encodeToByteArray()
        private val REASONS = mapOf(
            200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed",
            416 to "Range Not Satisfiable", 502 to "Bad Gateway",
        )

        /** A header value the gateway may send: printable ASCII and tab only, and not too long. Else none. */
        internal fun headerValue(value: String?): String? =
            value?.takeIf { it.length <= 8 * 1024 && it.all { c -> c == '\t' || c in ' '..'~' } }

        /**
         * A gateway for one stream, fetching through `:core:http`'s checked client; null when it cannot listen. The
         * player is then not started, rather than given a url that is not the gateway's.
         */
        fun start(referer: String?, userAgent: String?, limits: Limits = Limits()): IosMediaGateway? =
            start(referer, userAgent, HttpClientFactory().create { followRedirects = false; expectSuccess = false }, limits)

        /** [start] with the upstream [client] given: tests use one that may reach local origins. */
        internal fun start(referer: String?, userAgent: String?, client: HttpClient, limits: Limits): IosMediaGateway? {
            val listener = Loopback.listen() ?: return null
            return IosMediaGateway(listener, newToken() ?: return null.also { listener.close() }, client, referer, userAgent, limits)
        }

        /** 128 bits from the system's random source, base64url without padding: what [GatewayUrl.TOKEN] expects. */
        @OptIn(ExperimentalEncodingApi::class)
        private fun newToken(): String? {
            val bytes = ByteArray(16)
            val ok = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 16u, it.addressOf(0)) } == 0
            if (!ok) return null
            return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
        }
    }
}

/** Up to [max] bytes of the channel, or fewer at its end. Never more: a server that sends more grows nothing. */
private suspend fun ByteReadChannel.readCapped(max: Long): ByteArray {
    val out = ArrayList<Byte>()
    val buffer = ByteArray(64 * 1024)
    while (out.size < max) {
        val read = readAvailable(buffer, 0, minOf(buffer.size.toLong(), max - out.size).toInt())
        if (read <= 0) break
        for (i in 0 until read) out += buffer[i]
    }
    return out.toByteArray()
}
