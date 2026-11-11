package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Assume.assumeTrue

/**
 * Looks each service up with `bootstrap_look_up` and prints `name=result`, where 0 is found. Run as a media host's
 * main class, so it lives under exactly the policy a media host is given.
 */
object MachLookupProbe {
    /**
     * What no media host may look up: the certificate services (audit A-1), the rest of the security services, the
     * mDNS resolver, and the system configuration services (`BUG-96`, audit A-10).
     */
    val REFUSED = listOf(
        "com.apple.trustd",
        "com.apple.trustd.agent",
        "com.apple.ocspd",
        "com.apple.SecurityServer",
        "com.apple.securityd.xpc",
        "com.apple.dnssd.service",
        "com.apple.SystemConfiguration.configd",
        "com.apple.SystemConfiguration.DNSConfiguration",
    )

    val SERVICES = REFUSED + ALLOWED

    /** A service `system.sb` allows and the media host keeps: the control that lookups work at all in there. */
    const val ALLOWED = "com.apple.system.notification_center"

    fun lookUp(name: String): Int {
        val system = NativeLibrary.getInstance("System")
        val bootstrap = system.getGlobalVariableAddress("bootstrap_port").getInt(0)
        val lookUp = system.getFunction("bootstrap_look_up")
        return lookUp.invokeInt(arrayOf(bootstrap, name, IntByReference()))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        SERVICES.forEach { println("$it=${runCatching { lookUp(it) }.getOrElse { -1 }}") }
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}

/**
 * Audit A-1: `system.sb`, which every policy imports, allows the certificate services, so a media host's policy denies
 * them by name. A test of the policy's text cannot show that; this asks the kernel, from inside the real policy.
 */
class MediaHostServicesTest {
    private val directories = mutableListOf<File>()

    @AfterTest
    fun tearDown() = directories.forEach { it.deleteRecursively() }

    /** What the probe reports from inside the media host's policy, for a stream (with a gateway port) or a file. */
    private fun probe(gatewayPort: Int?): Map<String, Int> {
        val launcher = HostLauncher.forMediaHost("--probe", MachLookupProbe::class.java.name)
        assumeTrue("no sandbox-exec here", launcher != null)
        val directory = Files.createTempDirectory("media-host-services").toFile().canonicalFile.also { directories += it }
        val process = launcher!!.startMediaHost(File("/Applications/VLC.app"), directory, silent = true, gatewayPort = gatewayPort)
        process.outputStream.close()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        return output.lines().mapNotNull { line ->
            val (name, result) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            result.trim().toIntOrNull()?.let { name to it }
        }.toMap()
    }

    @Test
    fun `outside any sandbox every service it checks is there, so the probe can tell`() {
        assumeTrue("macOS only", System.getProperty("os.name").startsWith("Mac"))
        MachLookupProbe.REFUSED.forEach { assertEquals(0, MachLookupProbe.lookUp(it), it) }
    }

    @Test
    fun `a stream's media host cannot look up any of them`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertRefused(probe(gatewayPort = port))
    }

    @Test
    fun `a file's media host cannot look them up either`() {
        assertRefused(probe(gatewayPort = null))
    }

    private fun assertRefused(seen: Map<String, Int>) {
        assertEquals(0, seen[MachLookupProbe.ALLOWED], "the probe did not run, or lookups fail for everything: $seen")
        MachLookupProbe.REFUSED.forEach { assertNotEquals(0, seen[it] ?: 0, "$it was found: $seen") }
    }
}
