@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.lagradost.cloudstream4.core.http

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.AI_ADDRCONFIG
import platform.posix.AI_V4MAPPED_CFG
import platform.posix.ECONNABORTED
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.socket
import platform.posix.timeval
import platform.posix.write
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * `BUG-111`: the one way out of the Darwin session. An HTTP `CONNECT` proxy on 127.0.0.1, set as the session's only
 * proxy (see [gatedBy]), that refuses any destination [allow] does not accept, before it connects.
 *
 * `NSURLSession` resolves names and opens sockets itself and offers no hook between the two, so the address it is
 * about to connect to cannot be checked from outside it. Checking a name first and letting the session resolve it
 * again leaves a gap a short-lived DNS answer walks through. So the session connects only here, and this is what
 * connects onward:
 *
 * - **One lookup, then that address.** The host is resolved once, every address [allow] refuses is dropped, and the
 *   connection goes to one of the rest by address. Nothing resolves it again.
 * - **Every name.** `localhost`, a name that resolves to a private address and a redirect's new host all arrive here as
 *   a `CONNECT`, and are all judged by the address they resolve to. An address written in a URL is refused before the
 *   session sees it ([refuseAddressLiterals]): the session sends some of those past its proxy. One that does arrive here
 *   is judged the same way.
 * - **TLS stays end to end.** The tunnel carries the session's own TLS, checked against the real host name.
 * - **Nothing else.** A request that is not a `CONNECT` (plain http) is refused.
 * - **Nothing logged.** A refusal is a fixed status line; the host is never written anywhere.
 *
 * With nothing listening here the session's requests fail; they never go direct.
 *
 * @param allow judges each resolved address (4 or 16 bytes). [isPublicDestination] in production.
 * @param resolve the lookup, a seam for tests: a host to its addresses, empty when it does not resolve.
 */
internal class EgressGate(
    private val allow: (ByteArray) -> Boolean = ::isPublicDestination,
    private val resolve: (String) -> List<ByteArray> = ::systemResolve,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_TUNNELS + 1))
    private val active = AtomicInt(0)
    private val running = AtomicInt(1)
    private var listener: Int = listenOn(0)

    /** The port the session's proxy settings point at. It stays the same for the gate's life (see [serve]). */
    val port: Int = localPort(listener)

    /** How many `CONNECT`s carried a `Proxy-Authorization` header. Test-only measurement, never the header itself. */
    internal val connectsWithCredential = AtomicInt(0)

    init {
        scope.launch { serve() }
    }

    fun stop() {
        running.store(0)
        close(listener)
        scope.cancel()
    }

    private fun serve() {
        while (running.load() == 1) {
            val ready = memScoped {
                val fds = zeroed<pollfd>().apply { fd = listener; events = POLLIN.convert() }
                val n = poll(fds.ptr, 1u, POLL_SLICE_MS)
                if (n > 0 && (fds.revents.toInt() and (POLLERR or POLLNVAL or POLLHUP)) != 0) -1 else n
            }
            if (running.load() != 1) return
            if (ready < 0 && errno != EINTR) {
                relisten()
                continue
            }
            if (ready <= 0) continue
            val client = accept(listener, null, null)
            if (client < 0) {
                // A client that gave up between poll and accept is not the listener's fault.
                if (errno != EINTR && errno != ECONNABORTED) relisten()
                continue
            }
            if (active.incrementAndFetch() > MAX_TUNNELS) {
                active.decrementAndFetch()
                reply(client, BUSY)
                close(client)
                continue
            }
            scope.launch {
                try {
                    handle(client)
                } finally {
                    active.decrementAndFetch()
                }
            }
        }
    }

    /**
     * iOS reclaims a suspended app's listening sockets, and the session's settings still name this port. So the gate
     * listens again on the same port; until it can, the session's requests fail rather than go anywhere else.
     */
    private fun relisten() {
        close(listener)
        while (running.load() == 1) {
            val fresh = runCatching { listenOn(port) }.getOrNull()
            if (fresh != null) {
                listener = fresh
                return
            }
            poll(null, 0u, RELISTEN_BACKOFF_MS)
        }
    }

    private fun handle(client: Int) {
        try {
            noSigPipe(client)
            receiveTimeout(client, HEAD_TIMEOUT_SECONDS)
            val head = readHead(client) ?: return reply(client, BAD_REQUEST)
            if (head.hasCredential) connectsWithCredential.incrementAndFetch()
            val target = parseConnect(head.line) ?: return reply(client, if (head.line.startsWith("CONNECT ")) BAD_REQUEST else NOT_ALLOWED)
            val upstream = open(target)
            if (upstream == REFUSED) return reply(client, FORBIDDEN)
            if (upstream == UNREACHABLE) return reply(client, BAD_GATEWAY)
            try {
                receiveTimeout(client, 0)
                reply(client, ESTABLISHED)
                if (head.rest.isNotEmpty() && !writeAll(upstream, head.rest)) return
                splice(client, upstream)
            } finally {
                close(upstream)
            }
        } finally {
            close(client)
        }
    }

    /** A socket connected to an allowed address of [target]; [REFUSED] when none is allowed, [UNREACHABLE] when none answered. */
    private fun open(target: Target): Int {
        val addresses = runCatching { resolve(target.host) }.getOrDefault(emptyList())
        val allowed = addresses.filter { (it.size == 4 || it.size == 16) && allow(it) }
        if (allowed.isEmpty()) return REFUSED
        for (address in allowed) {
            connectTo(address, target.port)?.let { return it }
        }
        return UNREACHABLE
    }

    internal class Target(val host: String, val port: Int)

    private class Head(val line: String, val hasCredential: Boolean, val rest: ByteArray)

    /** The request line and headers, up to [MAX_HEAD] bytes, and whatever followed them. Null when malformed or slow. */
    private fun readHead(fd: Int): Head? {
        val buffer = ByteArray(MAX_HEAD)
        var size = 0
        while (size < MAX_HEAD) {
            val n = buffer.usePinned { read(fd, it.addressOf(size), (MAX_HEAD - size).convert()) }
            if (n <= 0) return null
            size += n.toInt()
            val end = indexOfBlankLine(buffer, size)
            if (end >= 0) {
                val lines = buffer.copyOf(end).decodeToString().split("\r\n")
                val credential = lines.drop(1).any { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
                return Head(lines.first(), credential, buffer.copyOfRange(end + 4, size))
            }
        }
        return null
    }

    private fun splice(a: Int, b: Int) = memScoped {
        val fds = allocArray<pollfd>(2)
        fds[0].fd = a
        fds[1].fd = b
        val chunk = ByteArray(CHUNK)
        while (running.load() == 1) {
            fds[0].events = POLLIN.convert()
            fds[1].events = POLLIN.convert()
            fds[0].revents = 0
            fds[1].revents = 0
            val n = poll(fds, 2u, IDLE_TIMEOUT_MS)
            if (n == 0) return@memScoped // idle
            if (n < 0) {
                if (errno == EINTR) continue
                return@memScoped
            }
            for (i in 0..1) {
                val revents = fds[i].revents.toInt()
                if (revents == 0) continue
                if ((revents and POLLIN) == 0) return@memScoped // hung up or failed with nothing to read
                val from = fds[i].fd
                val to = fds[1 - i].fd
                val read = chunk.usePinned { read(from, it.addressOf(0), CHUNK.convert()) }
                if (read <= 0) return@memScoped
                if (!writeAll(to, chunk.copyOf(read.toInt()))) return@memScoped
            }
        }
    }

    companion object {
        /** The gate every client from `HttpClientFactory()` goes through: one per process, started on first use. */
        val shared: EgressGate by lazy { EgressGate() }

        const val MAX_TUNNELS = 32
        private const val MAX_HEAD = 8192
        private const val CHUNK = 16 * 1024
        private const val HEAD_TIMEOUT_SECONDS = 10
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 60_000
        private const val POLL_SLICE_MS = 500
        private const val RELISTEN_BACKOFF_MS = 1_000

        private const val ESTABLISHED = "HTTP/1.1 200 Connection Established\r\n\r\n"
        private const val BAD_REQUEST = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val BUSY = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val FORBIDDEN = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val BAD_GATEWAY = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val REFUSED = -1
        private const val UNREACHABLE = -2

        /**
         * `CONNECT host:port HTTP/1.x`, the authority as a host and a port. A bracketed host is IPv6; a scope (`%`) or
         * any character a host name or address cannot hold is malformed.
         */
        internal fun parseConnect(line: String): Target? {
            val parts = line.split(' ')
            if (parts.size != 3 || parts[0] != "CONNECT" || !parts[2].startsWith("HTTP/1.")) return null
            val authority = parts[1]
            val (host, port) = if (authority.startsWith('[')) {
                val close = authority.indexOf(']')
                if (close < 0 || authority.getOrNull(close + 1) != ':') return null
                authority.substring(1, close) to authority.substring(close + 2)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon <= 0) return null
                authority.substring(0, colon).takeIf { ':' !in it } ?: return null
                authority.substring(0, colon) to authority.substring(colon + 1)
            }
            if (host.isEmpty() || host.length > 253 || !host.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == ':' }) return null
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return null
            val number = port.toInt()
            if (number !in 1..65535) return null
            return Target(host, number)
        }

        private fun indexOfBlankLine(buffer: ByteArray, size: Int): Int {
            for (i in 0..size - 4) {
                if (buffer[i] == '\r'.code.toByte() && buffer[i + 1] == '\n'.code.toByte() &&
                    buffer[i + 2] == '\r'.code.toByte() && buffer[i + 3] == '\n'.code.toByte()
                ) return i
            }
            return -1
        }

        /** The system's lookup, `AI_DEFAULT` as Apple asks (it synthesizes NAT64 addresses on an IPv6-only network). */
        internal fun systemResolve(host: String): List<ByteArray> = memScoped {
            val hints = zeroed<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_STREAM
                ai_flags = AI_ADDRCONFIG or AI_V4MAPPED_CFG
            }
            val result = alloc<CPointerVar<addrinfo>>()
            if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()
            val out = mutableListOf<ByteArray>()
            var node: CPointer<addrinfo>? = result.value
            while (node != null) {
                val info = node.pointed
                val address = info.ai_addr
                when {
                    address == null -> Unit
                    info.ai_family == AF_INET -> out += address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>().bytes(4)
                    info.ai_family == AF_INET6 -> out += address.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr.reinterpret<ByteVar>().bytes(16)
                }
                node = info.ai_next
            }
            freeaddrinfo(result.value)
            out
        }

        private fun CPointer<ByteVar>.bytes(count: Int) = ByteArray(count) { this[it] }

        /** A connected, blocking socket to [address]:[port], or null if it did not connect within the timeout. */
        private fun connectTo(address: ByteArray, port: Int): Int? = memScoped {
            val v6 = address.size == 16
            val fd = socket(if (v6) AF_INET6 else AF_INET, SOCK_STREAM, 0)
            if (fd < 0) return@memScoped null
            noSigPipe(fd)
            val flags = fcntl(fd, F_GETFL, 0)
            fcntl(fd, F_SETFL, flags or O_NONBLOCK)
            val result = if (v6) {
                val sa = zeroed<sockaddr_in6>()
                sa.sin6_len = sizeOf<sockaddr_in6>().convert()
                sa.sin6_family = AF_INET6.convert()
                sa.sin6_port = networkOrder(port)
                val bytes = sa.sin6_addr.ptr.reinterpret<UByteVar>()
                for (i in 0 until 16) bytes[i] = address[i].toUByte()
                connect(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in6>().convert())
            } else {
                val sa = zeroed<sockaddr_in>()
                sa.sin_len = sizeOf<sockaddr_in>().convert()
                sa.sin_family = AF_INET.convert()
                sa.sin_port = networkOrder(port)
                val bytes = sa.sin_addr.ptr.reinterpret<UByteVar>()
                for (i in 0 until 4) bytes[i] = address[i].toUByte()
                connect(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
            }
            val connected = when {
                result == 0 -> true
                errno != EINPROGRESS -> false
                else -> {
                    val pfd = zeroed<pollfd>().apply { this.fd = fd; events = POLLOUT.convert() }
                    val error = alloc<IntVar>()
                    val length = alloc<UIntVar>().apply { value = sizeOf<IntVar>().convert() }
                    poll(pfd.ptr, 1u, CONNECT_TIMEOUT_MS) == 1 &&
                        getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr) == 0 && error.value == 0
                }
            }
            if (!connected) {
                close(fd)
                return@memScoped null
            }
            fcntl(fd, F_SETFL, flags)
            fd
        }

        /** A listening socket on 127.0.0.1:[port] (0 for any free port). */
        private fun listenOn(port: Int): Int = memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            check(fd >= 0) { "socket failed" }
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val sa = zeroed<sockaddr_in>()
            sa.sin_len = sizeOf<sockaddr_in>().convert()
            sa.sin_family = AF_INET.convert()
            sa.sin_port = networkOrder(port)
            val bytes = sa.sin_addr.ptr.reinterpret<UByteVar>()
            bytes[0] = 127u; bytes[1] = 0u; bytes[2] = 0u; bytes[3] = 1u
            if (bind(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0 || listen(fd, 64) != 0) {
                close(fd)
                error("listen failed")
            }
            fd
        }

        private fun localPort(fd: Int): Int = memScoped {
            val sa = zeroed<sockaddr_in>()
            val length = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(fd, sa.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname failed" }
            val p = sa.sin_port.toInt()
            ((p and 0xFF) shl 8) or ((p shr 8) and 0xFF)
        }

        /** [port] in network byte order: htons, a C macro Kotlin/Native does not bind (arm64 is little-endian). */
        private fun networkOrder(port: Int): UShort = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()

        /** A write to a closed peer must fail, not raise SIGPIPE and end the app. */
        private fun noSigPipe(fd: Int) = memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
        }

        private fun receiveTimeout(fd: Int, seconds: Int) = memScoped {
            val tv = zeroed<timeval>().apply { tv_sec = seconds.convert(); tv_usec = 0 }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }

        private fun reply(fd: Int, text: String) {
            writeAll(fd, text.encodeToByteArray())
        }

        private fun writeAll(fd: Int, bytes: ByteArray): Boolean {
            var sent = 0
            while (sent < bytes.size) {
                val n = bytes.usePinned { write(fd, it.addressOf(sent), (bytes.size - sent).convert()) }
                if (n < 0 && errno == EINTR) continue
                if (n <= 0) return false
                sent += n.toInt()
            }
            return true
        }
    }
}

/** A C struct with every byte 0. `alloc` does not promise zeroed memory, and a C API reads every field it is given. */
internal inline fun <reified T : CVariable> MemScope.zeroed(): T = alloc<T>().also { memset(it.ptr, 0, sizeOf<T>().convert()) }
