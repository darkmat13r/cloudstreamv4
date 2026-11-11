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
        // Whether the certificate services are reachable is the kernel's answer, not this text's: system.sb, imported,
        // allows them. MediaHostServicesTest asks from inside the real policy (audit A-1).
        // Silent, and no certificate services: the media rules name no service, and write no rule for none. An
        // `allow mach-lookup` with no names would allow every service there is.
        assertFalse(Regex("\\(allow mach-lookup\\s*\\)").containsMatchIn(profile), profile)
        assertFalse("coreaudiod" in profile, "a silent host may reach audio")
    }

    @Test
    fun `a host that plays a downloaded file has no network at all`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val file = launcher.mediaHostProfile(File("/Applications/VLC.app"), writable, silent = false, gatewayPort = null)
        assertFalse("network-outbound" in file, file)
        assertFalse("system-socket" in file, file)
        // With sound, the audio services are named.
        assertTrue("coreaudiod" in file, file)
        assertFalse(Regex("\\(allow mach-lookup\\s*\\)").containsMatchIn(file), file)
    }

    @Test
    fun `a plugin host behind its gate keeps the resolver, as before`() {
        val profile = render(gatewayPort = 40_124, resolver = true)
        assertTrue("mDNSResponder" in profile)
        assertFalse("\"*:*\"" in profile)
        // It resolves names itself, so it keeps the configuration services (`BUG-96`, A-10).
        assertTrue("com.apple.SystemConfiguration.configd" in profile, profile)
    }

    /**
     * `BUG-96`: the certificate services are denied for every host, the plugin host included, last so nothing above wins;
     * and only a host with the network and the resolver gets the configuration services (A-10). The kernel's answer for
     * the same rules is `MediaHostServicesTest`'s, from inside a real media host.
     */
    @Test
    fun `every host is denied the certificate services, and only one that resolves names gets the configuration ones`() {
        val plugin = render(gatewayPort = 40_126, resolver = true)
        val converter = SeatbeltProfile.render(File("/usr/bin/java"), emptyList(), emptyList(), writable, network = false)
        val behindGateway = render(gatewayPort = 40_127, resolver = false)
        listOf(plugin, converter, behindGateway).forEach { profile ->
            val denied = profile.substringAfterLast("(deny mach-lookup")
            assertTrue("com.apple.trustd\"" in denied && "com.apple.trustd.agent" in denied, profile)
        }
        listOf(converter, behindGateway).forEach { profile ->
            assertFalse("SystemConfiguration" in profile, profile)
        }
    }
}
