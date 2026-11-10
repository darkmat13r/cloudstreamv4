package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboundGateTest {
    private fun Socket.through(gate: OutboundGate, name: String, port: Int) =
        connect(InetSocketAddress.createUnresolved(name, port), TIMEOUT_MILLIS)

    private fun proxied(gate: OutboundGate) =
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), gate.port)))

    @Test
    fun `a private address and a name that resolves to one are refused, and both are recorded`() {
        OutboundGate().use { gate ->
            listOf("192.168.0.1", "10.0.0.1", "169.254.169.254", "127.0.0.1", "localhost").forEach { host ->
                assertFailsWith<SocketException>(host) { proxied(gate).use { it.through(gate, host, 80) } }
            }
            assertTrue("localhost" in gate.contacted)
            assertTrue("169.254.169.254" in gate.contacted)
        }
    }

    @Test
    fun `an allowed address carries bytes both ways, and a close is passed on`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { echo ->
            thread(isDaemon = true) {
                echo.accept().use { it.getInputStream().copyTo(it.getOutputStream()) }
            }
            OutboundGate(allowed = { true }).use { gate ->
                proxied(gate).use { socket ->
                    socket.through(gate, "127.0.0.1", echo.localPort)
                    socket.getOutputStream().write("through".toByteArray())
                    socket.shutdownOutput()
                    assertEquals("through", socket.getInputStream().readBytes().decodeToString())
                }
            }
        }
    }

    @Test
    fun `an address checked is the address connected to, whatever the name says`() {
        val asked = mutableListOf<String>()
        OutboundGate(allowed = { address -> asked += address.hostAddress; false }).use { gate ->
            assertFailsWith<SocketException> { proxied(gate).use { it.through(gate, "localhost", 80) } }
        }
        assertTrue(asked.isNotEmpty() && asked.all { it == "127.0.0.1" || it.startsWith("0:0:0:0:0:0:0:1") }, "$asked")
    }

    @Test
    fun `garbage ends that connection, and the next one still works`() {
        OutboundGate().use { gate ->
            Socket(InetAddress.getLoopbackAddress(), gate.port).use {
                it.getOutputStream().write(ByteArray(64) { -1 })
                it.soTimeout = TIMEOUT_MILLIS
                // The gate only hangs up, which the OS may report either way.
                assertEquals(-1, runCatching { it.getInputStream().read() }.getOrDefault(-1))
            }
            assertFailsWith<SocketException> { proxied(gate).use { it.through(gate, "10.0.0.1", 80) } }
        }
    }

    @Test
    fun `addresses that only look public are refused, and real ones are not`() {
        listOf("240.0.0.1", "198.18.0.1", "192.0.0.1", "100.64.0.1", "64:ff9b::c0a8:1", "2002:c0a8:1::", "fd00::1", "fe80::1")
            .forEach { assertEquals(false, SandboxPolicy.isPublicAddress(InetAddress.getByName(it)), it) }
        listOf("1.1.1.1", "198.20.0.1", "2606:4700::1111")
            .forEach { assertEquals(true, SandboxPolicy.isPublicAddress(InetAddress.getByName(it)), it) }
    }

    @Test
    fun `a tunnel nothing crosses is closed once it has been idle`() {
        // BUG-94 (A-7): it was soTimeout 0 both ways, so a silent tunnel held two threads for the host's life.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val held = java.util.concurrent.CountDownLatch(1)
            thread(isDaemon = true) { silent.accept().use { held.await() } }
            OutboundGate(allowed = { true }, idleTimeoutMillis = IDLE_MILLIS).use { gate ->
                proxied(gate).use { socket ->
                    socket.through(gate, "127.0.0.1", silent.localPort)
                    socket.soTimeout = TIMEOUT_MILLIS
                    val started = System.nanoTime()
                    assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
                    val waited = (System.nanoTime() - started) / 1_000_000
                    assertTrue(waited >= IDLE_MILLIS - SLACK_MILLIS, "closed after $waited ms, before it was idle")
                    assertTrue(waited < TIMEOUT_MILLIS, "not closed: the read timed out instead")
                }
            }
            held.countDown()
        }
    }

    @Test
    fun `a tunnel whose bytes go one way only is not idle, however long the other way is silent`() {
        // A download: the plugin sends nothing for far longer than the idle timeout while its bytes come back.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { source ->
            thread(isDaemon = true) {
                source.accept().use { peer ->
                    repeat(TRICKLE_BYTES) {
                        peer.getOutputStream().apply { write(it); flush() }
                        Thread.sleep(IDLE_MILLIS / 3)
                    }
                    // Then the plugin's turn: its byte must still get through, and is sent back.
                    peer.getOutputStream().apply { write(peer.getInputStream().read()); flush() }
                }
            }
            OutboundGate(allowed = { true }, idleTimeoutMillis = IDLE_MILLIS).use { gate ->
                proxied(gate).use { socket ->
                    socket.through(gate, "127.0.0.1", source.localPort)
                    socket.soTimeout = TIMEOUT_MILLIS
                    // About 3.3 idle timeouts long, and every byte arrives.
                    val input = socket.getInputStream()
                    assertEquals(TRICKLE_BYTES, input.readNBytes(TRICKLE_BYTES).size)
                    // A clock per direction would have shut the plugin's side by now, and this would never arrive.
                    socket.getOutputStream().apply { write(REPLY_BYTE); flush() }
                    assertEquals(REPLY_BYTE, input.read())
                }
            }
        }
    }

    @Test
    fun `an IPv4-compatible IPv6 address is not public, whatever IPv4 address it carries`() {
        // BUG-94 (A-11): these reached the IPv6 branch, which checked only ULA and the translation prefixes.
        listOf("::127.0.0.1", "::a00:1", "::192.168.0.1", "::8.8.8.8").forEach { literal ->
            val address = InetAddress.getByName(literal)
            assertEquals(16, address.address.size, "$literal stays IPv6 in Java")
            assertEquals(false, SandboxPolicy.isPublicAddress(address), literal)
        }
        // Mapped addresses are another prefix, `::ffff:0:0/96`, which Java already returns as IPv4.
        assertEquals(true, SandboxPolicy.isPublicAddress(InetAddress.getByName("::ffff:8.8.8.8")))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
        const val IDLE_MILLIS = 600L
        const val SLACK_MILLIS = 100L
        const val TRICKLE_BYTES = 10
        const val REPLY_BYTE = 42
    }
}
