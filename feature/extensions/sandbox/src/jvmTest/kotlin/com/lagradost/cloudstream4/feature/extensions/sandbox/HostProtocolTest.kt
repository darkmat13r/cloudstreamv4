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
}
