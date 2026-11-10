package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.StandardSocketOptions
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Why the gateway answered with a failure. A kind and nothing else: no url, header or body goes with it (§4a). */
enum class GatewayFailure {
    RefusedAddress, TooManyRedirects, Downgrade, PlaylistTooLarge, PlaylistUnreadable, DashUnsupported,
    UpstreamTls, UpstreamTimeout, UpstreamUnreachable, UpstreamStatus4xx, UpstreamStatus5xx, BadRequest, NoToken,
}

/**
 * The media host's only way out (`KMP-113`, `docs/plans/media-gateway.md`): an HTTP/1.1 origin on loopback that fetches
 * each url it is asked for with an address-checked client, follows redirects itself, and rewrites HLS playlists so
 * every url in them comes back here.
 *
 * - **Checked:** every upstream connection meets the same two checks as `posterHttpClient`: a DNS filter on the names
 *   it resolves, and a check of the address actually connected to, on every hop. No proxy applies.
 * - **Owned headers:** `Referer` and `User-Agent` are the stream's own, given here; whatever libVLC sends for them is
 *   dropped. Only `Range` and `If-Range` (media only) and `Accept` are taken from libVLC, and never a value that names
 *   this gateway or holds its token.
 * - **Quiet:** it logs nothing and writes nothing to disk. A failure is a [GatewayFailure], counted in [failures].
 */
class MediaGateway private constructor(
    private val v4: ServerSocket,
    private val v6: ServerSocket,
    token: String,
    private val referer: String?,
    private val userAgent: String?,
    allow: (InetSocketAddress) -> Boolean,
    lookup: Dns,
    private val limits: Limits,
) : Closeable {

    data class Limits(
        val maxConnections: Int = 16,
        val maxHeadBytes: Int = 16 * 1024,
        val maxHeaders: Int = 100,
        val idleMillis: Int = 30_000,
        val connectMillis: Long = 15_000,
        val readMillis: Long = 30_000,
        val playlistMillis: Long = 5_000,
        val maxRedirects: Int = 5,
        val maxCookies: Int = 200,
        val rewriter: HlsRewriter.Limits = HlsRewriter.Limits(),
    )

    val port: Int = v4.localPort
    private val urls = GatewayUrl(port, token)
    private val rewriter = HlsRewriter(urls::encode, limits.rewriter)
    private val slots = Semaphore(limits.maxConnections)
    private val counts = EnumMap<GatewayFailure, Int>(GatewayFailure::class.java)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "media-gateway-watchdog").apply { isDaemon = true } }
    @Volatile private var closed = false

    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    lookup.lookup(hostname).filter { allow(InetSocketAddress(it, 0)) }.ifEmpty { throw Refused() }
            }
        )
        .addNetworkInterceptor(
            Interceptor { chain ->
                val remote = chain.connection()?.route()?.socketAddress
                if (remote == null || !allow(remote)) throw Refused()
                chain.proceed(chain.request())
            }
        )
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(MemoryCookieJar(limits.maxCookies))
        .connectTimeout(limits.connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(limits.readMillis, TimeUnit.MILLISECONDS)
        .cache(null)
        .build()

    /** The failures so far, by kind. For tests and counts; it holds nothing that came from a stream. */
    val failures: Map<GatewayFailure, Int> get() = synchronized(counts) { counts.toMap() }

    init {
        listOf(v4, v6).forEach { server ->
            thread(isDaemon = true, name = "media-gateway") { acceptLoop(server) }
        }
    }

    /** The url libVLC is given for a stream's first url: its role is unknown, so it is fetched as a playlist would be. */
    fun urlFor(streamUrl: String): String? = urls.encode(streamUrl, GatewayRole.Unknown)

    override fun close() {
        closed = true
        runCatching { v4.close() }
        runCatching { v6.close() }
        watchdog.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun acceptLoop(server: ServerSocket) {
        runCatching {
            while (!closed) {
                val socket = server.accept()
                if (!slots.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                thread(isDaemon = true, name = "media-gateway-connection") {
                    try {
                        socket.use { serve(it, isV6 = server === v6) }
                    } finally {
                        slots.release()
                    }
                }
            }
        }
    }

    private fun fail(kind: GatewayFailure) {
        synchronized(counts) { counts[kind] = (counts[kind] ?: 0) + 1 }
    }

    // ---- One connection from libVLC ---------------------------------------------------------------------------

    private class Head(val method: String, val target: String, val http10: Boolean, val headers: List<Pair<String, String>>) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private fun serve(socket: Socket, isV6: Boolean) {
        socket.soTimeout = limits.idleMillis
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        while (!closed) {
            val head = try {
                readHead(input) ?: return
            } catch (_: IOException) {
                return
            } catch (_: BadHead) {
                respond(output, 400, closing = true)
                fail(GatewayFailure.BadRequest)
                return
            }
            val keepAlive = handle(head, output, isV6)
            if (!keepAlive || head.http10 || head.header("Connection").equals("close", ignoreCase = true)) return
        }
    }

    /** Answers [head]; true when the connection can carry another request. */
    private fun handle(head: Head, output: OutputStream, isV6: Boolean): Boolean {
        if (isV6) return respond(output, 404)
        if (head.method == "CONNECT" || !head.target.startsWith("/")) {
            fail(GatewayFailure.BadRequest)
            return respond(output, 400, closing = true)
        }
        if (head.method != "GET" && head.method != "HEAD") return respond(output, 405, closing = true)
        // No request bodies: a GET or HEAD that says it has one is a request this does not read, so it is refused.
        if (head.header("Content-Length") != null || head.header("Transfer-Encoding") != null) {
            fail(GatewayFailure.BadRequest)
            return respond(output, 400, closing = true)
        }
        val target = urls.decode(head.target) ?: run {
            fail(GatewayFailure.NoToken)
            return respond(output, 404)
        }
        return fetch(target, head, output)
    }

    // ---- Upstream ---------------------------------------------------------------------------------------------

    private fun fetch(target: GatewayTarget, head: Head, output: OutputStream): Boolean {
        val playlist = target.role != GatewayRole.Media
        val fetched = try {
            followRedirects(target.upstream, head, playlist)
        } catch (failure: Failure) {
            fail(failure.kind)
            return respond(output, BAD_GATEWAY)
        }
        fetched.response.use {
            val code = it.code
            if (code !in 200..299 && code != 416) {
                fail(if (code in 400..499) GatewayFailure.UpstreamStatus4xx else GatewayFailure.UpstreamStatus5xx)
                return respond(output, BAD_GATEWAY)
            }
            if (head.method == "HEAD") return respondHead(output, it, playlist)
            return if (playlist) servePlaylist(fetched, output) else stream(it, output)
        }
    }

    private class Fetched(val call: Call, val response: Response)
    private class Failure(val kind: GatewayFailure) : Exception(null, null, false, false)
    private class Refused : IOException(null as String?)
    private class BadHead : Exception(null, null, false, false)

    private fun followRedirects(start: String, head: Head, playlist: Boolean): Fetched {
        var url = start.toHttpUrlOrNull() ?: throw Failure(GatewayFailure.BadRequest)
        repeat(limits.maxRedirects + 1) {
            val fetched = execute(request(url, head, playlist))
            val response = fetched.response
            if (response.code !in 300..399 || response.code == 304) return fetched
            val location = response.header("Location")
            response.close()
            val next = location?.let { url.resolve(it) } ?: throw Failure(GatewayFailure.UpstreamUnreachable)
            if (url.isHttps && !next.isHttps) throw Failure(GatewayFailure.Downgrade)
            if (next.username.isNotEmpty() || next.password.isNotEmpty()) throw Failure(GatewayFailure.BadRequest)
            url = next
        }
        throw Failure(GatewayFailure.TooManyRedirects)
    }

    private fun request(url: HttpUrl, head: Head, playlist: Boolean): Request = Request.Builder().url(url).apply {
        referer?.let { header("Referer", it) }
        userAgent?.let { header("User-Agent", it) }
        head.header("Accept")?.takeUnless(urls::namesGateway)?.let { header("Accept", it) }
        if (!playlist) {
            // Media streams through as it is: no compression for OkHttp to undo, and the ranges libVLC asked for.
            header("Accept-Encoding", "identity")
            head.header("Range")?.takeUnless(urls::namesGateway)?.let { header("Range", it) }
            head.header("If-Range")?.takeUnless(urls::namesGateway)?.let { header("If-Range", it) }
        }
        if (head.method == "HEAD") head() else get()
    }.build()

    private fun execute(request: Request): Fetched {
        val call = client.newCall(request)
        return try {
            Fetched(call, call.execute())
        } catch (_: Refused) {
            throw Failure(GatewayFailure.RefusedAddress)
        } catch (_: SocketTimeoutException) {
            throw Failure(GatewayFailure.UpstreamTimeout)
        } catch (_: SSLException) {
            throw Failure(GatewayFailure.UpstreamTls)
        } catch (failure: IOException) {
            // OkHttp wraps an interceptor's exception at times; the refusal is still a refusal.
            if (generateSequence<Throwable>(failure) { it.cause }.any { it is Refused }) throw Failure(GatewayFailure.RefusedAddress)
            throw Failure(GatewayFailure.UpstreamUnreachable)
        }
    }

    // ---- Answers ----------------------------------------------------------------------------------------------

    private fun servePlaylist(fetched: Fetched, output: OutputStream): Boolean {
        val response = fetched.response
        val source = response.body?.byteStream() ?: run {
            fail(GatewayFailure.UpstreamUnreachable)
            return respond(output, BAD_GATEWAY)
        }
        // The whole playlist, from its first byte, within its time limit: a slow trickle is a failure, not a wait.
        val timer = watchdog.schedule({ fetched.call.cancel() }, limits.playlistMillis, TimeUnit.MILLISECONDS)
        try {
            val sniff = readUpTo(source, SNIFF_BYTES, fetched.call)
                ?: run { fail(GatewayFailure.UpstreamTimeout); return respond(output, BAD_GATEWAY) }
            val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            val text = String(sniff, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            if (type == DASH_TYPE || text.startsWith("<MPD") || (text.startsWith("<?xml") && "<MPD" in text)) {
                // A manifest this does not rewrite yet. Passed through, libVLC would wait on urls it cannot reach.
                fail(GatewayFailure.DashUnsupported)
                return respond(output, BAD_GATEWAY)
            }
            if (type !in HLS_TYPES && !text.startsWith("#EXTM3U")) {
                // The first url was media after all: it streams through, whole, with no time limit on its length.
                timer.cancel(false)
                return streamFrom(response, sniff, source, output)
            }
            val rest = readUpTo(source, limits.rewriter.maxBytes + 1 - sniff.size, fetched.call)
                ?: run { fail(GatewayFailure.UpstreamTimeout); return respond(output, BAD_GATEWAY) }
            val whole = sniff + rest
            if (whole.size > limits.rewriter.maxBytes) {
                fail(GatewayFailure.PlaylistTooLarge)
                return respond(output, BAD_GATEWAY)
            }
            return when (val result = rewriter.rewrite(whole, response.request.url.toString())) {
                is Rewrite.Failed -> {
                    fail(
                        when (result.kind) {
                            RewriteFailure.Unreadable -> GatewayFailure.PlaylistUnreadable
                            else -> GatewayFailure.PlaylistTooLarge
                        }
                    )
                    respond(output, BAD_GATEWAY)
                }
                is Rewrite.Done -> {
                    val bytes = result.text.toByteArray(Charsets.UTF_8)
                    writeHead(output, 200, listOf("Content-Type" to HLS_OUT_TYPE) + passed(response), bytes.size.toLong())
                    runCatching { output.write(bytes); output.flush() }.isSuccess
                }
            }
        } finally {
            timer.cancel(false)
        }
    }

    /** Up to [max] bytes, or to the end. Null when the read fails, a cancelled call included. */
    private fun readUpTo(source: InputStream, max: Int, call: Call): ByteArray? = try {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER)
        while (buffer.size() < max) {
            val read = source.read(chunk, 0, minOf(chunk.size, max - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        if (call.isCanceled()) null else buffer.toByteArray()
    } catch (_: IOException) {
        null
    }

    private fun stream(response: Response, output: OutputStream): Boolean =
        streamFrom(response, ByteArray(0), response.body?.byteStream() ?: InputStream.nullInputStream(), output)

    private fun streamFrom(response: Response, first: ByteArray, rest: InputStream, output: OutputStream): Boolean {
        val length = response.body?.contentLength()?.takeIf { it >= 0 }
        writeHead(output, response.code, passed(response), length, closing = length == null)
        try {
            output.write(first)
            val chunk = ByteArray(BUFFER)
            while (true) {
                val read = rest.read(chunk)
                if (read < 0) break
                output.write(chunk, 0, read)
            }
            output.flush()
        } catch (_: IOException) {
            return false
        }
        return length != null
    }

    private fun respondHead(output: OutputStream, response: Response, playlist: Boolean): Boolean {
        val length = if (playlist) null else response.header("Content-Length")?.toLongOrNull()
        writeHead(output, response.code, passed(response), length, closing = length == null, headOnly = true)
        return length != null
    }

    /** The upstream headers libVLC may see (§4). Anything not named here, `Set-Cookie` and `Location` included, stays. */
    private fun passed(response: Response): List<Pair<String, String>> =
        PASSED.mapNotNull { name -> response.header(name)?.let { name to it } }

    private fun respond(output: OutputStream, code: Int, closing: Boolean = false): Boolean {
        val body = FIXED_BODY
        writeHead(output, code, listOf("Content-Type" to "text/plain"), body.size.toLong(), closing)
        runCatching { output.write(body); output.flush() }
        return !closing
    }

    private fun writeHead(
        output: OutputStream,
        code: Int,
        headers: List<Pair<String, String>>,
        length: Long?,
        closing: Boolean = false,
        headOnly: Boolean = false,
    ) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(REASONS[code] ?: "Status").append("\r\n")
            headers.forEach { (name, value) ->
                if (value.none { it == '\r' || it == '\n' }) append(name).append(": ").append(value).append("\r\n")
            }
            if (length != null && !headOnly) append("Content-Length: ").append(length).append("\r\n")
            if (closing) append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching { output.write(text.toByteArray(Charsets.ISO_8859_1)) }
    }

    // ---- Reading a request head ---------------------------------------------------------------------------------

    /** One request head, bounded. Null at a clean end of the stream; [BadHead] for anything malformed or too big. */
    private fun readHead(input: InputStream): Head? {
        val bytes = java.io.ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (bytes.size() == 0) return null
                throw BadHead()
            }
            bytes.write(b)
            if (bytes.size() > limits.maxHeadBytes) throw BadHead()
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (matched == 4) break
        }
        val lines = bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n").split("\r\n")
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

    /** Cookies for this stream only, in memory, matched by OkHttp's own rules (`Cookie.parse`, `Cookie.matches`). */
    private class MemoryCookieJar(private val max: Int) : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            cookies.forEach { cookie ->
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > now && this.cookies.size < max) this.cookies += cookie
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            this.cookies.removeAll { it.expiresAt <= now }
            return this.cookies.filter { it.matches(url) }
        }
    }

    companion object {
        private const val BAD_GATEWAY = 502
        private const val BUFFER = 64 * 1024
        private const val SNIFF_BYTES = 4096
        private const val BIND_ATTEMPTS = 5
        private const val DASH_TYPE = "application/dash+xml"
        private const val HLS_OUT_TYPE = "application/vnd.apple.mpegurl"
        private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
        private val PASSED = listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag", "Cache-Control")
        private val FIXED_BODY = "media gateway\n".toByteArray()
        private val REASONS = mapOf(
            200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed",
            416 to "Range Not Satisfiable", 502 to "Bad Gateway",
        )

        /**
         * Starts a gateway for one stream, or null when it cannot hold its port on both loopback addresses: then the
         * stream does not start, rather than run without it. The port is bound before the media host's policy can
         * name it (`docs/plans/media-gateway.md`, "The shape of the fix").
         *
         * @param allow decides an address, resolved or connected to; replaceable so a test can use loopback servers.
         * @param lookup the name lookup; replaceable so a test can give several made-up names to loopback.
         */
        fun start(
            referer: String?,
            userAgent: String?,
            allow: (InetSocketAddress) -> Boolean = { SandboxPolicy.isPublicAddress(it.address) },
            limits: Limits = Limits(),
            random: SecureRandom = SecureRandom(),
            lookup: Dns = Dns.SYSTEM,
        ): MediaGateway? {
            val token = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            repeat(BIND_ATTEMPTS) {
                val v4 = exclusive().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
                val v6 = runCatching {
                    exclusive().apply { bind(InetSocketAddress(Inet6Address.getByName("::1"), v4.localPort)) }
                }.getOrNull()
                if (v6 != null) return MediaGateway(v4, v6, token, referer, userAgent, allow, lookup, limits)
                v4.close()
            }
            return null
        }

        /**
         * A listener no other socket may share. `SO_REUSEADDR` is on by default for a Java `ServerSocket` on macOS, so
         * it is turned off; `SO_REUSEPORT`, where the platform has it, is off too.
         */
        private fun exclusive(): ServerSocket = ServerSocket().apply {
            reuseAddress = false
            if (StandardSocketOptions.SO_REUSEPORT in supportedOptions()) setOption(StandardSocketOptions.SO_REUSEPORT, false)
        }
    }
}
