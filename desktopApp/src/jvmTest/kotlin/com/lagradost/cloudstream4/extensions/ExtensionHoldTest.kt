package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import com.lagradost.cloudstream4.feature.extensions.sandbox.ExtensionScanner
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The hold, through the real pipeline: a real scan, real decisions on disk and a real host under Seatbelt.
 *
 * "Ran" is measured by the probe's own mark and by the directory a host is given, not by what a report says.
 * Skips where there is no `sandbox-exec`, because nothing is loaded on those platforms at all.
 */
class ExtensionHoldTest {
    private lateinit var home: File
    private lateinit var directory: File
    private lateinit var decisions: File
    private lateinit var extensions: SandboxedExtensions
    private val probe: File get() = File(directory, "Probe.jar")
    private val mark: File get() = File(directory, ".sandbox/Probe/$PROBE_MARK")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("hold").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
        decisions = ExtensionDecisions.fileFor(directory)
        extensions = SandboxedExtensions(directory)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { if (probe.isFile) runCatching { extensions.keepOff("Probe.jar", sha256Of(probe)) } }
        forgetProviders()
        extensions.close()
        scope.cancel()
        home.deleteRecursively()
    }

    private val hasSandbox get() = HostLauncher.forThisProcess() != null

    private fun allowOnDisk(file: File) =
        ExtensionDecisions(decisions).record(file.name, sha256Of(file), ExtensionChoice.Allow)

    private fun load() = runBlocking { extensions.loadInstalled() }

    private fun assertNothingRan() {
        assertFalse(mark.exists(), "the plugin's code ran")
        assertFalse(File(directory, ".sandbox/Probe").exists(), "a host was given a directory")
        assertTrue(extensions.providers.none { it.name == "Probe" }, "a provider was registered")
    }

    @Test
    fun `the fixture really is a warning from the real scanner, and the plain one is not`() {
        writeProbeJar(probe)
        val report = ExtensionScanner.scan(probe)
        assertFalse(report.blocked, "${report.findings}")
        assertTrue(report.warnings > 0, "${report.findings}")
        assertTrue(report.findings.any { it.rule == "filesystem" }, "${report.findings}")

        val plain = File(home, "Plain.jar")
        writeFixtureJar(plain, PlainPlugin::class.java, PlainApi::class.java)
        assertEquals(emptyList(), ExtensionScanner.scan(plain).findings)
    }

    @Test
    fun `with warnings and no decision, it does not start, and says so`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        val report = load()
        val held = report.loads.single()
        assertEquals(LoadState.Held, held.state)
        assertEquals(0, held.providers)
        assertEquals(sha256Of(probe), held.sha256)
        assertNothingRan()
        val lines = report.lines()
        assertTrue(lines.any { "Probe.jar" in it && "HELD" in it }, "$lines")
        assertTrue(lines.last().endsWith("1 held"), "$lines")
    }

    @Test
    fun `a scan-only pass holds it too, and starts nothing`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        assertEquals(LoadState.Held, runBlocking { extensions.scanInstalled() }.loads.single().state)
        assertNothingRan()
    }

    @Test
    fun `keep off stops it asking, and it still does not start`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        ExtensionDecisions(decisions).record("Probe.jar", sha256Of(probe), ExtensionChoice.KeepOff)
        assertEquals(LoadState.KeptOff, load().loads.single().state)
        assertNothingRan()
    }

    @Test
    fun `an allowed file starts on the next launch, in a real host`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        allowOnDisk(probe)
        val load = load().loads.single()
        assertEquals(LoadState.Running, load.state, load.outcome)
        assertEquals(1, load.providers)
        assertEquals("ran", mark.readText())
        assertEquals("denied", runBlocking {
            extensions.providers.single { it.name == "Probe" }.search("read:/etc/master.passwd", 1).items.single().name
        })
    }

    @Test
    fun `a clean plugin is not asked about at all`() {
        if (!hasSandbox) return
        writeFixtureJar(File(directory, "Plain.jar"), PlainPlugin::class.java, PlainApi::class.java)
        val load = load().loads.single()
        assertEquals(LoadState.Running, load.state, load.outcome)
        assertEquals(1, load.providers)
        assertEquals(
            ExtensionChoice.Seen,
            ExtensionDecisions(decisions).read().choiceFor("Plain.jar", sha256Of(File(directory, "Plain.jar"))),
            "recorded so that a later file is noticed, and as nobody's choice",
        )
        runBlocking { extensions.keepOff("Plain.jar", sha256Of(File(directory, "Plain.jar"))) }
        assertTrue(extensions.providers.none { it.name == "Plain" })
    }

    @Test
    fun `changing the file loses the approval`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        allowOnDisk(probe)
        val approved = sha256Of(probe)
        writeProbeJar(probe, padding = "an update")
        assertNotEquals(approved, sha256Of(probe))
        assertEquals(LoadState.Held, load().loads.single().state)
        assertNothingRan()
    }

    private val plain get() = File(directory, "Plain.jar")

    private fun writePlain(padding: String? = null) {
        writeFixtureJar(plain, PlainPlugin::class.java, PlainApi::class.java)
        if (padding != null) {
            // A real change to the file: a jar reads to its end record, so this is what an update looks like to a hash.
            java.util.zip.ZipFile(plain).close()
            val entries = plain.readBytes()
            plain.outputStream().use { out ->
                java.util.zip.ZipOutputStream(out).use { zip ->
                    java.util.zip.ZipInputStream(entries.inputStream()).use { input ->
                        generateSequence { input.nextEntry }.forEach { entry ->
                            zip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("padding.txt"))
                    zip.write(padding.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    /** The next launch. Providers live in a process-wide list, which a real launch would start without. */
    private fun restart() {
        forgetProviders()
        extensions.close()
        extensions = SandboxedExtensions(directory)
    }

    private fun forgetProviders() = extensions.providers.forEach { provider ->
        APIHolder.allProviders.remove(provider)
        APIHolder.removePluginMapping(provider)
    }

    @Test
    fun `a clean plugin that changes waits, though the scan has nothing to say`() {
        if (!hasSandbox) return
        writePlain()
        assertEquals(LoadState.Running, load().loads.single().state)
        restart()
        writePlain("an update")
        val load = load().loads.single()
        assertEquals(LoadState.Held, load.state, load.outcome)
        assertTrue(load.newVersion)
        assertEquals(0, load.scan?.warnings)
        assertTrue(extensions.providers.none { it.name == "Plain" }, "the update ran")
    }

    @Test
    fun `a clean plugin allowed once runs without asking again, until it changes`() {
        if (!hasSandbox) return
        writePlain()
        load()
        restart()
        assertEquals(LoadState.Running, load().loads.single().state)
    }

    @Test
    fun `allowing a new version that scans clean starts it, and it is listed so it can be stopped`() {
        if (!hasSandbox) return
        writePlain()
        load()
        restart()
        writePlain("an update")
        val held = load()
        val approvals = ExtensionApprovals(extensions, held, scope)
        assertEquals(ReviewStatus.Held, approvals.state.value.reviews.single().status)
        assertTrue(approvals.state.value.reviews.single().newVersion)
        approvals.onAction(ReviewAction.Allow("Plain.jar"))
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                while (approvals.state.value.reviews.single().status != ReviewStatus.Allowed) delay(POLL_MILLIS)
            }
        }
        assertTrue(extensions.providers.any { it.name == "Plain" })
    }

    @Test
    fun `kept off stays off for a clean file, and for its next version`() {
        if (!hasSandbox) return
        writePlain()
        ExtensionDecisions(decisions).record("Plain.jar", sha256Of(plain), ExtensionChoice.KeepOff)
        assertEquals(LoadState.KeptOff, load().loads.single().state)
        writePlain("an update")
        assertEquals(LoadState.Held, load().loads.single().state)
        assertTrue(extensions.providers.none { it.name == "Plain" })
    }

    @Test
    fun `replacing a plugin and its sidecar together still loses the approval`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        allowOnDisk(probe)
        writeProbeJar(probe, padding = "an update")
        File(directory, "Probe.jar.sha256").writeText("sha256-${sha256Of(probe)}")
        val load = load().loads.single()
        assertTrue(load.verification is ExtensionVerification.Verified, "${load.verification}")
        assertEquals(LoadState.Held, load.state)
        assertTrue(load.newVersion)
        assertNothingRan()
    }

    @Test
    fun `changing only the sidecar refuses the file, approved or not`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        allowOnDisk(probe)
        File(directory, "Probe.jar.sha256").writeText("sha256-" + "0".repeat(64))
        assertEquals(LoadState.HashMismatch, load().loads.single().state)
        assertNothingRan()
    }

    @Test
    fun `a jar dropped beside a kept-off cs3 takes its place and is held, however clean`() {
        if (!hasSandbox) return
        File(directory, "Plain.cs3").writeText("recorded, and never read")
        ExtensionDecisions(decisions).record("Plain.cs3", "a".repeat(64), ExtensionChoice.KeepOff)
        File(directory, "Plain.cs3").delete()
        writePlain()
        val load = load().loads.single()
        assertEquals(LoadState.Held, load.state, load.outcome)
        assertTrue(load.newVersion)
    }

    @Test
    fun `a seen record never allows warnings, and scan-only writes nothing`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        ExtensionDecisions(decisions).record(probe.name, sha256Of(probe), ExtensionChoice.Seen)
        assertEquals(LoadState.Held, load().loads.single().state)
        assertNothingRan()
        decisions.delete()
        writePlain()
        runBlocking { extensions.scanInstalled() }
        assertFalse(decisions.exists(), "a scan wrote a record")
    }

    @Test
    fun `the same bytes under another name do not inherit it`() {
        if (!hasSandbox) return
        writeProbeJar(File(home, "elsewhere.jar"))
        val approved = File(directory, "Other.jar").also { File(home, "elsewhere.jar").copyTo(it) }
        allowOnDisk(approved)
        approved.renameTo(probe)
        assertEquals(LoadState.Held, load().loads.single().state)
        assertNothingRan()
    }

    @Test
    fun `a corrupt decisions file holds what it had allowed, and is reported`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        allowOnDisk(probe)
        decisions.writeText(decisions.readText().replace("allow", "allow "))
        val report = load()
        assertTrue(report.decisionsUnreadable)
        assertEquals(LoadState.Held, report.loads.single().state)
        assertNothingRan()
    }

    @Test
    fun `an approval never reaches past a blocking finding`() {
        if (!hasSandbox) return
        val blocked = File(directory, "Blocked.jar")
        writeFixtureJar(blocked, BlockedFixture::class.java, ProbePlugin::class.java, ProbeApi::class.java)
        allowOnDisk(blocked)
        val load = load().loads.single()
        assertEquals(LoadState.Blocked, load.state)
        assertEquals(0, load.providers)
        assertFalse(File(directory, ".sandbox/Blocked").exists())
    }

    @Test
    fun `an approval never reaches past a hash mismatch`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        File(directory, "Probe.jar.sha256").writeText("sha256-" + "0".repeat(64))
        allowOnDisk(probe)
        val load = load().loads.single()
        assertEquals(LoadState.HashMismatch, load.state)
        assertTrue(load.verification is ExtensionVerification.HashMismatch)
        assertNothingRan()
    }

    @Test
    fun `the screen's state holder cannot be made to approve what is refused`() {
        if (!hasSandbox) return
        val blocked = File(directory, "Blocked.jar")
        writeFixtureJar(blocked, BlockedFixture::class.java, ProbePlugin::class.java, ProbeApi::class.java)
        val approvals = ExtensionApprovals(extensions, load(), scope)
        assertEquals(ReviewStatus.Blocked, approvals.state.value.reviews.single().status)
        approvals.onAction(ReviewAction.Allow("Blocked.jar"))
        approvals.onAction(ReviewAction.KeepOff("Blocked.jar"))
        runBlocking { delay(SETTLE_MILLIS) }
        assertEquals(ReviewStatus.Blocked, approvals.state.value.reviews.single().status)
        assertFalse(decisions.exists(), "nothing was recorded")
    }

    @Test
    fun `allow takes effect at once, keep off stops it at once, and neither needs a restart`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        val approvals = ExtensionApprovals(extensions, load(), scope)
        val before = approvals.state.value
        assertEquals(1, before.waiting)
        assertNothingRan()

        approvals.onAction(ReviewAction.Allow("Probe.jar"))
        val allowed = settleOn(approvals, ReviewStatus.Allowed)
        assertEquals(0, allowed.waiting)
        assertEquals(1, allowed.providerChanges, "so the screens read the new list")
        assertEquals("ran", mark.readText())
        assertTrue(extensions.providers.any { it.name == "Probe" })
        assertEquals(ExtensionChoice.Allow, ExtensionDecisions(decisions).read().choiceFor("Probe.jar", sha256Of(probe)))

        approvals.onAction(ReviewAction.KeepOff("Probe.jar"))
        val off = settleOn(approvals, ReviewStatus.KeptOff)
        assertEquals(2, off.providerChanges)
        assertTrue(extensions.providers.none { it.name == "Probe" }, "the provider went with it")
        assertEquals(ExtensionChoice.KeepOff, ExtensionDecisions(decisions).read().choiceFor("Probe.jar", sha256Of(probe)))

        approvals.onAction(ReviewAction.Allow("Probe.jar"))
        settleOn(approvals, ReviewStatus.Allowed)
        assertTrue(extensions.providers.any { it.name == "Probe" }, "and a change of mind brings it back")
    }

    /** The row on the screen described the old file, so that is all the press can have been about. */
    @Test
    fun `a file swapped after the warning was read does not get the approval`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        val approvals = ExtensionApprovals(extensions, load(), scope)
        val read = approvals.state.value.reviews.single().sha256
        writeProbeJar(probe, padding = "swapped in while the window was open")

        approvals.onAction(ReviewAction.Allow("Probe.jar"))
        val state = settleOn(approvals, ReviewStatus.Held)
        assertNothingRan()
        assertNotEquals(read, state.reviews.single().sha256, "the row now describes the new file")
    }

    @Test
    fun `a copy that does not have the promised hash is refused`() {
        val source = File(home, "source").apply { writeText("swapped") }
        assertFailsWith<IllegalStateException> { copyExpecting(source, File(home, "copy"), "0".repeat(64)) }
        copyExpecting(source, File(home, "copy"), sha256Of(source))
        assertEquals("swapped", File(home, "copy").readText())
    }

    @Test
    fun `a choice that cannot be saved starts nothing, and the screen says so`() {
        if (!hasSandbox) return
        writeProbeJar(probe)
        decisions.mkdirs()
        val approvals = ExtensionApprovals(extensions, load(), scope)
        assertTrue(approvals.state.value.decisionsUnreadable)
        approvals.onAction(ReviewAction.Allow("Probe.jar"))
        val state = runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                while (approvals.state.value.notSaved == null) delay(POLL_MILLIS)
                approvals.state.value
            }
        }
        assertEquals("Probe.jar", state.notSaved)
        assertEquals(ReviewStatus.Held, state.reviews.single().status)
        assertNothingRan()
    }

    private fun settleOn(approvals: ExtensionApprovals, status: ReviewStatus): ExtensionReviewState = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            while (approvals.state.value.reviews.single().status != status) delay(POLL_MILLIS)
            approvals.state.value
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 300L
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MILLIS = 60_000L
    }

    /**
     * `BUG-18`. Nothing this input can become is usable, so the only way to a passing scan is the old jar.
     * Names the last revision, not the current one: update it when `CONVERTER_REVISION` next moves.
     */
    @Test
    fun `a jar from the last converter is not used, so an owner gets the fixed conversion`() {
        if (!hasSandbox) return
        val cs3 = File(directory, "Old.cs3")
        ZipOutputStream(cs3.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.closeEntry()
        }
        val stale = File(directory, ".store/${sha256Of(cs3)}-c2.jar").apply { parentFile.mkdirs() }
        writeProbeJar(stale)

        val load = runBlocking { extensions.scanInstalled() }.loads.single()

        assertEquals(LoadState.Failed, load.state, load.outcome)
        assertTrue("no classes.dex" in load.outcome || "conversion failed" in load.outcome, load.outcome)
    }
}
