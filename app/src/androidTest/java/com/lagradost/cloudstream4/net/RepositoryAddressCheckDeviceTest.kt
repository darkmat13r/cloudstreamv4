package com.lagradost.cloudstream4.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream4.download.EngineDownloadSwitch
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BUG-112`: a repository the user added never makes Android connect to an address on the user's own network, through
 * any fetch it drives: its `repo.json`, the plugin lists it names, and a plugin's file. A server on this device's
 * 127.0.0.1 serves all three and counts the connections it is offered.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryAddressCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: ServerSocket
    private val accepts = AtomicInteger()
    private val port get() = server.localPort
    private val files = mutableListOf<File>()

    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepts.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching {
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val path = input.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        val (type, body) = when {
                            path.endsWith("/repo.json") -> "application/json" to repoJson(path.substringBeforeLast('/'))
                            path.endsWith("/plugins.json") -> "application/json" to pluginsJson(path.substringBeforeLast('/'))
                            else -> "application/octet-stream" to PLUGIN_BYTES
                        }
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                            write(body)
                            flush()
                        }
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    @After
    fun stopServer() {
        EngineDownloadSwitch.allowPrivateAddressesForTests = false
        server.close()
        files.forEach { it.delete() }
    }

    /** The lists and files a repository names are on the same host and port as the repository itself. */
    private fun repoJson(base: String) =
        """{"name":"$REPO_NAME","description":null,"iconUrl":null,"manifestVersion":1,"pluginLists":["http://127.0.0.1:$port$base/plugins.json"]}"""
            .toByteArray()

    private fun pluginsJson(base: String) =
        ("""[{"url":"http://127.0.0.1:$port$base/plugin.cs3","status":1,"version":1,"apiVersion":1,"name":"P",""" +
            """"internalName":"P","authors":[],"description":null,"repositoryUrl":null,"tvTypes":null,""" +
            """"language":null,"iconUrl":null,"fileSize":null,"fileHash":null}]""").toByteArray()

    /** Each fetch a repository drives, at [base]; true when it got what the server sent. Never throws for a refusal. */
    private val fetches: List<Pair<String, (String) -> Boolean>> = listOf(
        "repo.json" to { base ->
            runBlocking { withTimeout(TIME_LIMIT) { RepositoryManager.parseRepository("$base/repo.json") } }?.name == REPO_NAME
        },
        "plugin list" to { base ->
            runBlocking {
                withTimeout(TIME_LIMIT) { RepositoryManager.getRepoPlugins(RepositoryData("", REPO_NAME, "$base/repo.json")) }
            }.orEmpty().size == 1
        },
        "plugin list, alone" to { base -> parsePluginsDirectly("$base/plugins.json").size == 1 },
        "plugin file" to { base ->
            val target = File(context.cacheDir, "bug112-${System.nanoTime()}.cs3").also { files += it }
            runBlocking {
                withTimeout(TIME_LIMIT) { RepositoryManager.downloadPluginToFile(context, "$base/plugin.cs3", target, null) }
            }?.readBytes()?.contentEquals(PLUGIN_BYTES) == true
        },
    )

    /**
     * `RepositoryManager.parsePlugins` is private and is reached only after a `repo.json` loads, which no private address
     * can do while the check is on. It is called here directly, so its own fetch is proved on its own.
     */
    private fun parsePluginsDirectly(url: String): List<*> = runBlocking {
        withTimeout(TIME_LIMIT) {
            val method = RepositoryManager::class.java
                .getDeclaredMethod("parsePlugins", String::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                method.invoke(RepositoryManager, url, continuation)
            } as List<*>
        }
    }

    @Test
    fun noRepositoryFetchConnectsToLoopbackWrittenAnyWayOverHttpOrHttps() {
        val offered = mutableListOf<String>()
        for ((name, fetch) in fetches) for (scheme in listOf("http", "https")) for (host in LOOPBACK) {
            val before = accepts.get()
            val base = "$scheme://$host:$port/r"
            val got = fetch(base)
            Thread.sleep(SETTLE_MS)
            val count = accepts.get() - before
            Log.i(TAG, "fetch=$name base=$base accepts=$count got=$got")
            if (count != 0 || got) offered += "$name $base: $count connection(s), content=$got"
        }
        assertTrue("connections offered to loopback:\n" + offered.joinToString("\n"), offered.isEmpty())
    }

    /** The control: with the check off, the same server is reached by every fetch, so the zero above is a refusal. */
    @Test
    fun anAddressTheCheckAllowsIsStillFetchedByEveryRepositoryFetch() {
        EngineDownloadSwitch.allowPrivateAddressesForTests = true
        for ((name, fetch) in fetches) {
            val before = accepts.get()
            val got = fetch("http://127.0.0.1:$port/allowed")
            Thread.sleep(SETTLE_MS)
            val count = accepts.get() - before
            Log.i(TAG, "fetch=$name allowed accepts=$count got=$got")
            assertTrue("$name did not get the content from an allowed address", got)
            assertTrue("$name made no connection", count >= 1)
        }
        assertEquals("every fetch reached the server", true, accepts.get() >= fetches.size)
    }

    private companion object {
        const val TAG = "BUG112"
        const val TIME_LIMIT = 60_000L
        const val SETTLE_MS = 300L
        const val REPO_NAME = "bug112-repository"
        val LOOPBACK = listOf("127.0.0.1", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "2130706433", "0x7f000001")
        val PLUGIN_BYTES = "BUG-112 plugin bytes".toByteArray()
    }
}
