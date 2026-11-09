package com.lagradost.cloudstream3.utils

/*
 * BUG-63: a provider's failure, as one line a screen shows. Search's shortfall line, the detail page's failure and
 * the downloads page's failure all read `Resource.Failure.errorString`, which is a provider's own words.
 */

/**
 * [line] with its urls and user paths redacted, then without control or format characters, and at most [maxChars]
 * long. The caller picks the line.
 *
 * `redactedText` removes invisible characters before it looks for a url or a path (`BUG-52`, `BUG-64`), so one inside
 * `://` or `/Users/` cannot hide it, and the cleaning after it has nothing left to put back together.
 */
internal fun redactedReason(line: String, maxChars: Int): String =
    redactedText(line).filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(maxChars)
