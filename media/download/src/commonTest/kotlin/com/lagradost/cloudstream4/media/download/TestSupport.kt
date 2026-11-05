package com.lagradost.cloudstream4.media.download

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspend block that never actually suspends, with no coroutines-test dependency (so it works
 * in `commonTest` on iOS). Fails the test if the block does not finish at once.
 */
internal fun <T> runSync(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "block suspended" }.getOrThrow()
}

internal fun bytesOf(text: String): ByteArray = text.encodeToByteArray()
