package com.lagradost.cloudstream4.core.connectivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `BUG-121`: the read taken after registering, ordered against the callbacks. */
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
}
