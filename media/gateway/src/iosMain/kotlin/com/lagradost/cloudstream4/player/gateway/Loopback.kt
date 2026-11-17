@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.player.gateway

import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.SHUT_RDWR
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.write
import kotlin.concurrent.Volatile

/**
 * The gateway's sockets (`BUG-114`): one listener on 127.0.0.1 only, and blocking reads and writes on the connections it
 * accepts. Every write is `SO_NOSIGPIPE`, so a player that went away cannot end the app with SIGPIPE.
 */
internal object Loopback {

    class Listener internal constructor(private val fd: Int, val port: Int) {
        @Volatile private var open = true

        /** The next connection, or null after half a second without one (so a closed listener is noticed). */
        fun accept(): Int? {
            if (!open) return null
            val ready = memScoped {
                val p = zeroed<pollfd>().apply { this.fd = this@Listener.fd; events = POLLIN.convert() }
                poll(p.ptr, 1u, POLL_MS)
            }
            if (ready <= 0 || !open) return null
            val client = accept(fd, null, null)
            return client.takeIf { it >= 0 }
        }

        fun close() {
            open = false
            shutdown(fd, SHUT_RDWR)
            platform.posix.close(fd)
        }
    }

    /** A listener on 127.0.0.1 and a free port; null if the system will not give one. */
    fun listen(): Listener? = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return@memScoped null
        val sa = zeroed<sockaddr_in>()
        sa.sin_len = sizeOf<sockaddr_in>().convert()
        sa.sin_family = AF_INET.convert()
        sa.sin_port = 0u
        val bytes = sa.sin_addr.ptr.reinterpret<UByteVar>()
        bytes[0] = 127u; bytes[1] = 0u; bytes[2] = 0u; bytes[3] = 1u
        if (bind(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0 || listen(fd, BACKLOG) != 0) {
            platform.posix.close(fd)
            return@memScoped null
        }
        val length = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        if (getsockname(fd, sa.ptr.reinterpret<sockaddr>(), length.ptr) != 0) {
            platform.posix.close(fd)
            return@memScoped null
        }
        val p = sa.sin_port.toInt()
        Listener(fd, ((p and 0xFF) shl 8) or ((p shr 8) and 0xFF))
    }

    /** No SIGPIPE, and reads and writes that give up after [idleSeconds] without progress. */
    fun prepare(fd: Int, idleSeconds: Int) = memScoped {
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
        val tv = zeroed<timeval>().apply { tv_sec = idleSeconds.convert(); tv_usec = 0 }
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, tv.ptr, sizeOf<timeval>().convert())
    }

    fun close(fd: Int) {
        platform.posix.close(fd) // qualified: an unqualified call here would be this function
    }

    /** All of [bytes] to [fd]; false when the connection broke or timed out. */
    fun writeAll(fd: Int, bytes: ByteArray): Boolean {
        var sent = 0
        while (sent < bytes.size) {
            val n = bytes.usePinned { write(fd, it.addressOf(sent), (bytes.size - sent).convert()) }
            if (n < 0 && errno == EINTR) continue
            if (n <= 0) return false
            sent += n.toInt()
        }
        return true
    }

    /** A buffered byte reader over a connection: -1 at the end, a timeout or an error. */
    class Reader(private val fd: Int) {
        private val buffer = ByteArray(4096)
        private var position = 0
        private var limit = 0

        fun read(): Int {
            if (position == limit) {
                var n: Long
                do {
                    n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
                } while (n < 0 && errno == EINTR)
                if (n <= 0) return -1
                position = 0
                limit = n.toInt()
            }
            return buffer[position++].toInt() and 0xFF
        }
    }

    private const val POLL_MS = 500
    private const val BACKLOG = 32
}

/** A C struct with every byte 0: `alloc` does not promise zeroed memory. */
internal inline fun <reified T : CVariable> MemScope.zeroed(): T = alloc<T>().also { memset(it.ptr, 0, sizeOf<T>().convert()) }
