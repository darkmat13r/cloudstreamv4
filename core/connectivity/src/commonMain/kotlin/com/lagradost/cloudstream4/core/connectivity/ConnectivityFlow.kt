package com.lagradost.cloudstream4.core.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What this device knows about its network. */
enum class Connectivity {
    /** The default network claims internet. A server can still be down, and a failed request still has to say so. */
    ONLINE,

    /** There is no default network, or it does not claim internet. */
    OFFLINE,

    /**
     * This platform has no way to tell, or the question could not be asked.
     *
     * **Never draw this as offline.** A screen shows its offline state only for [OFFLINE]; with this
     * value it behaves as if online and lets a request fail on its own terms.
     */
    UNKNOWN,
}

/**
 * The seam every screen's offline state observes. The host makes one and hands it down.
 *
 * A [StateFlow] because a screen always has something to draw: there is a current value with no
 * suspension and no invented `initial` at the collection site, and any number of collectors share one
 * platform registration, which on Android is a counted resource (100 callbacks per app).
 *
 * Collect it. An implementation may stop watching the platform while nothing is collecting, so
 * reading [status]'s `value` with no collector can be out of date.
 */
interface ConnectivityFlow {
    val status: StateFlow<Connectivity>
}

/**
 * The answer for a platform that has no implementation: desktop and iOS today. It says [Connectivity.UNKNOWN]
 * for ever, rather than a guessed [Connectivity.ONLINE] that would look like a measurement.
 */
object UnknownConnectivityFlow : ConnectivityFlow {
    override val status: StateFlow<Connectivity> =
        MutableStateFlow(Connectivity.UNKNOWN).asStateFlow()
}
