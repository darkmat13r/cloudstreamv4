package com.lagradost.cloudstream4.extensions.repositories

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * A plain-http server on loopback for the repository tests: every path answers as [routes] says, and every request is
 * counted, so a test can tell "refused before asking" from "asked and refused". No real repository is ever reached.
 */
internal class LocalRepositoryServer : AutoCloseable {
    sealed interface Reply {
        data class Body(val bytes: ByteArray, val status: Int = 200, val gzip: Boolean = false) : Reply

        data class Redirect(val location: String) : Reply

        /** [total] bytes of zeros, with or without a `Content-Length`, until the client stops reading. */
        data class Stream(val total: Long, val declared: Boolean) : Reply

        /** Headers, then nothing, for [millis]. */
        data class Stall(val millis: Long) : Reply
    }

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val routes = ConcurrentHashMap<String, Reply>()
    val requests = AtomicInteger()
    val streamed = java.util.concurrent.atomic.AtomicLong()
    val port: Int get() = socket.localPort

    fun url(path: String) = "http://127.0.0.1:$port$path"

    init {
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val client = socket.accept()
                    thread(isDaemon = true) { client.use { runCatching { answer(it) } } }
                }
            }
        }
    }

    private fun answer(client: java.net.Socket) {
        val reader = client.getInputStream().bufferedReader()
        val line = reader.readLine() ?: return
        while (!reader.readLine().isNullOrEmpty()) Unit
        requests.incrementAndGet()
        val path = line.split(' ').getOrNull(1).orEmpty()
        val out = client.getOutputStream()
        when (val reply = routes[path] ?: Reply.Body("missing".toByteArray(), status = 404)) {
            is Reply.Body -> {
                val bytes = if (reply.gzip) gzip(reply.bytes) else reply.bytes
                val encoding = if (reply.gzip) "Content-Encoding: gzip\r\n" else ""
                head(out, reply.status, "Content-Length: ${bytes.size}\r\n$encoding")
                out.write(bytes)
            }
            is Reply.Redirect -> head(out, 302, "Location: ${reply.location}\r\nContent-Length: 0\r\n")
            is Reply.Stream -> {
                head(out, 200, if (reply.declared) "Content-Length: ${reply.total}\r\n" else "Transfer-Encoding: chunked\r\n")
                val chunk = ByteArray(CHUNK)
                while (streamed.get() < reply.total) {
                    if (reply.declared) out.write(chunk) else {
                        out.write("${Integer.toHexString(CHUNK)}\r\n".toByteArray()); out.write(chunk); out.write("\r\n".toByteArray())
                    }
                    streamed.addAndGet(CHUNK.toLong())
                }
                if (!reply.declared) out.write("0\r\n\r\n".toByteArray())
            }
            is Reply.Stall -> {
                head(out, 200, "Content-Length: 10\r\n")
                out.flush()
                Thread.sleep(reply.millis)
            }
        }
        out.flush()
    }

    private fun head(out: OutputStream, status: Int, headers: String) {
        out.write("HTTP/1.1 $status X\r\n${headers}Connection: close\r\n\r\n".toByteArray())
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }.toByteArray()

    override fun close() = socket.close()

    private companion object {
        const val CHUNK = 8 * 1024
    }
}
