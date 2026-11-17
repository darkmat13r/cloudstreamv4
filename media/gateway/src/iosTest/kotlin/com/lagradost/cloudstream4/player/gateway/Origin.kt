@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.lagradost.cloudstream4.player.gateway

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A scriptable HTTP/1.1 origin on 127.0.0.1 for the gateway's tests (`BUG-114`). Each request is answered by
 * [handler]; every connection is counted ([accepted]) and every request kept ([requests]), so a test can show both what
 * reached upstream and that a refused one reached nothing.
 */
internal class Origin(private val handler: (Request) -> Reply) {
    class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** A reply: [extra] bytes are sent after [body], beyond what `Content-Length` says. */
    class Reply(val status: Int, val headers: List<Pair<String, String>> = emptyList(), val body: ByteArray = ByteArray(0), val extra: ByteArray = ByteArray(0), val length: Long? = body.size.toLong())

    private val listener = Loopback.listen()!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accepts = AtomicInt(0)
    private val running = AtomicInt(1)
    private val lock = NSLock()
    private val seen = mutableListOf<Request>()

    val port: Int get() = listener.port
    val accepted: Int get() = accepts.load()
    val requests: List<Request> get() = lock.lock().let { try { seen.toList() } finally { lock.unlock() } }

    fun url(path: String) = "http://127.0.0.1:$port$path"

    init {
        scope.launch {
            // Until stopped: a closed listener's accept() returns null at once, so `while (true)` would spin on one of
            // Dispatchers.IO's 64 threads for the rest of the run, and enough stopped origins starve the gateways.
            while (running.load() == 1) {
                val fd = listener.accept() ?: continue
                accepts.incrementAndFetch()
                scope.launch { serve(fd) }
            }
        }
    }

    private fun serve(fd: Int) {
        Loopback.prepare(fd, 10)
        val reader = Loopback.Reader(fd)
        try {
            while (true) {
                val request = readRequest(reader) ?: return
                lock.lock(); try { seen += request } finally { lock.unlock() }
                val reply = handler(request)
                val head = buildString {
                    append("HTTP/1.1 ${reply.status} X\r\n")
                    reply.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    reply.length?.let { append("Content-Length: $it\r\n") }
                    if (reply.length == null) append("Connection: close\r\n")
                    append("\r\n")
                }
                if (!Loopback.writeAll(fd, head.encodeToByteArray())) return
                if (request.method != "HEAD" && !Loopback.writeAll(fd, reply.body + reply.extra)) return
                if (reply.length == null || reply.extra.isNotEmpty()) return
            }
        } finally {
            Loopback.close(fd)
        }
    }

    private fun readRequest(reader: Loopback.Reader): Request? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val b = reader.read()
            if (b < 0) return null
            bytes += b.toByte()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == 13.toByte() && bytes[n - 3] == 10.toByte() && bytes[n - 2] == 13.toByte() && bytes[n - 1] == 10.toByte()) break
        }
        val lines = bytes.toByteArray().decodeToString().removeSuffix("\r\n\r\n").split("\r\n")
        val (method, path) = lines.first().split(' ').let { it[0] to it[1] }
        val headers = lines.drop(1).associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return Request(method, path, headers)
    }

    fun stop() {
        running.store(0)
        listener.close()
        scope.cancel()
    }

    companion object {
        /** A body whose every byte says where it is: byte i is i mod 251, so a wrong offset shows. */
        fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

        /** [pattern], made an MPEG-TS stream to the gateway's sniff: the sync byte (0x47) at every 188-byte packet. */
        fun ts(size: Int) = pattern(size).also { b -> for (i in b.indices step 188) b[i] = 0x47 }

        /** A media file that answers ranges as a real server does, with an ETag. */
        fun rangedFile(body: ByteArray, etag: String = "\"v1\""): (Request) -> Reply = { request ->
            val ifRange = request.header("If-Range")
            // RFC 9110 13.1.5: a weak or different validator in If-Range means the whole body, as a 200.
            val range = request.header("Range").takeIf { ifRange == null || (!ifRange.startsWith("W/") && ifRange == etag) }
            if (range == null) {
                Reply(200, listOf("ETag" to etag), body)
            } else {
                val spec = range.removePrefix("bytes=")
                val first = spec.substringBefore('-').toLong()
                val last = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
                if (first >= body.size) Reply(416, listOf("Content-Range" to "bytes */${body.size}"))
                else Reply(206, listOf("Content-Range" to "bytes $first-$last/${body.size}", "ETag" to etag), body.copyOfRange(first.toInt(), last.toInt() + 1))
            }
        }
    }
}
