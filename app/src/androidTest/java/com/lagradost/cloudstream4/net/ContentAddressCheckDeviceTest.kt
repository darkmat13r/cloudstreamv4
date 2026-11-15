package com.lagradost.cloudstream4.net

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.utils.ImageLoader
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream4.download.EngineDownloadSwitch
import com.lagradost.cloudstream4.download.EngineVideoDownload
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BUG-109`: a url a page chose never makes Android connect to an address on the user's own network, through any of
 * the content clients: images, the player's subtitle, audio and video sources (OkHttp), the download engine and the
 * legacy downloader's direct files. A server on this device's 127.0.0.1 counts the connections it is offered.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ContentAddressCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: ServerSocket
    private val accepts = AtomicInteger()
    private val requests = AtomicInteger()

    @Volatile
    private var keepAlive = false
    private val port get() = server.localPort

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
                        // Answer each request with a 1-pixel PNG, which every path can take. While [keepAlive] is set
                        // the connection stays open for the next request, so a client can pool it.
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        do {
                            val requestLine = input.readLine() ?: break
                            if (requestLine.isEmpty()) continue
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            requests.incrementAndGet()
                            val connection = if (keepAlive) "keep-alive" else "close"
                            socket.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${PNG.size}\r\n" +
                                        "Accept-Ranges: none\r\nConnection: $connection\r\n\r\n").toByteArray()
                                )
                                write(PNG)
                                flush()
                            }
                        } while (keepAlive)
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
    }

    /** Each content path, fetching [url]; true when it got the content. Never throws for a refusal. */
    private val paths: List<Pair<String, (String) -> Boolean>> = listOf(
        "image" to { url ->
            runBlocking {
                withTimeout(TIME_LIMIT) {
                    ImageLoader.buildImageLoader(context).execute(ImageRequest.Builder(context).data(url).build())
                } is SuccessResult
            }
        },
        "player source" to { url -> playerSource(url, null) },
        "player source with a plugin's interceptor" to { url ->
            playerSource(url, Interceptor { chain -> chain.proceed(chain.request()) })
        },
        "download engine" to { url ->
            runCatching {
                runBlocking { withTimeout(TIME_LIMIT) { EngineVideoDownload.httpClient().get(url).bodyAsBytes() } }
            }.getOrNull()?.contentEquals(PNG) == true
        },
        "direct-file download" to { url ->
            runCatching {
                runBlocking { withTimeout(TIME_LIMIT) { VideoDownloadManager.streamLazy(url, emptyMap(), "", 0L) } }
            }.isSuccess
        },
    )

    private fun playerSource(url: String, interceptor: Interceptor?): Boolean = runCatching {
        val source = OkHttpDataSource.Factory(CS3IPlayer.contentClient(interceptor)).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
            ByteArray(PNG.size).also { source.read(it, 0, it.size) }.contentEquals(PNG)
        } finally {
            source.close()
        }
    }.getOrDefault(false)

    @Test
    fun noContentPathConnectsToLoopbackWrittenAnyWayOverHttpOrHttps() {
        val offered = mutableListOf<String>()
        for ((name, fetch) in paths) for (scheme in listOf("http", "https")) for (host in LOOPBACK) {
            val before = accepts.get()
            val url = "$scheme://$host:$port/poster.png"
            val got = fetch(url)
            Thread.sleep(SETTLE_MS)
            val count = accepts.get() - before
            Log.i(TAG, "path=$name url=$url accepts=$count got=$got")
            if (count != 0 || got) offered += "$name $url: $count connection(s), content=$got"
        }
        assertTrue("connections offered to loopback:\n" + offered.joinToString("\n"), offered.isEmpty())
    }

    @Test
    fun anAddressTheCheckAllowsIsStillFetchedByEveryPath() {
        EngineDownloadSwitch.allowPrivateAddressesForTests = true
        for ((name, fetch) in paths) {
            val before = accepts.get()
            val got = fetch("http://127.0.0.1:$port/poster.png")
            Thread.sleep(SETTLE_MS)
            Log.i(TAG, "path=$name allowed accepts=${accepts.get() - before} got=$got")
            assertTrue("$name did not get the content from an allowed address", got)
            assertTrue("$name made no connection", accepts.get() - before >= 1)
        }
    }

    /**
     * OkHttp gives a pooled connection to any client whose `Address` matches, and the match leaves out the socket factory.
     * So a base client that has the same proxy setting as the checked one (`NO_PROXY`, a reasonable hardening of the
     * plugins' client) opens a kept-alive connection to this device first; the checked client's request to the same
     * address must still be refused, not served on that connection.
     */
    @Test
    fun aConnectionTheBaseClientPooledIsNeverReusedByAContentClient() {
        keepAlive = true
        val base = app.baseClient.newBuilder().proxy(Proxy.NO_PROXY).build()
        val url = "http://127.0.0.1:$port/poster.png"
        val warmed = base.newCall(Request.Builder().url(url).build()).execute().use { it.body.bytes() }
        assertTrue("the base client got the content", warmed.contentEquals(PNG))
        assertTrue("the base client's connection is pooled", base.connectionPool.idleConnectionCount() >= 1)
        val acceptsBefore = accepts.get()
        val requestsBefore = requests.get()
        val got = runCatching {
            ContentClients.checked(base).newCall(Request.Builder().url(url).build()).execute().use { it.body.bytes() }
        }.getOrNull()
        Thread.sleep(SETTLE_MS)
        Log.i(TAG, "pool: accepts=${accepts.get() - acceptsBefore} requests=${requests.get() - requestsBefore} got=${got != null}")
        assertTrue("the content client was served on the base's pooled connection", got == null)
        assertTrue("a request reached the server", requests.get() == requestsBefore)
        assertTrue("a connection was offered", accepts.get() == acceptsBefore)
    }

    private companion object {
        const val TAG = "BUG109"
        const val TIME_LIMIT = 60_000L
        const val SETTLE_MS = 300L
        val LOOPBACK = listOf("127.0.0.1", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "2130706433", "0x7f000001")
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
    }
}
