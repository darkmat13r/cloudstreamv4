package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KMP-113`: the media host's policy behind its gateway: that one port on loopback, nothing else, and no resolver. */
class MediaHostProfileTest {
    private val writable = File("/var/empty/media-host")

    private fun render(gatewayPort: Int?, resolver: Boolean) = SeatbeltProfile.render(
        File("/usr/bin/java"), emptyList(), emptyList(), writable, network = true,
        gatewayPort = gatewayPort, resolver = resolver,
    )

    @Test
    fun `behind its gateway the host may reach that port on loopback and nothing else`() {
        val profile = render(gatewayPort = 40_123, resolver = false)
        assertTrue("(allow network-outbound (remote ip \"localhost:40123\"))" in profile, profile)
        assertFalse("\"*:*\"" in profile, "every address is open")
        assertFalse("mDNSResponder" in profile, "the resolver is open")
    }

    @Test
    fun `the policy a media host is really given, behind its gateway`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val profile = launcher.mediaHostProfile(File("/Applications/VLC.app"), writable, silent = true, gatewayPort = 40_125)
        assertTrue("(allow network-outbound (remote ip \"localhost:40125\"))" in profile, profile)
        assertFalse("\"*:*\"" in profile, "every address is open")
        assertFalse("mDNSResponder" in profile, "the resolver is open")
        // A host that plays a file keeps the policy it had.
        val file = launcher.mediaHostProfile(File("/Applications/VLC.app"), writable, silent = true, gatewayPort = null)
        assertTrue("\"*:*\"" in file && "mDNSResponder" in file)
    }

    @Test
    fun `a plugin host behind its gate keeps the resolver, as before`() {
        val profile = render(gatewayPort = 40_124, resolver = true)
        assertTrue("mDNSResponder" in profile)
        assertFalse("\"*:*\"" in profile)
    }
}
