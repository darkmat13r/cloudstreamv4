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
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.read
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.write

/**
 * A one-thread HTTP/1.1 server on 127.0.0.1 and a free port, for tests that must reach a real socket.
 * [reply] is written to each connection after its request is read; null holds the connection open
 * without answering, so a client's timeout is what ends it.
 */
internal class LocalServer(private val reply: String?) {
    private val fd: Int = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket failed" } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val held = mutableListOf<Int>()
    val port: Int

    init {
        memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            // 127.0.0.1 in network byte order; htonl is a C macro Kotlin/Native does not bind (arm64 is little-endian).
            address.sin_addr.s_addr = 0x0100007Fu
            check(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
            check(listen(fd, 8) == 0) { "listen failed" }
            val length = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(fd, address.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname failed" }
            port = address.sin_port.toInt().let { ((it and 0xFF) shl 8) or ((it shr 8) and 0xFF) }
        }
        scope.launch { serve() }
    }

    private fun serve() {
        while (true) {
            val connection = accept(fd, null, null)
            if (connection < 0) return
            memScoped {
                val buffer = allocArray<ByteVar>(4096)
                read(connection, buffer, 4096u)
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

    fun stop() {
        held.forEach { close(it) }
        close(fd)
        scope.cancel()
    }
}
