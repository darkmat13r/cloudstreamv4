package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KMP-113`: the media host's policy behind its gateway: that one port on loopback, nothing else, and no resolver. */
class MediaHostProfileTest {
    private val writable = File("/var/empty/media-host")
    private val libraries = File("/var/empty/media-host-jna")

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
        val profile = launcher.mediaHostProfile(File("/Applications/VLC.app"), writable, silent = true, gatewayPort = 40_125, nativeLibraries = libraries)
        assertTrue("(allow network-outbound (remote ip \"localhost:40125\"))" in profile, profile)
        assertFalse("\"*:*\"" in profile, "every address is open")
        assertFalse("mDNSResponder" in profile, "the resolver is open")
        // Whether the certificate services are reachable is the kernel's answer, not this text's: system.sb, imported,
        // allows them. MediaHostServicesTest asks from inside the real policy (audit A-1).
        // Silent, and no certificate services: the media rules name no service, and write no rule for none. An
        // `allow mach-lookup` with no names would allow every service there is.
        assertFalse(Regex("\\(allow mach-lookup\\s*\\)").containsMatchIn(profile), profile)
        assertFalse("coreaudiod" in profile, "a silent host may reach audio")
        // `BUG-96` (A-9): code is mapped from VLC and from JNA's read-only copy, never from the host's own directory.
        // The kernel's answer is SandboxEscapeAttemptTest's, through a real media host.
        val mapped = profile.lines().filter { "allow file-map-executable" in it }
        assertTrue(mapped.any { "\"${libraries.canonicalPath}\"" in it }, profile)
        assertTrue(mapped.none { "\"${writable.canonicalPath}\"" in it }, profile)
    }

    @Test
    fun `a host that plays a downloaded file has no network at all`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val file = launcher.mediaHostProfile(File("/Applications/VLC.app"), writable, silent = false, gatewayPort = null, nativeLibraries = libraries)
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
    }
}
