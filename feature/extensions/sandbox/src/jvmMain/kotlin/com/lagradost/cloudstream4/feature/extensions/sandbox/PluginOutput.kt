package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream4.feature.extensions.sandbox.PluginHost.withoutUrls
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A plugin's own text, for a developer's eyes only (`BUG-83`): what a plugin prints, logs or puts in an exception
 * message is untrusted, and can carry the URLs it fetches, the keys it uses and what was searched for.
 *
 * - Every url is cut to `scheme://host[:port]/<…>`; `file:` uris and user paths become `<path>` ([withoutUrls]).
 * - A path under the home directory written `~/…` becomes `<path>`.
 * - An address like `name@host.example` becomes `<email>`.
 * - An IPv4 literal, and an IPv6 literal with `::` or at least three colons, becomes `<ip>`.
 * - A run of 24 or more of `A-Z a-z 0-9 + / = _ -` (a token, base64, long hex) becomes `<…>`. A class name written
 *   with slashes (`android/view/View`) is such a run when it is long enough; with dots, as in a stack frame, it is not.
 *
 * The one copy of these rules: the plugin host's stderr log ([HostStderr.redactedLog]) and the extension check's
 * failure lines both use it.
 */
fun String.redactedPluginText(): String = withoutUrls()
    .replace(HOME_PATH, "<path>")
    .replace(EMAIL, "<email>")
    .replace(IPV4, "<ip>")
    .replace(IPV6, "<ip>")
    .replace(TOKEN, "<…>")

/**
 * [redactedPluginText], then at most [limit] characters: in that order, because a token or an address cut at the limit
 * first would be too short for its rule (`#335` review). A word cut by either cut goes whole, for the same reason.
 * Control characters go first, so a line break inside a token cannot split it into halves too short for the rule:
 * the halves join, and the whole is redacted. Joining two real words too is the safe direction.
 */
fun String.redactedWithin(limit: Int): String {
    val input = take(limit * INPUT_PER_OUTPUT)
    val whole = if (length > input.length) input.substringBeforeLast(' ', "") else input
    val redacted = whole.withoutControls().redactedPluginText()
    if (redacted.length <= limit) return redacted
    return redacted.take(limit - CUT_MARK.length).substringBeforeLast(' ', "") + CUT_MARK
}

/** Without its control characters, so the text on either side of one is read as one run. */
internal fun String.withoutControls(): String = filterNot { it.isISOControl() }

/** How much more text is read than kept: enough for redaction to shorten it, bounded so a huge message costs little. */
private const val INPUT_PER_OUTPUT = 4
private const val CUT_MARK = " <…>"

private val HOME_PATH = Regex("(?<![\\w.~/-])~/\\S*")
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
private val IPV4 = Regex("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])")

/** `::` somewhere, or at least three colons between hex groups. A time of day (`19:04:05`) has two and no `::`. */
private val IPV6 = Regex(
    "(?<![\\w:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![\\w:])"
        .let { candidate -> "(?=[0-9A-Fa-f:]*(?:::|(?:[0-9A-Fa-f]{0,4}:){3}))$candidate" }
)
private val TOKEN = Regex("[A-Za-z0-9+/=_-]{24,}")

/**
 * An exception's own name reads as words (`IllegalArgumentException`), and is often 24 letters or more. A run that
 * long in any other shape, as a forged class name could carry, is cut as a token is.
 */
private val WORDS = Regex("(?:[A-Z][a-z]+)+")
private const val TOKEN_LENGTH = 24

internal fun String.asReportedClass(): String = split('.').joinToString(".") { segment ->
    if (segment.length >= TOKEN_LENGTH && !WORDS.matches(segment)) "<…>" else segment
}

/**
 * A failure the plugin itself reported over the protocol (`BUG-83`): `SimpleName: message`, as the host sends it. The
 * message is redacted ([redactedPluginText]) before anything can read it. [reportedClass] is all a default output
 * may show of it.
 */
class PluginFailed(text: String) : IOException(text.redactedWithin(MAX_MESSAGE_CHARS)) {
    val reportedClass: String = text.substringBefore(": ")
        .takeIf { HostStderr.CLASS_NAME.matches(it) }?.asReportedClass() ?: "<class>"

    companion object {
        /** As long as a reported failure may be, on both sides of the protocol. */
        const val MAX_MESSAGE_CHARS = 400
    }
}

/**
 * What a plugin host wrote to stderr, kept so that none of the plugin's own text reaches a default output (`BUG-83`).
 *
 * - [summary] is fixed in shape: how many lines and bytes there were, and the class of the failure the host itself
 *   reported, when it did. It holds nothing else the plugin wrote.
 * - [redactedLog] is the plugin's own lines through [redactedPluginText], for `--check-extensions --verbose` and for
 *   tests. Nothing on a default path reads it.
 *
 * Text is taken a whole line at a time, so a url split between two reads is still one url. A line past
 * [MAX_LINE_BYTES] keeps only its words before the cut: a token or url in progress there goes with the rest.
 */
internal class HostStderr(private val maxLogChars: Int = MAX_LOG_CHARS) {
    private var bytes = 0L
    private var lines = 0
    private var failedClass: String? = null
    private val log = StringBuilder()
    private val line = ByteArrayOutputStream()
    private var cut = false

    @Synchronized
    fun feed(chunk: ByteArray, count: Int) {
        bytes += count
        for (index in 0 until count) {
            val byte = chunk[index]
            when {
                byte == NEWLINE -> endLine()
                line.size() < MAX_LINE_BYTES -> line.write(byte.toInt())
                else -> cut = true
            }
        }
    }

    /** The end of the stream: a last line without a newline still counts. */
    @Synchronized
    fun finish() {
        if (line.size() > 0 || cut) endLine()
    }

    val summary: String
        @Synchronized get() = buildString {
            append("stderr: $lines lines, $bytes bytes")
            failedClass?.let { append(" · failed: ${it.asReportedClass()}") }
        }

    val redactedLog: String
        @Synchronized get() = log.toString()

    private fun endLine() {
        lines++
        var text = line.toString(Charsets.UTF_8).removeSuffix("\r")
        line.reset()
        if (cut) {
            cut = false
            text = if (text.contains(' ')) text.substringBeforeLast(' ') + " <…>" else "<…>"
        }
        val marker = FAILURE_LINE.matchEntire(text)
        if (marker != null) {
            failedClass = marker.groupValues[1]
            return
        }
        if (log.length < maxLogChars) {
            log.append(text.withoutControls().redactedPluginText().take(maxLogChars - log.length))
            log.append('\n')
        }
    }

    companion object {
        /** What [PluginHost] writes when it fails: this, then a class name and nothing else on the line. */
        const val FAILURE_PREFIX = "plugin host: failed: "

        /** A class name as the JVM writes one. Anything else, including a forged line, is not a marker. */
        val CLASS_NAME = Regex("[A-Za-z0-9_.$]{1,128}")
        private val FAILURE_LINE = Regex(Regex.escape(FAILURE_PREFIX) + "(" + CLASS_NAME.pattern + ")")

        private const val NEWLINE = '\n'.code.toByte()
        private const val MAX_LINE_BYTES = 4096
        private const val MAX_LOG_CHARS = 16 * 1024
    }
}
