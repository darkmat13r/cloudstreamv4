package com.lagradost.cloudstream4.core.connectivity

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `BUG-121`, `BUG-122`: the reads taken after registering, ordered against the callbacks. */
class DefaultNetworkTrackerTest {
    private val online1 = DefaultNetworkState(1, Connectivity.ONLINE)
    private val offline = DefaultNetworkState(null, Connectivity.OFFLINE)

    @Test
    fun withNoCallbackTheReadReplacesTheFirstAnswer() {
        // The network went before registering: no callback ever comes, and the read is all there is.
        val tracker = DefaultNetworkTracker(online1)
        val mark = tracker.mark()
        assertEquals(Connectivity.OFFLINE, tracker.reread(mark, offline))
        assertEquals(offline, tracker.state)
    }

    @Test
    fun aCallbackBetweenTheMarkAndTheReadWins() {
        val tracker = DefaultNetworkTracker(online1)
        val mark = tracker.mark()
        assertEquals(Connectivity.OFFLINE, tracker.onEvent(NetworkEvent.Lost(1)))
        // The read was taken before that callback was applied, so it says the network is still there.
        assertNull(tracker.reread(mark, online1), "a read older than a callback was applied")
        assertEquals(offline, tracker.state)
    }

    @Test
    fun aCallbackBeforeTheMarkDoesNotStopTheRead() {
        val tracker = DefaultNetworkTracker(offline)
        tracker.onEvent(NetworkEvent.Available(1))
        val mark = tracker.mark()
        assertEquals(Connectivity.OFFLINE, tracker.reread(mark, offline))
    }

    @Test
    fun theReadReplacesTheNetworkSoLaterCallbacksReduceFromIt() {
        val tracker = DefaultNetworkTracker(online1)
        val mark = tracker.mark()
        tracker.reread(mark, DefaultNetworkState(2, Connectivity.ONLINE))
        // Network 1 is gone already, a handover: it changes nothing.
        assertEquals(Connectivity.ONLINE, tracker.onEvent(NetworkEvent.Lost(1)))
        assertEquals(Connectivity.OFFLINE, tracker.onEvent(NetworkEvent.Lost(2)))
    }

    @Test
    fun aNetworkOnlyAReadHasSeenIsProvisionalUntilACallbackNamesIt() {
        val tracker = DefaultNetworkTracker(online1)
        assertTrue(tracker.provisional)
        tracker.reread(tracker.mark(), online1)
        assertTrue(tracker.provisional, "a read does not confirm a network")
        tracker.onEvent(NetworkEvent.Available(1))
        assertFalse(tracker.provisional)
    }

    @Test
    fun aCallbackNamingAnotherNetworkDoesNotConfirmTheOneRead() {
        val tracker = DefaultNetworkTracker(offline)
        tracker.onEvent(NetworkEvent.Available(2))
        tracker.reread(tracker.mark(), online1)
        assertTrue(tracker.provisional)
    }

    @Test
    fun noNetworkIsNeverProvisional() {
        assertFalse(DefaultNetworkTracker(offline).provisional)
    }

    @Test
    fun aDyingNetworkIsReadAgainUntilItIsGone() {
        // BUG-122: the callback hears nothing, and the first reads still report the network being torn down.
        val tracker = DefaultNetworkTracker(online1)
        val reads = ArrayDeque(listOf(online1, online1, offline))
        val polls = settle(tracker) { reads.removeFirst() }
        assertEquals(3, polls)
        assertEquals(offline, tracker.state)
        assertFalse(tracker.provisional)
    }

    @Test
    fun aCallbackDuringTheReadsStopsThem() {
        val tracker = DefaultNetworkTracker(online1)
        var reads = 0
        val polls = settle(tracker) {
            reads++
            // The second read is taken just as the callback hears the network go, so the read is stale.
            if (reads == 2) tracker.onEvent(NetworkEvent.Lost(1))
            online1
        }
        assertEquals(2, polls)
        assertEquals(offline, tracker.state, "the callback, not the stale read, has the last word")
    }

    @Test
    fun theReadsStopAtTheCapAndKeepTheLastOne() {
        val tracker = DefaultNetworkTracker(online1)
        var pauses = 0
        val polls = settle(tracker, maxPolls = 5, pause = { pauses++ }) { online1 }
        assertEquals(5, polls)
        assertEquals(5, pauses)
        assertEquals(online1, tracker.state)
    }

    @Test
    fun aNetworkACallbackHasNamedIsNotReadAgain() {
        val tracker = DefaultNetworkTracker(online1)
        tracker.onEvent(NetworkEvent.Available(1))
        assertEquals(0, settle(tracker) { error("no read expected") })
    }

    /** Runs [settleProvisional] over [tracker] with [read] as the platform; [pause] never suspends, so it ends here. */
    private fun settle(
        tracker: DefaultNetworkTracker,
        maxPolls: Int = PROVISIONAL_POLLS,
        pause: () -> Unit = {},
        read: () -> DefaultNetworkState,
    ): Int {
        var result: Result<Int>? = null
        val run: suspend () -> Int = {
            settleProvisional(
                provisional = { tracker.provisional },
                reread = { tracker.reread(tracker.mark(), read()) },
                pause = { pause() },
                maxPolls = maxPolls,
            )
        }
        run.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return checkNotNull(result) { "settleProvisional suspended" }.getOrThrow()
    }
}
