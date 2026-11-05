package com.lagradost.cloudstream4.media.download

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UrlGuardTest {
    private val publicV4 = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)

    private class Recording(private val answer: (String) -> List<ByteArray>) : HostResolver {
        val asked = mutableListOf<String>()
        override suspend fun resolve(host: String): List<ByteArray> {
            asked += host
            return answer(host)
        }
    }

    private fun check(
        url: String,
        allowPrivate: Boolean = false,
        resolver: HostResolver = Recording { listOf(publicV4) },
    ): UrlCheck = runSync { UrlGuard(resolver, allowPrivate).check(url) }

    private fun assertRefusedWithoutResolving(url: String) {
        val resolver = Recording { error("must not resolve") }
        assertEquals(UrlCheck.Refused, check(url, resolver = resolver), url)
        assertTrue(resolver.asked.isEmpty(), "resolved $url")
    }

    @Test
    fun `an ordinary https url is allowed and resolved once`() {
        val resolver = Recording { listOf(publicV4) }
        assertIs<UrlCheck.Allowed>(check("https://example.com/video.mp4?token=abc", resolver = resolver))
        assertEquals(listOf("example.com"), resolver.asked)
    }

    @Test
    fun `scheme and host case do not matter`() {
        assertIs<UrlCheck.Allowed>(check("HTTP://EXAMPLE.COM:8080/a"))
    }

    @Test
    fun `every spelling of loopback is refused without resolving`() {
        listOf(
            "http://127.0.0.1/", "http://127.1/", "http://127.0.1/", "http://0x7f.0.0.1/", "http://0x7f000001/",
            "http://2130706433/", "http://017700000001/", "http://0177.0.0.1/", "http://127.000.000.001/",
            "http://[::1]/", "http://[0:0:0:0:0:0:0:1]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/",
            "http://[64:ff9b::7f00:1]/", "http://[::127.0.0.1]/", "http://127.0.0.1.:80/", "http://0/", "http://0.0.0.0/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `private link local and reserved addresses are refused`() {
        listOf(
            "http://169.254.169.254/latest/meta-data", "http://10.0.0.1/", "http://172.16.0.1/", "http://172.31.255.255/",
            "http://192.168.1.1/", "http://100.64.0.1/", "http://100.127.255.254/", "http://198.18.0.1/", "http://198.19.255.255/",
            "http://192.0.0.8/", "http://224.0.0.1/", "http://240.0.0.1/", "http://255.255.255.255/",
            "http://[fe80::1]/", "http://[fec0::1]/", "http://[fc00::1]/", "http://[fd12:3456::1]/", "http://[ff02::1]/",
            "http://[2002:7f00:1::]/", "http://[64:ff9b::808:808]/", "http://[::]/", "http://[::ffff:10.0.0.1]/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `the boundaries of the private ranges are public`() {
        listOf(
            "http://172.15.255.255/", "http://172.32.0.1/", "http://100.63.255.255/", "http://100.128.0.1/",
            "http://198.17.255.255/", "http://198.20.0.1/", "http://192.0.1.1/", "http://223.255.255.254/",
            "http://1.1.1.1/", "http://8.8.8.8:443/x",
        ).forEach { assertIs<UrlCheck.Allowed>(check(it, resolver = Recording { error("literal") }), it) }
    }

    @Test
    fun `a literal public ipv6 address is allowed`() {
        assertIs<UrlCheck.Allowed>(check("http://[2606:4700:4700::1111]/", resolver = Recording { error("literal") }))
    }

    @Test
    fun `a mapped public ipv4 address is allowed and a mapped private one is not`() {
        assertIs<UrlCheck.Allowed>(check("http://[::ffff:8.8.8.8]/", resolver = Recording { error("literal") }))
        assertRefusedWithoutResolving("http://[::ffff:192.168.0.1]/")
    }

    @Test
    fun `numeric looking hosts that are not valid addresses are refused`() {
        listOf(
            "http://256.1.1.1/", "http://1.2.3.4.5/", "http://999999999999/", "http://08.0.0.1/",
            "http://1.2.3.256/", "http://foo.1/", "http://4294967296/", "http://1.16777216/", "http://1..2/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `malformed ipv6 literals are refused`() {
        listOf(
            "http://[::1::]/", "http://[1:2:3:4:5:6:7:8:9]/", "http://[1:2:3]/", "http://[fe80::1%25eth0]/",
            "http://[12345::]/", "http://[g::1]/", "http://[::1/", "http://[]/", "http://[:::]/", "http://[1.2.3.4]/",
            "http://[::1.2.3]/", "http://[::256.1.1.1]/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `userinfo is refused whatever the host`() {
        listOf(
            "http://user:pw@example.com/", "http://user@example.com/", "http://@example.com/",
            "http://public:0000000000000000000000000000080@127.0.0.1/", "http://example.com@127.0.0.1/",
            "http://example.com\\@127.0.0.1/", "http://127.0.0.1\\@example.com/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `other schemes and shapes are refused`() {
        listOf(
            "ftp://example.com/a", "file:///etc/passwd", "javascript:alert(1)", "data:text/plain,hi", "gopher://example.com/",
            "//example.com/a", "example.com/a", "/a/b", "", " ", "http://", "http:///a", "https://", "http:example.com",
            "http://example.com:99999/", "http://example.com:abc/", "http://example.com:0/", "http://:80/",
            "http://exa mple.com/", "http://example.com/\n", "http://example.com/\u0000", "http://exam\tple.com/",
            "http://example.com\u0085/", "http://a..b/", "http://.example.com/", "http://./", "http://../", "http://[::1]:99999/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `localhost in all its forms is refused`() {
        listOf(
            "http://localhost/", "http://LOCALHOST/", "http://localhost./", "http://localhost../", "http://a.localhost/",
            "http://a.b.localhost./", "http://printer.local/", "http://x.local./", "http://localhost:8080/",
        ).forEach(::assertRefusedWithoutResolving)
    }

    @Test
    fun `a trailing dot does not change a public name`() {
        val resolver = Recording { listOf(publicV4) }
        assertIs<UrlCheck.Allowed>(check("https://example.com./a", resolver = resolver))
        assertEquals(listOf("example.com"), resolver.asked)
    }

    @Test
    fun `one private address among public ones refuses the name`() {
        val mixed = Recording { listOf(publicV4, byteArrayOf(10, 0, 0, 1), publicV4) }
        assertEquals(UrlCheck.Refused, check("https://example.com/", resolver = mixed))
        val mixedV6 = Recording { listOf(publicV4, ByteArray(15) + 1) }
        assertEquals(UrlCheck.Refused, check("https://example.com/", resolver = mixedV6))
    }

    @Test
    fun `an address of an impossible length is refused`() {
        assertEquals(UrlCheck.Refused, check("https://example.com/", resolver = Recording { listOf(ByteArray(5)) }))
        assertEquals(UrlCheck.Refused, check("https://example.com/", resolver = Recording { listOf(ByteArray(0)) }))
    }

    @Test
    fun `a resolver that throws or answers nothing is a resolve failure`() {
        assertEquals(UrlCheck.ResolveFailed, check("https://example.com/", resolver = Recording { throw IllegalStateException("dns") }))
        assertEquals(UrlCheck.ResolveFailed, check("https://example.com/", resolver = Recording { emptyList() }))
    }

    @Test
    fun `cancellation from the resolver propagates`() {
        assertFailsWith<CancellationException> {
            check("https://example.com/", resolver = Recording { throw CancellationException("stop") })
        }
    }

    @Test
    fun `allowPrivateAddresses skips the address rules and the resolver nothing else`() {
        val resolver = Recording { error("must not resolve") }
        assertIs<UrlCheck.Allowed>(check("http://127.0.0.1:8080/a", allowPrivate = true, resolver = resolver))
        assertIs<UrlCheck.Allowed>(check("http://localhost/a", allowPrivate = true, resolver = resolver))
        assertIs<UrlCheck.Allowed>(check("http://192.168.1.5/a", allowPrivate = true, resolver = resolver))
        assertEquals(UrlCheck.Refused, check("ftp://localhost/a", allowPrivate = true, resolver = resolver))
        assertEquals(UrlCheck.Refused, check("file:///etc/passwd", allowPrivate = true, resolver = resolver))
        assertEquals(UrlCheck.Refused, check("http://user:pw@localhost/", allowPrivate = true, resolver = resolver))
        assertEquals(UrlCheck.Refused, check("http://", allowPrivate = true, resolver = resolver))
    }

    @Test
    fun `no input makes the guard throw`() {
        val inputs = listOf(
            "http://[", "http://]", "http://[[::1]]/", "http://%00/", "http://%7f.0.0.1/", "http://example.com:/",
            "http://a:b:c/", "https://" + "a".repeat(20_000), "http://" + "a.".repeat(5_000) + "com/", "\u0000", "http://\uD800/",
            "http://\u2460.com/", "http://\uff11\uff12\uff17.\uff10.\uff10.\uff11/", "http://example.com/%zz", "http:/\\example.com", "http:\\\\example.com",
        )
        for (input in inputs) {
            val result = check(input)
            assertTrue(result is UrlCheck.Refused || result is UrlCheck.Allowed || result is UrlCheck.ResolveFailed)
        }
    }

    @Test
    fun `fullwidth and percent encoded loopback do not get through`() {
        val resolver = Recording { listOf(byteArrayOf(127, 0, 0, 1)) }
        // Either refused at once, or resolved and refused for what it resolved to: never allowed.
        listOf("http://\uff11\uff12\uff17.\uff10.\uff10.\uff11/", "http://%31%32%37.0.0.1/", "http://0x7f.1/").forEach {
            assertEquals(UrlCheck.Refused, check(it, resolver = resolver), it)
        }
    }

    @Test
    fun `isPublicAddress follows the sandbox classes over bytes`() {
        assertTrue(isPublicAddress(byteArrayOf(8, 8, 8, 8)))
        assertTrue(!isPublicAddress(byteArrayOf(127, 0, 0, 1)))
        assertTrue(!isPublicAddress(ByteArray(16)))
        assertTrue(!isPublicAddress(ByteArray(15) + 1))
        assertTrue(isPublicAddress(byteArrayOf(0x26, 0x06, 0x47, 0x00) + ByteArray(11) + 0x11))
    }

    @Test
    fun `redirect locations resolve against the base and bad ones are null`() {
        val base = io.ktor.http.Url("https://example.com/a/b/c.mp4?x=1")
        assertEquals("https://example.com/a/b/d.mp4", resolveRedirect(base, "d.mp4").toString())
        assertEquals("https://example.com/root.mp4", resolveRedirect(base, "/root.mp4").toString())
        assertEquals("http://other.net/z", resolveRedirect(base, "http://other.net/z").toString())
        assertEquals(null, resolveRedirect(base, "http://x/\r\nHost: y"))
        assertEquals(null, resolveRedirect(base, "a".repeat(MAX_URL_CHARS + 1)))
    }
}
