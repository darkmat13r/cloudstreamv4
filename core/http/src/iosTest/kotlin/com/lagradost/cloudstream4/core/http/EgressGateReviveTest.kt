@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.core.http

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.dup2
import platform.posix.errno
import platform.posix.S_IFIFO
import platform.posix.S_IFMT
import platform.posix.fstat
import platform.posix.stat
import platform.posix.poll
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socketpair
import platform.posix.timeval
import platform.posix.write
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `BUG-128`: the gate heals itself after iOS takes its listening port back.
 *
 * On the owner's iPad, after 18 minutes suspended, the listener's descriptor stayed valid, `poll` raised no event on
 * it, and a connection to its port was refused, until the app was relaunched. Each test here does the same to a gate:
 * [kill] puts an idle socket of another kind on the listener's descriptor number, which closes the real listener (the
 * port refuses) and leaves a valid descriptor that never raises an event. The gate is then told apart only by what it
 * does next.
 */
class EgressGateReviveTest {

    /** The request path: the first request after the death reaches its destination, through the same port, once. */
    @Test
    fun aRequestHealsAGateWhosePortIOSTookBack() = runBlocking {
        val server = LocalServer(OK)
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        val client = gatedFactory(gate).create()
        try {
            val port = gate.port
            kill(gate)
            assertFalse(answers(port), "the injected death did not take the port")
            withTimeout(60_000) { runCatching { send(client, server.port) } }
            // At least once: NSURLSession opens a second connection itself after the TLS fails (measured), so the exact
            // count is Apple's. What matters is that it got through at all; with the gate left dead it reaches nothing.
            assertTrue(server.accepted >= 1, "the request did not reach its destination through the gate")
            assertEquals(1, gate.revivals)
            assertEquals(port, gate.port)
            assertTrue(answers(port), "the gate does not answer on its port")
        } finally {
            client.close()
            gate.stop()
            server.stop()
        }
    }

    /** The backstop: with no request at all, the accept loop finds the dead port and listens again on it. */
    @Test
    fun theAcceptLoopHealsItWithNoRequest() = runBlocking {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = 500)
        try {
            val port = gate.port
            kill(gate)
            assertFalse(answers(port))
            var waited = 0
            while (gate.revivals == 0 && waited < 10_000) {
                poll(null, 0u, 50)
                waited += 50
            }
            assertEquals(1, gate.revivals, "not healed after $waited ms")
            assertTrue(answers(port))
        } finally {
            gate.stop()
        }
    }

    /**
     * Healing replaces the socket, not the rules: a name resolving to a private or loopback address is refused, and so
     * is anything but `CONNECT`. The loopback one is refused with a server listening there, which accepts nothing.
     */
    @Test
    fun theSameRulesHoldAfterHealing() = runBlocking {
        val server = LocalServer(OK)
        val names = mapOf("private.test" to byteArrayOf(10, 0, 0, 1), "loopback.test" to LOOPBACK)
        val gate = EgressGate(resolve = { names[it]?.let(::listOf).orEmpty() }, probeIntervalMs = NEVER)
        try {
            kill(gate)
            assertTrue(gate.revive(), "not healed")
            assertEquals(403, status(gate.port, "CONNECT loopback.test:${server.port} HTTP/1.1\r\n\r\n"))
            assertEquals(403, status(gate.port, "CONNECT private.test:443 HTTP/1.1\r\n\r\n"))
            assertEquals(405, status(gate.port, "GET http://loopback.test:${server.port}/ HTTP/1.1\r\n\r\n"))
            assertEquals(0, server.accepted, "a refused destination was connected to")
        } finally {
            gate.stop()
            server.stop()
        }
        // The control for that 0: the same death and healing, with the address allowed, does reach the server.
        val reached = LocalServer(OK)
        val open = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        try {
            kill(open)
            assertTrue(open.revive())
            assertEquals(200, status(open.port, "CONNECT loopback.test:${reached.port} HTTP/1.1\r\n\r\n"))
            assertEquals(1, reached.accepted)
        } finally {
            open.stop()
            reached.stop()
        }
    }

    /** A gate that answers is left as it is: no new listener, and the caller is told not to retry. */
    @Test
    fun aGateThatAnswersIsNotRevived() {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        try {
            val listener = gate.listenerFdForTest
            assertFalse(gate.revive())
            assertEquals(0, gate.revivals)
            assertEquals(listener, gate.listenerFdForTest)
        } finally {
            gate.stop()
        }
    }

    /** Many requests failing at once heal the gate once: one listens again, the rest wait for it. */
    @Test
    fun manyRevivesAtOnceListenOnce() = runBlocking {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        try {
            kill(gate)
            val results = (1..8).map { async(Dispatchers.IO) { gate.revive() } }.awaitAll()
            assertEquals(1, gate.revivals, "results $results")
            assertTrue(results.any { it }, "nobody saw it healed: $results")
            assertTrue(answers(gate.port))
        } finally {
            gate.stop()
        }
    }

    /** A stopped gate stays closed: reviving it listens nowhere. */
    @Test
    fun aStoppedGateIsNotRevived() {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        kill(gate)
        gate.stop()
        assertFalse(gate.revive())
        assertFalse(answers(gate.port))
        assertEquals(0, gate.revivals)
    }

    /**
     * The wake pipe outlives the loop (review S1): a [EgressGate.revive] that passed its check as the gate stopped may still
     * write to it, and a closed number could by then be another socket's. So both ends are still this gate's pipe after
     * [EgressGate.stop].
     */
    @Test
    fun theWakePipeStaysOpenAfterStop() {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        val pipe = gate.wakeFdsForTest
        gate.stop()
        pipe.forEach { fd -> assertTrue(isPipe(fd), "wake descriptor $fd is no longer the gate's pipe") }
    }

    /** The app's foreground call heals the shared gate without waiting for a request. */
    @Test
    fun theForegroundCallHealsTheSharedGate() {
        val gate = EgressGate.shared
        val before = gate.revivals
        kill(gate)
        assertFalse(answers(gate.port))
        reviveEgress()
        var waited = 0
        while (gate.revivals == before && waited < 5_000) {
            poll(null, 0u, 20)
            waited += 20
        }
        assertEquals(before + 1, gate.revivals, "not healed after $waited ms")
        assertTrue(answers(gate.port))
    }

    // ---- Helpers. ----

    /** A POST with a text body, the kind `BoundedHttp` sends, to a server that answers TLS with plain text, so it fails. */
    private suspend fun send(client: io.ktor.client.HttpClient, port: Int) =
        client.post("https://localhost:$port/") { setBody("body") }

    private val held = mutableListOf<Int>()

    /** Each test's other ends of [kill]'s socket pairs. */
    @AfterTest
    fun closeHeld() {
        held.forEach { close(it) }
        held.clear()
    }

    private fun isPipe(fd: Int): Boolean = memScoped {
        val st = alloc<stat>()
        fstat(fd, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFIFO
    }

    /**
     * What iOS did to the listener: an idle, connected socket of another kind takes its descriptor number. `dup2` closes
     * the listening socket first, so the port refuses; the descriptor stays valid and `poll` never reports on it.
     */
    private fun kill(gate: EgressGate) = memScoped {
        val pair = allocArray<IntVar>(2)
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0) { "socketpair failed, errno $errno" }
        val listener = gate.listenerFdForTest
        val result = dup2(pair[0], listener)
        check(result == listener) { "dup2(${pair[0]}, $listener) gave $result, errno $errno" }
        close(pair[0])
        held += pair[1] // the other end stays open, so the socket on the listener's number never hangs up
    }

    /** Whether a connection to 127.0.0.1:[port] is accepted. */
    private fun answers(port: Int): Boolean = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        try {
            connect(fd, address(port).ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0
        } finally {
            close(fd)
        }
    }

    /** The status code the gate on [port] answers [request] with; 0 when it does not answer. */
    private fun status(port: Int, request: String): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        try {
            if (connect(fd, address(port).ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0) return@memScoped 0
            val tv = alloc<timeval>().apply { tv_sec = 15; tv_usec = 0 }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
            val bytes = request.encodeToByteArray()
            bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
            val buffer = ByteArray(64)
            val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
            if (n <= 0) return@memScoped 0
            buffer.copyOf(n.toInt()).decodeToString().removePrefix("HTTP/1.1 ").take(3).toIntOrNull() ?: 0
        } finally {
            close(fd)
        }
    }

    private fun kotlinx.cinterop.MemScope.address(port: Int): sockaddr_in = alloc<sockaddr_in>().apply {
        sin_len = sizeOf<sockaddr_in>().convert()
        sin_family = AF_INET.convert()
        sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
        sin_addr.s_addr = 0x0100007Fu
    }

    private companion object {
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)
        const val NEVER = Int.MAX_VALUE
        const val OK = "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nreached"
    }
}
