package com.lagradost.cloudstream4.media.download

import com.lagradost.cloudstream4.media.model.DownloadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * How an attempt ended badly, as exceptions the engine throws and catches itself. None carries a
 * message or a cause: a network exception's text can hold the URL, and the URL is a credential.
 */
internal sealed class EngineException : RuntimeException()

/** No retry will change this: the download ends with [failure]. */
internal class FatalFailure(val failure: DownloadFailure) : EngineException()

/** A read, a connect or a server answer worth another attempt. [failure] is what to report if the attempts run out. */
internal class TransientFailure(
    val failure: DownloadFailure = DownloadFailure.NetworkLost,
    val retryAfterMillis: Long? = null,
    /** 429 or 503: another worker may be holding the host's connection allowance. */
    val throttled: Boolean = false,
) : EngineException()

/** A write, flush, open or move failed. Never retried (rule 8). [outOfSpace] is whether the error looks like a full disk. */
internal class StorageFailure(val outOfSpace: Boolean) : EngineException()

/** A 200 where a range was asked for: the server stopped honouring ranges (rule 5). */
internal class RangesIgnored : EngineException()

/**
 * Runs blocking storage work off the caller's thread and files whatever it throws as a
 * [StorageFailure]. Cancellation and the engine's own exceptions pass through untouched.
 */
internal suspend fun <T> storageCall(block: suspend () -> T): T = try {
    withContext(Dispatchers.IO) { block() }
} catch (e: CancellationException) {
    throw e
} catch (e: EngineException) {
    throw e
} catch (e: Throwable) {
    throw StorageFailure(looksLikeOutOfSpace(e))
}

private val OUT_OF_SPACE_WORDS = listOf("no space", "space left", "disk full", "quota", "enospc", "not enough space", "storage full")

internal fun looksLikeOutOfSpace(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 8) {
        val text = current.message?.lowercase()
        if (text != null && OUT_OF_SPACE_WORDS.any { it in text }) return true
        current = current.cause
        depth++
    }
    return false
}

/** Exponential backoff with jitter, never below a bounded `Retry-After`. [failures] is the count so far, at least 1. */
internal fun backoffMillis(failures: Int, retryAfterMillis: Long?, options: DownloadOptions): Long {
    val shift = (failures - 1).coerceIn(0, 20)
    val cap = (options.backoffBaseMillis shl shift).coerceAtMost(options.backoffMaxMillis)
    val jittered = Random.nextLong(cap / 2, cap + 1)
    val requested = retryAfterMillis?.coerceAtMost(options.backoffMaxMillis) ?: 0L
    return maxOf(jittered, requested)
}
