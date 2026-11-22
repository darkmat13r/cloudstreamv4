package com.lagradost.cloudstream4.downloads

/**
 * What differs between the Mac and the iPad below the queue (`KMP-123`), other than files ([QueueDisk]). The JVM
 * actuals are exactly what the Mac ran before the move.
 */

/** An exception's class, for a log line: never its message, which can hold a URL or a path. */
internal expect fun nameOf(e: Throwable): String

/** The parts of an absolute URL that [DesktopDownloads.cleanUrl] checks, or null when it does not parse. */
internal class UrlParts(val scheme: String?, val host: String?, val hasUserInfo: Boolean)

/** `java.net.URI` on the JVM; `NSURLComponents` on iOS. */
internal expect fun parseUrl(url: String): UrlParts?

/**
 * Runs [resolve] so that a timeout or a pause stops it even when it blocks rather than suspends: on the JVM on a thread
 * of its own that is interrupted. On iOS a coroutine cannot interrupt a thread, so it is called as it is; the iPad's
 * resolvers suspend.
 */
internal expect suspend fun <T> resolveInterruptibly(resolve: suspend () -> T): T

/** Whether [e] is the interruption [resolveInterruptibly] causes: `InterruptedException` on the JVM, nothing on iOS. */
internal expect fun isInterruption(e: Throwable): Boolean

/** [text] in Unicode normalisation form NFKC. */
internal expect fun nfkc(text: String): String
