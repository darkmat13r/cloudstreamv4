package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The frames are hostile input, so the failures are the interesting half. */
class HostProtocolTest {

    private fun body(frame: ByteArray) = HostProtocol.readFrame(ByteArrayInputStream(frame))!!

    @Test
    fun `a search page survives the round trip`() {
        val page = RemoteSearchPage(
            listOf(RemoteSearchItem("Título", "https://a/1", "Movie", null), RemoteSearchItem("b", "u", null, "p")),
            hasNext = true,
        )
        assertEquals(page, HostProtocol.decode(body(HostProtocol.encode { writeSearchPage(page) })) { readSearchPage() })
    }

    @Test
    fun `details survive the round trip`() {
        val details = RemoteDetails(
            "n", "u", "TvSeries", null, "p", null, null,
            listOf(RemoteEpisode(null, "d", 1, null)),
        )
        assertEquals(details, HostProtocol.decode(body(HostProtocol.encode { writeDetails(details) })) { readDetails() })
    }

    @Test
    fun `a provider's main page sections survive the round trip`() {
        val providers = listOf(
            RemoteProvider("n", "u", "en", true, listOf("Movie"), true, listOf(RemoteMainPageSection("Top", true)), 500),
            RemoteProvider("m", "v", "fr", false, emptyList()),
        )
        assertEquals(providers, HostProtocol.decode(body(HostProtocol.encode { writeProviders(providers) })) { readProviders() })
    }

    @Test
    fun `a main page survives the round trip`() {
        val page = RemoteHomePage(
            listOf(
                RemoteHomeRow("Top", true, listOf(RemoteSearchItem("a", "u", "Movie", "p"))),
                RemoteHomeRow("", false, emptyList()),
            ),
            hasNext = true,
        )
        assertEquals(page, HostProtocol.decode(body(HostProtocol.encode { writeHomePage(page) })) { readHomePage() })
    }

    @Test
    fun `a row count larger than its frame is refused`() {
        val frame = body(HostProtocol.encode { writeBoolean(true); writeInt(Int.MAX_VALUE) })
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readHomePage() } }
    }

    @Test
    fun `an item count larger than its frame is refused`() {
        val frame = body(
            HostProtocol.encode {
                writeBoolean(true)
                writeInt(1)
                writeText("row")
                writeBoolean(false)
                writeInt(Int.MAX_VALUE)
            },
        )
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readHomePage() } }
    }

    @Test
    fun `a negative row count is refused`() {
        val frame = body(HostProtocol.encode { writeBoolean(true); writeInt(-1) })
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readHomePage() } }
    }

    @Test
    fun `a main page cut off inside a row is refused`() {
        val frame = body(HostProtocol.encode { writeBoolean(true); writeInt(1); writeText("row") })
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readHomePage() } }
    }

    @Test
    fun `a section count larger than its frame is refused`() {
        val frame = body(
            HostProtocol.encode {
                writeInt(1)
                writeText("n"); writeText("u"); writeText("en")
                writeBoolean(false)
                writeInt(0)
                writeBoolean(true)
                writeInt(Int.MAX_VALUE)
            },
        )
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readProviders() } }
    }

    @Test
    fun `a main page is cleaned and cut to the limits`() {
        val item = RemoteSearchItem("evil\u001B[2J\n", "javascript\u0000:x", "T\nype", "file:///etc/passwd")
        val page = RemoteHomePage(
            List(HostProtocol.MAX_HOME_ROWS + 5) {
                RemoteHomeRow("row\u0007" + "x".repeat(5000), false, List(HostProtocol.MAX_HOME_ROW_ITEMS + 5) { item })
            },
            hasNext = true,
        ).cleaned()

        assertEquals(HostProtocol.MAX_HOME_ROWS, page.rows.size)
        val row = page.rows.first()
        assertEquals(HostProtocol.MAX_HOME_ROW_ITEMS, row.items.size)
        assertEquals(256, row.name.length + 1, "cut, then the control character dropped")
        assertEquals(RemoteSearchItem("evil[2J", "javascript:x", "Type", null), row.items.first())
    }

    @Test
    fun `a provider's sections and delay are cleaned and cut to the limits`() {
        val provider = RemoteProvider(
            "n", "u", "en", false, emptyList(),
            hasMainPage = true,
            mainPage = List(HostProtocol.MAX_MAIN_PAGE_SECTIONS + 5) { RemoteMainPageSection("a\u001Bb", false) },
            sequentialDelayMillis = Long.MAX_VALUE,
        ).cleaned()
        assertEquals(HostProtocol.MAX_MAIN_PAGE_SECTIONS, provider.mainPage.size)
        assertEquals("ab", provider.mainPage.first().name)
        assertEquals(HostProtocol.MAX_SEQUENTIAL_DELAY_MILLIS, provider.sequentialDelayMillis)
        val negative = provider.copy(sequentialDelayMillis = -5).cleaned()
        assertEquals(0L, negative.sequentialDelayMillis)
    }

    /**
     * `BUG-105`: a private address written as an address, in any spelling a URL can give it, is judged as the address it
     * is, by the predicate the Mac's clients connect by. Before, only the spellings a pattern knew were refused, and
     * `[::ffff:127.0.0.1]` or `2130706433` reached the client as a public host.
     */
    @Test
    fun `a private address is refused at the wire in every spelling a URL can write it`() {
        listOf(
            "http://[::ffff:127.0.0.1]/x", "https://[::ffff:7f00:1]/x", "https://[::ffff:a00:1]/x", "https://[::127.0.0.1]/x",
            "https://[0:0:0:0:0:ffff:c0a8:101]/x", "http://2130706433/x", "https://3232235777/x", "https://0x7f000001/x",
            "https://0177.0.0.1/x", "https://127.1/x", "https://0x0a.0.0.1/x", "https://10.1/x", "https://0/x",
            "https://0x7f.0.0.1/x", "https://4294967296/x", "https://08.0.0.1/x",
        ).forEach { url ->
            assertNull(url.httpUrlOrNull(), url)
            assertNull(url.httpsUrlOrNull(), url)
        }
        // A public address in any spelling, and a name that only looks numeric in part, are kept.
        listOf("https://93.184.216.34/x", "https://1572395042/x", "https://[2606:2800:220:1:248:1893:25c8:1946]/x",
            "https://cafe.be/x", "https://0x7f.example/x").forEach { url ->
            assertEquals(url, url.httpsUrlOrNull(), url)
        }
        // End to end: what a host sends for a title's art and cast reaches the page without them.
        val details = RemoteDetails(
            name = "A title", url = "u", type = "Movie", plot = null, posterUrl = "https://[::ffff:127.0.0.1]/p.jpg",
            year = null, dataUrl = null, episodes = emptyList(), backdropUrl = "https://2130706433/b.jpg",
            cast = listOf(RemoteCastMember(name = "A person", imageUrl = "https://[::ffff:7f00:1]/c.jpg")),
        ).cleaned()
        assertNull(details.posterUrl)
        assertNull(details.backdropUrl)
        assertNull(details.cast.single().imageUrl)
    }

    @Test
    fun `a poster URL can only point at a public http host`() {
        listOf(
            "https://archive.org/services/img/x",
            "http://example.com/a.jpg",
        ).forEach { assertEquals(it, it.httpUrlOrNull()) }
        listOf(
            "file:///etc/passwd", "jar:file:/x!/y", "javascript:alert(1)", "not a url",
            "http://localhost/x", "http://127.0.0.1:8080/x", "http://LOCALHOST/x", "http://[::1]/x",
            "http://10.0.0.1/x", "http://192.168.1.1/admin", "http://172.16.0.1/x", "http://169.254.169.254/latest",
            "http://0.0.0.0/x", "http://printer.local/x", "http://[fe80::1]/x", "http://[fd00::1]/x",
        ).forEach { assertNull(it.httpUrlOrNull(), it) }
        assertNull((null as String?).httpUrlOrNull())
    }

    @Test
    fun `end of stream is not a frame`() {
        assertNull(HostProtocol.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a frame claiming more than the limit is refused before allocating`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(Int.MAX_VALUE)
        assertFailsWith<IOException> { HostProtocol.readFrame(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test
    fun `a negative frame length is refused`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(-1)
        assertFailsWith<IOException> { HostProtocol.readFrame(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test
    fun `a string longer than its frame is refused`() {
        val frame = body(HostProtocol.encode { writeInt(1_000_000) })
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readText() } }
    }

    @Test
    fun `a list count larger than its frame is refused`() {
        val frame = body(HostProtocol.encode { writeBoolean(false); writeInt(Int.MAX_VALUE) })
        assertFailsWith<IOException> { HostProtocol.decode(frame) { readSearchPage() } }
    }

    @Test
    fun `a truncated frame is refused`() {
        val whole = HostProtocol.encode { writeText("hello") }
        assertFailsWith<IOException> { HostProtocol.readFrame(ByteArrayInputStream(whole.copyOf(whole.size - 2))) }
    }

    @Test
    fun `links carry their subtitles across the boundary`() {
        val links = RemoteLinks(
            links = listOf(RemoteLink("s", "n", "https://a/v", 720, "VIDEO", null, null, null)),
            subtitles = listOf(
                RemoteSubtitle("English", "en", "https://a/en.vtt", "https://r/", "ua"),
                RemoteSubtitle("asr", null, "https://a/x.srt", null, null),
            ),
        )
        assertEquals(links, HostProtocol.decode(body(HostProtocol.encode { writeLinks(links) })) { readLinks() })
    }

    @Test
    fun `a lying subtitle count ends the frame rather than allocating`() {
        val frame = HostProtocol.encode {
            writeInt(Int.MAX_VALUE)
        }
        assertFailsWith<IOException> { HostProtocol.decode(body(frame)) { readLinks() } }
    }

    private fun subtitle(language: String = "English", tag: String? = "en", url: String = "https://a/en.vtt") =
        RemoteSubtitle(language, tag, url, null, null)

    @Test
    fun `a plain track passes unchanged`() {
        assertEquals(subtitle(), subtitle().cleaned())
    }

    @Test
    fun `a label loses controls, format characters and line separators`() {
        val hostile = "\u202EEng\u200Blish\u0007\n\u2028\u2066"
        assertEquals("English", subtitle(language = hostile).cleaned()?.language)
    }

    @Test
    fun `a label loses every default-ignorable code point, not only controls and format characters`() {
        val hostile = "Eng\u034Flish\uFE0F\u3164\uDB40\uDD00\uDB40\uDC41"
        assertEquals("English", subtitle(language = hostile).cleaned()?.language)
        assertEquals("en", subtitle(language = "\u3164\uFFA0\u115F\u17B4").cleaned()?.language)
        assertEquals("한국어 \uD83D\uDE00", subtitle(language = "한국어 \uD83D\uDE00").cleaned()?.language)
    }

    @Test
    fun `a label is cut, and an empty one falls back on the tag or the track is dropped`() {
        assertEquals(128, subtitle(language = "x".repeat(5000)).cleaned()?.language?.length)
        assertEquals("en", subtitle(language = "\u202E  ").cleaned()?.language)
        assertNull(subtitle(language = "\u202E  ", tag = null).cleaned())
    }

    @Test
    fun `only http and https survive, and no userinfo`() {
        assertEquals("http://a/x", subtitle(url = "http://a/x").cleaned()?.url)
        for (url in listOf(
            "file:///etc/passwd",
            "ftp://a/x",
            "content://x",
            "javascript:alert(1)",
            "data:text/plain,x",
            "",
            "not a url",
            "https://",
            "https://user:pass@a/x",
            "https://a/x\ny",
            "https://a/" + "x".repeat(SandboxPolicy.MAX_URL_CHARS),
        )) {
            assertNull(subtitle(url = url).cleaned(), url)
        }
    }

    @Test
    fun `a tag that is not one is dropped and the track stays`() {
        assertEquals(null, subtitle(tag = "en\u202E").cleaned()?.languageTag)
        assertEquals(null, subtitle(tag = "x".repeat(99)).cleaned()?.languageTag)
        assertEquals("pt-BR", subtitle(tag = "pt-BR").cleaned()?.languageTag)
    }

    @Test
    fun `a url's address is not looked up while listing tracks`() {
        // Nobody chose this track yet, so a name a plugin picked must not be resolved. A literal stays too:
        // the fetch refuses it, and the person who picks it reads why.
        assertEquals("http://127.0.0.1/x", subtitle(url = "http://127.0.0.1/x").cleaned()?.url)
        assertEquals("https://no-such-name.invalid/x", subtitle(url = "https://no-such-name.invalid/x").cleaned()?.url)
    }

    @Test
    fun `referer and user agent are cut and lose controls`() {
        val cleaned = RemoteSubtitle("x", null, "https://a/", "https://r/\r\nX: 1", "u".repeat(5000)).cleaned()
        assertEquals("https://r/X: 1", cleaned?.referer)
        assertEquals(256, cleaned?.userAgent?.length)
    }

    @Test
    fun `a silent media host is refused every audio service`() {
        val app = java.io.File("/Applications/VLC.app")
        val dir = java.io.File("/tmp/x")
        val loud = SeatbeltProfile.mediaHostRules(app, dir).joinToString("\n")
        val silent = SeatbeltProfile.mediaHostRules(app, dir, audio = false).joinToString("\n")
        assertEquals(true, "audio" in loud.lowercase())
        assertEquals(false, "audio" in silent.lowercase(), silent)
        // No certificate service is allowed for a media host at all (`BUG-96`): render denies them for every host.
        assertEquals(false, "com.apple.trustd" in loud + silent, silent)
    }
}
