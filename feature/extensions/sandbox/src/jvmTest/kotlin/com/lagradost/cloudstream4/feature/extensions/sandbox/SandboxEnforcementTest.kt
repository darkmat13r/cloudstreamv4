package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.MainPageRequest
import java.io.File
import java.net.ServerSocket
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
                providers.getOrElse { throw AssertionError("host: ${client.redactedLog}", it) }.single().name,
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

    /**
     * Here the call's 60 s runs on virtual time, and everything that has to wait for the child, its
     * startup included, runs on real time. PluginHostClientTest holds the rest of this behaviour.
     */
    private fun runningOnVirtualTime(block: suspend (PluginHostClient) -> Unit) = runTest {
        val launcher = HostLauncher.forThisProcess() ?: return@runTest
        val (client, providers) = withContext(Dispatchers.IO) { PluginHostClient.start(launcher, jar, data, "hostile") }
        host = client
        providers.getOrElse { throw AssertionError("host: ${client.redactedLog}", it) }
        block(client)
    }

    private suspend fun realTimePause() = withContext(Dispatchers.Default) { delay(REPLY_SETTLE_MILLIS) }

    private suspend fun PluginHostClient.realTimeAttempt(action: String): String =
        withContext(Dispatchers.Default) { attempt(action) }

    @Test
    fun `a real plugin answering after the timeout keeps its host`() = runningOnVirtualTime { host ->
        val release = File(data, "release")
        val slow = assertFailsWith<java.io.IOException> { host.attempt("hold:${release.path}") }
        assertTrue("did not answer" in slow.message.orEmpty(), slow.message)

        release.writeText("")
        realTimePause()

        assertEquals("printed", host.realTimeAttempt("print:"))
    }

    @Test
    fun `a real plugin forging a reply to an id never issued ends its host`() = running { host ->
        assertFailsWith<Exception> { host.attempt("forge:$NEVER_ISSUED_ID") }
        assertFailsWith<Exception> { host.attempt("print:") }
    }

    private suspend fun PluginHostClient.firstOf(data: String): List<String> =
        linksAsTheyArrive("Hostile", data).first().links.map { it.name }

    /** Long enough for a real file to appear, and short of the 60 s a call would wait. */
    private suspend fun awaitFile(file: File) = withTimeout(FILE_WAIT_MILLIS) {
        while (!file.exists()) delay(REPLY_SETTLE_MILLIS)
    }

    @Test
    fun `a real link arrives while the real plugin is still working`() = running { host ->
        val release = File(data, "release")
        val seen = Channel<List<String>>(Channel.UNLIMITED)
        coroutineScope {
            val call = launch(Dispatchers.Default) {
                host.linksAsTheyArrive("Hostile", "one-then-wait:${release.path}").collect { seen.send(it.links.map { l -> l.name }) }
            }
            assertEquals(listOf("first"), withTimeout(FILE_WAIT_MILLIS) { seen.receive() })
            assertFalse(call.isCompleted, "the plugin is waiting for a file nobody has written")
            release.writeText("")
        }
        var last = emptyList<String>()
        seen.close()
        for (names in seen) last = names
        assertEquals(listOf("first", "second"), last)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `an honest plugin offering too much is cut, and keeps its host`() = running { host ->
        val answer = host.loadLinks("Hostile", "flood:")
        assertEquals(HostProtocol.MAX_LINKS, answer.links.size)
        assertEquals(HostProtocol.MAX_SUBTITLES, answer.subtitles.size)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `links too large for what a call may send are dropped, and keep the host`() = running { host ->
        val answer = host.loadLinks("Hostile", "oversized:")
        assertEquals("before", answer.links.first().name)
        assertEquals("after", answer.links.last().name)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `a plugin holding its callback past its last reply keeps its host`() = running { host ->
        val release = File(data, "release")
        assertEquals(listOf("first"), host.loadLinks("Hostile", "after-return:${release.path}").links.map { it.name })
        release.writeText("")
        awaitFile(File("${release.path}.done"))
        delay(REPLY_SETTLE_MILLIS)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `leaving stops a plugin that cooperates`() = running { host ->
        val stopped = File(data, "stopped")
        assertEquals(listOf("first"), host.firstOf("stoppable:${stopped.path}"))
        awaitFile(stopped)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `a plugin that will not stop has nothing more delivered, and keeps its host`() = running { host ->
        val release = File(data, "release")
        assertEquals(listOf("first"), host.firstOf("unstoppable:${release.path}"))
        delay(REPLY_SETTLE_MILLIS)
        release.writeText("")
        awaitFile(File("${release.path}.done"))
        delay(REPLY_SETTLE_MILLIS)
        assertEquals("printed", host.attempt("print:"))
    }

    /**
     * The bytes themselves, because the client drops what arrives for a call it gave up on and could not tell
     * a host that stopped sending from one that did not.
     */
    @Test
    fun `told to stop, a real host answers at once and sends nothing more for that call`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val release = File(data, "release")
        val process = launcher.start(
            arguments = listOf(PluginHost.MODE_SERVE, jar.parentFile.canonicalPath, jar.name, data.canonicalPath),
            readOnlyInputs = listOf(jar),
            writable = data,
            network = true,
        )
        val watchdog = thread(isDaemon = true) {
            runCatching { Thread.sleep(FILE_WAIT_MILLIS) }
            process.destroyForcibly()
        }
        try {
            val replies = process.inputStream.buffered()
            fun send(id: Int, op: Int, body: java.io.DataOutputStream.() -> Unit = {}) {
                process.outputStream.write(HostProtocol.encode { writeInt(id); writeInt(op); body() })
                process.outputStream.flush()
            }
            fun nextReply(): Pair<Int, Int> =
                HostProtocol.decode(assertNotNull(HostProtocol.readFrame(replies), "the host ended")) { readInt() to readByte().toInt() }

            assertEquals(HostProtocol.READY_ID to HostProtocol.REPLY_DONE, nextReply())
            send(1, HostProtocol.OP_LOAD_LINKS) {
                writeText("Hostile")
                writeText("unstoppable:${release.path}")
            }
            assertEquals(1 to HostProtocol.REPLY_PARTIAL, nextReply())

            send(1, HostProtocol.OP_CANCEL)
            assertEquals(1 to HostProtocol.REPLY_FAILED, nextReply(), "the plugin is still blocked, and the host answered")

            release.writeText("")
            runBlocking { awaitFile(File("${release.path}.done")) }
            send(2, HostProtocol.OP_SEARCH) {
                writeText("Hostile")
                writeText("print:")
                writeInt(1)
            }
            assertEquals(2 to HostProtocol.REPLY_DONE, nextReply(), "the plugin's later link must not have come first")
        } finally {
            watchdog.interrupt()
            process.destroyForcibly()
        }
    }

    @Test
    fun `a real partial is cleaned before the app sees it`() = running { host ->
        val answer = host.loadLinks("Hostile", "bad-urls:")
        assertEquals(listOf("file", "evil[2Jname"), answer.links.map { it.name }, "the URL filter is the source's, DesktopDetailSource")
        assertEquals(listOf("en"), answer.subtitles.map { it.language })
    }

    @Test
    fun `a real plugin failing after a link has answered with that link`() = running { host ->
        val answer = host.loadLinks("Hostile", "throw:")
        assertEquals(listOf("first"), answer.links.map { it.name })
        assertTrue("one source gave up" in answer.stoppedEarly.orEmpty(), answer.stoppedEarly)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `a real plugin forging a partial for an id never issued ends its host`() = running { host ->
        assertFailsWith<Exception> { host.loadLinks("Hostile", "forge-partial:$NEVER_ISSUED_ID") }
        assertFailsWith<Exception> { host.attempt("print:") }
    }

    @Test
    fun `what comes back is cleaned before the app sees it`() = running { host ->
        val item = host.search("Hostile", "hostile-text:", 1).items.single()
        assertEquals("evil[2Jforged line", item.name)
        assertNull(item.posterUrl, "a file: poster must not reach the image loader")
    }

    @Test
    fun `a malicious plugin's facts reach the app bounded, and the page still loads`() = running { host ->
        val page = host.load("Hostile", "<script>")
        val facts = page.facts

        assertEquals("Hostile title", page.name, "a bad fact costs only itself, never the page")
        assertEquals(84, facts.scoreTenths)
        assertEquals(RemoteShowStatus.Ongoing, facts.status)
        assertNull(facts.runtimeMinutes, "Int.MAX_VALUE minutes is not a runtime")
        assertNull(facts.contentRating, "markup is not a certification")
        assertEquals(HostProtocol.MAX_TAGS, facts.tags.size, "100,000 tags are cut to the bound")
        assertEquals("x".repeat(HostProtocol.MAX_TAG_CHARS), facts.tags[0], "a 1 MiB tag is cut to the bound")
        assertEquals(listOf("evil[2Jforged", "right to left", "zerowidthjoin"), facts.tags.subList(1, 4))
        assertTrue(facts.tags.none { it.isBlank() }, "a blank tag is no tag")
        facts.tags.forEach { tag ->
            assertTrue(tag.none { it.isISOControl() || it.category == CharCategory.FORMAT }, "invisible text in a tag")
        }

        assertNull(host.load("Hostile", "PG\n13").facts.contentRating, "a forged line is not a certification")
        assertEquals("FSK 16", host.load("Hostile", "FSK 16").facts.contentRating, "a real one passes")
        assertEquals("allowed", host.attempt("read:${File(data, "x").apply { writeText("") }.path}"), "the host lives on")
    }

    @Test
    fun `a malicious plugin's cast and recommendations reach the app bounded, and the page still loads`() = running { host ->
        val page = host.load("Hostile", "https://hostile.example/self")

        assertEquals("Hostile title", page.name)
        assertEquals(HostProtocol.MAX_CAST, page.cast.size, "100,000 people are cut to the bound")
        assertEquals("y".repeat(HostProtocol.MAX_PERSON_CHARS), page.cast[0].name, "a 1 MiB name is cut by the host")
        val ines = page.cast[1]
        assertEquals("Ines[2J", ines.name, "invisible characters and escapes are gone")
        assertEquals("evilforged", ines.role)
        assertEquals(RemoteActorRole.Main, ines.roleKind)
        assertNull(ines.imageUrl, "the metadata address must not reach the image loader")
        val userinfo = page.cast[2]
        assertEquals("Userinfo", userinfo.name)
        assertNull(userinfo.imageUrl, "a photo with userinfo is refused")
        assertNull(userinfo.voiceActor, "a blank voice actor is none")
        assertEquals("https://img.example/0.jpg", page.cast[3].imageUrl)
        page.cast.forEach { person ->
            assertTrue(person.name.none { it.isISOControl() || it.category == CharCategory.FORMAT }, "invisible text in a name")
        }

        val titles = page.recommendations.map { it.title }
        assertEquals(HostProtocol.MAX_RECOMMENDATIONS - 1, titles.size, "100,000 are cut to the bound, and the app drops itself")
        assertTrue("Another provider's" !in titles, "a recommendation naming another provider never arrives")
        assertTrue("Itself" !in titles, "the page never recommends itself")
        assertTrue("Bad key" !in titles)
        assertEquals("x".repeat(HostProtocol.MAX_RECOMMENDATION_TITLE_CHARS), titles.first(), "a 1 MiB title is cut by the host")
        assertTrue(page.recommendations.all { it.posterUrl == null }, "a file: poster must not reach the image loader")
        assertEquals("allowed", host.attempt("read:${File(data, "x").apply { writeText("") }.path}"), "the host lives on")
    }

    private suspend fun started(): Pair<PluginHostClient, RemoteProvider>? {
        val launcher = HostLauncher.forThisProcess() ?: return null
        val (client, providers) = PluginHostClient.start(launcher, jar, data, "hostile")
        host = client
        return client to providers.getOrThrow().single()
    }

    @Test
    fun `what a provider declares about its main page reaches the app cleaned and bounded`() = runBlocking {
        val (_, provider) = started() ?: return@runBlocking
        assertTrue(provider.hasMainPage)
        assertEquals("evil[2Jsection", provider.mainPage.first().name)
        assertEquals(5, provider.mainPage.size)
        assertEquals(HostProtocol.MAX_SEQUENTIAL_DELAY_MILLIS, provider.sequentialDelayMillis)
    }

    @Test
    fun `a main page comes back cleaned, and a file poster does not`() = running { host ->
        val page = host.mainPage("Hostile", HOSTILE_SECTION, 1)
        val row = page.rows.single()
        assertEquals("row[2Jname", row.name)
        assertEquals("evil[2Jforged", row.items.single().name)
        assertNull(row.items.single().posterUrl)
        assertTrue(page.hasNext)
    }

    @Test
    fun `an oversized main page is cut and does not end the host`() = running { host ->
        val page = host.mainPage("Hostile", 1, 1)
        assertEquals(HostProtocol.MAX_HOME_ROWS, page.rows.size)
        assertEquals(HostProtocol.MAX_HOME_ROW_ITEMS, page.rows.first().items.size)
        assertEquals("allowed", host.attempt("write:${File(data, "still-alive.txt").path}"))
    }

    @Test
    fun `a provider returning nothing has an empty page`() = running { host ->
        assertEquals(RemoteHomePage(emptyList(), false), host.mainPage("Hostile", 2, 1))
    }

    @Test
    fun `one main page failing ends neither the call's neighbours nor the host`() = running { host ->
        val failure = assertFailsWith<Exception> { host.mainPage("Hostile", 3, 7) }
        assertTrue("page 7" in failure.message.orEmpty(), failure.message)
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `a section index the provider never declared fails that call and only that call`() = running { host ->
        listOf(-1, 5, Int.MAX_VALUE, Int.MIN_VALUE).forEach { section ->
            val failure = assertFailsWith<Exception>("section $section") { host.mainPage("Hostile", section, 1) }
            assertTrue("unknown section" in failure.message.orEmpty(), failure.message)
        }
        assertEquals("printed", host.attempt("print:"))
    }

    @Test
    fun `garbage in place of a main page ends that host`() = running { host ->
        assertFailsWith<Exception> { host.mainPage("Hostile", 4, 1) }
        assertFailsWith<Exception> { host.attempt("print:") }
    }

    @Test
    fun `the proxy reaches a main page through the host and refuses a section it never offered`() = runBlocking {
        val (client, remote) = started() ?: return@runBlocking
        val proxy = SandboxedProvider(remote, client)
        assertTrue(proxy.hasMainPage)
        assertTrue(proxy.sequentialMainPage)
        val first = proxy.mainPage.first()
        val page = checkNotNull(
            proxy.getMainPage(1, MainPageRequest(first.name, first.data, first.horizontalImages)),
        )
        assertEquals("evil[2Jforged", page.items.single().list.single().name)
        assertNull(page.items.single().list.single().posterUrl)

        listOf("hostile", "99", "-1", "").forEach { data ->
            val refused = assertFailsWith<java.io.IOException>(data) { proxy.getMainPage(1, MainPageRequest("x", data, false)) }
            assertEquals("unknown main page section", refused.message, "refused here, before asking the host")
        }
    }

    /** It must lose its home page and keep its search, and nothing about it may stop the host starting. */
    @Test
    fun `a provider whose main page throws still loads`() = runBlocking {
        val launcher = HostLauncher.forThisProcess() ?: return@runBlocking
        val broken = File(root, "store/broken.jar")
        writeJar(broken, BrokenMainPagePlugin::class.java, BrokenMainPageApi::class.java)
        val (client, providers) = PluginHostClient.start(launcher, broken, data, "broken")
        host = client
        val provider = providers.getOrElse { throw AssertionError("host: ${client.redactedLog}", it) }.single()
        assertFalse(provider.hasMainPage)
        assertTrue(provider.mainPage.isEmpty())
        assertEquals("still searches", client.search("Broken", "q", 1).items.single().name)
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
            extraRules = SeatbeltProfile.mediaHostRules(vlc, File(root, "jna").apply { mkdirs() }),
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

    private companion object {
        const val HOSTILE_SECTION = 0
        const val NEVER_ISSUED_ID = 1_000_000
        const val REPLY_SETTLE_MILLIS = 1_000L
        const val FILE_WAIT_MILLIS = 20_000L
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
