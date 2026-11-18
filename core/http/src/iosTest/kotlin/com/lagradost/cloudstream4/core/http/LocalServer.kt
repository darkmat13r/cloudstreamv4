@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.core.http

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.set
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.read
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.socket
import platform.posix.write

/**
 * A one-thread HTTP/1.1 server on 127.0.0.1 (or ::1 when [ipv6]) and a free port, for tests that must reach a real
 * socket. [reply] is written to each connection after its request is read; null holds the connection open
 * without answering, so a client's timeout is what ends it. [accepted] counts every connection it took, and [received]
 * holds the first 4096 bytes each one sent.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LocalServer(private val reply: String?, private val ipv6: Boolean = false) {
    private val fd: Int = socket(if (ipv6) AF_INET6 else AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket failed" } }
    private val accepts = AtomicInt(0)

    /** Connections accepted so far: the proof a refused request never reached this address. */
    val accepted: Int get() = accepts.load()
    private val requests = AtomicReference(emptyList<String>())

    /** What each connection sent, in order: the request line and headers a test asserts on. */
    val received: List<String> get() = requests.load()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val held = mutableListOf<Int>()
    val port: Int

    init {
        port = if (ipv6) bindIpv6() else bindIpv4()
        check(listen(fd, 8) == 0) { "listen failed" }
        scope.launch { serve() }
    }

    private fun bindIpv4(): Int = memScoped {
        val address = alloc<sockaddr_in>().also { memset(it.ptr, 0, sizeOf<sockaddr_in>().convert()) }
        address.sin_family = AF_INET.convert()
        address.sin_port = 0u
        // 127.0.0.1 in network byte order; htonl is a C macro Kotlin/Native does not bind (arm64 is little-endian).
        address.sin_addr.s_addr = 0x0100007Fu
        check(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
        val length = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        check(getsockname(fd, address.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname failed" }
        address.sin_port.toInt().let { ((it and 0xFF) shl 8) or ((it shr 8) and 0xFF) }
    }

    private fun bindIpv6(): Int = memScoped {
        val address = alloc<sockaddr_in6>().also { memset(it.ptr, 0, sizeOf<sockaddr_in6>().convert()) }
        address.sin6_family = AF_INET6.convert()
        address.sin6_port = 0u
        address.sin6_addr.ptr.reinterpret<UByteVar>()[15] = 1u // ::1
        check(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in6>().convert()) == 0) { "bind failed" }
        val length = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in6>().convert() }
        check(getsockname(fd, address.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname failed" }
        address.sin6_port.toInt().let { ((it and 0xFF) shl 8) or ((it shr 8) and 0xFF) }
    }

    private fun serve() {
        while (true) {
            val connection = accept(fd, null, null)
            if (connection < 0) return
            accepts.incrementAndFetch()
            memScoped {
                val buffer = allocArray<ByteVar>(4096)
                val count = read(connection, buffer, 4096u).toInt()
                if (count > 0) record(buffer.readBytes(count).decodeToString())
            }
            if (reply == null) {
                held += connection
                continue
            }
            memScoped {
                val bytes = reply.encodeToByteArray()
                write(connection, reply.cstr.ptr, bytes.size.convert())
            }
            close(connection)
        }
    }

    private fun record(request: String) {
        while (true) {
            val now = requests.load()
            if (requests.compareAndSet(now, now + request)) return
        }
    }

    fun stop() {
        held.forEach { close(it) }
        close(fd)
        scope.cancel()
    }
}
