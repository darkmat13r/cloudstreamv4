package com.lagradost.cloudstream4.player.gateway

import java.net.IDN
import java.net.URI

/** Why a playlist was not rewritten. A kind only: nothing from the playlist goes with it (§4a). */
enum class RewriteFailure { TooLarge, TooManyLines, LineTooLong, TooManyAttributes, OutputTooLarge, Unreadable }

/** A rewritten playlist, or why there is none. */
sealed interface Rewrite {
    data class Done(val text: String) : Rewrite
    data class Failed(val kind: RewriteFailure) : Rewrite
}

/**
 * Rewrites an HLS playlist so every url in it is a gateway url (`KMP-113`, `docs/plans/media-gateway.md` §3).
 *
 * It is there for playback, not for safety: the media host can reach nothing but the gateway, so a url this misses
 * fails closed. That is why an unknown tag, a list that does not parse, and a url that is not http(s) all pass through
 * unchanged rather than being guessed at. What it must be is bounded, and every limit below is a failure, not a cut.
 */
class HlsRewriter(
    private val encode: (url: String, role: GatewayRole) -> String?,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxBytes: Int = 4 * MIB,
        val maxLines: Int = 100_000,
        val maxLineChars: Int = 16 * 1024,
        val maxAttributes: Int = 64,
        val maxOutputChars: Int = 8 * MIB,
    )

    /** [body], fetched from [base] (the final url, after redirects), rewritten. */
    fun rewrite(body: ByteArray, base: String): Rewrite {
        if (body.size > limits.maxBytes) return Rewrite.Failed(RewriteFailure.TooLarge)
        val baseUri = runCatching { withRootPath(URI(lenient(base))) }.getOrNull()
            ?: return Rewrite.Failed(RewriteFailure.Unreadable)
        val text = String(body, Charsets.UTF_8).removePrefix(BOM)
        val lines = text.split('\n')
        if (lines.size > limits.maxLines) return Rewrite.Failed(RewriteFailure.TooManyLines)
        val out = StringBuilder()
        var afterStreamInf = false
        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            if (line.length > limits.maxLineChars) return Rewrite.Failed(RewriteFailure.LineTooLong)
            val rewritten = when {
                line.isBlank() -> line
                line.startsWith("#") -> {
                    val tag = line.substringBefore(':')
                    if (tag == STREAM_INF) afterStreamInf = true
                    rewriteTag(line, tag, baseUri) ?: return Rewrite.Failed(RewriteFailure.TooManyAttributes)
                }
                else -> {
                    val role = if (afterStreamInf) GatewayRole.Playlist else GatewayRole.Media
                    afterStreamInf = false
                    rewriteUri(line.trim(), baseUri, role) ?: line
                }
            }
            if (rewritten.length > limits.maxLineChars) return Rewrite.Failed(RewriteFailure.OutputTooLarge)
            out.append(rewritten).append('\n')
            if (out.length > limits.maxOutputChars) return Rewrite.Failed(RewriteFailure.OutputTooLarge)
        }
        return Rewrite.Done(out.toString())
    }

    /** [line] with its url attributes rewritten; unchanged when the tag is unknown or its list does not parse. */
    private fun rewriteTag(line: String, tag: String, base: URI): String? {
        val attributes = URI_ATTRIBUTES[tag] ?: return line
        if (':' !in line) return line
        val list = line.substringAfter(':')
        val pairs = parseAttributes(list) ?: return line
        if (pairs.size > limits.maxAttributes) return null
        // A name twice is not a list this reads: which of two would libVLC take? Passed through unchanged.
        if (pairs.map { it.name }.toSet().size != pairs.size) return line
        val rebuilt = pairs.joinToString(",") { pair ->
            val role = attributes[pair.name]
            if (role == null || !pair.quoted) {
                pair.source
            } else {
                val rewritten = rewriteUri(pair.value, base, role)
                if (rewritten == null) pair.source else "${pair.name}=\"$rewritten\""
            }
        }
        return "$tag:$rebuilt"
    }

    /** [reference] resolved against [base] and encoded, or null to leave it as written (not http(s), or refused). */
    private fun rewriteUri(reference: String, base: URI, role: GatewayRole): String? {
        // Lenient first: real CDN urls carry `|`, `^`, `{}`, `[]` and spaces, which java.net.URI refuses (#348 review).
        val ref = runCatching { URI(lenient(reference)) }.getOrNull() ?: return null
        val scheme = ref.scheme?.lowercase()
        if (scheme != null && scheme !in HTTP) return null
        val resolved = runCatching { base.resolve(ref) }.getOrNull() ?: return null
        if (resolved.scheme?.lowercase() !in HTTP) return null
        val path = GatewayUrl.removeDotSegments(resolved.rawPath.orEmpty().ifEmpty { "/" }) ?: return null
        // Built as text: URI's own multi-part constructors quote every `%`, which would encode an encoded url twice.
        val authority = resolved.rawAuthority ?: return null
        val normalised = "${resolved.scheme}://$authority$path" + resolved.rawQuery?.let { "?$it" }.orEmpty()
        return encode(normalised, role)
    }

    private data class Attribute(val name: String, val value: String, val quoted: Boolean, val source: String)

    /** RFC 8216 §4.2: `NAME=value` pairs, commas outside quotes, quoted strings with no escapes. Null if it does not parse. */
    private fun parseAttributes(list: String): List<Attribute>? {
        val result = mutableListOf<Attribute>()
        var i = 0
        while (i < list.length) {
            val equals = list.indexOf('=', i)
            if (equals <= i) return null
            val name = list.substring(i, equals)
            if (!NAME.matches(name)) return null
            var j = equals + 1
            val quoted = j < list.length && list[j] == '"'
            val value: String
            if (quoted) {
                val close = list.indexOf('"', j + 1)
                if (close < 0) return null
                value = list.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = list.indexOf(',', j).let { if (it < 0) list.length else it }
                value = list.substring(j, comma)
                if ('"' in value) return null
                j = comma
            }
            result += Attribute(name, value, quoted, list.substring(i, j))
            if (j < list.length) {
                if (list[j] != ',') return null
                j++
                if (j == list.length) return null
            }
            i = j
            if (result.size > limits.maxAttributes) return result
        }
        return result
    }

    companion object {
        /**
         * [reference] with every character `java.net.URI` refuses percent-encoded, after its authority only, so an IPv6
         * host keeps its brackets. A `%` that does not start an escape becomes `%25`. Nothing already valid changes.
         */
        internal fun lenient(reference: String): String {
            val start = authorityEnd(reference)
            val out = StringBuilder(reference.length + 16).append(asciiAuthority(reference.substring(0, start)))
            var i = start
            while (i < reference.length) {
                val c = reference[i]
                val escape = c == '%' && i + 2 <= reference.lastIndex && reference[i + 1].isHex() && reference[i + 2].isHex()
                when {
                    escape -> out.append(c)
                    c != '%' && (c in ALLOWED) -> out.append(c)
                    else -> String(Character.toChars(reference.codePointAt(i))).toByteArray(Charsets.UTF_8)
                        .forEach { out.append('%').append("%02X".format(it.toInt() and 0xFF)) }
                }
                i += Character.charCount(reference.codePointAt(i))
            }
            return out.toString()
        }

        /**
         * [prefix] (`scheme://authority` or `//authority`) with a non-ASCII host as punycode, the way OkHttp would
         * write it; unchanged when it is ASCII already, or when the host does not convert.
         */
        private fun asciiAuthority(prefix: String): String {
            if (prefix.all { it.code < 0x80 }) return prefix
            val slashes = prefix.indexOf("//").takeIf { it >= 0 } ?: return prefix
            val head = prefix.substring(0, slashes + 2)
            val authority = prefix.substring(slashes + 2)
            val info = authority.substringBeforeLast('@', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it@" }
            val hostPort = authority.removePrefix(info)
            if (hostPort.startsWith("[")) return prefix
            val host = hostPort.substringBeforeLast(':').takeIf { ':' in hostPort } ?: hostPort
            val port = hostPort.removePrefix(host)
            val ascii = runCatching { IDN.toASCII(host) }.getOrNull() ?: return prefix
            return head + info + ascii + port
        }

        /** Where a reference's authority ends: after `scheme://host` or `//host`; 0 when it has none. */
        private fun authorityEnd(reference: String): Int {
            val scheme = SCHEME_PREFIX.find(reference)
            val from = when {
                scheme != null -> scheme.range.last + 1
                reference.startsWith("//") -> 2
                else -> return 0
            }
            val end = reference.indexOfAny(charArrayOf('/', '?', '#'), from)
            return if (end < 0) reference.length else end
        }

        /** A base with no path resolves as if its path were `/`. java.net.URI otherwise joins `host` and `a.ts`. */
        private fun withRootPath(base: URI): URI =
            if (base.rawPath.isNullOrEmpty() && base.rawAuthority != null) {
                URI("${base.scheme}://${base.rawAuthority}/" + base.rawQuery?.let { "?$it" }.orEmpty())
            } else {
                base
            }

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
        private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private const val ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#@!$&'()*+,;="

        private const val MIB = 1024 * 1024
        private const val BOM = "﻿"
        private const val STREAM_INF = "#EXT-X-STREAM-INF"
        private val HTTP = setOf("http", "https")
        private val NAME = Regex("[A-Z0-9-]+")

        /** The tags with a url attribute, exactly as RFC 8216 and its successors write them, and each url's role. */
        private val URI_ATTRIBUTES: Map<String, Map<String, GatewayRole>> = mapOf(
            "#EXT-X-MEDIA" to mapOf("URI" to GatewayRole.Playlist),
            "#EXT-X-I-FRAME-STREAM-INF" to mapOf("URI" to GatewayRole.Playlist),
            "#EXT-X-RENDITION-REPORT" to mapOf("URI" to GatewayRole.Playlist),
            "#EXT-X-KEY" to mapOf("URI" to GatewayRole.Media),
            "#EXT-X-SESSION-KEY" to mapOf("URI" to GatewayRole.Media),
            "#EXT-X-MAP" to mapOf("URI" to GatewayRole.Media),
            "#EXT-X-PART" to mapOf("URI" to GatewayRole.Media),
            "#EXT-X-PRELOAD-HINT" to mapOf("URI" to GatewayRole.Media),
            "#EXT-X-SESSION-DATA" to mapOf("URI" to GatewayRole.Media),
            "#EXT-X-CONTENT-STEERING" to mapOf("SERVER-URI" to GatewayRole.Media),
            "#EXT-X-DATERANGE" to mapOf("X-ASSET-URI" to GatewayRole.Playlist, "X-ASSET-LIST" to GatewayRole.Media),
        )
    }
}
