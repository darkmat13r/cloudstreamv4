@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.lagradost.cloudstream4.media.download

import kotlinx.cinterop.Arena
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreCrypto.CC_MD5_CTX
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_MD5_Final
import platform.CoreCrypto.CC_MD5_Init
import platform.CoreCrypto.CC_MD5_Update
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.memset
import platform.posix.uint8_tVar

/** `attributesOfFileSystemForPath`, asked of the nearest existing ancestor. 0 is read as unknown. */
internal actual suspend fun platformAvailableBytes(path: String): Long? = withContext(Dispatchers.IO) {
    val manager = NSFileManager.defaultManager
    var candidate = path
    while (candidate.isNotEmpty() && !manager.fileExistsAtPath(candidate)) {
        val cut = candidate.trimEnd('/').lastIndexOf('/')
        candidate = when {
            cut > 0 -> candidate.substring(0, cut)
            cut == 0 -> "/"
            else -> ""
        }
    }
    if (candidate.isEmpty()) return@withContext null
    val attributes = manager.attributesOfFileSystemForPath(candidate, error = null) ?: return@withContext null
    (attributes[NSFileSystemFreeSize] as? Number)?.toLong()?.takeIf { it > 0L }
}

/**
 * `getaddrinfo` off the caller's thread, returning the raw address bytes (4 or 16) of every result.
 * The offsets are Darwin's `sockaddr_in.sin_addr` (4) and `sockaddr_in6.sin6_addr` (8).
 * Throws when the name does not resolve; the message carries no host.
 */
actual object SystemHostResolver : HostResolver {
    override suspend fun resolve(host: String): List<ByteArray> = withContext(Dispatchers.IO) {
        memScoped {
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            val result = alloc<CPointerVar<addrinfo>>()
            if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) throw IllegalStateException("name did not resolve")
            val addresses = ArrayList<ByteArray>()
            try {
                var node = result.value
                while (node != null) {
                    val info = node.pointed
                    val address = info.ai_addr?.reinterpret<ByteVar>()
                    if (address != null) {
                        when {
                            info.ai_family == AF_INET && info.ai_addrlen >= 8u ->
                                addresses += ByteArray(4) { address[4 + it] }
                            info.ai_family == AF_INET6 && info.ai_addrlen >= 24u ->
                                addresses += ByteArray(16) { address[8 + it] }
                        }
                    }
                    node = info.ai_next
                }
            } finally {
                freeaddrinfo(result.value)
            }
            addresses
        }
    }
}

private class Sha256Hash : StreamingHash {
    private val arena = Arena()
    private val context = arena.alloc<CC_SHA256_CTX>()
    private var finished = false

    init {
        CC_SHA256_Init(context.ptr)
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "hash already finished" }
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return
        bytes.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(offset), length.convert()) }
    }

    override fun finishHex(): String {
        check(!finished) { "hash already finished" }
        finished = true
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret<uint8_tVar>(), context.ptr) }
        arena.clear()
        return digest.toHex()
    }
}

private class Md5Hash : StreamingHash {
    private val arena = Arena()
    private val context = arena.alloc<CC_MD5_CTX>()
    private var finished = false

    init {
        CC_MD5_Init(context.ptr)
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "hash already finished" }
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return
        bytes.usePinned { CC_MD5_Update(context.ptr, it.addressOf(offset), length.convert()) }
    }

    override fun finishHex(): String {
        check(!finished) { "hash already finished" }
        finished = true
        val digest = ByteArray(CC_MD5_DIGEST_LENGTH)
        digest.usePinned { CC_MD5_Final(it.addressOf(0).reinterpret<uint8_tVar>(), context.ptr) }
        arena.clear()
        return digest.toHex()
    }
}

internal actual fun newStreamingHash(algorithm: HashAlgorithm): StreamingHash = when (algorithm) {
    HashAlgorithm.SHA256 -> Sha256Hash()
    HashAlgorithm.MD5 -> Md5Hash()
}
