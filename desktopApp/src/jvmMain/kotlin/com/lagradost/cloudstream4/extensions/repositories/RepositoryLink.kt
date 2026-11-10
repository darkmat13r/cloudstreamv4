package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy

/** What a person typed into "Add repository", read without touching the network (`KMP-110`). */
sealed interface RepositoryLink {
    /** An https URL to fetch. */
    data class Https(val url: String) : RepositoryLink {
        /** Without the URL, which can carry a token: a log or an assertion message must not print it. */
        override fun toString(): String = "Https(url=<redacted>)"
    }

    /**
     * A short code, which Android resolves by asking a third-party URL shortener (`cutt.ly`, or `py.md` for `!code`).
     * The Mac does not: what someone types is not sent to a shortener, so the answer is "paste the full link".
     */
    data object Shortcode : RepositoryLink

    /** A URL that is not https, `http://` included. */
    data object NotHttps : RepositoryLink

    /** Nothing that could be a repository. */
    data object Invalid : RepositoryLink
}

/**
 * Reads [input] the way Android's `RepositoryManager.parseRepoUrl` does, minus the short codes.
 *
 * - `https://…` is used as typed.
 * - `cloudstreamrepo://<rest>` and `https://cs.repo/<rest>` (with or without a `?`) are links to `<rest>`, with
 *   `https://` added when `<rest>` has no scheme. Android fetches a `cs.repo` link as typed, because its first branch
 *   matches any `https://`; here it is unwrapped, since `cs.repo` is a share link, not a repository.
 * - `[A-Za-z0-9!_-]+` is a short code, [RepositoryLink.Shortcode].
 *
 * The result must then be https with a host, no user info, no control characters and at most [MAX_LINK_CHARS].
 */
fun parseRepositoryLink(input: String): RepositoryLink {
    val text = input.trim()
    if (text.isEmpty()) return RepositoryLink.Invalid
    val target = when {
        text.startsWith(APP_SCHEME, ignoreCase = true) -> withScheme(text.substring(APP_SCHEME.length))
        text.startsWith(SHARE_PREFIX, ignoreCase = true) -> withScheme(text.substring(SHARE_PREFIX.length).removePrefix("?"))
        HTTP_PREFIX.containsMatchIn(text) -> text
        SHORTCODE.matches(text) -> return RepositoryLink.Shortcode
        else -> return RepositoryLink.Invalid
    }
    if (target.length > MAX_LINK_CHARS || !SandboxPolicy.isHttpUrlShape(target)) return RepositoryLink.Invalid
    return if (target.startsWith("https://", ignoreCase = true)) RepositoryLink.Https(target) else RepositoryLink.NotHttps
}

private fun withScheme(rest: String): String = if (HTTP_PREFIX.containsMatchIn(rest)) rest else "https://$rest"

/** Measured 2026-11-10: the longest URL in 33 public repositories and their 583 plugins is 228 characters. */
internal const val MAX_LINK_CHARS = 2048

private const val APP_SCHEME = "cloudstreamrepo://"
private const val SHARE_PREFIX = "https://cs.repo/"
private val HTTP_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)
private val SHORTCODE = Regex("^[a-zA-Z0-9!_-]+$")
