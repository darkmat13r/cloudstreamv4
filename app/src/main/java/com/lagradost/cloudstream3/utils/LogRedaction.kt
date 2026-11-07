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

/** Any `scheme://…` up to the next whitespace. The same pattern as `:desktopApp`'s `withoutUrls`. */
private val URL_IN_TEXT = Regex("(?i)\\b[a-z][a-z0-9+.-]*://\\S+")

/** BUG-44: [text] with every `scheme://…` replaced by `<url>`. `null` stays `"null"`, as interpolation printed it. */
internal fun redactedText(text: String?): String = text?.replace(URL_IN_TEXT, "<url>") ?: "null"

/**
 * BUG-44: [throwable]'s stack trace, as `printStackTrace` and `Log.getStackTraceString` print it (class,
 * message, frames, `Caused by:` and `Suppressed:`), with every URL in a message replaced by `<url>`.
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
