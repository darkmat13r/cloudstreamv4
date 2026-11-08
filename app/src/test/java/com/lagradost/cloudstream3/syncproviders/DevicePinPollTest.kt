package com.lagradost.cloudstream3.syncproviders

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/**
 * `BUG-49`: a device-code pairing the user cancelled must never sign in. The fixture code is fake, and nothing is saved
 * anywhere: "saved" is a flag standing for `AuthRepo.setupLogin`, set only when `stillWanted` says yes, exactly where
 * the real overload saves.
 */
class DevicePinPollTest {

    private fun pin(expiresIn: Int = 60, interval: Int = 1) =
        AuthPinData(deviceCode = "fixture-device", userCode = "FAKE00", verificationUrl = "https://example.invalid/pin",
            expiresIn = expiresIn, interval = interval)

    private class Outcome {
        val signedIn = AtomicInteger()
        val expired = AtomicInteger()
        val errors = AtomicInteger()
    }

    private fun DevicePinPoll.startIn(scope: CoroutineScope, outcome: Outcome) = start(
        scope = scope, ui = EmptyCoroutineContext, io = Dispatchers.IO,
        onTick = {}, onSignedIn = { outcome.signedIn.incrementAndGet() },
        onExpired = { outcome.expired.incrementAndGet() }, onError = { outcome.errors.incrementAndGet() },
    )

    /** The bug itself: the code is entered while a poll is out, the user has cancelled by the time it answers. */
    @Test
    fun aPollInFlightWhenCancelledSavesNothing() = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        val codeEntered = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Boolean>()
        var saved = false
        val poll = DevicePinPoll(pin(), { _, stillWanted ->
            inFlight.complete(Unit)
            withContext(NonCancellable) { codeEntered.await() } // a network call that does not stop for a cancel
            val wanted = stillWanted()
            if (wanted) saved = true
            answered.complete(wanted)
            wanted
        }, tickMillis = 1)
        val outcome = Outcome()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        poll.startIn(scope, outcome)

        withTimeout(5_000) { inFlight.await() }
        poll.cancel()
        codeEntered.complete(Unit)

        assertFalse("A cancelled pairing was asked to save", withTimeout(5_000) { answered.await() })
        assertFalse(saved)
        assertEquals(0, outcome.signedIn.get())
        scope.cancel()
    }

    /** The other way out: the screen goes, taking the poll's scope; a late answer is still not saved. */
    @Test
    fun aPollInFlightWhenTheScreenGoesSavesNothing() = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        val codeEntered = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Boolean>()
        val poll = DevicePinPoll(pin(), { _, stillWanted ->
            inFlight.complete(Unit)
            withContext(NonCancellable) { codeEntered.await() }
            stillWanted().also { answered.complete(it) }
        }, tickMillis = 1)
        val outcome = Outcome()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        poll.startIn(scope, outcome)

        withTimeout(5_000) { inFlight.await() }
        scope.cancel()
        codeEntered.complete(Unit)

        assertFalse(withTimeout(5_000) { answered.await() })
        assertEquals(0, outcome.signedIn.get())
    }

    @Test
    fun noPollStartsAfterCancel() = runBlocking {
        val calls = AtomicInteger()
        val first = CompletableDeferred<Unit>()
        val poll = DevicePinPoll(pin(), { _, _ -> calls.incrementAndGet(); first.complete(Unit); false }, tickMillis = 1)
        val outcome = Outcome()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        poll.startIn(scope, outcome)

        withTimeout(5_000) { first.await() }
        poll.cancel()
        val atCancel = calls.get()
        delay(100) // a hundred ticks' worth

        assertEquals("The poll went on after Cancel", atCancel, calls.get())
        assertEquals(0, outcome.expired.get())
        scope.cancel()
    }

    @Test
    fun aCodeEnteredWhileOpenSignsInOnce() = runBlocking {
        val calls = AtomicInteger()
        val poll = DevicePinPoll(pin(), { _, stillWanted -> calls.incrementAndGet() >= 3 && stillWanted() }, tickMillis = 1)
        val outcome = Outcome()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        poll.startIn(scope, outcome)

        withTimeout(5_000) { while (outcome.signedIn.get() == 0) delay(1) }
        delay(50)

        assertEquals(1, outcome.signedIn.get())
        assertEquals("Polling went on after signing in", 3, calls.get())
        scope.cancel()
    }

    @Test
    fun anUnenteredCodeExpiresOnceAfterOnePollPerInterval() = runBlocking {
        val calls = AtomicInteger()
        val poll = DevicePinPoll(pin(expiresIn = 6, interval = 2), { _, _ -> calls.incrementAndGet(); false }, tickMillis = 1)
        val outcome = Outcome()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        poll.startIn(scope, outcome)

        withTimeout(5_000) { while (outcome.expired.get() == 0) delay(1) }

        assertEquals(1, outcome.expired.get())
        assertEquals(3, calls.get()) // at 4, 2 and 0 seconds left
        scope.cancel()
    }
}
