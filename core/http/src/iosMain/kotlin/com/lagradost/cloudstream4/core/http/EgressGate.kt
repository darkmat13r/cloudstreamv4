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
import platform.posix.pipe
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
 * **It heals itself** (`BUG-128`). After about 18 minutes suspended, iOS takes the listening port back behind a file
 * descriptor that stays valid and raises no event: `poll` stays silent, `accept` never fails, and a connection to the
 * port is refused. So the gate checks itself the only way that shows it, by connecting to its own port ([alive]), and
 * listens again on the same port when that is refused ([revive]): on every request that fails (see
 * [DarwinClientFactory]), and from the accept loop every [probeIntervalMs] and whenever the listener reports an error.
 * Only the listening socket is replaced; [allow], [resolve] and every rule above are the same objects throughout.
 *
 * @param allow judges each resolved address (4 or 16 bytes). [isPublicDestination] in production.
 * @param resolve the lookup, a seam for tests: a host to its addresses, empty when it does not resolve.
 * @param probeIntervalMs how often an idle accept loop checks that the port still answers. A seam for tests.
 */
internal class EgressGate(
    private val allow: (ByteArray) -> Boolean = ::isPublicDestination,
    private val resolve: (String) -> List<ByteArray> = ::systemResolve,
    private val probeIntervalMs: Int = PROBE_INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_TUNNELS + 1))
    private val active = AtomicInt(0)
    private val running = AtomicInt(1)

    /** Set once the accept loop has closed its sockets and returned. */
    private val stopped = AtomicInt(0)

    /**
     * The listening socket, -1 while there is none. **Only the accept loop's thread opens, replaces or closes it**
     * (`BUG-128`): a descriptor number closed by one thread can be handed to another socket at once, so a second close,
     * or a poll or accept on the old number, from any other thread could act on a socket that is not the gate's. Other
     * threads ask the loop ([revive], [stop]) through [wake] and wait for its answer.
     */
    private val listener = AtomicInt(listenOn(0))

    /**
     * A pipe the accept loop polls beside the listener: a byte written to it wakes the loop at once. It is never closed:
     * a [revive] that passed its check as the gate stopped may still write to it, and a closed number could by then be
     * another socket's. Two descriptors for the gate's life; the app has one gate.
     */
    private val wake: IntArray = wakePipe()

    /** Checks asked of the loop, and the last one it has answered: a caller waits until its own is answered. */
    private val asked = AtomicInt(0)
    private val answered = AtomicInt(0)

    private val revived = AtomicInt(0)

    /** The port the session's proxy settings point at. It stays the same for the gate's life (see [heal]). */
    val port: Int = localPort(listener.load())

    /** How many times the gate was found dead and listened again. In memory only, never logged (`BUG-128`). */
    internal val revivals: Int get() = revived.load()

    /** The listening socket, for tests that kill it the way iOS does. */
    internal val listenerFdForTest: Int get() = listener.load()

    /** The wake pipe's two ends, for the test that it outlives the loop. */
    internal val wakeFdsForTest: IntArray get() = wake.copyOf()

    init {
        scope.launch { serve() }
    }

    /** Stops listening and ends the tunnels. The accept loop closes the listener; this waits, briefly, for it to. */
    fun stop() {
        if (running.exchange(0) == 0) return
        signal()
        var waited = 0
        while (stopped.load() == 0 && waited < STOP_WAIT_MS) {
            poll(null, 0u, WAIT_SLICE_MS)
            waited += WAIT_SLICE_MS
        }
        scope.cancel()
    }

    private fun serve() {
        var idleMs = 0
        try {
            while (running.load() == 1) {
                val fd = listener.load()
                val event = memScoped {
                    val fds = allocArray<pollfd>(2)
                    fds[0].fd = fd // -1 while there is no listener: poll leaves it out
                    fds[0].events = POLLIN.convert()
                    fds[1].fd = wake[0]
                    fds[1].events = POLLIN.convert()
                    val n = poll(fds, 2u, POLL_SLICE_MS)
                    when {
                        n < 0 -> if (errno == EINTR) IDLE else BROKEN
                        n == 0 -> IDLE
                        (fds[1].revents.toInt() and POLLIN) != 0 -> WOKEN
                        (fds[0].revents.toInt() and (POLLERR or POLLNVAL or POLLHUP)) != 0 -> BROKEN
                        else -> READY
                    }
                }
                if (running.load() != 1) return
                when (event) {
                    WOKEN -> {
                        drain()
                        val upTo = asked.load()
                        heal()
                        answered.store(upTo)
                        idleMs = 0
                    }
                    BROKEN -> heal()
                    IDLE -> {
                        idleMs += POLL_SLICE_MS
                        // No listener yet, the interval passed, or a pending error (a hint only): check it.
                        if (fd < 0 || idleMs >= probeIntervalMs || pendingError(fd)) {
                            idleMs = 0
                            heal()
                        }
                    }
                    else -> accept(fd)
                }
            }
        } finally {
            listener.exchange(-1).let { if (it >= 0) close(it) }
            stopped.store(1)
        }
    }

    private fun accept(fd: Int) {
        val client = accept(fd, null, null)
        if (client < 0) {
            // A client that gave up between poll and accept is not the listener's fault.
            if (errno != EINTR && errno != ECONNABORTED) heal()
            return
        }
        if (active.incrementAndFetch() > MAX_TUNNELS) {
            active.decrementAndFetch()
            reply(client, BUSY)
            close(client)
            return
        }
        scope.launch {
            try {
                handle(client)
            } finally {
                active.decrementAndFetch()
            }
        }
    }

    /** Whether a connection to the gate's own port is accepted: the one check iOS's silent reclaim cannot hide. */
    private fun alive(): Boolean = connectTo(LOOPBACK_V4, port, PROBE_TIMEOUT_MS)?.let { close(it); true } ?: false

    /**
     * On the accept loop's thread only. If the port no longer answers, closes the listener and listens again on the same
     * port, a few times [RELISTEN_BACKOFF_MS] apart; until it can, the session's requests fail rather than go anywhere
     * else, and the loop tries again on its next pass. iOS reclaims a suspended app's listening socket, and the session's
     * settings still name this port. True only when it was dead and now answers.
     */
    private fun heal(): Boolean {
        if (listener.load() >= 0 && alive()) return false
        listener.exchange(-1).let { if (it >= 0) close(it) }
        repeat(RELISTEN_ATTEMPTS) { attempt ->
            if (running.load() != 1) return false
            val fresh = runCatching { listenOn(port) }.getOrNull()
            if (fresh != null) {
                listener.store(fresh)
                if (alive()) {
                    revived.incrementAndFetch()
                    return true
                }
                listener.store(-1)
                close(fresh)
            }
            if (attempt < RELISTEN_ATTEMPTS - 1) poll(null, 0u, RELISTEN_BACKOFF_MS)
        }
        return false
    }

    /**
     * Checks the gate and, if its port no longer answers, has it listen again on the same port (`BUG-128`). True only
     * when it was found dead and answers again, so a caller sends its request again only then. A gate that answers is
     * left alone, with no work for the accept loop. Otherwise the loop is woken to [heal], and this waits for its answer,
     * at most [REVIVE_WAIT_MS]; many callers at once are answered by one check.
     */
    internal fun revive(): Boolean {
        if (running.load() != 1) return false
        val before = revived.load()
        if (alive()) return false
        val ticket = asked.incrementAndFetch()
        signal()
        var waited = 0
        while (answered.load() < ticket && waited < REVIVE_WAIT_MS && running.load() == 1) {
            poll(null, 0u, WAIT_SLICE_MS)
            waited += WAIT_SLICE_MS
        }
        return revived.load() != before && alive()
    }

    /** [revive] on the gate's own threads, for a caller that must not wait (the app returning to the foreground). */
    internal fun reviveInBackground() {
        scope.launch { revive() }
    }

    private fun signal() {
        val one = byteArrayOf(1)
        one.usePinned { write(wake[1], it.addressOf(0), 1u) }
    }

    private fun drain() {
        val buffer = ByteArray(64)
        while (buffer.usePinned { read(wake[0], it.addressOf(0), buffer.size.convert()) } > 0) Unit
    }

    private fun handle(client: Int) {
        try {
            noSigPipe(client)
            receiveTimeout(client, HEAD_TIMEOUT_SECONDS)
            val head = readHead(client) ?: return reply(client, BAD_REQUEST)
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

    private class Head(val line: String, val rest: ByteArray)

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
                val line = buffer.copyOf(end).decodeToString().substringBefore("\r\n")
                return Head(line, buffer.copyOfRange(end + 4, size))
            }
        }
        return null
    }

    private fun splice(a: Int, b: Int) = memScoped {
        val fds = allocArray<pollfd>(2)
        fds[0].fd = a
        fds[1].fd = b
        val chunk = ByteArray(CHUNK)
        var idle = 0
        // Polled in slices, so a stopped gate ends its tunnels within one slice rather than at the idle timeout.
        while (running.load() == 1) {
            fds[0].events = POLLIN.convert()
            fds[1].events = POLLIN.convert()
            fds[0].revents = 0
            fds[1].revents = 0
            val n = poll(fds, 2u, POLL_SLICE_MS)
            if (n == 0) {
                idle += POLL_SLICE_MS
                if (idle >= IDLE_TIMEOUT_MS) return@memScoped
                continue
            }
            idle = 0
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
        private const val RELISTEN_BACKOFF_MS = 100
        private const val PROBE_INTERVAL_MS = 5_000
        private const val PROBE_TIMEOUT_MS = 250
        private const val REVIVE_WAIT_MS = 2_000
        private const val STOP_WAIT_MS = 2_000
        private const val WAIT_SLICE_MS = 10
        private const val RELISTEN_ATTEMPTS = 3
        private const val IDLE = 0
        private const val READY = 1
        private const val WOKEN = 2
        private const val BROKEN = 3
        private val LOOPBACK_V4 = byteArrayOf(127, 0, 0, 1)

        /** A pipe whose two ends never block: the loop polls the first, [signal] writes the second. */
        private fun wakePipe(): IntArray = memScoped {
            val fds = allocArray<IntVar>(2)
            check(pipe(fds) == 0) { "pipe failed" }
            IntArray(2) { fds[it] }.onEach { fcntl(it, F_SETFL, fcntl(it, F_GETFL, 0) or O_NONBLOCK) }
        }

        /** A pending error on [fd], read once (reading clears it). iOS left one on the reclaimed listener, sometimes. */
        private fun pendingError(fd: Int): Boolean = memScoped {
            val error = alloc<IntVar>()
            val length = alloc<UIntVar>().apply { value = sizeOf<IntVar>().convert() }
            getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr) == 0 && error.value != 0
        }

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

        /** A connected, blocking socket to [address]:[port], or null if it did not connect within [timeoutMs]. */
        private fun connectTo(address: ByteArray, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Int? = memScoped {
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
                    poll(pfd.ptr, 1u, timeoutMs) == 1 &&
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
