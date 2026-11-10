package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KMP-113` §1: the gateway url scheme, both ways. Made-up hosts only. */
class GatewayUrlTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUv"
    private val urls = GatewayUrl(port = 40_001, token = token)

    /** The request target of a gateway url: what libVLC asks for. */
    private fun target(encoded: String) = encoded.removePrefix("http://127.0.0.1:40001")

    @Test
    fun `a url round-trips with its role, path and query kept byte for byte`() {
        val url = "https://cdn.example/v/1080/seg%2C0001.ts?sig=a%2Bb%3D&exp=1"
        for (role in GatewayRole.entries) {
            val encoded = assertNotNull(urls.encode(url, role))
            assertTrue(encoded.startsWith("http://127.0.0.1:40001/$token/${role.code}/https/cdn.example/"), encoded)
            assertEquals(GatewayTarget(url, role), urls.decode(target(encoded)))
        }
    }

    @Test
    fun `the default port is dropped, another is kept, and the host is lowercased`() {
        assertEquals(
            "https://cdn.example/a",
            urls.decode(target(urls.encode("https://CDN.Example:443/a", GatewayRole.Media)!!))?.upstream,
        )
        assertEquals(
            "http://cdn.example:8080/a",
            urls.decode(target(urls.encode("http://cdn.example:8080/a", GatewayRole.Media)!!))?.upstream,
        )
    }

    @Test
    fun `an IPv6 literal is bracketed both ways, and a zone id is refused`() {
        val encoded = assertNotNull(urls.encode("http://[2001:db8::1]:8080/a", GatewayRole.Media))
        assertTrue("/http/[2001:db8::1]:8080/a" in encoded, encoded)
        assertEquals("http://[2001:db8::1]:8080/a", urls.decode(target(encoded))?.upstream)
        assertNull(urls.encode("http://[fe80::1%25en0]/a", GatewayRole.Media))
        assertNull(urls.decode("/$token/s/http/[fe80::1%25en0]/a"))
    }

    @Test
    fun `user info, an empty host, another scheme and a fragment`() {
        assertNull(urls.encode("https://viewer:secret@cdn.example/a", GatewayRole.Media))
        assertNull(urls.encode("https:///a", GatewayRole.Media))
        assertNull(urls.decode("/$token/s/https//a"))
        assertNull(urls.encode("ftp://cdn.example/a", GatewayRole.Media))
        assertNull(urls.decode("/$token/s/ftp/cdn.example/a"))
        assertEquals("https://cdn.example/a", urls.decode(target(urls.encode("https://cdn.example/a#frag", GatewayRole.Media)!!))?.upstream)
    }

    @Test
    fun `a missing, wrong or wrong-length token, or an unknown role, is no target`() {
        assertNull(urls.decode("/s/https/cdn.example/a"))
        assertNull(urls.decode("/AbCdEfGhIjKlMnOpQrStUx/s/https/cdn.example/a"))
        assertNull(urls.decode("/${token}x/s/https/cdn.example/a"))
        assertNull(urls.decode("/$token/x/https/cdn.example/a"))
        assertNotNull(urls.decode("/$token/s/https/cdn.example/a"))
    }

    @Test
    fun `an encoded slash cannot move a boundary`() {
        assertNull(urls.decode("/$token%2Fs/https/cdn.example/a"))
        assertNull(urls.decode("/$token/s%2Fhttps/cdn.example/a"))
        assertNull(urls.decode("/$token/s/https/cdn.example%2F..%2F127.0.0.1/a"))
        // In the path it is only a path byte, and goes upstream as it came.
        assertEquals("https://cdn.example/a%2Fb", urls.decode("/$token/s/https/cdn.example/a%2Fb")?.upstream)
    }

    @Test
    fun `dot segments are removed, and one that climbs above the root is refused, encoded or not`() {
        assertEquals("https://cdn.example/b/c", urls.decode("/$token/s/https/cdn.example/a/../b/./c")?.upstream)
        assertEquals("https://cdn.example/b", urls.decode("/$token/s/https/cdn.example/a/%2e%2E/b")?.upstream)
        assertNull(urls.decode("/$token/s/https/cdn.example/../a"))
        assertNull(urls.decode("/$token/s/https/cdn.example/%2e%2e/a"))
        assertNull(urls.decode("/$token/s/https/cdn.example/a/../../b"))
    }

    @Test
    fun `control characters, raw or encoded, and anything too long are refused`() {
        assertNull(urls.decode("/$token/s/https/cdn.example/a\nb"))
        assertNull(urls.decode("/$token/s/https/cdn.example/a%0Ab"))
        assertNull(urls.decode("/$token/s/https/cdn.example/a%7f"))
        assertNull(urls.encode("https://cdn.example/a\tb", GatewayRole.Media))
        val long = "https://cdn.example/" + "a".repeat(SandboxPolicy.MAX_URL_CHARS)
        assertNull(urls.encode(long, GatewayRole.Media))
        assertNull(urls.decode("/$token/s/https/cdn.example/" + "a".repeat(SandboxPolicy.MAX_URL_CHARS)))
    }

    @Test
    fun `a value naming the gateway or holding its token is recognised`() {
        assertTrue(urls.namesGateway("http://127.0.0.1:40001/x"))
        assertTrue(urls.namesGateway("http://[::1]:40001/x"))
        assertTrue(urls.namesGateway("bytes=0-; $token"))
        assertFalse(urls.namesGateway("bytes=0-1023"))
        assertFailsWith<IllegalArgumentException> { GatewayUrl(1, "short") }
    }
}
