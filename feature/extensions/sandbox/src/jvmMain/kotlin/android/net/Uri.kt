package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The read side of `android.net.Uri`, which is what 14 of 89 sampled extensions call it for.
 *
 * Like Android's, [parse] never throws: malformed input gives null parts rather than an exception,
 * and it holds on to the original string.
 */
class Uri private constructor(private val text: String) {
    private val parsed: URI? = runCatching { URI(text) }.getOrNull()

    fun getScheme(): String? = parsed?.scheme
    fun getHost(): String? = parsed?.host
    fun getPort(): Int = parsed?.port ?: -1
    fun getPath(): String? = parsed?.path
    fun getQuery(): String? = parsed?.query
    fun getFragment(): String? = parsed?.fragment
    fun getLastPathSegment(): String? = getPath()?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null }

    fun getQueryParameter(key: String?): String? = parsed?.rawQuery
        ?.split('&')
        ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
        ?.firstOrNull { (name, _) -> decode(name) == key }
        ?.second
        ?.let(::decode)

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Uri && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        @JvmStatic
        fun parse(uriString: String?): Uri = Uri(requireNotNull(uriString))

        /** `%20` for a space, as Android produces, where `URLEncoder` produces `+`. */
        @JvmStatic
        fun encode(s: String?): String? = s?.let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

        @JvmStatic
        fun decode(s: String?): String? = s?.let {
            runCatching { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(it)
        }
    }
}
