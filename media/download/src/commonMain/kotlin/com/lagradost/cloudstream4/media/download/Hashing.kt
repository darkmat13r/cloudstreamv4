package com.lagradost.cloudstream4.media.download

/**
 * A digest computed over bytes fed in order. One instance hashes one stream: after [finishHex] it is
 * spent. Not thread-safe.
 */
internal interface StreamingHash {
    /** Feeds `bytes[offset until offset + length]`. Arguments out of range throw [IndexOutOfBoundsException]. */
    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** The digest as lower-case hex. */
    fun finishHex(): String
}

internal fun StreamingHash.update(bytes: ByteArray) = update(bytes, 0, bytes.size)

/**
 * A new hash for [algorithm]: `MessageDigest` on Android and the JVM, CommonCrypto `CC_SHA256_*` and
 * `CC_MD5_*` on iOS.
 */
internal expect fun newStreamingHash(algorithm: HashAlgorithm): StreamingHash

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}
