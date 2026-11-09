package com.lagradost.cloudstream4.search

/**
 * Whether [url] could carry userinfo (`user:pass@host`) to any parser that reads it (`BUG-78`). A stored poster with
 * one is refused: a poster never needs it, it would put a credential on disk, and it is where URL parsers disagree
 * about the host.
 *
 * Read the widest way, as the WHATWG parser does for http(s): every `/` and `\` after the scheme is skipped, and the
 * authority runs to the first `/`, `\`, `?` or `#`. An `@` anywhere in that is userinfo to someone. So
 * `http:///user@host/` counts, although `java.net.URI` reads it as a path. An `@` in the path, as in `a@2x.png`, does
 * not count.
 */
internal fun hasPosterUserInfo(url: String): Boolean {
    val colon = url.indexOf(':')
    if (colon < 0) return false
    val start = (colon + 1 until url.length).firstOrNull { url[it] != '/' && url[it] != '\\' } ?: return false
    val end = (start until url.length).firstOrNull { url[it] in AUTHORITY_ENDS } ?: url.length
    return '@' in url.substring(start, end)
}

private val AUTHORITY_ENDS = charArrayOf('/', '\\', '?', '#')
