package com.lagradost.cloudstream4.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Takes the emulator's network away for real and watches [AndroidConnectivityFlow] say so, with no fake.
 * Each answer is checked against `activeNetwork` read directly, a second way of asking the same question.
 *
 * ## How the network goes away
 *
 * `Television_720p`'s default network is **Ethernet** (`eth0`). Measured on it: `svc wifi disable`,
 * `svc data disable` and `cmd connectivity airplane-mode enable` leave `activeNetwork` where it was; `ip link`
 * has no permission; and `EthernetManager.setEthernetEnabled` is hidden from a test. What works is the same
 * binder call that method makes, from the shell user, which holds `NETWORK_SETTINGS`:
 * `service call ethernet 13 i32 0`.
 *
 * **13 is `IEthernetManager`'s transaction number on this image's Android 16** and can change with it, so every
 * toggle is confirmed by reading `dumpsys ethernet`, and a different number fails here by saying so.
 *
 * [restore] undoes it. `device-tests.sh` boots the AVD `-read-only`, so even a crash cannot change the AVD.
 */
@RunWith(AndroidJUnit4::class)
class AndroidConnectivityFlowDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Runs as the shell user, but not through a shell: no pipes or redirects. */
    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
    }

    private fun ethernet(enabled: Boolean) {
        shell("service call ethernet $SET_ETHERNET_ENABLED i32 ${if (enabled) 1 else 0}")
        val state = shell("dumpsys ethernet").lineSequence().firstOrNull { "Ethernet State" in it }
        assertTrue(
            "Transaction $SET_ETHERNET_ENABLED did not set Ethernet to $enabled, it says: $state",
            state?.contains(if (enabled) "enabled" else "disabled") == true,
        )
    }

    private suspend fun ConnectivityFlow.awaits(wanted: Connectivity): Boolean =
        withTimeoutOrNull(WAIT_MS) { status.first { it == wanted } } != null

    /**
     * `BUG-117`: the flow can hold a value before a collector has appended it, so the test waits for [seen] itself
     * before it moves on or cancels. It runs only after [awaits] has passed on the flow, so it never makes the
     * flow's own answer wait for anything.
     */
    private suspend fun recorded(seen: List<Connectivity>, wanted: Connectivity): Boolean =
        withTimeoutOrNull(WAIT_MS) {
            while (synchronized(seen) { seen.lastOrNull() } != wanted) delay(POLL_MS)
        } != null

    @After
    fun restore() {
        shell("service call ethernet $SET_ETHERNET_ENABLED i32 1")
        scope.cancel()
    }

    @Test
    fun followsTheNetworkGoingAwayAndComingBack() = runBlocking {
        val flow = AndroidConnectivityFlow(context, scope)
        val seen = mutableListOf<Connectivity>()
        val collector = scope.launch { flow.status.collect { synchronized(seen) { seen += it } } }

        assertTrue("Never ONLINE; activeNetwork=${manager.activeNetwork}", flow.awaits(Connectivity.ONLINE))
        val before = manager.activeNetwork
        assertNotNull(before)

        ethernet(enabled = false)
        assertTrue("Never OFFLINE; activeNetwork=${manager.activeNetwork}", flow.awaits(Connectivity.OFFLINE))
        assertNull("The flow said OFFLINE with a default network", manager.activeNetwork)
        val offlineRecorded = recorded(seen, Connectivity.OFFLINE)
        assertTrue("The collector never recorded OFFLINE: ${synchronized(seen) { seen.toList() }}", offlineRecorded)

        ethernet(enabled = true)
        assertTrue("Never ONLINE again; activeNetwork=${manager.activeNetwork}", flow.awaits(Connectivity.ONLINE))
        val after = manager.activeNetwork
        assertNotNull(after)
        assertNotEquals("Ethernet comes back as a new network", before, after)
        val onlineRecorded = recorded(seen, Connectivity.ONLINE)
        assertTrue("The collector never recorded ONLINE again: ${synchronized(seen) { seen.toList() }}", onlineRecorded)

        collector.cancel()
        val sequence = synchronized(seen) { seen.toList() }
        Log.i(TAG, "activeNetwork $before -> $after, sequence: $sequence")
        assertEquals(
            listOf(Connectivity.ONLINE, Connectivity.OFFLINE, Connectivity.ONLINE),
            sequence.fold(mutableListOf<Connectivity>()) { out, value ->
                if (out.lastOrNull() != value) out += value
                out
            },
        )
    }

    /**
     * `BUG-121`: the network goes away after the flow's first read and before its callback is registered. A
     * default-network callback registered with no default network says nothing, so without a second read the
     * flow kept saying ONLINE. The hook pins the gap: it takes Ethernet down and waits for `activeNetwork` to go.
     */
    @Test
    fun aNetworkLostBeforeTheCallbackIsRegisteredReadsOffline() = runBlocking {
        val onlineAtFirstRead = manager.activeNetwork != null
        val goneBeforeRegister = AtomicBoolean()
        val flow = AndroidConnectivityFlow(context, scope) {
            ethernet(enabled = false)
            val until = System.currentTimeMillis() + WAIT_MS
            while (manager.activeNetwork != null && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
            goneBeforeRegister.set(manager.activeNetwork == null)
        }
        assertTrue("The test needs a default network to start from", onlineAtFirstRead)

        val collector = scope.launch { flow.status.collect {} }
        val offline = flow.awaits(Connectivity.OFFLINE)
        assertTrue("The network was still there when the callback was registered", goneBeforeRegister.get())
        assertTrue("Never OFFLINE after the network went before registration; activeNetwork=${manager.activeNetwork}", offline)
        assertNull("The flow said OFFLINE with a default network", manager.activeNetwork)
        collector.cancel()
    }

    /**
     * `dumpsys connectivity` lists each live request by uid, so a registration can be counted. Only the part
     * above `mNetworkRequestInfoLogs` counts: that is a history, where a release adds a line as well.
     */
    @Test
    fun registersOnceAndOnlyWhileCollected() = runBlocking {
        fun requests(): Int = shell("dumpsys connectivity")
            .substringBefore("mNetworkRequestInfoLogs")
            .lineSequence().count { it.contains("uid/pid:${Process.myUid()}/") }

        val before = requests()
        val flow = AndroidConnectivityFlow(context, scope)
        delay(SETTLE_MS)
        assertEquals("Making the flow must not register", before, requests())

        val first = scope.launch { flow.status.collect {} }
        delay(SETTLE_MS)
        val one = requests()
        Log.i(TAG, "live requests before=$before oneCollector=$one")
        assertEquals("One collector registers one callback", before + 1, one)

        val second = scope.launch { flow.status.collect {} }
        delay(SETTLE_MS)
        assertEquals("A second collector must share the callback", one, requests())

        first.cancel()
        second.cancel()
        delay(STOP_TIMEOUT_PLUS_MS)
        val after = requests()
        Log.i(TAG, "live requests after=$after")
        assertEquals("The callback must be gone once nothing collects", before, after)
    }

    /** What the flow's own read says, taken here a second way. */
    private fun platform(): DefaultNetworkState {
        val network = manager.activeNetwork ?: return DefaultNetworkState(null, Connectivity.OFFLINE)
        val internet = manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return DefaultNetworkState(network.networkHandle, if (internet) Connectivity.ONLINE else Connectivity.OFFLINE)
    }

    private fun awaitActiveNetwork(present: Boolean) {
        val until = System.currentTimeMillis() + WAIT_MS
        while ((manager.activeNetwork != null) != present && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
        assertEquals("activeNetwork present", present, manager.activeNetwork != null)
    }

    /**
     * Waits for a network and then [SETTLE_MS] more. Measured on `Television_720p`: Ethernet disabled within about
     * 100 ms of being enabled comes back up by itself 20 to 40 ms later, a new network the test did not ask for.
     */
    private fun awaitSettledNetwork() {
        awaitActiveNetwork(present = true)
        Thread.sleep(SETTLE_MS)
    }

    /** Puts the network back for the tests after this one, which start from it. */
    private fun ethernetBack() {
        ethernet(enabled = true)
        awaitSettledNetwork()
    }

    /** This uid's live requests, counted as [registersOnceAndOnlyWhileCollected] counts them. */
    private fun liveRequests(): Int = shell("dumpsys connectivity")
        .substringBefore("mNetworkRequestInfoLogs")
        .lineSequence().count { it.contains("uid/pid:${Process.myUid()}/") }

    /**
     * `BUG-122`: the network is torn down just before the platform applies the callback's registration, so the
     * callback hears nothing about it, ever, while reads still report it for a few ms. That race went wrong in 16 of
     * 20 runs; here it is certain. The network is gone before the flow starts, and the reads up to and including the
     * first one after registering report it, as the platform's did in that window.
     */
    @Test
    fun aNetworkTheCallbackNeverHearsAboutIsReadAgainUntilItIsGone() = runBlocking {
        awaitSettledNetwork()
        val dying = platform()
        assertEquals("The test needs a network to start from", Connectivity.ONLINE, dying.connectivity)
        try {
            ethernet(enabled = false)
            awaitActiveNetwork(present = false)
            val reads = AtomicInteger()
            val flow = AndroidConnectivityFlow(
                context,
                scope,
                read = { if (reads.incrementAndGet() <= STALE_READS) dying else platform() },
            ) {}
            val collector = scope.launch { flow.status.collect {} }
            val offline = flow.awaits(Connectivity.OFFLINE)
            assertTrue("Never OFFLINE after ${reads.get()} reads; activeNetwork=${manager.activeNetwork}", offline)
            assertTrue("The stale reads were not all taken: ${reads.get()}", reads.get() > STALE_READS)
            collector.cancel()
        } finally {
            ethernetBack()
        }
    }

    /**
     * `BUG-122`: the reads after registering run as a child of the flow, so a flow cancelled while they go on still
     * unregisters its callback, and nothing keeps reading. With no network, and a read that always reports one, the
     * state stays provisional for the whole of the reads' 3 s. Only the flow's own scope can stop it inside them: a
     * collector that leaves keeps it running for [STOP_TIMEOUT_PLUS_MS] more, longer than the reads.
     */
    @Test
    fun cancellingTheFlowWhileTheReadsGoOnUnregistersTheCallback() = runBlocking {
        awaitSettledNetwork()
        val stale = platform()
        assertEquals("The test needs a network to start from", Connectivity.ONLINE, stale.connectivity)
        try {
            ethernet(enabled = false)
            awaitActiveNetwork(present = false)
            val before = liveRequests()
            val reads = AtomicInteger()
            val flowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val flow = AndroidConnectivityFlow(context, flowScope, read = { reads.incrementAndGet(); stale }) {}
            flowScope.launch { flow.status.collect {} }
            delay(SETTLE_MS)
            val readsAtCancel = reads.get()
            assertTrue(
                "The reads are not going on at the cancel: $readsAtCancel",
                readsAtCancel in (STALE_READS + 1)..<(STALE_READS + PROVISIONAL_POLLS),
            )
            assertEquals("One collector registers one callback", before + 1, liveRequests())

            flowScope.coroutineContext.job.cancelAndJoin()
            val readsAfterCancel = reads.get()
            delay(SETTLE_MS)
            Log.i(TAG, "reads at cancel=$readsAtCancel after=$readsAfterCancel later=${reads.get()} before=$before")
            assertEquals("The callback must be gone once the flow is cancelled", before, liveRequests())
            assertEquals("Nothing may keep reading", readsAfterCancel, reads.get())
        } finally {
            ethernetBack()
        }
    }

    /**
     * `BUG-122`: across collections that start and stop, the reads end with each one, and the callback is gone once the
     * last collector has been away for the stop timeout.
     */
    @Test
    fun theReadsAndTheCallbackEndWithEachCollection() = runBlocking {
        awaitSettledNetwork()
        val stale = platform()
        assertEquals("The test needs a network to start from", Connectivity.ONLINE, stale.connectivity)
        try {
            ethernet(enabled = false)
            awaitActiveNetwork(present = false)
            val before = liveRequests()
            val reads = AtomicInteger()
            val flow = AndroidConnectivityFlow(context, scope, read = { reads.incrementAndGet(); stale }) {}
            repeat(CYCLES) { cycle ->
                val collector = scope.launch { flow.status.collect {} }
                delay(SETTLE_MS)
                collector.cancel()
                delay(STOP_TIMEOUT_PLUS_MS)
                val settled = reads.get()
                delay(SETTLE_MS)
                Log.i(TAG, "cycle $cycle reads=$settled requests=${liveRequests()} before=$before")
                assertEquals("Cycle $cycle: the callback must be gone", before, liveRequests())
                assertEquals("Cycle $cycle: nothing may keep reading", settled, reads.get())
            }
        } finally {
            ethernetBack()
        }
    }

    /**
     * `BUG-122` as it happens, with nothing faked: Ethernet goes down just before registering, without waiting, so
     * the platform's teardown and the registration race. Before the fix this ended ONLINE with no network in 16 of
     * 20 runs, and passed in the rest, so on its own it is not a proof, only the real thing checked.
     */
    @Test
    fun aNetworkTornDownAsTheCallbackIsRegisteredReadsOffline() = runBlocking {
        awaitSettledNetwork()
        try {
            val flow = AndroidConnectivityFlow(context, scope) {
                shell("service call ethernet $SET_ETHERNET_ENABLED i32 0")
            }
            val collector = scope.launch { flow.status.collect {} }
            val offline = flow.awaits(Connectivity.OFFLINE)
            assertTrue("Never OFFLINE; activeNetwork=${manager.activeNetwork}", offline)
            // The platform can still report the network for a few ms after the callback has heard it go.
            awaitActiveNetwork(present = false)
            collector.cancel()
        } finally {
            ethernetBack()
        }
    }

    private companion object {
        const val TAG = "ConnectivityDeviceTest"
        const val SET_ETHERNET_ENABLED = 13
        const val WAIT_MS = 60_000L
        const val SETTLE_MS = 1_000L
        const val STOP_TIMEOUT_PLUS_MS = 6_000L
        const val POLL_MS = 50L

        /** The flow's reads before polling: its first value, its first read, and the one after registering. */
        const val STALE_READS = 3

        const val CYCLES = 2
    }
}
