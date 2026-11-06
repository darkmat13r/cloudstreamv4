package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

/**
 * `File.usableSpace`, which reports 0 for a path that does not exist and for an I/O error alike, so
 * the nearest existing ancestor is asked and 0 is read as unknown. A volume that really is full shows
 * up as `StorageFull` on the first write instead.
 */
internal actual suspend fun platformAvailableBytes(path: String): Long? = withContext(Dispatchers.IO) {
    var directory: File? = File(path)
    while (directory != null && !directory.exists()) directory = directory.parentFile
    directory?.usableSpace?.takeIf { it > 0L }
}

/**
 * `File.renameTo`, which is POSIX `rename(2)` on Android and Linux: atomic within one volume, and it
 * replaces a file already at [to]. It only reports `false`, so that becomes an `IOException` with
 * [unsupported] as its cause. The message names no path: paths hold user folder and file names.
 */
internal actual fun renameFallback(from: PlatformFile, to: PlatformFile, unsupported: UnsupportedOperationException) {
    if (!File(from.path).renameTo(File(to.path))) {
        throw IOException("rename failed where an atomic move was not supported", unsupported)
    }
}

/** `InetAddress.getAllByName` off the caller's thread. Throws when the name does not resolve. */
actual object SystemHostResolver : HostResolver {
    override suspend fun resolve(host: String): List<ByteArray> = withContext(Dispatchers.IO) {
        InetAddress.getAllByName(host).map { it.address }
    }
}

private class JdkStreamingHash(algorithm: HashAlgorithm) : StreamingHash {
    private val digest = MessageDigest.getInstance(
        when (algorithm) {
            HashAlgorithm.SHA256 -> "SHA-256"
            HashAlgorithm.MD5 -> "MD5"
        },
    )

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        digest.update(bytes, offset, length)
    }

    override fun finishHex(): String = digest.digest().toHex()
}

internal actual fun newStreamingHash(algorithm: HashAlgorithm): StreamingHash = JdkStreamingHash(algorithm)
