package com.lagradost.cloudstream4.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

        ethernet(enabled = true)
        assertTrue("Never ONLINE again; activeNetwork=${manager.activeNetwork}", flow.awaits(Connectivity.ONLINE))
        val after = manager.activeNetwork
        assertNotNull(after)
        assertNotEquals("Ethernet comes back as a new network", before, after)

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

    private companion object {
        const val TAG = "ConnectivityDeviceTest"
        const val SET_ETHERNET_ENABLED = 13
        const val WAIT_MS = 60_000L
        const val SETTLE_MS = 1_000L
        const val STOP_TIMEOUT_PLUS_MS = 6_000L
    }
}
