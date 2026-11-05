package com.lagradost.cloudstream4.core.connectivity

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultNetworkStateTest {
    private val offline = DefaultNetworkState(null, Connectivity.OFFLINE)

    private fun DefaultNetworkState.after(vararg events: NetworkEvent) =
        events.fold(this) { state, event -> state.reduce(event) }.connectivity

    @Test
    fun availableIsOnlineWithNoCapabilitiesCallback() {
        assertEquals(Connectivity.ONLINE, offline.after(NetworkEvent.Available(1)))
    }

    @Test
    fun losingTheDefaultNetworkIsOffline() {
        assertEquals(
            Connectivity.OFFLINE,
            offline.after(NetworkEvent.Available(1), NetworkEvent.Lost(1)),
        )
    }

    @Test
    fun aNetworkThatStopsClaimingInternetIsOffline() {
        assertEquals(
            Connectivity.OFFLINE,
            offline.after(
                NetworkEvent.Available(1),
                NetworkEvent.CapabilitiesChanged(1, hasInternet = false),
            ),
        )
    }

    @Test
    fun aNetworkThatClaimsInternetAgainIsOnline() {
        assertEquals(
            Connectivity.ONLINE,
            offline.after(
                NetworkEvent.Available(1),
                NetworkEvent.CapabilitiesChanged(1, hasInternet = false),
                NetworkEvent.CapabilitiesChanged(1, hasInternet = true),
            ),
        )
    }

    @Test
    fun aHandoverDoesNotFlickerOfflineWhenTheOldNetworkIsLostAfterward() {
        assertEquals(
            Connectivity.ONLINE,
            offline.after(
                NetworkEvent.Available(1),
                NetworkEvent.Available(2),
                NetworkEvent.Lost(1),
            ),
        )
    }

    @Test
    fun theNewNetworkIsTheOneThatCountsAfterAHandover() {
        assertEquals(
            Connectivity.OFFLINE,
            offline.after(
                NetworkEvent.Available(1),
                NetworkEvent.Available(2),
                NetworkEvent.Lost(2),
            ),
        )
    }

    @Test
    fun aLostNetworkWithNothingTrackedIsStillOffline() {
        val fromUnknown = DefaultNetworkState(null, Connectivity.UNKNOWN)
        assertEquals(Connectivity.OFFLINE, fromUnknown.after(NetworkEvent.Lost(1)))
    }

    @Test
    fun unknownIsWhatTheFallbackFlowAlwaysSays() {
        assertEquals(Connectivity.UNKNOWN, UnknownConnectivityFlow.status.value)
    }
}
