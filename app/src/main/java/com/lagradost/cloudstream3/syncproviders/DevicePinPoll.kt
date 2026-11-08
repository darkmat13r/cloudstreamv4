package com.lagradost.cloudstream3.syncproviders

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The device-code wait: counts [AuthPinData.expiresIn] down and asks [signIn] every [AuthPinData.interval] seconds
 * whether the code has been entered on another device.
 *
 * `BUG-49`: the pairing dialog's Cancel did nothing to its timer, so polling went on until expiry and a code entered
 * after Cancel still signed the TV in. Here:
 * - [cancel] stops the loop, and is what every way out of the dialog calls;
 * - a poll already in flight is asked, through [signIn]'s `stillWanted`, after the provider answers and before anything
 *   is saved; it answers no once cancelled or once [start]'s scope is gone (the screen was destroyed).
 *
 * Polls run one at a time; the old timer could start a new request every interval while the last was still out.
 * Nothing here touches the code or the token: it only counts and asks.
 */
internal class DevicePinPoll(
    private val pin: AuthPinData,
    private val signIn: suspend (AuthPinData, stillWanted: () -> Boolean) -> Boolean,
    private val tickMillis: Long = 1000L,
) {
    @Volatile
    var isCancelled = false
        private set
    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
        ui: CoroutineContext,
        io: CoroutineContext,
        onTick: (secondsLeft: Int) -> Unit,
        onSignedIn: () -> Unit,
        onExpired: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        job = scope.launch(ui) {
            var left = pin.expiresIn
            while (left > 0) {
                onTick(left)
                delay(tickMillis)
                left -= 1
                if (isCancelled) return@launch
                if (pin.interval > 0 && left % pin.interval == 0) {
                    val signedIn = try {
                        withContext(io) { signIn(pin) { !isCancelled && isActive } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        onError(t)
                        false
                    }
                    if (signedIn && !isCancelled) {
                        onSignedIn()
                        return@launch
                    }
                }
            }
            if (!isCancelled) onExpired()
        }
    }

    fun cancel() {
        isCancelled = true
        job?.cancel()
    }
}
