@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.lagradost.cloudstream4.core.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
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
import platform.posix.poll
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socketpair
import platform.posix.timeval
import platform.posix.write
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `BUG-129`: the gate trusts only itself. Once iOS has taken its port back, another process may listen there, and the
 * session's settings name only the port. Each test that needs one puts an impostor on the port the way that would
 * happen: [kill] takes the gate's listener away as iOS does (see `EgressGateReviveTest`), and a [LocalServer] binds
 * 127.0.0.1 on the freed port. The impostor counts every `CONNECT` it is sent, which is what it would learn.
 */
class EgressGateIdentityTest {

    /**
     * The defect: with an impostor on the port, no request reaches it. The gate cannot prove itself, so the request is
     * refused with [GateNotOwned] before the session sees it. When the impostor lets go, the gate takes its port back and
     * the next request reaches its destination.
     */
    @Test
    fun anImpostorOnThePortIsNeverTheSessionsProxy() = runBlocking {
        val server = LocalServer(OK)
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        val client = gatedFactory(gate).create()
        var impostor: LocalServer? = null
        try {
            kill(gate)
            impostor = LocalServer(BAD_GATEWAY, fixedPort = gate.port)
            val failure = withTimeout(60_000) { runCatching { send(client, server.port) }.exceptionOrNull() }
            // What the impostor learnt first: that is the leak. Then that it was the gate's own refusal that stopped it.
            assertEquals(0, impostor.received.count { it.startsWith("CONNECT ") }, "the impostor was sent a CONNECT")
            assertIs<GateNotOwned>(failure, "the request was not refused by the gate check")
            assertEquals(0, server.accepted)
            assertEquals(0, gate.revivals)

            impostor.stop()
            impostor = null
            withTimeout(60_000) { runCatching { send(client, server.port) } }
            assertTrue(server.accepted >= 1, "the request did not reach its destination once the port was free")
            assertEquals(1, gate.revivals)
        } finally {
            impostor?.stop()
            client.close()
            gate.stop()
            server.stop()
        }
    }

    /**
     * The accept loop does not give up on a held port: with no request at all, it keeps refusing to count the impostor
     * as itself, and takes the port back within a few passes of its being let go. Each pass while the port is held is a
     * failed attempt to listen (there is no listener left to prove), so taking it back after seconds of that is what
     * shows it kept trying.
     */
    @Test
    fun theGateKeepsTryingForItsPortWhileAnotherHoldsIt() {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = 500)
        var impostor: LocalServer? = null
        try {
            kill(gate)
            impostor = LocalServer(BAD_GATEWAY, fixedPort = gate.port)
            poll(null, 0u, 2_000) // several of the loop's passes
            assertFalse(gate.ensureOwned(), "the gate counted the impostor as itself")
            assertEquals(0, gate.revivals)

            impostor.stop()
            impostor = null
            var waited = 0
            while (gate.revivals == 0 && waited < 5_000) {
                poll(null, 0u, 50)
                waited += 50
            }
            assertEquals(1, gate.revivals, "the port was not taken back after $waited ms")
            assertTrue(gate.ensureOwned())
        } finally {
            impostor?.stop()
            gate.stop()
        }
    }

    /**
     * The nonce is checked, not only where the probe came from: a probe whose bytes differ from the nonce is no proof,
     * though it comes out of the gate's own queue, from the right address. The control is the same gate as it is.
     */
    @Test
    fun aProbeWhoseBytesDifferIsNoProof() {
        val wrong = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER, probeBytes = { ByteArray(it.size) })
        try {
            assertFalse(wrong.ensureOwned(), "a probe with the wrong bytes was taken as proof")
            assertEquals(0, wrong.revivals)
        } finally {
            wrong.stop()
        }
        val right = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER)
        try {
            assertTrue(right.ensureOwned())
        } finally {
            right.stop()
        }
    }

    /**
     * A proof takes clients out of the queue on its way to its own probe, and serves them: a request already waiting when
     * the probe connects is answered, here with the gate's 405 for anything but `CONNECT`.
     */
    @Test
    fun aClientWaitingInTheQueueIsServedDuringAProof() {
        val armed = AtomicInt(0)
        val waiting = AtomicInt(-1)
        lateinit var gate: EgressGate
        gate = EgressGate(
            allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER,
            beforeProbe = { if (armed.compareAndSet(1, 0)) waiting.store(openAndSend(gate.port, "GET http://queued.test/ HTTP/1.1\r\n\r\n")) },
        )
        try {
            armed.store(1)
            assertTrue(gate.ensureOwned())
            val fd = waiting.load()
            assertTrue(fd >= 0, "the waiting client was never opened")
            assertEquals(405, statusOf(fd), "the client waiting in the queue was not served")
        } finally {
            waiting.load().let { if (it >= 0) close(it) }
            gate.stop()
        }
    }

    /** A proof lets requests through for [EgressGate.PROOF_TTL_MS] and no longer: then the next request is checked. */
    @Test
    fun aProofIsUsedForOneSecondAndThenCheckedAgain() {
        val clock = AtomicLong(1_000_000L)
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER, now = { clock.load() })
        try {
            assertTrue(gate.ensureOwned())
            val first = gate.proofs
            clock.store(clock.load() + EgressGate.PROOF_TTL_MS)
            assertTrue(gate.ensureOwned())
            assertEquals(first, gate.proofs, "a proof was not reused within its second")
            clock.store(clock.load() + 1)
            assertTrue(gate.ensureOwned())
            assertEquals(first + 1, gate.proofs, "a stale proof was used")
        } finally {
            gate.stop()
        }
    }

    /** Coming back to the foreground forgets the proof, whatever the clock says: the next request is checked. */
    @Test
    fun aForgottenProofIsCheckedAgain() {
        val gate = EgressGate(allow = { true }, resolve = { listOf(LOOPBACK) }, probeIntervalMs = NEVER, now = { 1_000_000L })
        try {
            assertTrue(gate.ensureOwned())
            val first = gate.proofs
            assertTrue(gate.ensureOwned())
            assertEquals(first, gate.proofs)
            gate.forgetProof()
            assertTrue(gate.ensureOwned())
            assertEquals(first + 1, gate.proofs, "the request after the foreground used the old proof")
        } finally {
            gate.stop()
        }
    }

    /** The app's foreground call forgets the shared gate's proof itself, before its own check runs in the background. */
    @Test
    fun theForegroundCallForgetsTheSharedGatesProof() {
        val before = EgressGate.shared.proofEpoch
        reviveEgress()
        assertEquals(before + 1, EgressGate.shared.proofEpoch)
    }

    /** `::ffff:127.0.0.1` is 127.0.0.1; `::1` and every other IPv6 address are not, and nor is another port. */
    @Test
    fun anEndpointIsTheSameEndSpeltEitherWay() {
        val v4 = Endpoint.of(LOOPBACK, 5000)
        val mapped = Endpoint.of(ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 1), 5000)
        assertEquals(v4, mapped)
        assertNotEquals(v4, Endpoint.of(LOOPBACK, 5001))
        assertNotEquals(v4, Endpoint.of(ByteArray(15) + byteArrayOf(1), 5000)) // ::1
        assertNotEquals(v4, Endpoint.of(ByteArray(12) + LOOPBACK, 5000)) // ::127.0.0.1, IPv4-compatible, not mapped
        assertNotEquals(v4, Endpoint.of(ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 2), 5000))
    }

    @Test
    fun sameBytesComparesEveryByte() {
        val a = ByteArray(16) { it.toByte() }
        assertTrue(EgressGate.sameBytes(a, a.copyOf()))
        assertFalse(EgressGate.sameBytes(a, a.copyOf().also { it[15] = 99 }))
        assertFalse(EgressGate.sameBytes(a, a.copyOf().also { it[0] = 99 }))
        assertFalse(EgressGate.sameBytes(a, a.copyOf(15)))
    }

    /**
     * Every redirect hop passes the client's check ([GateNotOwned]) only if the session never follows one itself. Ktor's
     * Darwin delegate refuses them (read in its 3.3.3 source); this pins it on the version built here. With Ktor's own
     * redirects off, a 302 comes back as it is, and its target is never reached.
     */
    @Test
    fun theSessionFollowsNoRedirectItself() = runBlocking {
        val target = LocalServer(OK)
        val first = LocalServer("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${target.port}/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        try {
            val client = HttpClient(Darwin.create { configureSession { bounded() } }) { followRedirects = false }
            val status = withTimeout(30_000) { client.get("http://127.0.0.1:${first.port}/").status.value }
            client.close()
            assertEquals(302, status)
            assertEquals(1, first.accepted)
            assertEquals(0, target.accepted, "the session followed the redirect itself")
        } finally {
            first.stop()
            target.stop()
        }
    }

    // ---- Helpers. ----

    private suspend fun send(client: HttpClient, port: Int) = client.get("https://localhost:$port/")

    private val held = mutableListOf<Int>()

    @AfterTest
    fun closeHeld() {
        held.forEach { close(it) }
        held.clear()
    }

    /** What iOS does to the listener, as in `EgressGateReviveTest`: the port is freed, the descriptor stays valid. */
    private fun kill(gate: EgressGate) = memScoped {
        val pair = allocArray<IntVar>(2)
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0) { "socketpair failed, errno $errno" }
        val listener = gate.listenerFdForTest
        check(dup2(pair[0], listener) == listener) { "dup2 failed, errno $errno" }
        close(pair[0])
        held += pair[1]
    }

    /** A socket connected to 127.0.0.1:[port] that has sent [request]; -1 if it could not connect. */
    private fun openAndSend(port: Int, request: String): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return@memScoped -1
        val sa = alloc<sockaddr_in>().apply {
            sin_len = sizeOf<sockaddr_in>().convert()
            sin_family = AF_INET.convert()
            sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
            sin_addr.s_addr = 0x0100007Fu
        }
        if (connect(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0) {
            close(fd)
            return@memScoped -1
        }
        val bytes = request.encodeToByteArray()
        bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
        fd
    }

    /** The status code read from [fd]; 0 when nothing comes within 15 s. */
    private fun statusOf(fd: Int): Int = memScoped {
        val tv = alloc<timeval>().apply { tv_sec = 15; tv_usec = 0 }
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        val buffer = ByteArray(64)
        val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
        if (n <= 0) return@memScoped 0
        buffer.copyOf(n.toInt()).decodeToString().removePrefix("HTTP/1.1 ").take(3).toIntOrNull() ?: 0
    }

    private companion object {
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)
        const val NEVER = Int.MAX_VALUE
        const val OK = "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nreached"
        const val BAD_GATEWAY = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
