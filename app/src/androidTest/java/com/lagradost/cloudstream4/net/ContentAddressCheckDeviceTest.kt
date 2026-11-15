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
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.utils.ImageLoader
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream4.download.EngineVideoDownload
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
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
                        // Read the request head, then answer with a 1-pixel PNG, which every path can take.
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${PNG.size}\r\n" +
                                    "Accept-Ranges: none\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            write(PNG)
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
        ContentClients.allowForTests = null
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
        ContentClients.allowForTests = { true }
        for ((name, fetch) in paths) {
            val before = accepts.get()
            val got = fetch("http://127.0.0.1:$port/poster.png")
            Thread.sleep(SETTLE_MS)
            Log.i(TAG, "path=$name allowed accepts=${accepts.get() - before} got=$got")
            assertTrue("$name did not get the content from an allowed address", got)
            assertTrue("$name made no connection", accepts.get() - before >= 1)
        }
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
