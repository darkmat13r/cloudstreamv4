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
 * The callback's [DefaultNetworkState], and the order of what changes it (`BUG-121`, `BUG-122`). Not thread-safe: its
 * one owner holds a lock around every call, so the counter, the state and the emission that follows change together.
 *
 * The callbacks arrive through [onEvent]. Reads of the platform, taken after the callback is registered, arrive
 * through [mark] and [reread]: [mark] before the read is taken, [reread] after. If a callback arrived between them, the
 * read is dropped, because that callback is at least as recent.
 *
 * ## A read is not durable until a callback names its network
 *
 * Once a callback has named a network, every later change to that network brings another callback, so a read can
 * never be the last word about it. That does **not** hold for a network only a read has seen (`BUG-122`). Registering
 * the callback only queues a request: the platform applies it later, on its own thread. If the network was torn down
 * before then, the callback hears nothing about it, ever. A read is not ordered after that, though, and can still
 * report the dying network. Measured on `Television_720p`, 16 of 20 races ended like that: ONLINE, with no network,
 * and nothing left to correct it.
 *
 * So a network in [state] that no callback has named is [provisional], and its owner keeps reading
 * ([settleProvisional]) until a callback names a network or a read finds none.
 */
internal class DefaultNetworkTracker(initial: DefaultNetworkState) {
    var state: DefaultNetworkState = initial
        private set

    private var events = 0L

    /** The network the last callback named, or null if none has. */
    private var announced: Long? = null

    /**
     * True while [state] holds a network that only a read has seen: one no callback has named, so nothing may ever
     * say it went. A callback that names another network does not count for this one.
     */
    val provisional: Boolean
        get() = state.network != null && state.network != announced

    /** A callback: reduce, and say what to emit. */
    fun onEvent(event: NetworkEvent): Connectivity {
        events++
        if (event !is NetworkEvent.Lost) announced = event.handle
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

/** How long [settleProvisional] waits between reads. */
internal const val PROVISIONAL_POLL_MS = 100L

/** How many reads [settleProvisional] takes at most: 3 s of them. */
internal const val PROVISIONAL_POLLS = 30

/**
 * Reads again while [provisional] says the state holds a network no callback has named (`BUG-122`). It stops at the
 * first of these: a callback names a network, which then speaks for itself; a read finds no network, which is
 * OFFLINE; or [maxPolls] reads have been taken. Each [reread] goes through [DefaultNetworkTracker.mark] and
 * [DefaultNetworkTracker.reread], so a callback that arrives during a read still wins over it.
 *
 * The cap makes this a bounded heuristic. A network the platform still reports, but has not named to the callback,
 * after [maxPolls] reads stays as read, which is how it was before `BUG-122`. That should not happen: the platform
 * names an existing default network to a callback as soon as it applies the registration, which took 2 ms in the
 * measured runs, and a torn-down network leaves its reads within tens of ms. A teardown applied later than the cap
 * would still stick.
 *
 * @return how many reads were taken.
 */
internal suspend fun settleProvisional(
    provisional: () -> Boolean,
    reread: () -> Unit,
    pause: suspend () -> Unit,
    maxPolls: Int = PROVISIONAL_POLLS,
): Int {
    var polls = 0
    while (provisional() && polls < maxPolls) {
        pause()
        reread()
        polls++
    }
    return polls
}
