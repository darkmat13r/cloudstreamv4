package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.feature.extensions.sandbox.HostLauncher
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A plugin that could write the decisions file could approve itself, so a real plugin in a real host under the
 * real Seatbelt policy, laid out the way the app lays it out, is asked to try every way there is.
 *
 * Every denial has a control beside it, or a host that could do nothing at all would pass. Skips where there is
 * no `sandbox-exec`: nothing runs on those platforms.
 */
class ExtensionSandboxReachTest {
    private lateinit var home: File
    private lateinit var directory: File
    private lateinit var decisionsFile: File
    private lateinit var decisions: ExtensionDecisions
    private lateinit var extensions: SandboxedExtensions
    private val probe: File get() = File(directory, "Probe.jar")
    private val own: File get() = File(directory, ".sandbox/Probe")

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("reach").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
        extensions = SandboxedExtensions(directory)
        decisionsFile = ExtensionDecisions.fileFor(directory)
        decisions = ExtensionDecisions(decisionsFile)
        writeProbeJar(probe)
        decisions.record(probe.name, sha256Of(probe), ExtensionChoice.Allow)
        decisions.record("Other.jar", OTHER_HASH, ExtensionChoice.KeepOff)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { runCatching { extensions.keepOff(probe.name, sha256Of(probe)) } }
        extensions.close()
        home.deleteRecursively()
    }

    private fun running(block: suspend (attempt: suspend (String) -> String) -> Unit) {
        if (HostLauncher.forThisProcess() == null) return
        runBlocking {
            val load = extensions.loadInstalled().loads.first { it.fileName == probe.name }
            assertEquals(LoadState.Running, load.state, load.outcome)
            val provider = extensions.providers.single { it.name == "Probe" }
            block { query -> provider.search(query, 1).items.single().name }
        }
    }

    @Test
    fun `a sandboxed plugin can neither read nor change the decisions file`() = running { attempt ->
        val before = decisionsFile.readBytes()
        val path = decisionsFile.path

        assertEquals("denied", attempt("read:$path"), "it could learn what was approved")
        assertEquals("denied", attempt("write:$path"), "it could approve itself")
        assertEquals("denied", attempt("append:$path"))
        assertEquals("denied", attempt("delete:$path"))
        assertEquals("denied", attempt("rename:$path"))
        assertEquals("denied", attempt("replace:$path"), "moving a forged file over it")
        assertEquals("denied", attempt("readlink:$path"), "a link in its own directory, followed by the kernel")
        assertEquals("denied", attempt("hardlink:$path"), "a second name for the same file, inside its own directory")
        assertEquals("denied", attempt("write:$path.part"), "a staging file beside it")
        assertEquals("denied", attempt("list:${decisionsFile.parent}"), "the directory it lives in")

        assertContentEquals(before, decisionsFile.readBytes())
        assertEquals(setOf("extensions", decisionsFile.name), home.list()!!.toSet(), "nothing was dropped beside it")

        // Controls: the host really is running these attempts, and each verb works where it should.
        val mine = File(own, "control.txt")
        assertEquals("allowed", attempt("write:${mine.path}"))
        assertEquals("allowed", attempt("append:${mine.path}"))
        assertEquals("allowed", attempt("read:${mine.path}"))
        assertEquals("allowed", attempt("replace:${File(own, "control2.txt").path}"))
        assertEquals("allowed", attempt("readlink:${mine.path}"))
        assertEquals("allowed", attempt("hardlink:${mine.path}"))
        assertEquals("allowed", attempt("rename:${mine.path}"))
        assertEquals("allowed", attempt("delete:${File(own, "control2.txt").path}"))
        assertEquals("allowed", attempt("list:${own.path}"))
    }

    /** The decision itself, not only the bytes: what a forged write would have been for. */
    @Test
    fun `its attempt to approve a neighbour leaves that neighbour off`() = running { attempt ->
        assertEquals("denied", attempt("write:${decisionsFile.path}"))
        val snapshot = ExtensionDecisions(decisionsFile).read()
        assertFalse(snapshot.unreadable)
        assertEquals(ExtensionChoice.KeepOff, snapshot.choiceFor("Other.jar", OTHER_HASH))
        assertNull(snapshot.choiceFor("Forged.jar", OTHER_HASH))
    }

    /** `.store`, `.sandbox` and `.converting` are inside the directory, so each is checked by name. */
    @Test
    fun `inside the extensions directory it reaches its own directory and its own jar, and nothing else`() = running { attempt ->
        val store = File(directory, ".store")
        val ownJar = store.listFiles()!!.single()
        val otherJar = File(store, "other.jar").apply { writeText("another extension") }
        val otherData = File(directory, ".sandbox/Other/cookies").apply { parentFile.mkdirs(); writeText("session") }
        val converting = File(directory, ".converting/convert1/input.cs3").apply { parentFile.mkdirs(); writeText("dex") }
        val neighbour = File(directory, "Other.jar").apply { writeText("not a jar") }
        val sidecar = File(directory, "Probe.jar.sha256")

        assertEquals("allowed", attempt("read:${ownJar.path}"), "its jar is its one read-only input")
        assertEquals("denied", attempt("write:${ownJar.path}"), "what was scanned is what runs")
        assertEquals("denied", attempt("delete:${ownJar.path}"))
        assertEquals("denied", attempt("hardlink:${otherJar.path}"))
        assertEquals("denied", attempt("read:${otherJar.path}"))
        assertEquals("denied", attempt("write:${otherJar.path}"))
        assertEquals("denied", attempt("write:${File(store, "planted.jar").path}"))
        assertEquals("denied", attempt("list:${store.path}"))
        assertEquals("denied", attempt("read:${otherData.path}"))
        assertEquals("denied", attempt("write:${otherData.path}"))
        assertEquals("denied", attempt("list:${otherData.parent}"))
        assertEquals("denied", attempt("list:${File(directory, ".sandbox").path}"))
        assertEquals("denied", attempt("write:${File(directory, ".sandbox/New").path}"))
        assertEquals("denied", attempt("read:${converting.path}"))
        assertEquals("denied", attempt("write:${converting.path}"))
        assertEquals("denied", attempt("read:${neighbour.path}"))
        assertEquals("denied", attempt("write:${File(directory, "Dropped.jar").path}"), "it could install another")
        assertEquals("denied", attempt("write:${sidecar.path}"), "it could vouch for its own hash")
        assertEquals("denied", attempt("rename:${probe.path}"))
        assertEquals("denied", attempt("list:${directory.path}"))

        assertFalse(File(directory, "Dropped.jar").exists())
        assertFalse(sidecar.exists())
        assertTrue(probe.isFile)
        assertEquals("another extension", otherJar.readText())
        assertEquals("session", otherData.readText())
    }

    /**
     * The one thing a host can learn, measured so nobody has to take it on trust: `file-read-metadata` is open on
     * every path, because Java canonicalises. So it can tell the file exists and how long it is, not what is in it.
     */
    @Test
    fun `it can see the file's size and nothing else about it`() = running { attempt ->
        assertEquals(decisionsFile.length().toString(), attempt("stat:${decisionsFile.path}"))
        assertEquals("denied", attempt("read:${decisionsFile.path}"))
    }

    /** Would pass on a policy that denied everything, so the location is pinned on its own too. */
    @Test
    fun `the decisions file is under nothing a host is given`() {
        assertFalse(decisionsFile.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        assertEquals(directory.parentFile.canonicalPath, decisionsFile.parentFile.canonicalPath)
    }

    private companion object {
        val OTHER_HASH = "c".repeat(64)
    }
}
