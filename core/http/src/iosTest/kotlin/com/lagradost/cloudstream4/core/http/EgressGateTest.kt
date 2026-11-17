@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.core.http

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.in6_addr
import platform.darwin.inet_pton
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `BUG-111`: the Darwin session's gate connects only to public addresses.
 *
 * Every refusal is proved the way `BUG-105` taught: a [LocalServer] stands where the refused connection would land, and
 * it must have **accepted nothing**. An exception alone would not show that no connection was made. Each listener is
 * also shown to count (see [anAllowedAddressIsTunnelledAndCounted]), so a 0 is a real 0.
 */
class EgressGateTest {

    // ---- The gate, spoken to directly. The resolver seam stands in for DNS. ----

    /** Every kind of address that is not public, as a name resolving to it. Each is refused before any connection. */
    @Test
    fun aNameResolvingToANonPublicAddressIsRefusedWithoutConnecting() = withServers { v4, v6 ->
        val cases = listOf(
            "127.0.0.1" to v4, "0.0.0.0" to v4, "::ffff:127.0.0.1" to v4, "64:ff9b::7f00:1" to v4,
            "::1" to v6,
            // Not where a listener is, but the 403 (not a 502) shows no connection was tried.
            "10.0.0.1" to v4, "172.16.0.1" to v4, "192.168.1.1" to v4, "169.254.169.254" to v4, "100.64.0.1" to v4,
            "fe80::1" to v6, "fd00::1" to v6, "::ffff:192.168.1.1" to v4, "224.0.0.1" to v4,
        )
        for ((address, server) in cases) {
            val gate = EgressGate(resolve = { listOf(ip(address)) })
            try {
                assertEquals(403, status(gate, "CONNECT name.test:${server.port} HTTP/1.1\r\nHost: name.test\r\n\r\n"), address)
            } finally {
                gate.stop()
            }
        }
        assertEquals(0, v4.accepted, "a refused address was connected to (127.0.0.1)")
        assertEquals(0, v6.accepted, "a refused address was connected to (::1)")
    }

    /** The control for every 0 above: the same listener, allowed, is reached through the tunnel and counts it. */
    @Test
    fun anAllowedAddressIsTunnelledAndCounted() = withServers { v4, _ ->
        val gate = EgressGate(allow = { true }, resolve = { listOf(ip("127.0.0.1")) })
        try {
            val reply = exchange(gate, "CONNECT name.test:${v4.port} HTTP/1.1\r\n\r\n", then = "GET / HTTP/1.1\r\n\r\n")
            assertTrue(reply.startsWith("HTTP/1.1 200 Connection Established\r\n\r\n"), reply)
            assertTrue(reply.endsWith(OK_BODY), "the tunnel did not carry the server's answer: $reply")
            assertEquals(1, v4.accepted)
        } finally {
            gate.stop()
        }
    }

    /** One answer with an allowed and a refused address: only the allowed one is tried, never the refused as a fallback. */
    @Test
    fun aRefusedAddressIsNeverTheFallback() = withServers { v4, _ ->
        // 127.0.0.2 stands for an allowed address that does not answer; the listener is the refused 127.0.0.1.
        val gate = EgressGate(allow = { it.contentEquals(ip("127.0.0.2")) }, resolve = { listOf(ip("127.0.0.1"), ip("127.0.0.2")) })
        try {
            assertEquals(502, status(gate, "CONNECT name.test:${v4.port} HTTP/1.1\r\n\r\n"))
            assertEquals(0, v4.accepted)
        } finally {
            gate.stop()
        }
    }

    /** The name is looked up once per connection, so a second, different answer can never be the one connected to. */
    @Test
    fun aNameIsLookedUpOncePerConnection() = withServers { v4, _ ->
        var lookups = 0
        // The first answer is allowed (127.0.0.2, which does not answer); a second would be the listener.
        val gate = EgressGate(
            allow = { it.contentEquals(ip("127.0.0.2")) },
            resolve = { lookups++; if (lookups == 1) listOf(ip("127.0.0.2")) else listOf(ip("127.0.0.1")) },
        )
        try {
            assertEquals(502, status(gate, "CONNECT name.test:${v4.port} HTTP/1.1\r\n\r\n"))
            assertEquals(1, lookups)
            assertEquals(0, v4.accepted)
        } finally {
            gate.stop()
        }
    }

    /** Plain http (an absolute-form request to the proxy) is refused: nothing but a tunnel leaves the gate. */
    @Test
    fun aRequestThatIsNotAConnectIsRefused() = withServers { v4, _ ->
        val gate = EgressGate(allow = { true }, resolve = { listOf(ip("127.0.0.1")) })
        try {
            assertEquals(405, status(gate, "GET http://name.test:${v4.port}/ HTTP/1.1\r\nHost: name.test\r\n\r\n"))
            assertEquals(0, v4.accepted)
        } finally {
            gate.stop()
        }
    }

    /** The real lookup answers: a gate whose lookup found nothing would refuse every host and look secure doing it. */
    @Test
    fun theSystemLookupFindsLocalhost() {
        val found = EgressGate.systemResolve("localhost")
        assertTrue(found.any { it.contentEquals(ip("127.0.0.1")) || it.contentEquals(ip("::1")) }, "localhost resolved to ${found.size} addresses")
    }

    @Test
    fun theConnectLineIsReadStrictly() {
        val good = EgressGate.parseConnect("CONNECT example.com:443 HTTP/1.1")
        assertEquals("example.com", good?.host)
        assertEquals(443, good?.port)
        assertEquals("::1", EgressGate.parseConnect("CONNECT [::1]:8443 HTTP/1.1")?.host)
        listOf(
            "CONNECT example.com HTTP/1.1", "CONNECT example.com:0 HTTP/1.1", "CONNECT example.com:65536 HTTP/1.1",
            "CONNECT example.com:+443 HTTP/1.1", "CONNECT [fe80::1%en0]:443 HTTP/1.1", "CONNECT ::1:443 HTTP/1.1",
            "CONNECT exa mple.com:443 HTTP/1.1", "CONNECT exämple.com:443 HTTP/1.1", "CONNECT :443 HTTP/1.1",
            "connect example.com:443 HTTP/1.1", "CONNECT example.com:443 HTTP/2", "GET / HTTP/1.1",
        ).forEach { assertNull(EgressGate.parseConnect(it), it) }
    }

    // ---- The production wiring, end to end: NSURLSession, its proxy settings, the gate and the system's DNS. ----

    /**
     * An address written in the URL, in every spelling measured to go past the session's proxy (and two that do not),
     * is refused before the session sees it: the gate is never asked and the listener accepts nothing.
     */
    @Test
    fun anAddressInTheUrlIsRefusedBeforeTheSession() = withServers { v4, v6 ->
        val asked = mutableListOf<String>()
        val gate = EgressGate(resolve = { host -> asked += host; EgressGate.systemResolve(host) })
        val client = gatedFactory(gate).create()
        try {
            withTimeout(60_000) {
                for (scheme in listOf("http", "https")) {
                    listOf("127.0.0.1", "127.1", "2130706433", "0x7f000001", "0177.0.0.1", "0.0.0.0", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "10.0.0.1")
                        .map { "$scheme://$it:${v4.port}/" }
                        .plus(listOf("[::1]", "[0:0:0:0:0:0:0:1]", "[fe80::1]", "[fd00::1]").map { "$scheme://$it:${v6.port}/" })
                        .forEach { url ->
                            val error = assertFailsWith<Throwable>(url) { client.get(url) }
                            assertTrue(generateSequence(error) { it.cause }.any { it is RefusedDestination }, "$url: $error")
                        }
                }
            }
            assertEquals(emptyList(), asked, "an address reached the gate instead of being refused first")
            assertEquals(0, v4.accepted, "the session connected to 127.0.0.1")
            assertEquals(0, v6.accepted, "the session connected to ::1")
        } finally {
            client.close()
            gate.stop()
        }
    }

    /** A name, however local, goes to the gate and is judged by what it resolves to: the listener accepts nothing. */
    @Test
    fun aLocalNameIsRefusedAtTheGate() = withServers { v4, v6 ->
        val asked = mutableListOf<String>()
        val gate = EgressGate(resolve = { host -> asked += host; EgressGate.systemResolve(host) })
        val client = gatedFactory(gate).create()
        try {
            withTimeout(60_000) {
                for (scheme in listOf("http", "https")) {
                    for (server in listOf(v4, v6)) {
                        listOf("localhost", "LOCALHOST", "localhost.").forEach { host ->
                            val url = "$scheme://$host:${server.port}/"
                            // https fails in the session; plain http gets the gate's own 405, not the listener's 200.
                            val outcome = runCatching { client.get(url).status.value }
                            assertTrue(outcome.isFailure || outcome.getOrNull() == 405, "$url: $outcome")
                        }
                    }
                }
            }
            // https asks the gate for each; http is refused as not a CONNECT, so the gate is not asked at all.
            assertEquals(List(2) { listOf("localhost", "localhost", "localhost.") }.flatten(), asked)
            assertEquals(0, v4.accepted, "the session connected to 127.0.0.1")
            assertEquals(0, v6.accepted, "the session connected to ::1")
        } finally {
            client.close()
            gate.stop()
        }
    }

    /** With the gate gone, the session's requests fail; they do not go direct. */
    @Test
    fun aStoppedGateFailsClosed() = withServers { v4, v6 ->
        val gate = EgressGate(allow = { true })
        val client = gatedFactory(gate).create()
        gate.stop()
        try {
            withTimeout(60_000) {
                for (scheme in listOf("http", "https")) for (server in listOf(v4, v6)) {
                    val url = "$scheme://localhost:${server.port}/"
                    val outcome = runCatching { client.get(url).status.value }
                    assertTrue(outcome.isFailure, "$url: $outcome")
                }
            }
            assertEquals(0, v4.accepted, "the session went around a stopped gate to 127.0.0.1")
            assertEquals(0, v6.accepted, "the session went around a stopped gate to ::1")
        } finally {
            client.close()
        }
    }

    /**
     * A redirect's next hop is refused like a first request. The Darwin engine follows redirects itself, sending each hop
     * as a new request, and the address check runs on every one. The origin here is reached by name through a session
     * with that check and no gate (the gate would refuse a local origin too, and the gated session cannot reach the
     * network under the Gradle test's simulator, which has no DNS); its 302 points at an address.
     */
    @Test
    fun aRedirectToAnAddressIsRefusedOnTheHop() = runBlocking {
        val target = LocalServer(OK)
        val origin = LocalServer("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${target.port}/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val client = DarwinClientFactory(Darwin.create { configureSession { bounded() }; configureRequest { refuseAddressLiterals() } }).create()
        try {
            withTimeout(60_000) {
                val error = assertFailsWith<Throwable> { client.get("http://localhost:${origin.port}/") }
                assertTrue(generateSequence(error) { it.cause }.any { it is RefusedDestination }, "$error")
            }
            assertEquals(1, origin.accepted, "the origin was not reached, so no redirect was tested")
            assertEquals(0, target.accepted, "the redirect reached 127.0.0.1")
        } finally {
            client.close()
            origin.stop()
            target.stop()
        }
    }

    @Test
    fun anAddressIsToldFromANameInEverySpelling() {
        listOf(
            "127.0.0.1", "127.1", "2130706433", "0x7f000001", "0177.0.0.1", "0.0.0.0", "8.8.8.8", "::1", "::ffff:127.0.0.1",
            "fe80::1%en0", "1.2.3.0x4", "1.2.3.077", "example.123", "example.123.", "", "[::1]",
        ).forEach { assertTrue(isAddressLiteral(it), it) }
        listOf("example.com", "localhost", "localhost.", "image.tmdb.org", "a1.example", "0x.example", "123.example.com")
            .forEach { assertFalse(isAddressLiteral(it), it) }
    }

    // ---- Helpers. ----

    private fun withServers(block: suspend (v4: LocalServer, v6: LocalServer) -> Unit) = runBlocking {
        val v4 = LocalServer(OK)
        val v6 = LocalServer(OK, ipv6 = true)
        try {
            block(v4, v6)
        } finally {
            v4.stop()
            v6.stop()
        }
    }

    /** An address in text as its bytes, through the system's own parser. */
    private fun ip(text: String): ByteArray = memScoped {
        val v6 = alloc<in6_addr>()
        if (':' in text) {
            check(inet_pton(AF_INET6, text, v6.ptr) == 1) { "not an address: $text" }
            ByteArray(16) { v6.ptr.reinterpret<UByteVar>()[it].toByte() }
        } else {
            check(inet_pton(AF_INET, text, v6.ptr) == 1) { "not an address: $text" }
            ByteArray(4) { v6.ptr.reinterpret<UByteVar>()[it].toByte() }
        }
    }

    /** The status code the gate answers [request] with. */
    private fun status(gate: EgressGate, request: String): Int {
        val reply = exchange(gate, request)
        return assertNotNull(reply.removePrefix("HTTP/1.1 ").take(3).toIntOrNull(), "no status line: $reply")
    }

    /** Sends [request] to the gate, then [then] if given, and reads until the gate closes or 15 s pass. */
    private fun exchange(gate: EgressGate, request: String, then: String? = null): String = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        try {
            val sa = alloc<sockaddr_in>()
            sa.sin_len = sizeOf<sockaddr_in>().convert()
            sa.sin_family = AF_INET.convert()
            sa.sin_port = (((gate.port and 0xFF) shl 8) or ((gate.port shr 8) and 0xFF)).toUShort()
            sa.sin_addr.s_addr = 0x0100007Fu
            check(connect(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "the gate is not listening" }
            val tv = alloc<timeval>().apply { tv_sec = 15; tv_usec = 0 }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
            send(fd, request)
            val out = StringBuilder()
            val buffer = ByteArray(4096)
            var sentThen = then == null
            while (true) {
                val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
                if (n <= 0) break
                out.append(buffer.copyOf(n.toInt()).decodeToString())
                if (!sentThen && out.contains("\r\n\r\n")) {
                    send(fd, then!!)
                    sentThen = true
                }
            }
            out.toString()
        } finally {
            close(fd)
        }
    }

    private fun send(fd: Int, text: String) {
        val bytes = text.encodeToByteArray()
        bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
    }

    private companion object {
        const val OK_BODY = "reached"
        const val OK = "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\n$OK_BODY"
    }
}
