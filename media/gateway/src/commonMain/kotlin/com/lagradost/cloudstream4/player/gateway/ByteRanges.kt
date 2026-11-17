package com.lagradost.cloudstream4.player.gateway

/**
 * The byte-range arithmetic of the iPad's gateway (`BUG-114`), on its own so it can be tested to the byte.
 *
 * The gateway fetches media upstream in chunks, each a short request through the checked client: an iOS session caps
 * every request at 60 s, and a session cannot be paused, so a long single fetch is either cut or held in memory. The
 * player still sees one response for the range it asked for. All ranges here are inclusive, as HTTP writes them.
 */
internal object ByteRanges {

    /** What the player asked for: [first] to [last] inclusive, `last` null for "to the end"; or the last [suffix] bytes. */
    data class Request(val first: Long?, val last: Long?, val suffix: Long?) {
        companion object {
            /** No `Range` header: the whole body. */
            val WHOLE = Request(first = 0, last = null, suffix = null)
        }
    }

    /**
     * A `Range` header with one byte range: `bytes=a-b`, `bytes=a-` or `bytes=-n`. Null for anything else (no header,
     * other units, several ranges, a malformed one): the whole body is served, as HTTP lets a server ignore a range.
     */
    fun parse(header: String?): Request? {
        val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return null
        if (',' in spec) return null
        val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (a.isEmpty()) {
            val n = b.toDigitsOrNull() ?: return null
            return if (n > 0) Request(first = null, last = null, suffix = n) else null
        }
        val first = a.toDigitsOrNull() ?: return null
        val last = if (b.isEmpty()) null else (b.toDigitsOrNull() ?: return null)
        if (last != null && last < first) return null
        return Request(first, last, suffix = null)
    }

    /** Where the first upstream chunk starts, before the total is known: the asked start, or 0 for a suffix range. */
    fun probeStart(request: Request): Long = request.first ?: 0

    /** What to serve, once the total is known. */
    sealed interface Plan {
        /** Bytes [first]..[last] of [total]; [partial] when the player asked for a range (a 206), else a 200. */
        data class Serve(val first: Long, val last: Long, val total: Long, val partial: Boolean) : Plan {
            val length: Long get() = if (total == 0L) 0 else last - first + 1
            val contentRange: String get() = "bytes $first-$last/$total"
        }

        /** The range starts at or past the end: a 416 with `Content-Range: bytes * /total`. */
        data class Unsatisfiable(val total: Long) : Plan {
            val contentRange: String get() = "bytes */$total"
        }
    }

    /** [request] against a body of [total] bytes. [ranged] is false when the player sent no usable `Range`. */
    fun plan(request: Request, total: Long, ranged: Boolean): Plan {
        if (!ranged) return Plan.Serve(first = 0, last = (total - 1).coerceAtLeast(0), total = total, partial = false)
        if (total == 0L) return Plan.Unsatisfiable(total)
        val suffix = request.suffix
        if (suffix != null) return Plan.Serve(first = maxOf(0, total - suffix), last = total - 1, total = total, partial = true)
        val first = request.first ?: 0
        if (first >= total) return Plan.Unsatisfiable(total)
        val last = minOf(request.last ?: (total - 1), total - 1)
        return Plan.Serve(first, last, total, partial = true)
    }

    /** The upstream `Range` header for the chunk of at most [size] bytes starting at [from], not past [last]. */
    fun chunk(from: Long, last: Long, size: Long): LongRange = from..minOf(from + size - 1, last)

    fun header(range: LongRange): String = "bytes=${range.first}-${range.last}"

    /** An upstream `Content-Range: bytes a-b/total`: its first, last and total (total null for `*`). Null if malformed. */
    data class Upstream(val first: Long, val last: Long, val total: Long?)

    fun parseContentRange(header: String?): Upstream? {
        val spec = header?.trim()?.takeIf { it.startsWith("bytes ", ignoreCase = true) }?.substring(6)?.trim() ?: return null
        val slash = spec.indexOf('/').takeIf { it > 0 } ?: return null
        val range = spec.substring(0, slash)
        val totalText = spec.substring(slash + 1)
        val total = if (totalText == "*") null else (totalText.toDigitsOrNull() ?: return null)
        val dash = range.indexOf('-').takeIf { it > 0 } ?: return null
        val first = range.substring(0, dash).toDigitsOrNull() ?: return null
        val last = range.substring(dash + 1).toDigitsOrNull() ?: return null
        if (last < first || (total != null && last >= total)) return null
        return Upstream(first, last, total)
    }

    /** Digits only (no sign, no space), as a non-negative Long; null when not, or too long to be one. */
    private fun String.toDigitsOrNull(): Long? =
        if (isEmpty() || length > 18 || any { it !in '0'..'9' }) null else toLong()
}
