package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.net.ServerSocket
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the sandbox stops, measured against a real child process under a real Seatbelt policy.
 *
 * Every denial has a positive control beside it. Without one, a host that could do nothing at all
 * would pass, and so would a typo in the action name.
 *
 * Skips where there is no `sandbox-exec`: nothing runs unsandboxed on those platforms either.
 */
class SandboxEnforcementTest {
    private lateinit var root: File
    private lateinit var data: File
    private lateinit var outside: File
    private lateinit var jar: File
    private var host: PluginHostClient? = null

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("sandbox-test", "").apply { delete(); mkdirs() }.canonicalFile
        data = File(root, "data").apply { mkdirs() }
        outside = File(root, "outside").apply { mkdirs() }
        jar = File(root, "store/hostile.jar").apply { parentFile.mkdirs() }
        writeJar(jar, HostilePlugin::class.java, HostileApi::class.java)
    }

    @AfterTest
    fun tearDown() {
        host?.close()
        root.deleteRecursively()
    }

    private fun running(block: suspend (PluginHostClient) -> Unit) {
        val launcher = HostLauncher.forThisProcess() ?: return
        runBlocking {
            val (client, providers) = PluginHostClient.start(launcher, jar, data, "hostile")
            host = client
            assertEquals(
                "Hostile",
                providers.getOrElse { throw AssertionError("host: ${client.hostOutput}", it) }.single().name,
            )
            block(client)
        }
    }

    private suspend fun PluginHostClient.attempt(action: String): String =
        search("Hostile", action, 1).items.single().name

    @Test
    fun `a file outside the sandbox cannot be read, and its own directory can`() = running { host ->
        val secret = File(outside, "secret.txt").apply { writeText("ssh key") }
        val own = File(data, "own.txt").apply { writeText("mine") }

        assertEquals("denied", host.attempt("read:${secret.path}"))
        assertEquals("allowed", host.attempt("read:${own.path}"))
    }

    @Test
    fun `the real home directory cannot be read`() = running { host ->
        val home = File(System.getProperty("user.home"))
        val profile = listOf(".zshrc", ".bash_profile", ".profile", ".gitconfig", ".ssh/known_hosts")
            .map { File(home, it) }.firstOrNull { it.isFile } ?: return@running
        assertEquals("denied", host.attempt("read:${profile.path}"))
    }

    @Test
    fun `nothing outside its directory can be written, including its own jar`() = running { host ->
        val target = File(outside, "dropped.txt")
        val own = File(data, "written.txt")
        val jarBefore = jar.readBytes().toList()

        assertEquals("denied", host.attempt("write:${target.path}"))
        assertEquals("denied", host.attempt("write:${jar.path}"))
        assertEquals("allowed", host.attempt("write:${own.path}"))

        assertFalse(target.exists())
        assertEquals(jarBefore, jar.readBytes().toList())
        assertEquals("written by a plugin", own.readText())
    }

    @Test
    fun `no process can be started`() = running { host ->
        assertEquals("denied", host.attempt("exec:/bin/sh"))
        assertEquals("denied", host.attempt("exec:/usr/bin/id"))
    }

    @Test
    fun `services on this machine cannot be reached`() = running { host ->
        ServerSocket(0).use { listener ->
            assertEquals("denied", host.attempt("connect:127.0.0.1/${listener.localPort}"))
        }
    }

    @Test
    fun `the environment holds nothing of the user's`() = running { host ->
        // JAVA_MAIN_CLASS_<pid> and __CF_USER_TEXT_ENCODING are added by the launcher and by macOS
        // inside the child. They carry a class name and a text encoding, and nothing of the user's.
        val names = host.attempt("env:").split(',')
            .filterNot { it.startsWith("JAVA_MAIN_CLASS_") || it == "__CF_USER_TEXT_ENCODING" }
        assertEquals(listOf("HOME", "JAVA_TOOL_OPTIONS", "TMPDIR"), names)
    }

    @Test
    fun `plugin output cannot corrupt the protocol`() = running { host ->
        assertEquals("printed", host.attempt("print:"))
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `garbage on the real stdout ends that host and only that host`() = running { host ->
        assertFailsWith<Exception> { host.attempt("corrupt:") }
        assertFailsWith<Exception> { host.attempt("print:") }
    }

    @Test
    fun `what comes back is cleaned before the app sees it`() = running { host ->
        val item = host.search("Hostile", "hostile-text:", 1).items.single()
        assertEquals("evil[2Jforged line", item.name)
        assertNull(item.posterUrl, "a file: poster must not reach the image loader")
    }

    /**
     * The attack is a folder whose name closes the string and opens `(allow default)`. Given to the
     * real `sandbox-exec`, because a substring check on the text proves nothing about how SBPL parses
     * it: `\\"))(allow default)` contains the very substring the escaping is meant to neutralise.
     */
    @Test
    fun `a path cannot break out of the policy`() {
        if (HostLauncher.forThisProcess() == null) return
        val hostile = File(root, "x\"))(allow default)(deny file-read* (literal \"y").apply { mkdirs() }
        val secret = File(outside, "secret.txt").apply { writeText("ssh key") }
        val profile = SeatbeltProfile.render(
            executable = File("/bin/cat"),
            readable = emptyList(),
            readOnlyInputs = emptyList(),
            writable = hostile,
            network = false,
        )
        val inside = File(hostile, "inside.txt").apply { writeText("visible") }

        fun cat(file: File): Pair<Int, String> {
            val process = ProcessBuilder(SeatbeltProfile.SANDBOX_EXEC, "-p", profile, "/bin/cat", file.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            return process.waitFor() to output
        }

        val (deniedCode, deniedOutput) = cat(secret)
        assertTrue(deniedCode != 0 && "Operation not permitted" in deniedOutput, deniedOutput)
        // The control: the policy parsed, and the oddly named directory really is the writable one.
        assertEquals(0 to "visible", cat(inside))
    }

    /**
     * The media policy is the plugin policy plus what libVLC needs, so what it must not gain is checked
     * through the real `sandbox-exec`. The positive control is a path only those extra rules open.
     */
    @Test
    fun `the media policy opens nothing of the user's`() {
        if (HostLauncher.forThisProcess() == null) return
        val writable = File(root, "media").apply { mkdirs() }
        val vlc = File(root, "VLC.app").apply { mkdirs() }
        val own = File(vlc, "library").apply { writeText("visible") }
        val secret = File(outside, "secret.txt").apply { writeText("ssh key") }
        val profile = SeatbeltProfile.render(
            executable = File("/bin/cat"),
            readable = emptyList(),
            readOnlyInputs = emptyList(),
            writable = writable,
            network = true,
            extraRules = SeatbeltProfile.mediaHostRules(vlc, writable),
        )

        fun cat(file: File): Pair<Int, String> {
            val process = ProcessBuilder(SeatbeltProfile.SANDBOX_EXEC, "-p", profile, "/bin/cat", file.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            return process.waitFor() to output
        }

        assertEquals(0 to "visible", cat(own))
        val (code, output) = cat(secret)
        assertTrue(code != 0 && "Operation not permitted" in output, output)
        val home = File(System.getProperty("user.home"))
        listOf(".zshrc", ".bash_profile", ".profile", ".gitconfig", ".ssh/known_hosts")
            .map { File(home, it) }.firstOrNull { it.isFile }?.let { profileFile ->
                val (homeCode, homeOutput) = cat(profileFile)
                assertTrue(homeCode != 0 && "Operation not permitted" in homeOutput, homeOutput)
            }
    }

    @Test
    fun `a control character in a path is refused`() {
        assertFailsWith<IllegalArgumentException> {
            SeatbeltProfile.render(File("/bin/sh"), emptyList(), emptyList(), File("/tmp/a\nb"), false)
        }
    }

    private fun writeJar(target: File, vararg classes: Class<*>) {
        JarOutputStream(target.outputStream()).use { out ->
            classes.forEach { type ->
                val path = type.name.replace('.', '/') + ".class"
                out.putNextEntry(JarEntry(path))
                type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
}
