package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream4.media.download.DownloadStorage
import com.lagradost.cloudstream4.media.download.FileKitStorage
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A deterministic byte source: byte `i` depends only on `(seed, i)`, so a finished file is compared
 * with it byte for byte without keeping it in memory. Copied from `:media:download`'s jvmTest.
 */
class SeededSource(private val seed: Long, val size: Long) {
    fun fill(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
        var pos = offset
        var cachedBlock = -1L
        var word = 0L
        for (i in bufferOffset until bufferOffset + length) {
            val block = pos ushr 3
            if (block != cachedBlock) {
                word = mix(block)
                cachedBlock = block
            }
            buffer[i] = (word ushr ((pos and 7L).toInt() shl 3)).toByte()
            pos++
        }
    }

    /** `null` when [input] holds exactly this source, otherwise where it first differs. */
    fun describeMismatch(input: InputStream): String? {
        val actual = ByteArray(1 shl 20)
        val expected = ByteArray(1 shl 20)
        var pos = 0L
        input.use {
            while (true) {
                val read = it.read(actual)
                if (read < 0) break
                if (pos + read > size) return "longer than $size"
                fill(pos, expected, 0, read)
                for (i in 0 until read) if (actual[i] != expected[i]) return "first differing byte at ${pos + i}"
                pos += read
            }
        }
        return if (pos != size) "length $pos != $size" else null
    }

    private fun mix(block: Long): Long {
        var z = seed + block * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}

/** What the server does to one request instead of answering it in full. */
sealed interface Fault {
    /** Send [bytes] of the body, then close cleanly: the client sees the stream end early. */
    data class DropAfter(val bytes: Long) : Fault
}

/** One request as the server saw it. [rangeStart]/[rangeEnd] are inclusive, `null` with no `Range`. */
class Served(val index: Int, val method: String, val rangeStart: Long?, val rangeEnd: Long?) {
    @Volatile var status = 0
    @Volatile var bytesSent = 0L

    /** The engine's probe is `Range: bytes=0-0`. */
    val isProbe: Boolean get() = rangeStart == 0L && rangeEnd == 0L
    override fun toString() = "#$index $method $rangeStart-$rangeEnd -> $status sent=$bytesSent"
}

/**
 * One file on 127.0.0.1 over a raw socket, one request per connection, with an ETag so the engine can
 * resume. A trimmed copy of `:media:download`'s jvmTest `TestServer`: the faults it needs are not
 * expressible through a higher-level server.
 */
class EngineTestServer(val source: SeededSource, private val etag: String = "\"v1\"") : AutoCloseable {
    private val socket = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "engine-test-server").apply { isDaemon = true } }
    private val counter = AtomicInteger()
    val log: CopyOnWriteArrayList<Served> = CopyOnWriteArrayList()
    val url: String = "http://127.0.0.1:${socket.localPort}/file.mp4"

    /** Decided per request. */
    @Volatile var fault: (Served) -> Fault? = { null }

    /** Body bytes per second per connection; 0 is unthrottled. */
    @Volatile var bytesPerSecond: Long = 0L

    /** When set, every request is answered with this status and no body, the way an expired link is. */
    @Volatile var refuseWith: Int? = null

    /** Runs on the server's thread before each request is answered, so a test can hold the answer back. */
    @Volatile var beforeAnswer: () -> Unit = {}

    init {
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                open += client
                pool.execute { handle(client) }
            }
        }, "engine-test-server-accept").apply { isDaemon = true }.start()
    }

    /** Requests that carried a body range, not the probe. */
    fun bodyRequests(): List<Served> = log.filter { !it.isProbe && it.method == "GET" }

    /** Closes every connection in flight; the server keeps accepting new ones. */
    fun cutOpenConnections() {
        open.forEach { runCatching { it.close() } }
    }

    override fun close() {
        runCatching { socket.close() }
        open.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            respond(client, requestLine.substringBefore(' '), headers)
        } catch (_: IOException) {
            // The client hung up.
        } catch (_: InterruptedException) {
            // close() while a throttled body was sleeping: ending the response is all that is left to do.
        } finally {
            open -= client
            runCatching { client.close() }
        }
    }

    private fun respond(client: Socket, method: String, headers: Map<String, String>) {
        val total = source.size
        val rangeHeader = headers["range"]?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")
        val requestedStart = rangeHeader?.substringBefore('-')?.trim()?.toLongOrNull()
        val requestedEnd = rangeHeader?.substringAfter('-')?.trim()?.toLongOrNull()
        beforeAnswer()
        val served = Served(counter.incrementAndGet(), method, requestedStart, requestedEnd)
        log += served
        val out = client.getOutputStream()
        refuseWith?.let { status ->
            served.status = status
            out.write("HTTP/1.1 $status Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }
        val ifRange = headers["if-range"]
        val ranged = requestedStart != null && (ifRange == null || ifRange == etag)

        val start = if (ranged) requestedStart!! else 0L
        val end = if (ranged) minOf(requestedEnd ?: (total - 1), total - 1) else total - 1
        val length = end - start + 1
        served.status = if (ranged) 206 else 200
        val head = StringBuilder("HTTP/1.1 ${served.status} ${if (ranged) "Partial Content" else "OK"}\r\n")
            .append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n")
            .append("ETag: $etag\r\nContent-Length: $length\r\n")
        if (ranged) head.append("Content-Range: bytes $start-$end/$total\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        val limit = (fault(served) as? Fault.DropAfter)?.bytes ?: Long.MAX_VALUE
        serveBody(client, out, served, start, length, limit)
    }

    private fun serveBody(client: Socket, out: OutputStream, served: Served, start: Long, length: Long, limit: Long) {
        val chunk = ByteArray(16 * 1024)
        val startedAt = System.nanoTime()
        var sent = 0L
        while (sent < length) {
            if (sent >= limit) {
                out.flush()
                client.shutdownOutput()
                return
            }
            val n = minOf(chunk.size.toLong(), length - sent, limit - sent).toInt()
            source.fill(start + sent, chunk, 0, n)
            out.write(chunk, 0, n)
            sent += n
            served.bytesSent = sent
            val bps = bytesPerSecond
            if (bps > 0) {
                val waitNanos = sent * 1_000_000_000L / bps - (System.nanoTime() - startedAt)
                if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
            }
        }
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
            if (line.length > 16_384) return null
        }
    }
}

/**
 * A [DownloadStorage] whose disk fills up after [limitBytes] written through any sink, while
 * [availableBytes] keeps claiming plenty, so the engine's pre-flight check passes and the write fails
 * the way a full disk does. Copied, trimmed, from `:media:download`'s jvmTest `QuotaStorage`.
 */
class QuotaStorage(
    private val limitBytes: Long,
    private val inner: DownloadStorage = FileKitStorage(),
) : DownloadStorage by inner {
    val written = AtomicLong()

    override fun sink(file: PlatformFile, append: Boolean): RawSink {
        val delegate = inner.sink(file, append)
        return object : RawSink {
            override fun write(source: Buffer, byteCount: Long) {
                val room = limitBytes - written.get()
                if (byteCount <= room) {
                    delegate.write(source, byteCount)
                    written.addAndGet(byteCount)
                    return
                }
                if (room > 0) {
                    delegate.write(source, room)
                    written.addAndGet(room)
                }
                throw IOException("No space left on device")
            }

            override fun flush() = delegate.flush()
            override fun close() = delegate.close()
        }
    }

    override suspend fun availableBytes(directory: PlatformFile): Long = 64L * 1024 * 1024 * 1024
}

/**
 * A [DownloadStorage] that delegates everything to [FileKitStorage] and records every [size] answer,
 * in call order. The engine's rule 1 reads a part file's size before each attempt and requests from
 * `part.start + size`, so this is the offset the next request for that part must carry.
 *
 * [stamp] is read at each call; given the server's request count it orders a size read against the
 * requests the server has already seen.
 */
class SizeRecordingStorage(
    private val stamp: () -> Int,
    private val inner: DownloadStorage = FileKitStorage(),
) : DownloadStorage by inner {
    /** One [size] call: the file's name, what it returned (`null` when missing), and [stamp] at the time. */
    class SizeRead(val name: String, val size: Long?, val stamp: Int) {
        override fun toString() = "$name=$size@$stamp"
    }

    val reads: CopyOnWriteArrayList<SizeRead> = CopyOnWriteArrayList()

    override fun size(file: PlatformFile): Long? {
        val stampBefore = stamp()
        return inner.size(file).also { reads += SizeRead(file.name, it, stampBefore) }
    }

    fun readsOf(name: String): List<SizeRead> = reads.filter { it.name == name }
}
