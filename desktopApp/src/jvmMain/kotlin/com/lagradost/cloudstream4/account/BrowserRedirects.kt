package com.lagradost.cloudstream4.account

import kotlinx.coroutines.CompletableDeferred
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * `cloudstreamapp://` redirects coming back from the browser (`KMP-95e`, `DS-25` A10), accepted **only** as the rules
 * the owner and the reviewer set:
 *
 * - **Only while a sign-in from this window is waiting**: an unsolicited `cloudstreamapp://` URL does nothing.
 * - **Only for that sign-in's path** (`mallogin`), and **only with the `state` it sent**: random, compared in constant
 *   time. A missing or wrong state does nothing, and the sign-in keeps waiting for the right one.
 * - **Only the bare form** `cloudstreamapp://mallogin?…` (the reviewer of `#296`): a user part (`x@mallogin`), a port
 *   (`mallogin:99`) or a path (`mallogin/other`) is refused, so nothing dressed up as the path gets through.
 * - **Once**: the first accepted redirect ends the wait, and any after it does nothing.
 * - **Cancel ends the wait at once**, so a redirect that arrives late does nothing.
 * - **The URL is never logged or stored**. Only its query's values reach the sign-in, which keeps nothing but the
 *   token it trades them for.
 *
 * Thread-safe: macOS hands a URL over on the AWT thread, and a sign-in waits from the window's coroutines.
 */
internal class BrowserRedirects {

    /** A sign-in waiting for its redirect. [await] gives the redirect's query, without the state. */
    inner class Waiting internal constructor(val path: String, private val state: String) {
        internal val result = CompletableDeferred<Map<String, String>>()

        internal fun matches(candidate: String?): Boolean =
            candidate != null && MessageDigest.isEqual(candidate.toByteArray(), state.toByteArray())

        suspend fun await(): Map<String, String> = result.await()

        /** Ends the wait: nothing after this reaches it. */
        fun cancel() {
            synchronized(lock) { if (waiting === this) waiting = null }
            result.cancel()
        }

        override fun toString(): String = "Waiting(path=$path, state=<hidden>)"
    }

    private val lock = Any()
    private var waiting: Waiting? = null

    /** Starts waiting for [path]'s redirect carrying [state]. A sign-in already waiting is ended: one at a time. */
    fun expect(path: String, state: String): Waiting {
        val next = Waiting(path, state)
        val previous = synchronized(lock) { waiting.also { waiting = next } }
        previous?.result?.cancel()
        return next
    }

    /**
     * A URL macOS handed over. Returns whether it ended a wait. Anything else is dropped without a word: there is no
     * log a URL could reach.
     */
    fun deliver(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return false
        if (uri.rawUserInfo != null || uri.port != -1 || !uri.rawPath.isNullOrEmpty()) return false
        val query = parameters(uri.rawQuery) ?: return false
        val accepted = synchronized(lock) {
            val current = waiting ?: return false
            if (uri.host != current.path || !current.matches(query[STATE])) return false
            waiting = null
            current
        }
        return accepted.result.complete(query - STATE)
    }

    companion object {
        const val SCHEME = "cloudstreamapp"
        const val STATE = "state"

        /**
         * The query's values, each at most once; null for a query that repeats a name or cannot be read. Decoded as
         * RFC 3986 says a URI's query is (the reviewer of `#296`): `%XX` only, so a `+` stays a `+`. `URLDecoder` alone
         * is HTML form decoding, which would turn it into a space and spoil a code or a token carrying one.
         */
        internal fun parameters(raw: String?): Map<String, String>? {
            if (raw.isNullOrEmpty()) return emptyMap()
            if (raw.length > MAX_QUERY_CHARS) return null
            val pairs = raw.split('&').filter { it.isNotEmpty() }.map { part ->
                val name = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                runCatching { decode(name) to decode(value) }.getOrNull() ?: return null
            }
            // A name twice is someone guessing which one a parser keeps.
            if (pairs.map { it.first }.toSet().size != pairs.size) return null
            return pairs.toMap()
        }

        private fun decode(part: String): String = URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8)

        /** A fresh `state`: 32 bytes from [SecureRandom], base64url with no padding. */
        fun newState(): String = randomUrlSafe(STATE_BYTES)

        /**
         * A fresh PKCE verifier, as `AuthAPI.generateCodeVerifier` makes it (checked, `DS-25`): 96 bytes from
         * [SecureRandom], base64url with no padding, 128 characters. Never reused.
         */
        fun newVerifier(): String = randomUrlSafe(VERIFIER_BYTES)

        private val random = SecureRandom()

        private fun randomUrlSafe(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        private const val STATE_BYTES = 32
        private const val VERIFIER_BYTES = 96
        private const val MAX_QUERY_CHARS = 8 * 1024
    }
}

/**
 * Hands macOS's open-URL events to [redirects] (`KMP-95e`). Returns false where the platform has none, as off a Mac or
 * outside a packaged app; the browser's sign-ins are then not reachable, and nothing else changes.
 */
internal fun installRedirectHandler(redirects: BrowserRedirects): Boolean = runCatching {
    if (!java.awt.Desktop.isDesktopSupported()) return false
    val desktop = java.awt.Desktop.getDesktop()
    if (!desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) return false
    desktop.setOpenURIHandler { event -> redirects.deliver(event.uri.toString()) }
    true
}.getOrDefault(false)
