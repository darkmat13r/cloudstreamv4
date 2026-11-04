package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * An attempt to break out, through a real plugin host and a real media host under the real policies.
 *
 * Laid out as the app lays things out, with a decoy of what a host must not touch beside its directory. Reads also try
 * the real home, because that is the point, and report a word and never what they saw. Nothing outside the decoy is
 * ever written to. Prints the table it found; each host's expectations are pinned below it.
 */
class SandboxEscapeAttemptTest {
    private lateinit var root: File
    private lateinit var extensions: File
    private lateinit var own: File
    private lateinit var other: File
    private lateinit var jar: File
    private lateinit var listener: ServerSocket
    private var decoy: Process? = null

    private val realHome = File(System.getProperty("user.home"))
    private val lanAddress: String? by lazy {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
    private val gateway: String? by lazy {
        runCatching {
            ProcessBuilder("/sbin/route", "-n", "get", "default").start().inputStream.bufferedReader().readText()
                .lineSequence().firstOrNull { "gateway:" in it }?.substringAfter(':')?.trim()
        }.getOrNull()
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("escape").toFile().canonicalFile
        extensions = File(root, ".cloudstream4/extensions").apply { mkdirs() }
        own = File(extensions, ".sandbox/Hostile").apply { mkdirs() }
        other = File(extensions, ".sandbox/Other").apply { mkdirs() }
        File(other, "secret").writeText("another plugin's")
        File(extensions, "extensions.decisions").writeText("decoy")
        File(extensions, "Real.jar").writeText("decoy")
        File(extensions, "Real.jar.sha256").writeText("sha256-decoy")
        File(root, ".ssh").mkdirs()
        File(root, ".ssh/id_ed25519").writeText("decoy key")
        File(root, "Library/LaunchAgents").mkdirs()
        jar = File(extensions, ".store/hostile.jar").apply { parentFile.mkdirs() }
        writeJar(jar, HostilePlugin::class.java, HostileApi::class.java, EscapeAttempts::class.java)
        listener = ServerSocket(0)
        decoy = ProcessBuilder("/bin/sleep", "120").start()
    }

    @AfterTest
    fun tearDown() {
        listener.close()
        decoy?.destroyForcibly()
        root.deleteRecursively()
    }

    private fun attempts(dir: File): List<Pair<String, String>> {
        val port = listener.localPort
        val unix = File(root, "s.sock")
        val decoyPid = decoy!!.pid()
        return buildList {
            fun add(what: String, attempt: String) = add(what to attempt)
            listOf(
                ".ssh/id_ed25519", ".ssh/id_rsa", ".ssh/known_hosts", ".zshrc", ".gitconfig",
                ".cloudstream4/extensions.decisions", "Library/Keychains/login.keychain-db",
                "Library/Application Support/Google/Chrome/Local State", "Library/Safari/Bookmarks.plist",
                "Library/Cookies/Cookies.binarycookies",
            ).forEach { add("real home: read ~/$it", "read|${File(realHome, it).path}") }
            add("real home: list ~", "list|${realHome.path}")
            add("real home: list ~/.ssh", "list|${File(realHome, ".ssh").path}")
            add("list /Users", "list|/Users")
            add("stat a file in the real home", "stat|${File(realHome, ".ssh").path}")
            add("decoy: read the key", "read|${File(root, ".ssh/id_ed25519").path}")
            add("decoy: read decisions", "read|${File(extensions, "extensions.decisions").path}")
            add("decoy: read another plugin's directory", "read|${File(other, "secret").path}")
            add("decoy: list the extensions directory", "list|${extensions.path}")
            add("decoy: tamper with decisions", "append|${File(extensions, "extensions.decisions").path}")
            add("decoy: tamper with a hash sidecar", "append|${File(extensions, "Real.jar.sha256").path}")
            add("decoy: replace a plugin", "append|${File(extensions, "Real.jar").path}")
            add("decoy: delete another plugin's data", "delete|${File(other, "secret").path}")
            add("decoy: write a LaunchAgent", "write|${File(root, "Library/LaunchAgents/x.plist").path}")
            add("write /private/tmp", "write|/private/tmp/cs-escape-${System.nanoTime()}")
            add("write /Users/Shared", "write|/Users/Shared/cs-escape-${System.nanoTime()}")
            add("decoy: hard link the key in, then read", "hardlink-read|${File(root, ".ssh/id_ed25519").path}>${File(dir, "hl").path}")
            add("decoy: symlink the key in, then read", "symlink-read|${File(root, ".ssh/id_ed25519").path}>${File(dir, "sl").path}")
            add("decoy: move a file out", "move-out|${File(dir, "mv").path}>${File(root, "moved").path}")
            add("exec /bin/sh", "exec|/bin/sh")
            add("exec /usr/bin/id", "exec|/usr/bin/id")
            add("exec /usr/bin/open", "exec|/usr/bin/open")
            add("listen on a port", "listen|")
            add("load a dropped native library", "load|${File(dir, "lib.dylib").path}")
            add("connect 127.0.0.1", "connect|127.0.0.1/$port")
            add("connect 127.0.0.2", "connect|127.0.0.2/$port")
            add("connect [::1]", "connect|::1/$port")
            add("connect ::ffff:127.0.0.1", "connect|::ffff:7f00:1/$port")
            add("connect 0.0.0.0", "connect|0.0.0.0/$port")
            add("connect localhost", "connect|localhost/$port")
            lanAddress?.let { add("connect this machine by its LAN address", "connect|$it/$port") }
            gateway?.let { add("connect the LAN gateway :80", "connect|$it/80") }
            add("connect cloud metadata 169.254.169.254", "connect|169.254.169.254/80")
            add("connect a public address through the gate", "connect|1.1.1.1/443")
            add("connect a public name through the gate", "connect|one.one.one.one/443")
            add("skip the gate: a public address, directly", "connect-direct|1.1.1.1/443")
            lanAddress?.let { add("skip the gate: this machine, directly", "connect-direct|$it/$port") }
            gateway?.let { add("skip the gate: the LAN gateway, directly", "connect-direct|$it/80") }
            add("skip the gate: 127.0.0.1, directly", "connect-direct|127.0.0.1/$port")
            add("skip the gate: UDP to a public address", "udp|1.1.1.1/53")
            gateway?.let { add("skip the gate: UDP to the LAN gateway", "udp|$it/53") }
            add("connect a unix socket outside", "unix|${unix.path}")
            add("connect ~/.docker/run/docker.sock", "unix|${File(realHome, ".docker/run/docker.sock").path}")
            add("connect /var/run/docker.sock", "unix|/var/run/docker.sock")
            add("list every process", "processes|")
            add("read a process's command", "process-command|$decoyPid")
            add("kill another process", "signal|$decoyPid")
            add("user.name", "property|user.name")
            add("user.home", "property|user.home")
            add("hostname", "hostname|")
            add("network interfaces with a MAC", "addresses|")
            add("working directory", "cwd|")
        }
    }

    /** What a result says, with anything personal replaced by whether it matched. */
    private fun word(what: String, result: String): String = when {
        what == "user.name" -> if (result == System.getProperty("user.name")) "real name visible" else "hidden"
        what == "user.home" -> if (result == realHome.path) "real home visible" else "not the real home"
        what == "hostname" -> if (result == java.net.InetAddress.getLocalHost().hostName) "real hostname visible" else "hidden"
        what == "working directory" -> if (result.startsWith(realHome.path)) "under the real home" else "elsewhere"
        else -> result
    }

    private fun printTable(host: String, results: List<Pair<String, String>>) {
        println("ESCAPE ===== $host")
        results.forEach { (what, result) -> println("ESCAPE $what\t${word(what, result)}") }
    }

    /** @param nativeAllowed the media host must load JNA's library from its own directory, so it may load one. */
    private fun assertContained(results: Map<String, String>, nativeAllowed: Boolean = false) {
        val openDoors = results.filterKeys { it.startsWith("decoy:") || it.startsWith("real home:") || it.startsWith("write ") ||
            it.startsWith("exec ") || it == "listen on a port" || it.startsWith("connect 127") || it == "connect [::1]" ||
            it == "connect localhost" || it == "connect a unix socket outside" || (it == "load a dropped native library" && !nativeAllowed) ||
            it == "list every process" || it == "network interfaces with a MAC" }
            .filterValues { it == "allowed" || it == "reached:refused" || it.toIntOrNull() != null && it != "0" }
        assertEquals(emptyMap(), openDoors, "got through")
        assertEquals("denied", results["read a process's command"])
        assertTrue(results["user.name"] != System.getProperty("user.name"), "the account name")
        assertTrue(results["user.home"] != realHome.path, "the home directory")
        assertTrue(results["hostname"] != java.net.InetAddress.getLocalHost().hostName, "the hostname")
        assertTrue(decoy!!.isAlive, "another process was killed")
        assertEquals("decoy", File(extensions, "extensions.decisions").readText())
        assertEquals("sha256-decoy", File(extensions, "Real.jar.sha256").readText())
        assertEquals("another plugin's", File(other, "secret").readText())
        assertTrue(File(root, "Library/LaunchAgents").list().isNullOrEmpty())
    }

    @Test
    fun `through a real plugin host`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        File(own, "mv").writeText("mine")
        dropLibrary(own)
        val results = runBlocking {
            val (client, providers) = PluginHostClient.start(launcher, jar, own, "hostile")
            try {
                providers.getOrElse { throw AssertionError("host: ${client.hostOutput}", it) }
                attempts(own).map { (what, attempt) ->
                    what to client.search("Hostile", "attempt:$attempt", 1).items.single().name
                }
            } finally {
                client.close()
            }
        }
        printTable("plugin host", results)
        assertContained(results.toMap())
        val byName = results.toMap()
        lanAddress?.let { assertEquals("refused-by-gate", byName["connect this machine by its LAN address"]) }
        gateway?.let { assertEquals("refused-by-gate", byName["connect the LAN gateway :80"]) }
        assertEquals("refused-by-gate", byName["connect cloud metadata 169.254.169.254"])
        assertEquals("refused-by-gate", byName["connect 127.0.0.1"])
        byName.filterKeys { it.startsWith("skip the gate") }.forEach { (what, result) -> assertEquals("denied", result, what) }
    }

    @Test
    fun `through a real media host`() {
        val launcher = HostLauncher.forMediaHost("--unused", EscapeAttemptsMain::class.java.name) ?: return
        val vlc = File("/Applications/VLC.app").takeIf { it.isDirectory } ?: File(root, "VLC.app").apply { mkdirs() }
        val dir = File(extensions, ".media-host/one").apply { mkdirs() }
        File(dir, "mv").writeText("mine")
        dropLibrary(dir)
        val list = attempts(dir)
        File(dir, "attempts.txt").writeText(list.joinToString("\n") { it.second })
        val process = launcher.startMediaHost(vlc, dir, silent = true)
        val errors = StringBuilder()
        val drain = thread { process.errorStream.bufferedReader().forEachLine { if (errors.length < MAX_ERROR_CHARS) errors.appendLine(it) } }
        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)
        process.destroyForcibly()
        drain.join()
        val byAttempt = output.filter { it.startsWith("RESULT\t") }.map { it.split('\t') }.associate { it[1] to it[2] }
        assertEquals(list.size, byAttempt.size, "the host stopped early: $errors")
        val results = list.map { (what, attempt) -> what to byAttempt.getValue(attempt) }
        printTable("media host", results)
        assertContained(results.toMap(), nativeAllowed = true)
    }

    /** A real library, from the JVM running this test, so that a failure to load it is about where it is. */
    private fun dropLibrary(directory: File) {
        val library = File(System.getProperty("java.home"), "lib/libj2pcsc.dylib").takeIf { it.isFile }
            ?: File(System.getProperty("java.home"), "lib/libnet.dylib")
        library.copyTo(File(directory, "lib.dylib"))
    }

    /**
     * The same policy with no JVM in the way: a shell, whose builtins make the system calls directly. A JVM could
     * fail any of these for its own reasons, and `exec` above is stopped at its helper before the kernel sees the target.
     */
    @Test
    fun `a native process under the policy`() {
        if (!File(SeatbeltProfile.SANDBOX_EXEC).canExecute()) return
        val shell = File("/bin/bash") // `/bin/sh` is a stub that execs a shell, which this policy refuses.
        val profile = SeatbeltProfile.render(shell, emptyList(), emptyList(), own, network = true)
        fun run(script: String): Int {
            val process = ProcessBuilder(SeatbeltProfile.SANDBOX_EXEC, "-p", profile, shell.path, "-c", script)
                .directory(own).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)
            return process.exitValue()
        }
        val key = File(root, ".ssh/id_ed25519")
        val results = listOf(
            "shell control: runs, and reads its own directory" to run("echo x > own.txt && read a < own.txt"),
            "shell: read the decoy key" to run("read a < '${key.path}'"),
            "shell: write outside" to run("echo x > '${File(root, "dropped").path}'"),
            "shell: exec /usr/bin/id" to run("/usr/bin/id"),
            "shell: signal another process" to run("kill -0 ${decoy!!.pid()}"),
        )
        results.forEach { (what, code) -> println("ESCAPE $what\texit $code") }
        assertEquals(0, results[0].second, "the control")
        results.drop(1).forEach { (what, code) -> assertTrue(code != 0, "$what succeeded") }
        assertTrue(!File(root, "dropped").exists())
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

    private companion object {
        const val WAIT_SECONDS = 120L
        const val MAX_ERROR_CHARS = 2_000
    }
}
