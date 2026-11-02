package android.util

import java.util.Base64 as JavaBase64

/**
 * `android.util.Base64` with its flags, over the JDK's.
 *
 * The defaults differ and the difference is silent: Android's `DEFAULT` wraps at 76 columns and ends
 * with a newline, and its decoder takes either alphabet's padding loosely. Extensions decode stream
 * URLs with this, so a stricter decoder would turn working links into exceptions.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private const val LINE_LENGTH = 76

    @JvmStatic
    fun decode(str: String?, flags: Int): ByteArray =
        decode(requireNotNull(str).toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic
    fun decode(input: ByteArray?, flags: Int): ByteArray {
        val text = String(requireNotNull(input), Charsets.US_ASCII)
        val standard = if (flags and URL_SAFE != 0) text.replace('-', '+').replace('_', '/') else text
        // The MIME decoder is the JDK's lenient one: it skips line breaks and accepts no padding.
        return JavaBase64.getMimeDecoder().decode(standard.toByteArray(Charsets.US_ASCII))
    }

    @JvmStatic
    fun encode(input: ByteArray?, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun encodeToString(input: ByteArray?, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) JavaBase64.getUrlEncoder() else JavaBase64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val flat = encoder.encodeToString(requireNotNull(input))
        if (flags and NO_WRAP != 0) return flat
        val lineEnd = if (flags and CRLF != 0) "\r\n" else "\n"
        return flat.chunked(LINE_LENGTH).joinToString("") { it + lineEnd }
    }
}
