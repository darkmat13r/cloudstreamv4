package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile

/** A digest the finished file must match. Only what every platform can compute. */
enum class HashAlgorithm(internal val hexLength: Int) {
    SHA256(64),
    MD5(32),
}

/**
 * The hash the caller expects the finished file to have.
 *
 * [hex] is normalised to lower case. A string of the wrong length or with a non-hex character is
 * rejected here, so a typo cannot turn into a download that always fails its check at the end.
 */
class ExpectedHash(val algorithm: HashAlgorithm, hex: String) {
    val hex: String = hex.trim().lowercase()

    init {
        require(this.hex.length == algorithm.hexLength && this.hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "not a $algorithm digest"
        }
    }

    override fun equals(other: Any?): Boolean = other is ExpectedHash && other.algorithm == algorithm && other.hex == hex
    override fun hashCode(): Int = 31 * algorithm.hashCode() + hex.hashCode()
    override fun toString(): String = "ExpectedHash($algorithm, $hex)"
}

/**
 * Tuning for one download. The constructor clamps every value into the range the engine is tested for, so a caller cannot ask for 10 000 connections.
 *
 * Ranges: [parts] 1..[MAX_PARTS]; [maxPartFiles] [parts]..[MAX_PART_FILES] (hard cap, splits included);
 * [maxAttemptsPerPart] 1..20 (consecutive failures that delivered no new byte); backoff 1 ms..10 min
 * with the maximum never below the base; [stallTimeoutMillis] 100 ms..10 min; [maxRedirects] 0..10;
 * [progressIntervalMillis] 1 ms..10 s; [bufferBytes] 1 KiB..1 MiB.
 */
class DownloadOptions(
    parts: Int = 8,
    minPartBytes: Long = 1L * 1024 * 1024,
    maxPartFiles: Int = 64,
    maxAttemptsPerPart: Int = 5,
    backoffBaseMillis: Long = 500,
    backoffMaxMillis: Long = 8_000,
    stallTimeoutMillis: Long = 30_000,
    maxRedirects: Int = 5,
    val allowPrivateAddresses: Boolean = false,
    val rejectHtml: Boolean = true,
    progressIntervalMillis: Long = 250,
    bufferBytes: Int = 64 * 1024,
) {
    val parts: Int = parts.coerceIn(1, MAX_PARTS)
    val minPartBytes: Long = minPartBytes.coerceAtLeast(1L)
    val maxPartFiles: Int = maxPartFiles.coerceIn(this.parts, MAX_PART_FILES)
    val maxAttemptsPerPart: Int = maxAttemptsPerPart.coerceIn(1, 20)
    val backoffBaseMillis: Long = backoffBaseMillis.coerceIn(1L, TEN_MINUTES_MILLIS)
    val backoffMaxMillis: Long = backoffMaxMillis.coerceIn(this.backoffBaseMillis, TEN_MINUTES_MILLIS)
    val stallTimeoutMillis: Long = stallTimeoutMillis.coerceIn(100L, TEN_MINUTES_MILLIS)
    val maxRedirects: Int = maxRedirects.coerceIn(0, 10)
    val progressIntervalMillis: Long = progressIntervalMillis.coerceIn(1L, 10_000L)
    val bufferBytes: Int = bufferBytes.coerceIn(1024, 1024 * 1024)

    private fun fields(): List<Any> = listOf(
        this.parts, this.minPartBytes, this.maxPartFiles, this.maxAttemptsPerPart, this.backoffBaseMillis,
        this.backoffMaxMillis, this.stallTimeoutMillis, this.maxRedirects, allowPrivateAddresses, rejectHtml,
        this.progressIntervalMillis, this.bufferBytes,
    )

    override fun equals(other: Any?): Boolean = other is DownloadOptions && other.fields() == fields()
    override fun hashCode(): Int = fields().hashCode()
    override fun toString(): String = "DownloadOptions(${fields().joinToString()})"

    companion object {
        const val MAX_PARTS: Int = 32
        const val MAX_PART_FILES: Int = 128
        private const val TEN_MINUTES_MILLIS = 600_000L
    }
}

/**
 * One file to fetch. Not a data class, and [toString] says nothing about [url], headers or referer:
 * a signed URL is a credential.
 *
 * [headers] is what was passed after normalisation (rule 16), and already includes `Referer` when one
 * is to be sent, so the engine copies it onto a request as it is and then sets its own `Range`,
 * `If-Range` and `Accept-Encoding`.
 */
class DownloadRequest(
    val url: String,
    val folder: PlatformFile,
    val fileName: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    val expectedHash: ExpectedHash? = null,
    val overwrite: Boolean = false,
    val options: DownloadOptions = DownloadOptions(),
) {
    val headers: Map<String, String> = normalizeRequestHeaders(headers, referer)

    override fun toString(): String = "DownloadRequest(fileName=$fileName, overwrite=$overwrite)"
}

private val OWNED_HEADERS = setOf(
    "range", "if-range", "accept-encoding", "host", "content-length", "transfer-encoding", "connection",
)
private const val MAX_HEADERS = 64
private const val MAX_HEADER_NAME = 128
private const val MAX_HEADER_VALUE = 8192
private const val TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"

/**
 * Cleans caller headers so none can break the engine's own protocol or inject a second header line.
 *
 * Case-insensitive throughout: `Range`, `If-Range` and `Accept-Encoding` are the engine's, and
 * `Host`, `Content-Length`, `Transfer-Encoding` and `Connection` belong to the transport, so all seven
 * are dropped. A name that is not an HTTP token, a value with a control character (CR, LF, NUL, ...) or
 * a non-ASCII character (OkHttp throws on those) is dropped, as is a blank `Referer`. When two entries
 * differ only in case the later one wins. A non-blank [referer] argument beats any `Referer` header. At
 * most 64 headers are kept.
 */
internal fun normalizeRequestHeaders(headers: Map<String, String>, referer: String?): Map<String, String> {
    val byLowerName = LinkedHashMap<String, Pair<String, String>>()
    for ((name, rawValue) in headers) {
        if (!isHeaderName(name)) continue
        val lower = name.lowercase()
        if (lower in OWNED_HEADERS) continue
        val value = rawValue.trim(' ', '\t')
        if (!isHeaderValue(value)) continue
        byLowerName.remove(lower)
        byLowerName[lower] = name to value
    }
    val refererValue = referer?.trim(' ', '\t')?.takeIf { it.isNotEmpty() && isHeaderValue(it) }
    if (refererValue != null) {
        byLowerName.remove("referer")
        byLowerName["referer"] = "Referer" to refererValue
    } else {
        byLowerName["referer"]?.takeIf { it.second.isEmpty() }?.let { byLowerName.remove("referer") }
    }
    val result = LinkedHashMap<String, String>()
    for ((name, value) in byLowerName.values) {
        if (result.size >= MAX_HEADERS) break
        result[name] = value
    }
    return result
}

private fun isHeaderName(name: String): Boolean =
    name.isNotEmpty() && name.length <= MAX_HEADER_NAME &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_PUNCTUATION }

private fun isHeaderValue(value: String): Boolean =
    value.length <= MAX_HEADER_VALUE && value.all { it == '\t' || it in ' '..'~' }
