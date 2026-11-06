package com.lagradost.cloudstream4.media.download

/**
 * A parsed `Content-Range` response header. [start] and [endInclusive] are both `null` for the
 * unsatisfied form `bytes` followed by `*` and `/total`. [total] is `null` when the server wrote `*`.
 */
internal class ContentRange(val start: Long?, val endInclusive: Long?, val total: Long?)

private const val MAX_RANGE_DIGITS = 19

/** Decimal digits only, up to 19 of them and within [Long], else `null`. No sign, so no negative. */
private fun parseDigits(text: String): Long? {
    if (text.isEmpty() || text.length > MAX_RANGE_DIGITS || !text.all { it in '0'..'9' }) return null
    return text.toLongOrNull()
}

/**
 * `bytes a-b/c`, `bytes a-b/` plus a star, or `bytes` plus a star and `/c`, as `a`, `b` and `c` in [ContentRange]. Anything else is
 * `null`: another unit, a missing part, a sign, `a > b`, `b >= c` (a range cannot reach past the
 * total), a star for both, and any number too large for a [Long], which cannot overflow because it is
 * never added to.
 */
internal fun parseContentRange(value: String?): ContentRange? {
    val text = value?.trim() ?: return null
    if (text.length > 80 || !text.startsWith("bytes ", ignoreCase = true)) return null
    val spec = text.substring(6).trim()
    val slash = spec.indexOf('/')
    if (slash < 0) return null
    val range = spec.substring(0, slash)
    val totalText = spec.substring(slash + 1)
    val total = if (totalText == "*") null else parseDigits(totalText) ?: return null
    if (range == "*") return if (total == null) null else ContentRange(null, null, total)
    val dash = range.indexOf('-')
    if (dash < 0) return null
    val start = parseDigits(range.substring(0, dash)) ?: return null
    val end = parseDigits(range.substring(dash + 1)) ?: return null
    if (start > end) return null
    if (total != null && end >= total) return null
    return ContentRange(start, end, total)
}

/**
 * `Retry-After` as whole seconds, clamped to [maxSeconds], or `null` when it is missing or not a
 * plain number. The HTTP-date form is ignored: the engine has no wall clock it trusts, and a
 * server that wants a long wait gets the bounded backoff instead.
 */
internal fun parseRetryAfterSeconds(value: String?, maxSeconds: Long): Long? {
    val text = value?.trim() ?: return null
    if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
    if (text.length > MAX_RANGE_DIGITS) return maxSeconds
    return (text.toLongOrNull() ?: return maxSeconds).coerceAtMost(maxSeconds)
}

/**
 * An entity tag. [raw] is the header value as sent, `"abc"` or `W/"abc"`, and is what goes in
 * `If-Range` and the state file. A weak tag may be compared but never used in `If-Range`.
 */
internal class ETag(val raw: String, val strong: Boolean) {
    /** The tag without `W/` and the quotes: what weak comparison compares. */
    val opaque: String get() = raw.removePrefix("W/").removeSurrounding("\"")

    /** Weak comparison (RFC 9110 8.8.3.2): the opaque tags are equal, whatever their weakness. */
    fun weaklyMatches(other: ETag): Boolean = opaque == other.opaque

    /** Strong comparison: both strong and byte-identical. */
    fun stronglyMatches(other: ETag): Boolean = strong && other.strong && raw == other.raw
}

private const val MAX_ETAG_LENGTH = 256

/**
 * Classifies an `ETag` header value: strong (`"x"`), weak (`W/"x"`), or `null` when it is missing,
 * unquoted, over 256 characters, or holds a control character, a space, or a quote inside the tag.
 */
internal fun parseETag(value: String?): ETag? {
    val text = value?.trim() ?: return null
    if (text.isEmpty() || text.length > MAX_ETAG_LENGTH) return null
    val weak = text.startsWith("W/")
    val quoted = if (weak) text.substring(2) else text
    if (quoted.length < 2 || quoted.first() != '"' || quoted.last() != '"') return null
    val inner = quoted.substring(1, quoted.length - 1)
    if (inner.any { it <= ' ' || it == '"' || it == '\u007f' }) return null
    return ETag(text, strong = !weak)
}
