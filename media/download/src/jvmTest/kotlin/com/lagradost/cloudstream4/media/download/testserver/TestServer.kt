package com.lagradost.cloudstream4.media.download.testserver

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deterministic byte source: byte `i` depends only on `(seed, i)`, so any slice of a "file" can be
 * regenerated on demand and a downloaded file can be compared with it without keeping it in memory.
 */
class SeededSource(val seed: Long, val size: Long) {
    fun fill(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
        var pos = offset
        var i = bufferOffset
        val end = bufferOffset + length
        var cachedBlock = -1L
        var word = 0L
        while (i < end) {
            val block = pos ushr 3
            if (block != cachedBlock) {
                word = mix(seed, block)
                cachedBlock = block
            }
            buffer[i] = (word ushr ((pos and 7L).toInt() shl 3)).toByte()
            i++
            pos++
        }
    }

    fun bytes(offset: Long, length: Int): ByteArray = ByteArray(length).also { fill(offset, it, 0, length) }

    fun digest(algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val chunk = ByteArray(1 shl 20)
        var pos = 0L
        while (pos < size) {
            val n = minOf(chunk.size.toLong(), size - pos).toInt()
            fill(pos, chunk, 0, n)
            md.update(chunk, 0, n)
            pos += n
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(): String = digest("SHA-256")
    fun md5Hex(): String = digest("MD5")

    /** `null` when [file] holds exactly this source, otherwise a description of the first difference. */
    fun describeMismatch(file: File): String? {
        if (!file.isFile) return "no such file: ${file.path}"
        if (file.length() != size) return "length ${file.length()} != expected $size"
        val actual = ByteArray(1 shl 20)
        val expected = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            var pos = 0L
            while (pos < size) {
                val want = minOf(actual.size.toLong(), size - pos).toInt()
                var got = 0
                while (got < want) {
                    val n = input.read(actual, got, want - got)
                    if (n < 0) return "file ended at ${pos + got}"
                    got += n
                }
                fill(pos, expected, 0, want)
                for (i in 0 until want) {
                    if (actual[i] != expected[i]) return "first differing byte at offset ${pos + i}"
                }
                pos += want
            }
        }
        return null
    }

    private fun mix(seed: Long, block: Long): Long {
        var z = seed + block * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}

/** What the server currently holds at a path. Replace it whole to "change the file between requests". */
class Content(
    val seed: Long,
    val size: Long,
    val etag: String? = "\"v$seed\"",
    val lastModified: String? = null,
) {
    val source: SeededSource get() = SeededSource(seed, size)
}

enum class LengthMode {
    /** Content-Length is the body size. */
    DECLARED,

    /** No Content-Length: the body ends when the server closes. */
    MISSING,

    /** Content-Length claims more than is sent, then the server closes: a truncated response. */
    TOO_LONG,
}

sealed interface Route

/** A file. All switches are read afresh on every request, so a test can flip them mid-download. */
class Resource(@Volatile var content: Content) : Route {
    @Volatile var supportsRange: Boolean = true
    @Volatile var lengthMode: LengthMode = LengthMode.DECLARED
    @Volatile var honorIfRange: Boolean = true
    @Volatile var contentType: String = "application/octet-stream"
    @Volatile var contentEncoding: String? = null

    /** `true`: a range on an empty file is a 416 with `*` and `/0`. `false`: ranges are ignored, 200 with length 0. */
    @Volatile var emptyAs416: Boolean = true
    @Volatile var extraHeaders: Map<String, String> = emptyMap()
    val size: Long get() = content.size
    val source: SeededSource get() = content.source
}

class Redirect(val location: String, val status: Int = 302) : Route

/** What the server decides to do to one request instead of answering it normally. */
sealed interface Fault {
    /**
     * Send [bytes] of the body, then close cleanly (FIN): the client sees an early end of stream.
     *
     * [holdUntil], when given, is polled before the close, for at most ten seconds (`BUG-100`): a
     * test that measures what the client kept can wait for the client to take in what was sent, rather than racing
     * the bytes still queued inside the client when the stream ends.
     */
    data class DropAfter(val bytes: Long, val holdUntil: (() -> Boolean)? = null) : Fault

    /** Send [bytes] of the body, then reset the connection (RST). */
    data class ResetAfter(val bytes: Long) : Fault

    /** Send [bytes] of the body, then go silent for [millis] and close. */
    data class StallAfter(val bytes: Long, val millis: Long) : Fault

    /** Answer with this status and a tiny text body. */
    data class Status(val code: Int, val retryAfterSeconds: Int? = null) : Fault

    /** Answer a ranged request with a plain 200 and the whole body. */
    data object IgnoreRange : Fault
}

class RequestInfo(
    /** 1-based, across the whole server. */
    val index: Int,
    val path: String,
    val method: String,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val rangeStart: Long?,
    /** Inclusive; `null` for an open `bytes=a-`. */
    val rangeEnd: Long?,
    /** How many body-serving requests were in flight, this one included. */
    val active: Int,
) {
    /** The engine's probe is `Range: bytes=0-0`. */
    val isProbe: Boolean get() = rangeStart == 0L && rangeEnd == 0L
    override fun toString(): String = "#$index $method $path range=$rangeStart-$rangeEnd"
}

class LogEntry(val info: RequestInfo) {
    @Volatile var status: Int = 0
    @Volatile var bytesSent: Long = 0
    @Volatile var servedStart: Long? = null
    @Volatile var servedEnd: Long? = null

    /** Bytes of body the response promised (a 206's range length, a 200's total), 0 for non-body answers. */
    @Volatile var declaredBytes: Long = 0
    val timeNanos: Long = System.nanoTime()
    override fun toString(): String = "${info} -> $status sent=$bytesSent"
}

/**
 * A raw-socket HTTP/1.1 server on 127.0.0.1, port chosen by the OS, one request per connection
 * (`Connection: close`).
 *
 * It is a socket server and not `com.sun.net.httpserver` because the faults the engine must survive
 * (a Content-Length that lies, a connection reset mid-body, a body that ends at close) are not
 * expressible through that API without relying on its exception paths.
 */
class TestServer : AutoCloseable {
    private val serverSocket = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, Route>()
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "test-server").apply { isDaemon = true } }
    private val counter = AtomicInteger()
    private val admissionLock = Any()
    private var serving = 0

    @Volatile private var peak = 0

    val log: CopyOnWriteArrayList<LogEntry> = CopyOnWriteArrayList()
    val port: Int get() = serverSocket.localPort

    /** More than this many requests in flight at once get a 503. 0 is no limit. */
    @Volatile var connectionLimit: Int = 0

    /** Per request fault, decided after [onRequest] ran. */
    @Volatile var fault: (RequestInfo) -> Fault? = { null }

    /** Body bytes per second for one connection. 0 is unthrottled. */
    @Volatile var throttle: (RequestInfo) -> Long = { 0L }

    /** Runs on the handler thread before the request is answered: a place to mutate content or inspect disk. */
    @Volatile var onRequest: (RequestInfo) -> Unit = {}

    val peakConcurrency: Int get() = peak

    init {
        Thread({
            while (!serverSocket.isClosed) {
                val sock = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                open += sock
                pool.execute { handle(sock) }
            }
        }, "test-server-accept").apply { isDaemon = true }.start()
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    fun add(path: String, content: Content): Resource = Resource(content).also { routes[path] = it }

    fun redirect(path: String, location: String, status: Int = 302) {
        routes[path] = Redirect(location, status)
    }

    /** Every request that was not the engine's 0-0 probe. */
    fun bodyRequests(): List<LogEntry> = log.filter { !it.info.isProbe }

    /** Log entries from [mark] on (`mark = log.size` taken earlier). */
    fun since(mark: Int): List<LogEntry> = log.drop(mark)

    override fun close() {
        runCatching { serverSocket.close() }
        open.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16_384) return null
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            val tokens = requestLine.split(' ')
            val method = tokens[0]
            val path = tokens.getOrElse(1) { "/" }.substringBefore('?')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            respond(sock, method, path, headers)
        } catch (_: IOException) {
            // The client went away. Not the server's problem.
        } finally {
            open -= sock
            runCatching { sock.close() }
        }
    }

    private fun parseRange(value: String?): Pair<Long, Long?>? {
        if (value == null || !value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=")
        val dash = spec.indexOf('-')
        if (dash <= 0) return null
        val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
        val endText = spec.substring(dash + 1).trim()
        val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return null
        return start to end
    }

    private fun respond(sock: Socket, method: String, path: String, headers: Map<String, String>) {
        val index = counter.incrementAndGet()
        val range = parseRange(headers["range"])
        val route = routes[path]
        var admitted: Boolean
        var active: Int
        synchronized(admissionLock) {
            val limit = connectionLimit
            admitted = limit <= 0 || serving < limit
            if (admitted) {
                serving++
                if (serving > peak) peak = serving
            }
            active = serving
        }
        val info = RequestInfo(index, path, method, headers, range?.first, range?.second, active)
        val entry = LogEntry(info)
        log += entry
        val out = sock.getOutputStream()
        try {
            onRequest(info)
            if (!admitted) {
                entry.status = 503
                simple(out, 503, "busy")
                return
            }
            val fault = fault(info)
            if (fault is Fault.Status) {
                entry.status = fault.code
                val extra = fault.retryAfterSeconds?.let { listOf("Retry-After" to it.toString()) } ?: emptyList()
                simple(out, fault.code, "fault", extra)
                return
            }
            when (route) {
                null -> {
                    entry.status = 404
                    simple(out, 404, "not found")
                }
                is Redirect -> {
                    entry.status = route.status
                    head(out, route.status, listOf("Location" to route.location, "Content-Length" to "0"))
                }
                is Resource -> serveResource(sock, out, route, info, entry, fault)
            }
        } catch (_: IOException) {
            // The client closed mid-response.
        } finally {
            if (admitted) synchronized(admissionLock) { serving-- }
        }
    }

    private fun serveResource(
        sock: Socket,
        out: OutputStream,
        res: Resource,
        info: RequestInfo,
        entry: LogEntry,
        fault: Fault?,
    ) {
        val c = res.content
        val total = c.size
        var range: Pair<Long, Long?>? =
            if (res.supportsRange && info.rangeStart != null) info.rangeStart to info.rangeEnd else null
        if (total == 0L && !res.emptyAs416) range = null
        val ifRange = info.headers["if-range"]
        if (range != null && ifRange != null && res.honorIfRange && ifRange != c.etag && ifRange != c.lastModified) range = null
        if (fault is Fault.IgnoreRange) range = null

        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to res.contentType
        if (res.supportsRange) headers += "Accept-Ranges" to "bytes"
        c.etag?.let { headers += "ETag" to it }
        c.lastModified?.let { headers += "Last-Modified" to it }
        res.contentEncoding?.let { headers += "Content-Encoding" to it }
        headers += res.extraHeaders.map { it.key to it.value }

        if (range != null && (range.first >= total)) {
            entry.status = 416
            headers += "Content-Range" to "bytes */$total"
            headers += "Content-Length" to "0"
            head(out, 416, headers)
            return
        }

        val status: Int
        val bodyStart: Long
        val length: Long
        if (range != null) {
            val end = minOf(range.second ?: (total - 1), total - 1)
            status = 206
            bodyStart = range.first
            length = end - range.first + 1
            headers += "Content-Range" to "bytes ${range.first}-$end/$total"
            entry.servedStart = range.first
            entry.servedEnd = end
        } else {
            status = 200
            bodyStart = 0
            length = total
            entry.servedStart = 0
            entry.servedEnd = total - 1
        }
        entry.status = status
        entry.declaredBytes = length
        when (res.lengthMode) {
            LengthMode.DECLARED -> headers += "Content-Length" to length.toString()
            LengthMode.TOO_LONG -> headers += "Content-Length" to (length + TOO_LONG_EXTRA).toString()
            LengthMode.MISSING -> Unit
        }
        head(out, status, headers)
        if (info.method == "HEAD") return

        val bps = throttle(info)
        val faultBytes = when (fault) {
            is Fault.DropAfter -> fault.bytes
            is Fault.ResetAfter -> fault.bytes
            is Fault.StallAfter -> fault.bytes
            else -> Long.MAX_VALUE
        }
        val source = c.source
        val chunk = ByteArray(CHUNK)
        val startedAt = System.nanoTime()
        var sent = 0L
        while (sent < length) {
            if (sent >= faultBytes) {
                out.flush()
                when (fault) {
                    is Fault.ResetAfter -> {
                        sock.setSoLinger(true, 0)
                        sock.close()
                    }
                    is Fault.StallAfter -> Thread.sleep(fault.millis)
                    else -> {
                        (fault as? Fault.DropAfter)?.holdUntil?.let(::hold)
                        sock.shutdownOutput()
                    }
                }
                return
            }
            val n = minOf(CHUNK.toLong(), length - sent, faultBytes - sent).toInt()
            source.fill(bodyStart + sent, chunk, 0, n)
            out.write(chunk, 0, n)
            sent += n
            entry.bytesSent = sent
            if (bps > 0) {
                val dueNanos = sent * 1_000_000_000L / bps
                val waitNanos = dueNanos - (System.nanoTime() - startedAt)
                if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
            }
        }
        out.flush()
    }

    /** Waits for [ready], or [HOLD_LIMIT_MILLIS], whichever comes first. A test that times out here fails on its own check. */
    private fun hold(ready: () -> Boolean) {
        val until = System.nanoTime() + HOLD_LIMIT_MILLIS * 1_000_000L
        while (!ready() && System.nanoTime() < until) Thread.sleep(HOLD_POLL_MILLIS)
    }

    private fun simple(out: OutputStream, status: Int, text: String, extra: List<Pair<String, String>> = emptyList()) {
        val body = text.toByteArray()
        head(out, status, listOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()) + extra)
        out.write(body)
        out.flush()
    }

    private fun head(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        302 -> "Found"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val HOLD_LIMIT_MILLIS = 10_000L
        const val HOLD_POLL_MILLIS = 5L
        const val TOO_LONG_EXTRA = 1000L
    }
}
