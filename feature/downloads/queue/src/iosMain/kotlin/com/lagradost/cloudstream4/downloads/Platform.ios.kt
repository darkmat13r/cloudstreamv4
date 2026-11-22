package com.lagradost.cloudstream4.downloads

import platform.Foundation.NSString
import platform.Foundation.NSURLComponents
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping

/** Kotlin/Native keeps qualified class names, so a log line reads as the Mac's does. */
internal actual fun nameOf(e: Throwable): String = e::class.qualifiedName ?: "Throwable"

/**
 * `NSURLComponents`, which parses RFC 3986 and returns nil for what it cannot, as `java.net.URI` throws. A user or a
 * password, even an empty one, is user information.
 */
internal actual fun parseUrl(url: String): UrlParts? {
    val components = NSURLComponents.componentsWithString(url) ?: return null
    return UrlParts(
        scheme = components.scheme,
        host = components.percentEncodedHost,
        hasUserInfo = components.percentEncodedUser != null || components.percentEncodedPassword != null,
    )
}

/** Called as it is: nothing on iOS interrupts a thread, and the iPad's resolvers suspend, so a timeout cancels them. */
internal actual suspend fun <T> resolveInterruptibly(resolve: suspend () -> T): T = resolve()

internal actual fun isInterruption(e: Throwable): Boolean = false

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkc(text: String): String =
    (NSString.create(string = text)).precomposedStringWithCompatibilityMapping
