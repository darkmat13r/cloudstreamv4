package com.lagradost.cloudstream4.core.connectivity

/**
 * What a default-network callback reports, without the platform's types.
 *
 * [handle] identifies the network. Only its identity matters here.
 */
internal sealed interface NetworkEvent {
    val handle: Long

    data class Available(override val handle: Long) : NetworkEvent

    data class CapabilitiesChanged(override val handle: Long, val hasInternet: Boolean) : NetworkEvent

    data class Lost(override val handle: Long) : NetworkEvent
}

/**
 * The default network as far as the callbacks have said, and what that means.
 *
 * ## Internet, not validated
 *
 * [NetworkEvent.CapabilitiesChanged.hasInternet] is `NET_CAPABILITY_INTERNET`, the same test as `:app`'s
 * one-shot `isNetworkAvailable` (`AppContextUtils.kt`). It is deliberately not `NET_CAPABILITY_VALIDATED`:
 * that one means the platform reached Google's check URL, so a network where that URL is blocked, or a
 * captive portal, or a LAN serving the user's own media, would read as offline with working connectivity.
 * A false offline banner is worse here than a request that fails and says why.
 */
internal data class DefaultNetworkState(
    val network: Long?,
    val connectivity: Connectivity,
) {
    /**
     * [NetworkEvent.Available] means online by itself: the default network request requires internet, and
     * below API 26 no capabilities callback is guaranteed to follow it. A [NetworkEvent.Lost] for a network
     * other than the one being tracked is a handover that has already happened, and changes nothing.
     */
    fun reduce(event: NetworkEvent): DefaultNetworkState = when (event) {
        is NetworkEvent.Available -> DefaultNetworkState(event.handle, Connectivity.ONLINE)
        is NetworkEvent.CapabilitiesChanged -> DefaultNetworkState(
            event.handle,
            if (event.hasInternet) Connectivity.ONLINE else Connectivity.OFFLINE,
        )
        is NetworkEvent.Lost ->
            if (network != null && network != event.handle) this
            else DefaultNetworkState(null, Connectivity.OFFLINE)
    }
}

/**
 * The callback's [DefaultNetworkState], and the order of what changes it (`BUG-121`). Not thread-safe: its one owner
 * holds a lock around every call, so the counter, the state and the emission that follows change together.
 *
 * The callbacks arrive through [onEvent]. One read of the platform, taken after the callback is registered, arrives
 * through [mark] and [reread]: [mark] before the read is taken, [reread] after. If a callback arrived between them, the
 * read is dropped: that callback is at least as recent, and every change after it brings another, so a read taken
 * before it can never be the last word.
 */
internal class DefaultNetworkTracker(initial: DefaultNetworkState) {
    var state: DefaultNetworkState = initial
        private set

    private var events = 0L

    /** A callback: reduce, and say what to emit. */
    fun onEvent(event: NetworkEvent): Connectivity {
        events++
        state = state.reduce(event)
        return state.connectivity
    }

    /** Taken before reading the platform; [reread] compares against it. */
    fun mark(): Long = events

    /**
     * [read], taken after [mark], replaces the whole state, the network as well, so later callbacks reduce from it.
     * Null, and nothing changes, if a callback arrived since [mark].
     */
    fun reread(mark: Long, read: DefaultNetworkState): Connectivity? {
        if (events != mark) return null
        state = read
        return state.connectivity
    }
}
