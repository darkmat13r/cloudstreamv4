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
