package com.lagradost.cloudstream4.player.gateway

/**
 * `java.net.URI`'s parser and its `resolve`, ported to common Kotlin for the iPad (`BUG-114`), where there is no JDK.
 *
 * It follows the JDK's `URI.Parser` (RFC 2396 with RFC 2732's IPv6 literals, and the JDK's documented deviations: an
 * empty authority before a path, an empty path) and `URI.resolve`/`normalize` (RFC 2396 §5.2, so `?q` resolves against
 * the base's directory, and a relative path is normalised, redundant slashes included). Only what [ParsedUri] reports
 * is kept: no fragment, no scheme-specific part.
 *
 * It lives in `commonMain`, not `iosMain`, so that `PortableUriParityTest` can hold it to `java.net.URI` itself on the
 * JVM, over generated input. That test is what says this port is faithful.
 */
internal object PortableUri {

    /** `URI(text)`, or null where `java.net.URI` would throw. */
    fun parse(text: String): ParsedUri? = Parser(text).parse()?.toParsed()

    /** `URI(base).resolve(URI(reference))`, or null where either does not parse. */
    fun resolve(base: String, reference: String): ParsedUri? {
        val b = Parser(base).parse() ?: return null
        val c = Parser(reference).parse() ?: return null
        return resolve(b, c).toParsed()
    }

    /** The fields of one parsed URI, as `java.net.URI` holds them. */
    private class Parts(
        var scheme: String? = null,
        var opaque: Boolean = false,
        var authority: String? = null,
        var userInfo: String? = null,
        var host: String? = null,
        var port: Int = -1,
        var path: String? = null,
        var query: String? = null,
        var fragment: String? = null,
    ) {
        fun toParsed() = ParsedUri(scheme, userInfo, host, port, authority, path, query)
    }

    private class Fail : Exception()

    /** `URI.Parser`, for parsing only (no server-authority requirement: `URI(String)`). */
    private class Parser(private val input: String) {
        private val parts = Parts()
        private var ipv6Bytes = 0

        fun parse(): Parts? = try {
            parseAll(); parts
        } catch (_: Fail) {
            null
        }

        private fun fail(): Nothing = throw Fail()

        private fun at(start: Int, end: Int, c: Char) = start < end && input[start] == c
        private fun at(start: Int, end: Int, s: String): Boolean {
            if (start + s.length > end) return false
            for (i in s.indices) if (input[start + i] != s[i]) return false
            return true
        }

        /** `scan(start, end, c)`: one char. */
        private fun scan(start: Int, end: Int, c: Char): Int = if (start < end && input[start] == c) start + 1 else start

        /** `scan(start, end, err, stop)`: -1 at a char in [err], else the index of the first char in [stop], or end. */
        private fun scan(start: Int, end: Int, err: String, stop: String): Int {
            var p = start
            while (p < end) {
                val c = input[p]
                if (err.indexOf(c) >= 0) return -1
                if (stop.indexOf(c) >= 0) break
                p++
            }
            return p
        }

        /** `scan(start, end, lowMask, highMask)`: chars in [cls], and escapes or "other" chars when it allows them. */
        private fun scan(start: Int, end: Int, cls: Cls): Int {
            var p = start
            while (p < end) {
                val c = input[p]
                if (cls.matches(c)) {
                    p++; continue
                }
                if (cls.escaped) {
                    val q = scanEscape(p, end, c)
                    if (q > p) {
                        p = q; continue
                    }
                }
                break
            }
            return p
        }

        private fun scanEscape(start: Int, end: Int, c: Char): Int {
            if (c == '%') {
                if (start + 3 <= end && HEX.matches(input[start + 1]) && HEX.matches(input[start + 2])) return start + 3
                fail()
            } else if (c.code > 128 && !isSpaceChar(c) && !c.isISOControl()) {
                return start + 1
            }
            return start
        }

        private fun checkChars(start: Int, end: Int, cls: Cls) {
            if (scan(start, end, cls) < end) fail()
        }

        private fun parseAll() {
            val n = input.length
            var p = scan(0, n, "/?#", ":")
            if (p >= 0 && at(p, n, ':')) {
                if (p == 0) fail()
                if (!ALPHA.matches(input[0])) fail()
                checkChars(1, p, SCHEME)
                parts.scheme = input.substring(0, p)
                p++
                if (at(p, n, '/')) {
                    p = parseHierarchical(p, n)
                } else {
                    val q = scan(p, n, "", "#")
                    if (q <= p) fail()
                    checkChars(p, q, URIC)
                    parts.opaque = true
                    p = q
                }
            } else {
                p = parseHierarchical(0, n)
            }
            if (at(p, n, '#')) {
                checkChars(p + 1, n, URIC)
                parts.fragment = input.substring(p + 1, n)
                p = n
            }
            if (p < n) fail()
        }

        private fun parseHierarchical(start: Int, n: Int): Int {
            var p = start
            if (at(p, n, '/') && at(p + 1, n, '/')) {
                p += 2
                val q = scan(p, n, "", "/?#")
                if (q > p) {
                    p = parseAuthority(p, q)
                } else if (q >= n) {
                    fail()
                }
                // else: an empty authority before a path, query or fragment (a JDK deviation)
            }
            var q = scan(p, n, "", "?#")
            checkChars(p, q, PATH)
            parts.path = input.substring(p, q)
            p = q
            if (at(p, n, '?')) {
                p++
                q = scan(p, n, "", "#")
                checkChars(p, q, URIC)
                parts.query = input.substring(p, q)
                p = q
            }
            return p
        }

        private fun parseAuthority(start: Int, n: Int): Int {
            val p = start
            var q = p
            val serverChars = if (scan(p, n, "", "]") > p) scan(p, n, SERVER_PERCENT) == n else scan(p, n, SERVER) == n
            val regChars = scan(p, n, REG_NAME) == n
            if (regChars && !serverChars) {
                parts.authority = input.substring(p, n)
                return n
            }
            if (serverChars) {
                try {
                    q = parseServer(p, n)
                    if (q < n) fail()
                    parts.authority = input.substring(p, n)
                } catch (_: Fail) {
                    parts.userInfo = null
                    parts.host = null
                    parts.port = -1
                    q = p
                }
            }
            if (q < n) {
                if (regChars) parts.authority = input.substring(p, n)
                else fail()
            }
            return n
        }

        private fun parseServer(start: Int, n: Int): Int {
            var p = start
            var q = scan(p, n, "/?#", "@")
            if (q >= p && at(q, n, '@')) {
                checkChars(p, q, USERINFO)
                parts.userInfo = input.substring(p, q)
                p = q + 1
            }
            if (at(p, n, '[')) {
                p++
                q = scan(p, n, "/?#", "]")
                if (q > p && at(q, n, ']')) {
                    val r = scan(p, q, "", "%")
                    if (r > p) {
                        parseIPv6Reference(p, r)
                        if (r + 1 == q) fail()
                        checkChars(r + 1, q, SCOPE_ID)
                    } else {
                        parseIPv6Reference(p, q)
                    }
                    parts.host = input.substring(p - 1, q + 1)
                    p = q + 1
                } else {
                    fail()
                }
            } else {
                q = parseIPv4Address(p, n)
                if (q <= p) q = parseHostname(p, n)
                p = q
            }
            if (at(p, n, ':')) {
                p++
                q = scan(p, n, DIGIT)
                if (q > p) {
                    parts.port = input.substring(p, q).toIntOrNull() ?: fail()
                    p = q
                }
            }
            if (p < n) fail()
            return p
        }

        /** `parseIPv4Address`: the end of an IPv4 address at [start], or -1. */
        private fun parseIPv4Address(start: Int, n: Int): Int {
            var p = try {
                scanIPv4Address(start, n, false)
            } catch (_: Fail) {
                return -1
            }
            if (p > start && p < n && input[p] != ':') p = -1
            if (p > start) parts.host = input.substring(start, p)
            return p
        }

        private fun scanIPv4Address(start: Int, n: Int, strict: Boolean): Int {
            var p = start
            var q: Int
            val m = scan(p, n, DIGIT_DOT)
            if (m <= p || (strict && m != n)) return -1
            while (true) {
                q = scanByte(p, m); if (q <= p) break; p = q
                q = scan(p, m, '.'); if (q <= p) break; p = q
                q = scanByte(p, m); if (q <= p) break; p = q
                q = scan(p, m, '.'); if (q <= p) break; p = q
                q = scanByte(p, m); if (q <= p) break; p = q
                q = scan(p, m, '.'); if (q <= p) break; p = q
                q = scanByte(p, m); if (q <= p) break; p = q
                if (q < m) break
                return q
            }
            fail()
        }

        /** `scanByte`: the end of up to all digits at [start], if they are at most 255; [start] if more. */
        private fun scanByte(start: Int, n: Int): Int {
            val q = scan(start, n, DIGIT)
            if (q <= start) return q
            // Integer.parseInt: a run too long for an int throws, which parseIPv4Address turns into "not an address".
            val value = input.substring(start, q).toIntOrNull() ?: fail()
            return if (value > 255) start else q
        }

        private fun takeIPv4Address(start: Int, n: Int): Int {
            val p = scanIPv4Address(start, n, true)
            if (p <= start) fail()
            return p
        }

        private fun parseIPv6Reference(start: Int, n: Int): Int {
            var p = start
            var compressedZeros = false
            val q = scanHexSeq(p, n)
            if (q > p) {
                p = q
                if (at(p, n, "::")) {
                    compressedZeros = true
                    p = scanHexPost(p + 2, n)
                } else if (at(p, n, ':')) {
                    p = takeIPv4Address(p + 1, n)
                    ipv6Bytes += 4
                }
            } else if (at(p, n, "::")) {
                compressedZeros = true
                p = scanHexPost(p + 2, n)
            }
            if (p < n) fail()
            if (ipv6Bytes > 16) fail()
            if (!compressedZeros && ipv6Bytes < 16) fail()
            if (compressedZeros && ipv6Bytes == 16) fail()
            return p
        }

        private fun scanHexPost(start: Int, n: Int): Int {
            var p = start
            if (p == n) return p
            val q = scanHexSeq(p, n)
            if (q > p) {
                p = q
                if (at(p, n, ':')) {
                    p++
                    p = takeIPv4Address(p, n)
                    ipv6Bytes += 4
                }
            } else {
                p = takeIPv4Address(p, n)
                ipv6Bytes += 4
            }
            return p
        }

        private fun scanHexSeq(start: Int, n: Int): Int {
            var p = start
            var q = scan(p, n, HEX)
            if (q <= p) return -1
            if (at(q, n, '.')) return -1
            if (q > p + 4) fail()
            ipv6Bytes += 2
            p = q
            while (p < n) {
                if (!at(p, n, ':')) break
                if (at(p + 1, n, ':')) break
                p++
                q = scan(p, n, HEX)
                if (q <= p) fail()
                if (at(q, n, '.')) {
                    p--; break
                }
                if (q > p + 4) fail()
                ipv6Bytes += 2
                p = q
            }
            return p
        }

        private fun parseHostname(start: Int, n: Int): Int {
            var p = start
            var q: Int
            var l = -1
            do {
                q = scan(p, n, ALPHANUM)
                if (q <= p) break
                l = p
                p = q
                q = scan(p, n, ALPHANUM_DASH)
                if (q > p) {
                    if (input[q - 1] == '-') fail()
                    p = q
                }
                q = scan(p, n, '.')
                if (q <= p) break
                p = q
            } while (p < n)
            if (p < n && !at(p, n, ':')) fail()
            if (l < 0) fail()
            if (l > start && !ALPHA.matches(input[l])) fail()
            parts.host = input.substring(start, p)
            return p
        }
    }

    // ---- resolve and normalize, as `java.net.URI` does them ----

    private fun resolve(base: Parts, child: Parts): Parts {
        if (child.opaque || base.opaque) return child
        if (child.scheme == null && child.authority == null && child.path.isNullOrEmpty() &&
            child.fragment != null && child.query == null
        ) {
            if (base.fragment != null && child.fragment == base.fragment) return base
            return Parts(
                scheme = base.scheme, authority = base.authority, userInfo = base.userInfo, host = base.host,
                port = base.port, path = base.path, fragment = child.fragment, query = base.query,
            )
        }
        if (child.scheme != null) return child
        val ru = Parts(scheme = base.scheme, query = child.query, fragment = child.fragment)
        if (child.authority == null) {
            ru.authority = base.authority
            ru.host = base.host
            ru.userInfo = base.userInfo
            ru.port = base.port
            val cp = child.path.orEmpty()
            ru.path = if (cp.isNotEmpty() && cp[0] == '/') child.path else resolvePath(base.path.orEmpty(), cp, base.scheme != null)
        } else {
            ru.authority = child.authority
            ru.host = child.host
            ru.userInfo = child.userInfo
            ru.port = child.port
            ru.path = child.path
        }
        return ru
    }

    private fun resolvePath(base: String, child: String, absolute: Boolean): String {
        val i = base.lastIndexOf('/')
        val path = if (child.isEmpty()) {
            if (i >= 0) base.substring(0, i + 1) else ""
        } else if (i >= 0 || !absolute) {
            base.substring(0, i + 1) + child
        } else {
            "/$child"
        }
        return normalize(path)
    }

    /**
     * `URI.normalize` on a path: "." segments removed, a segment followed by ".." removed with it, redundant slashes
     * collapsed, a leading ".." kept. A path the JDK finds already normal comes back as it was. The JDK's leading "./"
     * for a relative path whose first segment holds a colon is not needed here: the gateway only resolves against an
     * absolute path.
     */
    private fun normalize(path: String): String {
        if (!needsNormalization(path)) return path
        val absolute = path.startsWith("/")
        // Each segment, and whether a slash followed it.
        val segments = mutableListOf<Pair<String, Boolean>>()
        var p = 0
        while (p < path.length && path[p] == '/') p++
        while (p < path.length) {
            val start = p
            while (p < path.length && path[p] != '/') p++
            val slash = p < path.length
            segments += path.substring(start, p) to slash
            while (p < path.length && path[p] == '/') p++
        }
        val kept = ArrayDeque<Pair<String, Boolean>>()
        for (segment in segments) {
            when (segment.first) {
                "." -> Unit
                ".." -> if (kept.isNotEmpty() && kept.last().first != "..") kept.removeLast() else kept.addLast(segment)
                else -> kept.addLast(segment)
            }
        }
        val out = StringBuilder()
        if (absolute) out.append('/')
        for ((name, slash) in kept) {
            out.append(name)
            if (slash) out.append('/')
        }
        return out.toString()
    }

    /** `URI.needsNormalization`: a redundant slash, a "." or a ".." segment. */
    private fun needsNormalization(path: String): Boolean {
        val end = path.length - 1
        var p = 0
        while (p <= end && path[p] == '/') p++
        if (p > 1) return true
        while (p <= end) {
            if (path[p] == '.' && (p == end || path[p + 1] == '/' || (path[p + 1] == '.' && (p + 1 == end || path[p + 2] == '/')))) {
                return true
            }
            while (p <= end) {
                if (path[p++] != '/') continue
                if (p <= end && path[p] == '/') return true
                break
            }
        }
        return false
    }

    // ---- character classes, as `java.net.URI` defines them ----

    private class Cls(val chars: String, val escaped: Boolean = false) {
        private val set = BooleanArray(128).also { a -> chars.forEach { a[it.code] = true } }
        fun matches(c: Char) = c.code < 128 && set[c.code]
    }

    private const val DIGITS = "0123456789"
    private const val ALPHAS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val MARK = "-_.!~*'()"
    private const val UNRESERVED = DIGITS + ALPHAS + MARK
    private const val RESERVED = ";/?:@&=+$,[]"

    private val DIGIT = Cls(DIGITS)
    private val DIGIT_DOT = Cls("$DIGITS.")
    private val ALPHA = Cls(ALPHAS)
    private val ALPHANUM = Cls(DIGITS + ALPHAS)
    private val ALPHANUM_DASH = Cls("$DIGITS$ALPHAS-")
    private val HEX = Cls("${DIGITS}abcdefABCDEF")
    private val SCHEME = Cls("$DIGITS$ALPHAS+-.")
    private val URIC = Cls(RESERVED + UNRESERVED, escaped = true)
    private val PATH = Cls("$UNRESERVED:@&=+$,;/", escaped = true)
    private val USERINFO = Cls("$UNRESERVED;:&=+$,", escaped = true)
    private val REG_NAME = Cls("$UNRESERVED$,;:@&=+", escaped = true)
    private val SERVER = Cls("$UNRESERVED;:&=+$,$DIGITS$ALPHAS-.:@[]", escaped = true)
    private val SERVER_PERCENT = Cls("$UNRESERVED;:&=+$,$DIGITS$ALPHAS-.:@[]%", escaped = true)
    private val SCOPE_ID = Cls("$DIGITS${ALPHAS}_.")

    /** `Character.isSpaceChar`: a Unicode space, line or paragraph separator. */
    private fun isSpaceChar(c: Char) = c.category == CharCategory.SPACE_SEPARATOR ||
        c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR
}
