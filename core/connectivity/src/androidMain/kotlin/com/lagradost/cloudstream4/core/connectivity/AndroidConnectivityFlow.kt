package com.lagradost.cloudstream4.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * [ConnectivityFlow] over `ConnectivityManager`. Make one per process: every collector shares its one
 * callback, which is registered while something collects and for [STOP_TIMEOUT_MS] after, so a
 * configuration change does not re-register it.
 *
 * What [Connectivity.ONLINE] means, and why it is not "validated", is on [DefaultNetworkState].
 *
 * Anything that goes wrong asking the platform, such as a missing permission or the app's 100 callbacks
 * being used up, gives [Connectivity.UNKNOWN], never a guess.
 *
 * @param scope owns the shared callback. The default lives as long as the process, which suits the one
 *   instance a host makes.
 */
class AndroidConnectivityFlow(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ConnectivityFlow {
    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val status: StateFlow<Connectivity> = observe().stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        readNow(manager).connectivity,
    )

    private fun observe(): Flow<Connectivity> = callbackFlow {
        val initial = readNow(manager)
        // Before registering, because a default-network callback says nothing when there is no network.
        trySend(initial.connectivity)

        val emit: (Connectivity) -> Unit = { trySendBlocking(it) }
        val callback =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) DefaultNetworkCallback(initial, emit)
            else ActiveNetworkCallback(manager, emit)

        val registered = manager != null && runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                val anyInternet = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(anyInternet, callback)
            }
        }.isSuccess
        if (!registered) trySend(Connectivity.UNKNOWN)

        awaitClose {
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun capabilitiesOf(manager: ConnectivityManager, network: Network): Boolean =
    manager.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

/** The one-shot read: where a collection starts, and all there is on API 23. */
private fun readNow(manager: ConnectivityManager?): DefaultNetworkState = runCatching {
    val network = manager?.activeNetwork
    when {
        manager == null -> DefaultNetworkState(null, Connectivity.UNKNOWN)
        network == null -> DefaultNetworkState(null, Connectivity.OFFLINE)
        else -> DefaultNetworkState(
            network.networkHandle,
            if (capabilitiesOf(manager, network)) Connectivity.ONLINE else Connectivity.OFFLINE,
        )
    }
}.getOrDefault(DefaultNetworkState(null, Connectivity.UNKNOWN))

/**
 * API 24 and up. Runs on the one thread the platform delivers callbacks on, and the platform asks that a
 * callback does not call back into `ConnectivityManager`, so it works only from what it is told.
 */
private class DefaultNetworkCallback(
    initial: DefaultNetworkState,
    private val emit: (Connectivity) -> Unit,
) : ConnectivityManager.NetworkCallback() {
    @Volatile
    private var state = initial

    private fun apply(event: NetworkEvent) {
        state = state.reduce(event)
        emit(state.connectivity)
    }

    override fun onAvailable(network: Network) =
        apply(NetworkEvent.Available(network.networkHandle))

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = apply(
        NetworkEvent.CapabilitiesChanged(
            network.networkHandle,
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        ),
    )

    override fun onLost(network: Network) = apply(NetworkEvent.Lost(network.networkHandle))
}

/**
 * API 23, which has no default-network callback. This one hears about every network with internet, which
 * says only that something changed, so each event re-reads the active network. A network reported lost
 * may still be the active one for a moment, so it counts as gone.
 */
private class ActiveNetworkCallback(
    private val manager: ConnectivityManager?,
    private val emit: (Connectivity) -> Unit,
) : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = emit(readNow(manager).connectivity)

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
        emit(readNow(manager).connectivity)

    override fun onLost(network: Network) {
        val now = readNow(manager)
        emit(if (now.network == network.networkHandle) Connectivity.OFFLINE else now.connectivity)
    }
}
