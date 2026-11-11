package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The one way a plugin host reaches the network: a SOCKS5 proxy on loopback, in the app and so outside the sandbox.
 *
 * Seatbelt can name only `localhost` or `*` (measured: anything else is "host must be * or localhost"), so it cannot
 * tell the internet from this Mac's LAN address or the router. A plugin could reach both. So the policy refuses every
 * address except this port, and this decides, on the address it is about to connect to, so a name cannot resolve to
 * something public here and something private later.
 *
 * Setting a JVM property is what sends a plugin's sockets here, and a plugin can undo that. It gains nothing: without
 * this proxy the kernel refuses the connection, and `SandboxEscapeAttemptTest` tries it.
 *
 * Traffic stays end to end, so all this can see is where a connection goes. That is what [contacted] reports.
 *
 * A tunnel nothing has crossed, either way, for [idleTimeoutMillis] is closed (`BUG-94`). Without that, a plugin could
 * hold [MAX_CONNECTIONS] silent tunnels, two threads each, for as long as its host lives. It is idle in both directions,
 * never one: a download sends nothing for minutes while its bytes come back, and must not be cut.
 */
class OutboundGate(
    private val allowed: (InetAddress) -> Boolean = SandboxPolicy::isPublicAddress,
    private val idleTimeoutMillis: Long = IDLE_TIMEOUT_MILLIS,
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val hosts = ConcurrentHashMap.newKeySet<String>()

    val port: Int get() = server.localPort

    /** Tunnels open now: what [MAX_CONNECTIONS] bounds. For a test. */
    internal val openTunnels: Int get() = MAX_CONNECTIONS - slots.availablePermits()

    /** Names and addresses this host asked for, allowed or not. Never a path: the proxy does not see one. */
    val contacted: Set<String> get() = hosts.toSet()

    init {
        thread(isDaemon = true, name = "outbound-gate") {
            runCatching {
                while (true) {
                    val client = server.accept()
                    if (!slots.tryAcquire()) {
                        client.close()
                        continue
                    }
                    thread(isDaemon = true, name = "outbound-gate-client") {
                        try {
                            client.use(::serve)
                        } finally {
                            slots.release()
                        }
                    }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val remote = runCatching { handshake(input, output) }.getOrNull() ?: return
        remote.use {
            // Each read wakes this often to ask whether the tunnel as a whole has gone quiet.
            val wake = minOf(idleTimeoutMillis, IDLE_CHECK_MILLIS).toInt().coerceAtLeast(1)
            client.soTimeout = wake
            remote.soTimeout = wake
            val crossed = AtomicLong(System.nanoTime())
            // Idle closes both ends, not only the one it read from (`BUG-96`): a write the other way may be blocked on a
            // peer that stopped reading, and only closing its socket wakes it. Without that, the tunnel never ended.
            val cut: () -> Unit = {
                runCatching { client.close() }
                runCatching { remote.close() }
            }
            val back = thread(isDaemon = true, name = "outbound-gate-back") { pump(remote.getInputStream(), output, client, crossed, cut) }
            pump(input, remote.getOutputStream(), remote, crossed, cut)
            back.join()
        }
    }

    /** @return the connection, with success already told to the client, or null with the failure told to it. */
    private fun handshake(input: DataInputStream, output: OutputStream): Socket? {
        if (input.readUnsignedByte() != VERSION) return null
        input.skipBytes(input.readUnsignedByte())
        output.write(byteArrayOf(VERSION.toByte(), NO_AUTHENTICATION))
        output.flush()
        if (input.readUnsignedByte() != VERSION) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val (name, literal) = when (input.readUnsignedByte()) {
            ADDRESS_IPV4 -> ByteArray(IPV4_BYTES).let { input.readFully(it); InetAddress.getByAddress(it).let { a -> a.hostAddress to a } }
            ADDRESS_IPV6 -> ByteArray(IPV6_BYTES).let { input.readFully(it); InetAddress.getByAddress(it).let { a -> a.hostAddress to a } }
            ADDRESS_NAME -> ByteArray(input.readUnsignedByte()).let { input.readFully(it); String(it, Charsets.US_ASCII) to null }
            else -> return reply(output, REPLY_ADDRESS_UNSUPPORTED)
        }
        val port = input.readUnsignedShort()
        if (command != COMMAND_CONNECT) return reply(output, REPLY_COMMAND_UNSUPPORTED)
        if (hosts.size < MAX_RECORDED_HOSTS && name.length <= MAX_NAME_CHARS && name.none { it.isISOControl() }) hosts += name
        val addresses = literal?.let(::listOf) ?: runCatching { InetAddress.getAllByName(name).toList() }.getOrNull()
            ?: return reply(output, REPLY_HOST_UNREACHABLE)
        val open = addresses.filter(allowed)
        if (open.isEmpty()) return reply(output, REPLY_NOT_ALLOWED)
        for (address in open) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                output.write(byteArrayOf(VERSION.toByte(), 0, 0, ADDRESS_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
                output.flush()
                return socket
            } catch (_: IOException) {
                socket.close()
            }
        }
        return reply(output, REPLY_HOST_UNREACHABLE)
    }

    private fun reply(output: OutputStream, code: Byte): Socket? {
        output.write(byteArrayOf(VERSION.toByte(), code, 0, ADDRESS_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
        return null
    }

    /**
     * Copies until the end, a failure, or [crossed] (shared by both directions) is older than the idle timeout, when it
     * [cut]s the whole tunnel. A write is not timed by itself: a write blocked on a peer that stopped reading crosses
     * nothing, so the other direction finds the tunnel idle and [cut] ends it.
     */
    private fun pump(from: InputStream, to: OutputStream, target: Socket, crossed: AtomicLong, cut: () -> Unit) {
        runCatching {
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = try {
                    from.read(buffer)
                } catch (_: SocketTimeoutException) {
                    if ((System.nanoTime() - crossed.get()) / NANOS_PER_MILLI >= idleTimeoutMillis) {
                        cut()
                        break
                    }
                    continue
                }
                if (read < 0) break
                crossed.set(System.nanoTime())
                to.write(buffer, 0, read)
                to.flush()
            }
        }
        runCatching { target.shutdownOutput() }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private companion object {
        const val BACKLOG = 64
        /** Far more than any plugin measured opens at once, and a host past it gets no more threads out of the app. */
        const val MAX_CONNECTIONS = 256
        const val MAX_RECORDED_HOSTS = 1024
        const val MAX_NAME_CHARS = 255
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        /** Longer than OkHttp keeps an idle pooled connection (five minutes), so a plugin's pool never meets it first. */
        const val IDLE_TIMEOUT_MILLIS = 6 * 60_000L
        const val IDLE_CHECK_MILLIS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val BUFFER_BYTES = 16 * 1024
        const val VERSION = 5
        const val NO_AUTHENTICATION: Byte = 0
        const val COMMAND_CONNECT = 1
        const val ADDRESS_IPV4 = 1
        const val ADDRESS_NAME = 3
        const val ADDRESS_IPV6 = 4
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val REPLY_NOT_ALLOWED: Byte = 2
        const val REPLY_HOST_UNREACHABLE: Byte = 4
        const val REPLY_COMMAND_UNSUPPORTED: Byte = 7
        const val REPLY_ADDRESS_UNSUPPORTED: Byte = 8
    }
}
