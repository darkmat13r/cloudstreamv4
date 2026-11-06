package com.lagradost.cloudstream4.downloads

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * A loopback server for the JDK's `HttpServer`, with `Range` and an `ETag`, so the engine resumes against it.
 *
 * Files are served at `/<name>`. [chunkDelayMillis] slows every body down to keep a download running for as long
 * as a test needs; [ranges] records every `Range` header it was sent, per file.
 */
internal class RangeServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val files = ConcurrentHashMap<String, ByteArray>()

    /** Milliseconds slept after each [CHUNK] bytes. 0 is as fast as loopback goes. */
    val chunkDelayMillis = AtomicLong(0L)

    /** Every `Range` header received, by file name, in order. The probe's is `bytes=0-0`. */
    val ranges = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    init {
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            try {
                serve(exchange)
            } catch (_: IOException) {
                // The client went away: a pause, a cancel or the end of a test.
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun add(name: String, size: Int, seed: Int = name.hashCode()): ByteArray =
        Random(seed).nextBytes(size).also { files[name] = it }

    fun url(name: String) = "http://127.0.0.1:${server.address.port}/$name"

    /** Ranged requests that did not start at 0: what a resume sends. */
    fun resumedFrom(name: String): List<Long> = ranges[name].orEmpty()
        .map { it.removePrefix("bytes=").substringBefore('-').toLong() }
        .filter { it > 0L }

    private fun serve(exchange: HttpExchange) {
        val name = exchange.requestURI.path.removePrefix("/")
        val body = files[name]
        if (body == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        exchange.responseHeaders.add("ETag", "\"${name.hashCode()}\"")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        val header = exchange.requestHeaders.getFirst("Range")
        if (header != null) {
            ranges.getOrPut(name) { CopyOnWriteArrayList() }.add(header)
            val (a, b) = header.removePrefix("bytes=").split('-')
            val start = a.toInt()
            val end = if (b.isEmpty()) body.size - 1 else minOf(b.toInt(), body.size - 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
            exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            write(exchange, body, start, end + 1)
        } else {
            exchange.sendResponseHeaders(200, body.size.toLong())
            write(exchange, body, 0, body.size)
        }
    }

    private fun write(exchange: HttpExchange, body: ByteArray, from: Int, to: Int) {
        exchange.responseBody.use { out ->
            var at = from
            while (at < to) {
                val n = minOf(CHUNK, to - at)
                out.write(body, at, n)
                out.flush()
                at += n
                val pause = chunkDelayMillis.get()
                if (pause > 0) Thread.sleep(pause)
            }
        }
    }

    override fun close() = server.stop(0)

    companion object {
        const val CHUNK = 16 * 1024
    }
}
