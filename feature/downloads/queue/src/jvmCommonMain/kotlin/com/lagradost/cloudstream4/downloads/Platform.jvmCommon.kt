package com.lagradost.cloudstream4.downloads

import java.net.URI
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

internal actual fun nameOf(e: Throwable): String = e.javaClass.name

internal actual fun parseUrl(url: String): UrlParts? {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    return UrlParts(scheme = uri.scheme, host = uri.host, hasUserInfo = uri.rawUserInfo != null)
}

internal actual suspend fun <T> resolveInterruptibly(resolve: suspend () -> T): T =
    runInterruptible(Dispatchers.IO) { runBlocking { resolve() } }

internal actual fun isInterruption(e: Throwable): Boolean = e is InterruptedException

internal actual fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
