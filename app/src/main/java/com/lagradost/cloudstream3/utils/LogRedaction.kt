package com.lagradost.cloudstream3.utils

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.SubtitleData

/*
 * BUG-43: what a log line may say about a stream, a subtitle or a failure.
 *
 * Logcat reaches bug reports, adb and any app holding READ_LOGS. A stream's URL, its headers and
 * its referer can carry cookies and auth tokens, and the `toString` of `ExtractorLink`,
 * `SubtitleFile` and `SubtitleData` prints all three. These helpers return only fields that carry
 * none of that: the provider's display name, the quality, the link type, the subtitle language,
 * the exception classes and the HTTP status. Never add a URL, a header, a referer or a path here.
 */

private const val MAX_CAUSES = 8

// BUG-44: one of three identical copies, which must stay in sync: `:app` `utils/LogRedaction.kt`,
// `:library` `mvvm/ArchComponentExt.kt` and `:feature:extensions:sandbox` `PluginHost.kt`.
// `:library` is the published API and cannot depend on an internal module, so they cannot share one.

/** `file:/…`, with any number of slashes, up to the next whitespace. */
private val FILE_URI = Regex("(?i)\\bfile:/\\S+")

/**
 * `scheme://[userinfo@]host[:port]`, then whatever follows up to the next whitespace. BUG-52: also
 * when whitespace or a Unicode space (`\p{Z}`, NBSP among them) splits `scheme:` from `//`. BUG-64: and when
 * up to two other characters do, such as a braille blank (U+2800), which a screen draws as nothing, or up to two
 * that are neither letters nor digits sit between the slashes.
 */
private val URL_IN_TEXT = Regex(
    "(?i)\\b([a-z][a-z0-9+.-]*):[\\s\\p{Z}]*(?:[^\\s\\p{Z}/][\\s\\p{Z}]*){0,2}/[\\s\\p{Z}]*(?:[^\\s\\p{Z}/\\p{L}\\p{N}][\\s\\p{Z}]*){0,2}/(?:[^\\s/?#@]*@)?([^\\s/?#@]*)(\\S*)"
)

/**
 * An absolute path under a storage, home or volume root, up to a newline, a quote, `": "` or the
 * end. A path may hold spaces, so it is not cut at whitespace: what follows a path on its line is
 * lost with it, which is the safe way round.
 */
private val USER_PATH = Regex(
    "(?<![\\w.:/-])/(?:storage|sdcard|data/user|mnt|Users|home|Volumes)(?:/[^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
)

/**
 * BUG-47: a Windows home path, `C:\Users\…` (any drive letter, either case, either slash), with the
 * same ends as [USER_PATH].
 */
private val WINDOWS_USER_PATH = Regex(
    "(?<![\\w.:/\\\\-])(?i:[a-z]):[\\\\/](?i:users)(?:[\\\\/][^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
)

/**
 * BUG-52: control and format characters (Unicode Cc and Cf: ZWSP, ZWJ, ZWNJ, BOM, bidi marks and the
 * rest) out before any pattern runs, since one inside `://` (`https:<ZWSP>//`) would hide a url from
 * [URL_IN_TEXT]. Line breaks stay, because they shape a stack trace. A tab becomes a space: a screen
 * that strips controls after redaction (Home, Detail, the HUD) would otherwise rejoin `https:<tab>//`.
 * BUG-64: then every code point Unicode calls default-ignorable, which Cc and Cf do not cover.
 */
private fun String.withoutHiddenCharacters(): String = replace('\t', ' ').filterNot { c ->
    c.category == CharCategory.FORMAT ||
        (c.category == CharCategory.CONTROL && c != '\n' && c != '\r')
}.withoutDefaultIgnorable()

/**
 * BUG-64: Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt), the code points a screen draws nothing
 * for. Cc and Cf miss some of them: U+034F, the variation selectors, the Hangul fillers. One of these inside `://` or
 * `/Users/` hides it from a pattern while the text reads the same. The same list is in every copy of this check.
 */
private fun isDefaultIgnorable(codePoint: Int): Boolean = when (codePoint) {
    0x00AD, 0x034F, 0x061C, 0x3164, 0xFEFF, 0xFFA0 -> true
    in 0x115F..0x1160, in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E,
    in 0x2060..0x206F, in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A,
    in 0xE0000..0xE0FFF -> true
    else -> false
}

/** Without [isDefaultIgnorable]'s code points, read a code point at a time, so a surrogate pair goes or stays whole. */
private fun String.withoutDefaultIgnorable(): String {
    val text = this
    return buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val codePoint =
                if (pair) 0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else text[i].code
            val end = if (pair) i + 2 else i + 1
            if (!isDefaultIgnorable(codePoint)) appendRange(text, i, end)
            i = end
        }
    }
}

/**
 * Text with a `file:` uri, a path under `/storage`, `/sdcard`, `/data/user`, `/mnt`, `/Users`, `/home`
 * or `/Volumes`, or a Windows `C:\Users\…` path replaced by `<path>`, and every other url cut to
 * `scheme://host[:port]/<…>`. The host is kept because it helps triage and is not a secret; the
 * userinfo, path, query and fragment are not.
 */
private fun String.withoutUrls(): String = this
    .withoutHiddenCharacters()
    .replace(FILE_URI, "<path>")
    .replace(URL_IN_TEXT) { match ->
        val (scheme, host, rest) = match.destructured
        "$scheme://$host" + if (rest.isEmpty()) "" else "/<…>"
    }
    .replace(USER_PATH, "<path>")
    .replace(WINDOWS_USER_PATH, "<path>")

/** BUG-44: [text] with its urls and user paths redacted (see `withoutUrls`). `null` stays `"null"`, as interpolation printed it. */
internal fun redactedText(text: String?): String = text?.withoutUrls() ?: "null"

/**
 * BUG-44: [throwable]'s stack trace, as `printStackTrace` and `Log.getStackTraceString` print it (class,
 * message, frames, `Caused by:` and `Suppressed:`), with the urls and user paths in every message redacted.
 * Use it where a throwable would otherwise go to the platform's `Log.x(tag, msg, throwable)`:
 * `Log.e(TAG, "$msg\n${redactedStackTrace(t)}")` prints the same lines.
 */
internal fun redactedStackTrace(throwable: Throwable): String =
    redactedText(throwable.stackTraceToString())

/** `source=<provider> quality=<q> type=<type>`, or `link=none`. */
internal fun redactedLinkInfo(link: ExtractorLink?): String =
    if (link == null) "link=none"
    else "source=${link.source} quality=${link.quality} type=${link.type}"

/** A downloaded file: says that it is local, never its URI or path. */
internal fun redactedLinkInfo(uri: ExtractorUri?): String =
    if (uri == null) "link=none" else "type=local"

/** The player's current selection: an online link, a downloaded file, or neither. */
internal fun redactedLinkInfo(selection: Pair<ExtractorLink?, ExtractorUri?>?): String = when {
    selection?.first != null -> redactedLinkInfo(selection.first)
    selection?.second != null -> redactedLinkInfo(selection.second)
    else -> "link=none"
}

/** `lang=<lang>`, or `subtitle=none`. */
internal fun redactedSubtitleInfo(file: SubtitleFile?): String =
    if (file == null) "subtitle=none" else "lang=${file.lang}"

/**
 * `origin=<origin> mime=<mime> lang=<code>`, or `subtitle=none`. Never the name: for a file picked
 * from the device it is the file name or the whole `content://` uri.
 */
internal fun redactedSubtitleInfo(subtitle: SubtitleData?): String =
    if (subtitle == null) "subtitle=none"
    else "origin=${subtitle.origin} mime=${subtitle.mimeType} lang=${subtitle.languageCode}"

/**
 * The cause chain as class names, outermost first: `a.B(ERROR_CODE_X) <- c.D(status=403)`.
 * Messages are left out on purpose, because an HTTP layer's message often quotes the URL.
 */
internal fun redactedThrowable(throwable: Throwable?): String {
    if (throwable == null) return "none"
    return generateSequence(throwable) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSES)
        .joinToString(" <- ") { describe(it) }
}

private fun describe(throwable: Throwable): String = when (throwable) {
    is PlaybackException -> "${throwable.javaClass.name}(${throwable.errorCodeName})"
    is HttpDataSource.InvalidResponseCodeException ->
        "${throwable.javaClass.name}(status=${throwable.responseCode})"
    else -> throwable.javaClass.name
}
