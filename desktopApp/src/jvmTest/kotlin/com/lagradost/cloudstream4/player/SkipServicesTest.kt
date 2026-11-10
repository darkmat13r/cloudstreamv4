package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream4.feature.detail.TitleIds
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Dns

/**
 * The skip services' client (`KMP-86`), against one server on this machine standing in for all three hosts: a [Dns]
 * sends their names, and one other, to loopback. Plain http here, so the server needs no certificate; https itself is
 * its own test.
 */
class SkipServicesTest {

    private class Seen(val host: String, val uri: String, val headers: Map<String, List<String>>)

    private class Reply(val status: Int = 200, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

    private val seen = CopyOnWriteArrayList<Seen>()

    /** Answers by host; anything not given is a 404 with no body. */
    private var replies: (host: String, uri: String) -> Reply? = { _, _ -> null }

    /** A host that answers 200 and then sends its body a byte at a time, [DRIP_STEP_MS] apart, for [DRIP_FOR_MS]. */
    private var drips: String? = null

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val host = exchange.requestHeaders.getFirst("Host").substringBefore(':')
            val uri = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            seen += Seen(host, uri, exchange.requestHeaders.mapKeys { it.key.lowercase() })
            if (host == drips) {
                exchange.sendResponseHeaders(200, 0)
                runCatching {
                    exchange.responseBody.use { body ->
                        repeat((DRIP_FOR_MS / DRIP_STEP_MS).toInt()) {
                            body.write('['.code)
                            body.flush()
                            Thread.sleep(DRIP_STEP_MS)
                        }
                    }
                }
                return@createContext
            }
            val reply = replies(host, uri) ?: Reply(status = 404)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(reply.status, if (reply.body.isEmpty()) -1 else reply.body.size.toLong())
            exchange.responseBody.use { it.write(reply.body) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val loopbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname in SkipServices.HOSTS || hostname == OTHER_HOST) {
                listOf(InetAddress.getLoopbackAddress())
            } else {
                throw UnknownHostException(hostname)
            }
    }

    private fun services(enabled: () -> Boolean = { true }, httpsOnly: Boolean = false, callTimeoutSeconds: Long = 10) = SkipServices(
        enabled = enabled,
        client = SkipServices.skipHttpClient(
            allow = { it.address.isLoopbackAddress },
            httpsOnly = httpsOnly,
            dns = loopbackDns,
            callTimeoutSeconds = callTimeoutSeconds,
        ),
        scheme = "http",
        port = server.address.port,
    )

    private fun json(text: String) = Reply(body = text.toByteArray(), headers = mapOf("Content-Type" to "application/json"))

    private val anime = SkipQuery("Anime", TitleIds(malId = 21, tmdbId = 37854), season = 1, episode = 5, movie = false)

    private val aniSkipOpening = json(
        """{"found":true,"results":[{"interval":{"startTime":90.5,"endTime":180.0},"skipType":"op","skipId":"x","episodeLength":1440.0}],"message":"ok","statusCode":200}"""
    )

    private fun stamps(services: SkipServices, query: SkipQuery = anime, durationMs: Long = 1_440_500L) =
        runBlocking { services.stamps(query, durationMs) }

    @Test
    fun `AniSkip is asked exactly as Android asks, and sent nothing more`() {
        replies = { host, _ -> aniSkipOpening.takeIf { host == SkipServices.ANISKIP_HOST } }
        assertEquals(listOf(SkipStamp(SkipKind.Opening, 90_500, 180_000)), stamps(services()))
        val request = seen.single()
        assertEquals(SkipServices.ANISKIP_HOST, request.host)
        // Android's string, byte for byte: brackets unencoded, and the length the player's own, in whole seconds.
        assertEquals(
            "/v2/skip-times/21/5?types[]=ed&types[]=mixed-ed&types[]=mixed-op&types[]=op&types[]=recap&episodeLength=1440",
            request.uri,
        )
        assertEquals(listOf(USER_AGENT), request.headers["user-agent"])
        assertNull(request.headers["referer"])
        assertNull(request.headers["cookie"])
    }

    @Test
    fun `a body that drips past the call's time is no stamps, and the wait ends with it`() {
        assertEquals(10_000, SkipServices.skipHttpClient().callTimeoutMillis, "the real client's whole call: 10 s")
        drips = SkipServices.ANISKIP_HOST
        // AniSkip alone is asked: anime, with a MAL id and nothing else.
        val onlyAniSkip = anime.copy(ids = TitleIds(malId = 21))
        val started = System.nanoTime()
        assertEquals(emptyList(), stamps(services(callTimeoutSeconds = 1), onlyAniSkip))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf(SkipServices.ANISKIP_HOST), seen.map { it.host })
        assertTrue(tookMs in 1_000 until DRIP_FOR_MS, "given up at the call's time, ${tookMs} ms, not at the body's end")
    }

    @Test
    fun `the first service with stamps is the last one asked`() {
        // AniSkip has none for this episode, so TheIntroDB is asked, has some, and IntroDb never is.
        val series = anime.copy(typeName = "TvSeries", ids = TitleIds(tmdbId = 37854, imdbId = "tt0388629"), season = 2, episode = 3)
        replies = { host, _ ->
            when (host) {
                SkipServices.THEINTRODB_HOST -> json("""{"tmdb_id":37854,"type":"tv","intro":[{"start_ms":1000,"end_ms":61000}]}""")
                else -> json("""{"intro":{"start_ms":5,"end_ms":6}}""")
            }
        }
        assertEquals(listOf(SkipStamp(SkipKind.Intro, 1_000, 61_000)), stamps(services(), series))
        assertEquals(listOf("${SkipServices.THEINTRODB_HOST} /v2/media?tmdb_id=37854&season=2&episode=3"), seen.map { "${it.host} ${it.uri}" })
    }

    @Test
    fun `a service with none passes to the next, in Android's order`() {
        replies = { host, _ ->
            when (host) {
                SkipServices.ANISKIP_HOST -> json("""{"found":false,"results":[],"message":"none","statusCode":404}""")
                SkipServices.THEINTRODB_HOST -> json("""{"tmdb_id":37854,"type":"tv","credits":[{"start_ms":1380000}]}""")
                else -> null
            }
        }
        // TheIntroDB's credits with no end run to the end, as Android reads them.
        assertEquals(listOf(SkipStamp(SkipKind.Credits, 1_380_000, 1_440_500)), stamps(services()))
        assertEquals(listOf(SkipServices.ANISKIP_HOST, SkipServices.THEINTRODB_HOST), seen.map { it.host })
    }

    @Test
    fun `IntroDb is asked by IMDb id, and a film asks TheIntroDB with no episode`() {
        replies = { host, _ -> json("""{"intro":{"start_ms":0,"end_ms":30000},"outro":{"start_ms":2500000}}""").takeIf { host == SkipServices.INTRODB_HOST } }
        val series = SkipQuery("TvSeries", TitleIds(imdbId = "tt0388629"), season = 2, episode = 3, movie = false)
        // The outro has no end, which IntroDb's reading drops, as Android's does.
        assertEquals(listOf(SkipStamp(SkipKind.Opening, 0, 30_000)), stamps(services(), series, 2_600_000))
        assertEquals(
            listOf("/v2/media?imdb_id=tt0388629&season=2&episode=3", "/segments?imdb_id=tt0388629&season=2&episode=3"),
            seen.map { it.uri },
        )
        seen.clear()
        stamps(services(), SkipQuery("Movie", TitleIds(tmdbId = 603), null, null, movie = true), 8_000_000)
        assertEquals(listOf("/v2/media?tmdb_id=603"), seen.map { it.uri })
    }

    @Test
    fun `off sends nothing at all`() {
        replies = { _, _ -> aniSkipOpening }
        var on = false
        val services = services(enabled = { on })
        assertEquals(emptyList(), stamps(services))
        assertEquals(0, seen.size)
        on = true
        assertEquals(1, stamps(services).size)
        assertEquals(1, seen.size, "and on again, it asks")
    }

    @Test
    fun `a redirect is not followed, to another host or anywhere`() {
        val elsewhere = "http://$OTHER_HOST:${server.address.port}/collect"
        replies = { host, _ -> if (host == OTHER_HOST) aniSkipOpening else Reply(status = 302, headers = mapOf("Location" to elsewhere)) }
        assertEquals(emptyList(), stamps(services()))
        assertTrue(seen.none { it.host == OTHER_HOST }, "the fourth host was never asked")
        assertEquals(listOf(SkipServices.ANISKIP_HOST, SkipServices.THEINTRODB_HOST), seen.map { it.host })
    }

    @Test
    fun `a redirect is not followed even to one of the three`() {
        // Anime is never asked of IntroDb, so any request reaching it was a redirect followed.
        val sideways = "http://${SkipServices.INTRODB_HOST}:${server.address.port}/segments?imdb_id=tt0388629&season=1&episode=1"
        replies = { host, _ -> if (host == SkipServices.ANISKIP_HOST) Reply(status = 302, headers = mapOf("Location" to sideways)) else null }
        assertEquals(emptyList(), stamps(services()))
        assertTrue(seen.none { it.host == SkipServices.INTRODB_HOST }, seen.map { it.host }.toString())
    }

    @Test
    fun `a hop to any other host is refused before it is sent`() {
        val client = SkipServices.skipHttpClient(allow = { it.address.isLoopbackAddress }, httpsOnly = false, dns = loopbackDns)
        val request = okhttp3.Request.Builder().url("http://$OTHER_HOST:${server.address.port}/x").build()
        val failure = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
        assertTrue(failure is java.io.IOException, failure.toString())
        assertEquals(0, seen.size)
    }

    @Test
    fun `plain http is refused before it is sent`() {
        replies = { _, _ -> aniSkipOpening }
        assertEquals(emptyList(), stamps(services(httpsOnly = true)))
        assertEquals(0, seen.size)
    }

    @Test
    fun `only finite stamps inside the episode are kept, sixteen at most`() {
        val good = (0 until 20).joinToString(",") { i ->
            """{"interval":{"startTime":${i * 10}.0,"endTime":${i * 10 + 5}.0},"skipType":"op","skipId":"$i","episodeLength":1440.0}"""
        }
        val bad = listOf(-1.0 to 5.0, 50.0 to 50.0, 60.0 to 55.0, 100.0 to 1441.0, 1e300 to 2e300).joinToString(",") { (start, end) ->
            """{"interval":{"startTime":$start,"endTime":$end},"skipType":"ed","skipId":"b","episodeLength":1440.0}"""
        }
        replies = { host, _ -> json("""{"found":true,"results":[$bad,$good]}""").takeIf { host == SkipServices.ANISKIP_HOST } }
        val kept = stamps(services())
        assertEquals((0 until 16).map { SkipStamp(SkipKind.Opening, it * 10_000L, it * 10_000L + 5_000L) }, kept)
    }

    @Test
    fun `a body past the cap is no stamps, on the wire and after gunzip`() {
        val padding = " ".repeat(SkipServices.MAX_BYTES.toInt())
        val text = """{"found":true,"results":[{"interval":{"startTime":1.0,"endTime":2.0},"skipType":"op","skipId":"x","episodeLength":1.0}]}"""
        replies = { host, _ -> json(text + padding).takeIf { host == SkipServices.ANISKIP_HOST } }
        assertEquals(emptyList(), stamps(services()), "past the cap as sent")
        // A few kilobytes on the wire that open to more than the cap: only the read after OkHttp's gunzip sees it.
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write((text + padding).toByteArray()) } }.toByteArray()
        assertTrue(zipped.size < SkipServices.MAX_BYTES / 100)
        replies = { host, _ ->
            Reply(body = zipped, headers = mapOf("Content-Encoding" to "gzip", "Content-Type" to "application/json"))
                .takeIf { host == SkipServices.ANISKIP_HOST }
        }
        assertEquals(emptyList(), stamps(services()), "past the cap once opened")
        // The same text without the padding is read, so it is the cap that refused it.
        replies = { host, _ -> json(text).takeIf { host == SkipServices.ANISKIP_HOST } }
        assertEquals(1, stamps(services(), durationMs = 3_000).size)
    }

    @Test
    fun `numbers out of range ask nothing`() {
        replies = { _, _ -> aniSkipOpening }
        stamps(services(), anime.copy(episode = 10_001, season = -1))
        stamps(services(), anime.copy(episode = null))
        stamps(services(), anime, durationMs = 999)
        assertEquals(0, seen.size)
    }

    @Test
    fun `an episode is asked about once`() {
        replies = { host, _ -> aniSkipOpening.takeIf { host == SkipServices.ANISKIP_HOST } }
        val services = services()
        val first = stamps(services)
        assertEquals(first, stamps(services))
        assertEquals(1, seen.size)
    }

    @Test
    fun `nothing a service sent is kept from a reply that is not json`() {
        replies = { _, _ -> Reply(body = "<html>SECRET</html>".toByteArray()) }
        assertEquals(emptyList(), stamps(services()))
    }

    private companion object {
        const val OTHER_HOST = "collector.example"
        const val DRIP_STEP_MS = 100L
        const val DRIP_FOR_MS = 4_000L
    }
}
