@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.player.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.read
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `BUG-114`: the iPad's media gateway. The player's side is a raw HTTP client on the gateway's port; upstream is an
 * [Origin] (or a [victim] that counts connections). The gateway is given an ungated client so local origins can stand
 * in for the internet; the address check itself is `BUG-111`'s, and [theProductionGatewayReachesNoLocalAddress] runs
 * the gated client.
 */
class IosMediaGatewayTest {
    private val chunk = 1000L
    private val limits = IosMediaGateway.Limits(chunkBytes = chunk, maxUnrangedBytes = 5000, idleSeconds = 5)
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = cleanup.reversed().forEach { it() }

    private fun origin(handler: (Origin.Request) -> Origin.Reply) = Origin(handler).also { o -> cleanup += { o.stop() } }

    /** A listener that answers nothing a player would want, and counts connections: the proof a refusal reached nothing. */
    private fun victim() = origin { Origin.Reply(404) }

    private fun gateway(limits: IosMediaGateway.Limits = this.limits, referer: String? = "https://ref.example/", ua: String? = "UA/1"): IosMediaGateway {
        val client = HttpClient(Darwin) { followRedirects = false; expectSuccess = false }
        val gw = assertNotNull(IosMediaGateway.start(referer, ua, client, limits))
        cleanup += { gw.close(); client.close() }
        return gw
    }

    // ---- The player's side ----

    private class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray, val closedEarly: Boolean)

    /** [request] (a whole request head) to the gateway; everything it sends back until it closes. */
    private fun raw(gw: IosMediaGateway, request: String): ByteArray = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        val sa = zeroed<sockaddr_in>()
        sa.sin_len = sizeOf<sockaddr_in>().convert()
        sa.sin_family = AF_INET.convert()
        sa.sin_port = (((gw.port and 0xFF) shl 8) or ((gw.port shr 8) and 0xFF)).toUShort()
        val a = sa.sin_addr.ptr.reinterpret<UByteVar>(); a[0] = 127u; a[1] = 0u; a[2] = 0u; a[3] = 1u
        check(connect(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0)
        Loopback.prepare(fd, 15)
        Loopback.writeAll(fd, request.encodeToByteArray())
        val out = ArrayList<Byte>()
        val buffer = ByteArray(65536)
        while (true) {
            val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
            if (n <= 0) break
            for (i in 0 until n.toInt()) out += buffer[i]
        }
        close(fd)
        out.toByteArray()
    }

    private fun get(gw: IosMediaGateway, target: String, vararg headers: String, method: String = "GET"): Answer {
        val bytes = raw(gw, "$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n")
        val split = (0..bytes.size - 4).firstOrNull { bytes[it] == 13.toByte() && bytes[it + 1] == 10.toByte() && bytes[it + 2] == 13.toByte() && bytes[it + 3] == 10.toByte() }
            ?: return Answer(-1, emptyMap(), ByteArray(0), closedEarly = true)
        val lines = bytes.copyOf(split).decodeToString().split("\r\n")
        val headerMap = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = bytes.copyOfRange(split + 4, bytes.size)
        val length = headerMap["content-length"]?.toLong()
        return Answer(lines[0].split(' ')[1].toInt(), headerMap, body, closedEarly = length != null && body.size.toLong() < length)
    }

    private fun path(url: String) = url.substringAfter("127.0.0.1:").substringAfter('/').let { "/$it" }

    private fun first(gw: IosMediaGateway, upstream: String) = path(assertNotNull(gw.urlFor(upstream)))

    // ---- The token and the request ----

    @Test
    fun aWrongOrMissingTokenGetsOneFixedAnswerAndFetchesNothing() {
        val up = origin { Origin.Reply(200, body = "G".encodeToByteArray()) }
        val gw = gateway()
        val good = first(gw, up.url("/a.ts"))
        val wrongToken = good.replaceRange(1, 23, "BBBBBBBBBBBBBBBBBBBBBB")
        val answers = listOf(wrongToken, "/", "/x/u/https/example.com/a", good.replace("/u/", "/z/")).map { get(gw, it) }
        answers.forEach { assertEquals(404, it.status) }
        assertEquals(1, answers.map { it.body.decodeToString() }.toSet().size, "the answers differ, so they tell something")
        assertEquals(0, up.accepted)
    }

    @Test
    fun onlyGetAndHeadAreServed() {
        val up = origin { Origin.Reply(200) }
        val gw = gateway()
        val target = first(gw, up.url("/a.ts"))
        assertEquals(405, get(gw, target, method = "POST").status)
        assertEquals(400, get(gw, "http://example.com/a", method = "GET").status)
        assertEquals(400, get(gw, "example.com:443", method = "CONNECT").status)
        assertEquals(0, up.accepted)
    }

    // ---- Content decides ----

    @Test
    fun anHlsFirstUrlIsRewrittenSoItNamesOnlyTheGatewayEvenServedAsHtml() {
        val victim = victim()
        // https, as strict requires: the rewrite names it through the gateway and fetches nothing.
        val segment = "https://127.0.0.1:${victim.port}/seg.ts"
        val up = origin { Origin.Reply(200, listOf("Content-Type" to "text/html"), "#EXTM3U\n#EXTINF:4,\n$segment\n#EXT-X-ENDLIST\n".encodeToByteArray()) }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/master.m3u8")))
        assertEquals(200, answer.status)
        val text = answer.body.decodeToString()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { assertTrue(gw.isGatewayUrl(it), it) }
        assertEquals(0, victim.accepted)
    }

    @Test
    fun aManifestOrAnotherPlaylistFormatIsRefusedHoweverItIsLabelled() {
        val victim = victim()
        val bodies = listOf(
            "<?xml version=\"1.0\"?><MPD><BaseURL>${victim.url("/")}</BaseURL></MPD>" to "application/dash+xml",
            "G" + "x".repeat(300) + "<MPD><BaseURL>${victim.url("/")}</BaseURL></MPD>" to "video/mp2t",
            "<SmoothStreamingMedia><QualityLevel Url=\"${victim.url("/")}\"/></SmoothStreamingMedia>" to "video/mp4",
            "<manifest xmlns=\"http://ns.adobe.com/f4m/1.0\"><baseURL>${victim.url("/")}</baseURL></manifest>" to "video/mp4",
            "[playlist]\nFile1=${victim.url("/pls")}\n" to "video/mp2t",
            "<?xml version=\"1.0\"?><playlist><trackList><track><location>${victim.url("/x")}</location></track></trackList></playlist>" to "video/mp4",
            "#EXTINF:-1,x\n${victim.url("/m3u")}\n" to "video/mp2t",
        )
        for ((body, type) in bodies) {
            val up = origin { Origin.Reply(200, listOf("Content-Type" to type), body.encodeToByteArray()) }
            val gw = gateway()
            val answer = get(gw, first(gw, up.url("/movie.ts")))
            assertEquals(502, answer.status, body.take(40))
            assertFalse(victim.port.toString() in answer.body.decodeToString())
        }
        assertEquals(0, victim.accepted)
    }

    @Test
    fun aFirstUrlThatIsNotAKnownContainerIsRefused() {
        val up = origin { Origin.Reply(200, body = "just some text".encodeToByteArray()) }
        val gw = gateway()
        assertEquals(502, get(gw, first(gw, up.url("/x"))).status)
        assertEquals(1, gw.failures[IosMediaGateway.Failure.NotMedia])
    }

    @Test
    fun aSegmentThatIsAPlaylistIsRefused() {
        val victim = victim()
        val segment = "#EXTM3U\n${victim.url("/x.ts")}\n".encodeToByteArray()
        val up = origin(Origin.rangedFile(segment))
        val gw = gateway()
        // What a rewritten playlist would name for a segment (a strict playlist names https only; this origin is http).
        val segmentUrl = assertNotNull(gw.urlFor(up.url("/seg.ts"), GatewayRole.Media))
        val answer = get(gw, path(segmentUrl))
        assertTrue(answer.status == 502 || answer.closedEarly || answer.body.isEmpty(), "a playlist was served as a segment")
        assertFalse(victim.port.toString() in answer.body.decodeToString())
        assertEquals(0, victim.accepted)
    }

    /** The offset-0 decision is kept per stream, and a seek-first request is judged by bytes 0 to 4095 as well. */
    @Test
    fun aSeekFirstRequestOnADisguisedManifestIsJudgedByItsStart() {
        val victim = victim()
        val body = ("<MPD><BaseURL>${victim.url("/")}</BaseURL></MPD>".padEnd(5000, ' ')).encodeToByteArray() + Origin.ts(5000)
        val up = origin(Origin.rangedFile(body))
        val gw = gateway()
        val target = first(gw, up.url("/movie.ts"))
        assertEquals(502, get(gw, target, "Range: bytes=5000-").status)
        assertEquals("bytes=0-4095", up.requests.first().header("Range"), "the probe was not of the start")
        assertEquals(502, get(gw, target, "Range: bytes=6000-").status)
        assertEquals(1, up.requests.count { it.header("Range") == "bytes=0-4095" }, "the verdict was not kept")
        assertEquals(0, victim.accepted)
    }

    /**
     * Commit security review: the probe and the fetch are two requests, and an origin can answer them differently. Here
     * it answers the probe (bytes 0 to 4095) with MPEG-TS and the fetch with a playlist: the served bytes are judged.
     */
    @Test
    fun anOriginAnsweringTheProbeAndTheFetchDifferentlyIsRefused() {
        val victim = victim()
        val playlist = "#EXTM3U\n#EXTINF:4,\nhttp://127.0.0.1:${victim.port}/seg.ts\n".encodeToByteArray()
        val media = Origin.ts(3000)
        val up = origin { r ->
            if (r.header("Range") == "bytes=0-4095") Origin.rangedFile(media)(r)
            else Origin.Reply(206, listOf("Content-Range" to "bytes 0-${playlist.size - 1}/${playlist.size}"), playlist)
        }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")))
        assertEquals(502, answer.status, "a playlist was served after a media probe")
        assertFalse("seg.ts" in answer.body.decodeToString())
        assertEquals(1, gw.failures[IosMediaGateway.Failure.Refused])
        assertEquals(0, victim.accepted)
    }

    /** The same, on a seek-first request: the probe's start is media, the bytes at the seek are a manifest. */
    @Test
    fun aSeekWhoseBytesAreAManifestIsRefusedWhateverTheProbeSaid() {
        val victim = victim()
        val manifest = "<MPD><BaseURL>http://127.0.0.1:${victim.port}/</BaseURL></MPD>".padEnd(2000, ' ').encodeToByteArray()
        val body = Origin.ts(5000) + manifest
        val up = origin(Origin.rangedFile(body))
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")), "Range: bytes=5000-")
        assertEquals(502, answer.status)
        assertFalse("BaseURL" in answer.body.decodeToString())
        assertEquals(0, victim.accepted)
    }

    /** libVLC picks a playlist reader by the location's extension too: the last `.` of the whole url, query included. */
    @Test
    fun aFirstUrlEndingInAPlaylistExtensionIsRefusedUnlessItIsHls() {
        val victim = victim()
        val up = origin { r ->
            if (r.path.startsWith("/list")) Origin.Reply(200, body = "#EXTM3U\n#EXTINF:4,\nhttps://cdn.example/a.ts\n".encodeToByteArray())
            else Origin.rangedFile(Origin.ts(2000))(r)
        }
        val gw = gateway()
        for (path in listOf("/movie.m3u", "/movie.ts?f=.m3u", "/movie.PLS", "/x.asx", "/x.xspf", "/x.ts?.sdp")) {
            assertEquals(502, get(gw, first(gw, up.url(path))).status, path)
        }
        assertEquals(200, get(gw, first(gw, up.url("/list.m3u8"))).status, "an HLS playlist named .m3u8")
        assertEquals(200, get(gw, first(gw, up.url("/movie.ts?v=1.0"))).status, "a container with a dotted query")
        assertEquals(0, victim.accepted)
    }

    /** A "TS" (sync bytes at 0 and 188) with a line between them that starts with a url is a playlist to libVLC. */
    @Test
    fun aContainerHidingAUrlLineIsRefused() {
        val victim = victim()
        val body = Origin.ts(3000).also { b -> "\nhttps://127.0.0.1:${victim.port}/x\n".encodeToByteArray().copyInto(b, 10) }
        val up = origin(Origin.rangedFile(body))
        val gw = gateway()
        assertEquals(502, get(gw, first(gw, up.url("/movie.ts"))).status)
        assertEquals(0, victim.accepted)
    }

    /** A weak ETag is never sent as If-Range (a server must answer it with the whole body); it is compared instead. */
    @Test
    fun aWeakEtagStreamsToTheEnd() {
        val body = Origin.ts(3500)
        val up = origin(Origin.rangedFile(body, "W/\"weak\""))
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")))
        assertEquals(200, answer.status)
        assertContentEquals(body, answer.body)
        up.requests.forEach { assertNull(it.header("If-Range"), "a weak ETag was sent as If-Range") }
        assertNull(gw.failures[IosMediaGateway.Failure.ObjectChanged])
    }

    // ---- prepare: what the player is given, and the demuxer it is told to use ----

    @Test
    fun prepareGivesTheGatewayUrlAndTheDemuxerToForce() = kotlinx.coroutines.runBlocking {
        val mp4 = ftyp + plainMoov + mdat(2000)
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(2000)
        val up = origin { r ->
            when {
                r.path.startsWith("/hls") -> Origin.Reply(200, body = "#EXTM3U\n#EXTINF:4,\nhttps://cdn.example/a.ts\n".encodeToByteArray())
                r.path.startsWith("/mp4") -> Origin.rangedFile(mp4)(r)
                r.path.startsWith("/mkv") -> Origin.rangedFile(mkv)(r)
                else -> Origin.rangedFile(Origin.ts(3000))(r)
            }
        }
        val gw = gateway()
        val expected = mapOf("/hls.m3u8" to "adaptive", "/ts.ts" to "ts", "/mp4.mp4" to "mp4", "/mkv.mkv" to "mkv")
        for ((path, demux) in expected) {
            val stream = assertNotNull(gw.prepare(up.url(path)), path)
            assertEquals(demux, stream.demux, path)
            assertTrue(gw.isGatewayUrl(stream.url), path)
        }
        // The probe prepare made is the one the gateway keeps: playing the stream does not probe it again.
        val stream = assertNotNull(gw.prepare(up.url("/ts.ts")))
        assertEquals(200, get(gw, path(stream.url)).status)
        assertEquals(1, up.requests.count { it.path == "/ts.ts" && it.header("Range") == "bytes=0-4095" })
    }

    @Test
    fun prepareRefusesWhatTheGatewayWouldRefuse() = kotlinx.coroutines.runBlocking {
        val victim = victim()
        val up = origin { r ->
            when {
                r.path.startsWith("/mpd") -> Origin.Reply(200, body = "<MPD><BaseURL>http://127.0.0.1:${victim.port}/</BaseURL></MPD>".encodeToByteArray())
                r.path.startsWith("/text") -> Origin.Reply(200, body = "just text".encodeToByteArray())
                else -> Origin.rangedFile(Origin.ts(3000))(r)
            }
        }
        val gw = gateway()
        assertNull(gw.prepare(up.url("/mpd")))
        assertNull(gw.prepare(up.url("/text")))
        assertNull(gw.prepare(up.url("/movie.m3u")), "a container with a playlist extension")
        assertNull(gw.prepare("ftp://127.0.0.1:${victim.port}/x"))
        assertEquals(0, victim.accepted)
    }

    private fun utf16le(text: String) =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.flatMap { c -> listOf((c.code and 0xFF).toByte(), (c.code shr 8).toByte()) }

    private fun utf16be(text: String) =
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.flatMap { c -> listOf((c.code shr 8).toByte(), (c.code and 0xFF).toByte()) }

    /**
     * UTF-16: libVLC's HDS reader (a stream filter: probed on the first bytes the player gets, whatever demuxer it is
     * told to use, and it fetches by itself) decodes UTF-16 before it looks for `<manifest`. Each body is refused by the
     * probe ([IosMediaGateway.prepare]) and, at a seek whose probe was media, by the bytes served. Plain media at the
     * same seek is the control: it passes.
     */
    @Test
    fun utf16PlaylistsAndManifestsAreRefusedInTheProbeAndInTheBytesServed() = kotlinx.coroutines.runBlocking {
        val victim = victim()
        val v = "http://127.0.0.1:${victim.port}/"
        val bodies = mapOf(
            "f4m LE" to utf16le("<manifest xmlns=\"http://ns.adobe.com/f4m/1.0\"><baseURL>$v</baseURL><media url=\"x\"/></manifest>"),
            "f4m BE" to utf16be("<manifest xmlns=\"http://ns.adobe.com/f4m/1.0\"><baseURL>$v</baseURL><media url=\"x\"/></manifest>"),
            "#EXTM3U" to utf16le("#EXTM3U\n#EXTINF:4,\n${v}seg.ts\n"),
            "url line" to utf16le("x\n${v}seg.ts\n"),
        )
        for ((name, wide) in bodies) {
            val up = origin { r -> if (r.path == "/alone") Origin.rangedFile(wide)(r) else Origin.rangedFile(Origin.ts(5000) + wide)(r) }
            val gw = gateway()
            assertNull(gw.prepare(up.url("/alone")), "$name: the probe let it through")
            val answer = get(gw, first(gw, up.url("/movie.ts")), "Range: bytes=5000-")
            assertEquals(502, answer.status, "$name: served at a seek")
        }
        // The control: plain media at the same seek.
        val plain = origin(Origin.rangedFile(Origin.ts(5000) + Origin.ts(2000)))
        val gw = gateway()
        assertEquals(206, get(gw, first(gw, plain.url("/movie.ts")), "Range: bytes=5000-").status)
        assertEquals(0, victim.accepted)
    }

    /** The demuxer forced is the probe's kind; bytes served from 0 must be that kind, or libVLC would be misled. */
    @Test
    fun theBytesServedFromTheStartMustBeTheKindTheDemuxerWasChosenFor() = kotlinx.coroutines.runBlocking {
        val mp4 = ftyp + plainMoov + mdat(3000)
        // As long as the MP4: a file of another length is refused before its bytes are looked at (BUG-119).
        val ts = Origin.ts(mp4.size)
        val up = origin { r -> if (r.header("Range") == "bytes=0-4095") Origin.rangedFile(mp4)(r) else Origin.rangedFile(ts)(r) }
        val gw = gateway()
        assertEquals("mp4", assertNotNull(gw.prepare(up.url("/movie"))).demux)
        assertEquals(502, get(gw, first(gw, up.url("/movie"))).status, "TS was served where MP4 was forced")
        assertEquals(1, gw.failures[IosMediaGateway.Failure.NotMedia])
    }

    // ---- BUG-119: an MP4's boxes, wherever its moov is ----

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray = ByteArray(0)) = u32(8L + payload.size) + type.encodeToByteArray() + payload
    private val ftyp = box("ftyp", "isom".encodeToByteArray() + ByteArray(16))
    private val mvhd = box("mvhd", ByteArray(100))
    private fun mdat(size: Int) = box("mdat", Origin.pattern(size))

    /** A reference movie: libVLC would add [url] as a subitem of the stream. */
    private fun rmra(url: String) =
        box("rmra", box("rmda", box("rdrf", u32(0) + "url ".encodeToByteArray() + u32(url.length.toLong()) + url.encodeToByteArray())))

    private val plainMoov = box("moov", mvhd)
    private fun refMoov(url: String) = box("moov", mvhd + rmra(url))

    /** A plain moov exactly as long as [refMoov] for [url]: the same file layout, with no reference. */
    private fun paddedMoov(url: String) = box("moov", mvhd + box("free", ByteArray(rmra(url).size - 8)))

    private fun ByteArray.has(text: String): Boolean {
        val t = text.encodeToByteArray()
        return (0..size - t.size).any { i -> t.indices.all { this[i + it] == t[it] } }
    }

    /**
     * The case a check of the probed first 4096 bytes alone would miss: a reference movie whose moov is at the file's
     * tail. Refused by [IosMediaGateway.prepare], from offset 0 and at a seek; the same file with a plain moov plays.
     */
    @Test
    fun aReferenceMovieAtTheTailIsRefusedAndAPlainOnePlays() = kotlinx.coroutines.runBlocking {
        val victim = victim()
        val ref = ftyp + mdat(20_000) + refMoov(victim.url("/x.ts"))
        val refOrigin = origin(Origin.rangedFile(ref))
        val gw = gateway()
        assertNull(gw.prepare(refOrigin.url("/movie.mp4")), "prepare let a tail rmra through")
        val walked = refOrigin.requests.size
        assertEquals(502, get(gw, first(gw, refOrigin.url("/movie.mp4"))).status)
        assertEquals(502, get(gw, first(gw, refOrigin.url("/movie.mp4")), "Range: bytes=20000-").status, "at a seek")
        assertEquals(2, gw.failures[IosMediaGateway.Failure.Mp4Refused])
        // The verdict is kept: the refused file is not fetched again for the player.
        assertEquals(walked, refOrigin.requests.size)
        // The control: the same file with a plain moov.
        val plain = ftyp + mdat(20_000) + plainMoov
        val plainOrigin = origin(Origin.rangedFile(plain))
        val stream = assertNotNull(gw.prepare(plainOrigin.url("/movie.mp4")))
        assertEquals("mp4", stream.demux)
        val answer = get(gw, path(stream.url))
        assertEquals(200, answer.status)
        assertContentEquals(plain, answer.body)
        assertEquals(0, victim.accepted)
    }

    /** A size-0 box after the moov runs to the end of the file: an ordinary mdat, and it plays. */
    @Test
    fun aMoovThenASizeZeroMdatPlays() = kotlinx.coroutines.runBlocking {
        val file = ftyp + plainMoov + u32(0) + "mdat".encodeToByteArray() + Origin.pattern(5000)
        val up = origin(Origin.rangedFile(file))
        val gw = gateway()
        val stream = assertNotNull(gw.prepare(up.url("/movie.mp4")))
        assertContentEquals(file, get(gw, path(stream.url)).body)
    }

    /**
     * An origin that answers the walk with a plain moov and the player with a reference movie: the gateway serves the
     * very bytes it judged, or none. From offset 0 the response ends before the changed moov; at a seek into it, nothing
     * is promised at all.
     */
    @Test
    fun aMoovChangedAfterItWasJudgedIsNeverServed() = kotlinx.coroutines.runBlocking {
        val victim = victim()
        val url = victim.url("/x.ts")
        val clean = ftyp + mdat(6000) + paddedMoov(url)
        val ref = ftyp + mdat(6000) + refMoov(url)
        assertEquals(clean.size, ref.size)
        val swapped = kotlin.concurrent.AtomicInt(0)
        val up = origin { r -> Origin.rangedFile(if (swapped.value == 1) ref else clean)(r) }
        val gw = gateway()
        val stream = assertNotNull(gw.prepare(up.url("/movie.mp4")))
        swapped.value = 1
        val whole = get(gw, path(stream.url))
        assertTrue(whole.closedEarly, "the response was not cut")
        assertFalse(whole.body.has("rmra"), "the reference movie reached the player")
        val moovAt = ref.size - refMoov(url).size
        assertEquals(502, get(gw, path(stream.url), "Range: bytes=$moovAt-").status, "a seek into the changed moov")
        assertEquals(2, gw.failures[IosMediaGateway.Failure.ObjectChanged])
        assertEquals(0, victim.accepted)
    }

    /** A file of another length is not the one whose boxes were judged: refused before anything is promised. */
    @Test
    fun anMp4OfAnotherLengthThanTheOneWalkedIsRefused() = kotlinx.coroutines.runBlocking {
        val file = ftyp + plainMoov + mdat(3000)
        val grown = kotlin.concurrent.AtomicInt(0)
        val up = origin { r -> Origin.rangedFile(if (grown.value == 1) file + mdat(100) else file)(r) }
        val gw = gateway()
        val stream = assertNotNull(gw.prepare(up.url("/movie.mp4")))
        grown.value = 1
        assertEquals(502, get(gw, path(stream.url)).status)
        assertEquals(1, gw.failures[IosMediaGateway.Failure.ObjectChanged])
    }

    /** A tail moov can only be reached by ranges of a known length: an MP4 from an origin without them is refused. */
    @Test
    fun anMp4WithNoRangesOrNoLengthIsRefused() = kotlinx.coroutines.runBlocking {
        val file = ftyp + plainMoov + mdat(3000)
        val unranged = origin { Origin.Reply(200, body = file) }
        val unknown = origin { r ->
            if (r.header("Range") == null) Origin.Reply(200, body = file)
            else Origin.Reply(206, listOf("Content-Range" to "bytes 0-${file.size - 1}/*"), file)
        }
        // A 200 that states a total anyway is still not a range: refused, though the file fits in the probe.
        val whole = origin { Origin.Reply(200, listOf("Content-Range" to "bytes 0-${file.size - 1}/${file.size}"), file) }
        val gw = gateway()
        assertNull(gw.prepare(unranged.url("/movie.mp4")), "an origin that ignores Range")
        assertNull(gw.prepare(unknown.url("/movie.mp4")), "an origin that hides the length")
        assertNull(gw.prepare(whole.url("/movie.mp4")), "a 200 stating a total")
    }

    // ---- Redirects ----

    @Test
    fun redirectsAreFollowedHereAndThePlayerNeverSeesWhere() {
        val body = Origin.ts(800)
        val file = origin(Origin.rangedFile(body))
        val hop = origin { Origin.Reply(302, listOf("Location" to file.url("/real.ts"))) }
        val gw = gateway()
        val answer = get(gw, first(gw, hop.url("/start.ts")))
        assertEquals(200, answer.status)
        assertContentEquals(body, answer.body)
        assertNull(answer.headers["location"])
        assertFalse(file.port.toString() in answer.headers.values.joinToString())
    }

    @Test
    fun aRedirectThatIsTooLongDowngradesOrCarriesUserInfoIsRefused() {
        val victim = victim()
        val loop = origin { r -> Origin.Reply(302, listOf("Location" to "/again" + r.path.length)) }
        val withUser = origin { Origin.Reply(302, listOf("Location" to "http://user@127.0.0.1:${victim.port}/x")) }
        val toFtp = origin { Origin.Reply(302, listOf("Location" to "ftp://127.0.0.1:${victim.port}/x")) }
        val gw = gateway()
        assertEquals(502, get(gw, first(gw, loop.url("/a.ts"))).status)
        assertEquals(1, gw.failures[IosMediaGateway.Failure.TooManyRedirects])
        assertEquals(502, get(gw, first(gw, withUser.url("/a.ts"))).status)
        assertEquals(502, get(gw, first(gw, toFtp.url("/a.ts"))).status)
        assertEquals(2, gw.failures[IosMediaGateway.Failure.BadRedirect])
        assertEquals(0, victim.accepted)
    }

    // ---- Exact bytes for every Range form ----

    private fun mediaGateway(size: Int, etag: String = "\"v1\""): Triple<IosMediaGateway, String, ByteArray> {
        val body = Origin.ts(size)
        val up = origin(Origin.rangedFile(body, etag))
        val gw = gateway()
        return Triple(gw, first(gw, up.url("/movie.ts")), body)
    }

    @Test
    fun everyRangeFormServesExactlyTheBytesAsked() {
        for (size in listOf(1, 999, 1000, 1001, 2500)) {
            val (gw, target, body) = mediaGateway(size)
            val total = size.toLong()
            fun check(range: String?, status: Int, expected: ByteArray, contentRange: String?) {
                val answer = if (range == null) get(gw, target) else get(gw, target, "Range: $range")
                assertEquals(status, answer.status, "$size $range")
                assertContentEquals(expected, answer.body, "$size $range")
                assertEquals(contentRange, answer.headers["content-range"], "$size $range")
                assertEquals(expected.size.toString(), answer.headers["content-length"], "$size $range")
            }
            check(null, 200, body, null)
            check("bytes=0-", 206, body, "bytes 0-${total - 1}/$total")
            check("bytes=${total - 1}-", 206, body.copyOfRange(size - 1, size), "bytes ${total - 1}-${total - 1}/$total")
            check("bytes=0-0", 206, body.copyOfRange(0, 1), "bytes 0-0/$total")
            check("bytes=-1", 206, body.copyOfRange(size - 1, size), "bytes ${total - 1}-${total - 1}/$total")
            if (size > 10) {
                check("bytes=3-", 206, body.copyOfRange(3, size), "bytes 3-${total - 1}/$total")
                check("bytes=5-9", 206, body.copyOfRange(5, 10), "bytes 5-9/$total")
                check("bytes=-10", 206, body.copyOfRange(size - 10, size), "bytes ${total - 10}-${total - 1}/$total")
                check("bytes=4-${total + 50}", 206, body.copyOfRange(4, size), "bytes 4-${total - 1}/$total")
            }
            // Several ranges: ignored, the whole body served.
            check("bytes=0-1,5-6", 200, body, null)
            // Past the end: 416, with the total.
            val past = get(gw, target, "Range: bytes=$total-")
            assertEquals(416, past.status)
            assertEquals("bytes */$total", past.headers["content-range"])
        }
    }

    @Test
    fun upstreamRequestsAreChunksWithTheStreamsOwnHeadersAndIfRange() {
        val body = Origin.ts(3500)
        val up = origin(Origin.rangedFile(body, "\"tag\""))
        val gw = gateway()
        get(gw, first(gw, up.url("/movie.ts")), "Range: bytes=100-", "Accept: secret/value", "Cookie: c=1", "Referer: https://player.example/")
        val media = up.requests.filter { it.header("Range") != "bytes=0-4095" }
        assertEquals(listOf("bytes=100-1099", "bytes=1100-2099", "bytes=2100-3099", "bytes=3100-3499"), media.map { it.header("Range") })
        media.forEach { r ->
            assertEquals("https://ref.example/", r.header("Referer"))
            assertEquals("UA/1", r.header("User-Agent"))
            assertNull(r.header("Cookie"))
            assertTrue(r.header("Accept") != "secret/value")
        }
        assertNull(media.first().header("If-Range"))
        media.drop(1).forEach { assertEquals("\"tag\"", it.header("If-Range")) }
    }

    @Test
    fun onlyTheGatewaysOwnHeadersReachThePlayer() {
        val body = Origin.ts(500)
        val up = origin { Origin.Reply(200, listOf("Set-Cookie" to "s=1", "Location" to "https://elsewhere.example/", "X-Upstream" to "https://cdn.example/a", "Content-Type" to "text/html"), body) }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")))
        val allowed = setOf("content-type", "content-length", "content-range", "accept-ranges", "connection")
        assertTrue(answer.headers.keys.all { it in allowed }, "a header the gateway does not own reached the player: ${answer.headers.keys}")
        listOf("set-cookie", "location", "x-upstream").forEach { assertNull(answer.headers[it], it) }
        assertEquals("application/octet-stream", answer.headers["content-type"], "the upstream's own type was passed")
    }

    // ---- Upstream that does not match what was asked ----

    @Test
    fun aRangeUpstreamIgnoresMidFileIsAFailureNotBytesFromTheWrongPlace() {
        val body = Origin.ts(3000)
        val up = origin { r -> if (r.header("Range") == "bytes=0-4095") Origin.rangedFile(body)(r) else Origin.Reply(200, body = body) }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")), "Range: bytes=1500-")
        assertEquals(502, answer.status)
        assertEquals(1, gw.failures[IosMediaGateway.Failure.RangeIgnored])
    }

    @Test
    fun aLarge200OrOneWithNoLengthIsAFixedFailureNotACut() {
        val big = Origin.ts(6000)
        val noLength = origin { Origin.Reply(200, body = big.copyOf(500), length = null) }
        val large = origin { Origin.Reply(200, body = big) }
        val gw = gateway()
        val a = get(gw, first(gw, large.url("/a.ts")))
        val b = get(gw, first(gw, noLength.url("/b.ts")))
        assertEquals(502, a.status)
        assertEquals(502, b.status)
        assertEquals(2, gw.failures[IosMediaGateway.Failure.UnrangedTooLarge])
        assertFalse("127.0.0.1:${large.port}" in a.body.decodeToString())
    }

    @Test
    fun aChangedObjectMidStreamEndsTheStream() {
        val body = Origin.ts(3000)
        var served = 0
        val up = origin { r ->
            if (r.header("Range") == "bytes=0-4095") return@origin Origin.rangedFile(body)(r)
            served++
            Origin.rangedFile(body, if (served >= 2) "\"other\"" else "\"v1\"")(r)
        }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")))
        assertEquals(200, answer.status)
        assertTrue(answer.closedEarly, "a changed object was spliced in")
        assertContentEquals(body.copyOf(answer.body.size), answer.body)
        assertEquals(1, gw.failures[IosMediaGateway.Failure.ObjectChanged])
    }

    @Test
    fun anUpstreamSendingMoreThanItSaidGrowsNothing() {
        val body = Origin.ts(800)
        val up = origin { r ->
            val reply = Origin.rangedFile(body)(r)
            Origin.Reply(reply.status, reply.headers, reply.body, extra = ByteArray(100_000) { 7 })
        }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")))
        assertContentEquals(body, answer.body)
    }

    @Test
    fun aShortChunkBeforeTheTotalEndsTheStream() {
        val body = Origin.ts(3000)
        val up = origin { r ->
            val reply = Origin.rangedFile(body)(r)
            if (r.header("Range") == "bytes=1000-1999") Origin.Reply(206, listOf("Content-Range" to "bytes 1000-1499/3000", "ETag" to "\"v1\""), body.copyOfRange(1000, 1500)) else reply
        }
        val gw = gateway()
        val answer = get(gw, first(gw, up.url("/movie.ts")))
        assertTrue(answer.closedEarly)
        assertEquals(1500, answer.body.size)
        assertFalse(up.requests.any { it.header("Range") == "bytes=1500-2499" || it.header("Range") == "bytes=2000-2999" }, "it fetched past a short chunk")
    }

    @Test
    fun anUnknownTotalIsServedFromTheStartUntilAShortChunkAndRefusedElsewhere() {
        val body = Origin.ts(2500)
        val up = origin { r ->
            val reply = Origin.rangedFile(body)(r)
            Origin.Reply(reply.status, reply.headers.map { (k, v) -> if (k == "Content-Range") k to v.substringBefore('/') + "/*" else k to v }, reply.body)
        }
        val gw = gateway()
        val target = first(gw, up.url("/movie.ts"))
        val whole = get(gw, target)
        assertEquals(200, whole.status)
        assertContentEquals(body, whole.body)
        assertEquals(502, get(gw, target, "Range: bytes=10-").status)
        assertEquals(1, gw.failures[IosMediaGateway.Failure.UnknownLength])
    }

    // ---- Limits ----

    @Test
    fun connectionsPastTheCapAreRefusedNotQueued() {
        val up = origin { Origin.Reply(404) }
        val gw = gateway(limits.copy(maxConnections = 2))
        val held = List(2) { openIdle(gw) }
        val past = raw(gw, "GET ${first(gw, up.url("/a.ts"))} HTTP/1.1\r\nConnection: close\r\n\r\n")
        assertEquals(0, past.size, "a connection past the cap was answered")
        held.forEach { close(it) }
    }

    private fun openIdle(gw: IosMediaGateway): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        val sa = zeroed<sockaddr_in>()
        sa.sin_len = sizeOf<sockaddr_in>().convert()
        sa.sin_family = AF_INET.convert()
        sa.sin_port = (((gw.port and 0xFF) shl 8) or ((gw.port shr 8) and 0xFF)).toUShort()
        val a = sa.sin_addr.ptr.reinterpret<UByteVar>(); a[0] = 127u; a[1] = 0u; a[2] = 0u; a[3] = 1u
        check(connect(fd, sa.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0)
        platform.posix.usleep(200_000u)
        fd
    }

    // ---- The production gateway: the gated client ----

    /** With `:core:http`'s checked client, a stream url naming a local address or name reaches nothing. */
    @Test
    fun theProductionGatewayReachesNoLocalAddress() {
        val victim = victim()
        val gw = assertNotNull(IosMediaGateway.start(null, null, limits)).also { g -> cleanup += { g.close() } }
        for (upstream in listOf("https://127.0.0.1:${victim.port}/a.ts", "https://localhost:${victim.port}/a.ts", "http://localhost:${victim.port}/a.ts")) {
            val target = gw.urlFor(upstream) ?: continue
            assertEquals(502, get(gw, path(target)).status, upstream)
        }
        assertEquals(0, victim.accepted, "the gated client connected to a local address")
    }
}
