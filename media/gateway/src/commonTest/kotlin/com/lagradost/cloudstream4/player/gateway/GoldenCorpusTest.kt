package com.lagradost.cloudstream4.player.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `BUG-114`: the golden corpus. The same rows run on the JVM (the Mac's url mechanics) and on iOS (the iPad's), so the
 * two give the same answers, and the one place they differ is a row of its own ([theOneNamedDivergence]).
 *
 * Expected values are test data under the no-re-record rule: never changed to make a run pass. A change to a row needs
 * its reason in the PR that makes it. First recorded from the Mac's behaviour (`KMP-113`'s code, unchanged), each row
 * reviewed by hand.
 *
 * - [encode]: what [GatewayUrl.encode] makes of a url, or null where it refuses.
 * - [rewrite]: a one-line playlist, rewritten with `strict` off (the Mac: what it did before this module) and on (the
 *   iPad: refused where anything that could be a url was not rewritten).
 */
class GoldenCorpusTest {
    private val urls = GatewayUrl(8123, "AAAAAAAAAAAAAAAAAAAAAA")
    private val base = "https://cdn.example/v/master.m3u8"

    private class EncodeRow(val name: String, val url: String, val expected: String?)

    /** [mac] and [strict] are the whole output, or `failed:<kind>`. */
    private class RewriteRow(val name: String, val line: String, val mac: String, val strict: String)

    private fun rewrite(line: String, strict: Boolean): String =
        when (val result = HlsRewriter(urls::encode, strict = strict).rewrite("#EXTM3U\n$line\n".encodeToByteArray(), base)) {
            is Rewrite.Done -> result.text
            is Rewrite.Failed -> "failed:${result.kind.name}"
        }
    private val encodeRows = listOf(
        EncodeRow("plain", "https://cdn.example/v/a.m3u8", "${P}u/https/cdn.example/v/a.m3u8"),
        EncodeRow("host lowercased, default port dropped, query kept", "https://CDN.Example:443/a.ts?sig=%2F%3D", "${P}u/https/cdn.example/a.ts?sig=%2F%3D"),
        EncodeRow("a non-default port kept", "http://h.example:8080/a", "${P}u/http/h.example:8080/a"),
        EncodeRow("IPv6 literal with port", "https://[2001:db8::1]:8443/x", "${P}u/https/[2001:db8::1]:8443/x"),
        EncodeRow("user info refused", "https://user@host.example/a", null),
        EncodeRow("two @ refused", "https://a@b@c.example/a", null),
        EncodeRow("IPv6 zone refused", "https://[fe80::1%25en0]/a", null),
        EncodeRow("empty authority refused", "https:///a", null),
        EncodeRow("dot segments above the root refused", "https://host.example/a/../../b", null),
        EncodeRow("encoded dot segments removed", "https://host.example/a/%2e%2e/b", "${P}u/https/host.example/b"),
        EncodeRow("backslash refused", "https://host.example/a\\b", null),
        EncodeRow("space refused", "https://host.example/a b", null),
        EncodeRow("leading space refused", " https://host.example/a", null),
        EncodeRow("tab refused", "https://host.example/a\tb", null),
        EncodeRow("registry host refused", "https://host_name.example/a", null),
        EncodeRow("ftp refused", "ftp://host.example/a", null),
        EncodeRow("fragment dropped", "https://host.example/a#frag", "${P}u/https/host.example/a"),
        EncodeRow("second ? kept in the query", "https://host.example/a?x=1?y#z", "${P}u/https/host.example/a?x=1?y"),
        EncodeRow("address literal encoded (the client judges it)", "https://127.0.0.1/a", "${P}u/https/127.0.0.1/a"),
        EncodeRow("percent-encoded host refused", "https://%65xample.com/a", null),
        EncodeRow("scheme-relative refused", "//host.example/a", null),
        EncodeRow("trailing-dot host refused", "https://host.example./a", null),
        EncodeRow("encoded slash kept in the path", "https://host.example/a%2Fb", "${P}u/https/host.example/a%2Fb"),
        EncodeRow("encoded control refused", "https://host.example/a%0Ab", null),
    )

    private val rewriteRows = listOf(
        RewriteRow(
            "relative segment", "a.ts",
            mac = "#EXTM3U\n${P}s/https/cdn.example/v/a.ts\n\n",
            strict = "#EXTM3U\n${P}s/https/cdn.example/v/a.ts\n\n",
        ),
        RewriteRow(
            "parent segment", "../a.ts",
            mac = "#EXTM3U\n${P}s/https/cdn.example/a.ts\n\n",
            strict = "#EXTM3U\n${P}s/https/cdn.example/a.ts\n\n",
        ),
        RewriteRow(
            "above the root: left as is, refused when strict", "../../../a.ts",
            mac = "#EXTM3U\n../../../a.ts\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "query-only reference resolves against the directory", "?q=1",
            mac = "#EXTM3U\n${P}s/https/cdn.example/v/?q=1\n\n",
            strict = "#EXTM3U\n${P}s/https/cdn.example/v/?q=1\n\n",
        ),
        RewriteRow(
            "scheme-relative", "//other.example/x.ts",
            mac = "#EXTM3U\n${P}s/https/other.example/x.ts\n\n",
            strict = "#EXTM3U\n${P}s/https/other.example/x.ts\n\n",
        ),
        RewriteRow(
            "http: kept on the Mac, refused when strict", "http://other.example/x.ts",
            mac = "#EXTM3U\n${P}s/http/other.example/x.ts\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "data: left as is, refused when strict", "data:,x",
            mac = "#EXTM3U\ndata:,x\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "rtsp: left as is, refused when strict", "rtsp://h.example/x",
            mac = "#EXTM3U\nrtsp://h.example/x\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "space made legal", "seg 1.ts",
            mac = "#EXTM3U\n${P}s/https/cdn.example/v/seg%201.ts\n\n",
            strict = "#EXTM3U\n${P}s/https/cdn.example/v/seg%201.ts\n\n",
        ),
        RewriteRow(
            "backslash made legal", "a\\b.ts",
            mac = "#EXTM3U\n${P}s/https/cdn.example/v/a%5Cb.ts\n\n",
            strict = "#EXTM3U\n${P}s/https/cdn.example/v/a%5Cb.ts\n\n",
        ),
        RewriteRow(
            "encoded dot segments", "%2e%2e/a.ts",
            mac = "#EXTM3U\n${P}s/https/cdn.example/a.ts\n\n",
            strict = "#EXTM3U\n${P}s/https/cdn.example/a.ts\n\n",
        ),
        RewriteRow(
            "key", "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            mac = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"${P}s/https/cdn.example/v/key.bin\"\n\n",
            strict = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"${P}s/https/cdn.example/v/key.bin\"\n\n",
        ),
        RewriteRow(
            "map not quoted: left as is, refused when strict", "#EXT-X-MAP:URI=init.mp4",
            mac = "#EXTM3U\n#EXT-X-MAP:URI=init.mp4\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "define: left as is, refused when strict", "#EXT-X-DEFINE:NAME=\"a\",VALUE=\"b\"",
            mac = "#EXTM3U\n#EXT-X-DEFINE:NAME=\"a\",VALUE=\"b\"\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "unknown tag with URI: left as is, refused when strict", "#EXT-X-VENDOR-THING:URI=\"x.ts\"",
            mac = "#EXTM3U\n#EXT-X-VENDOR-THING:URI=\"x.ts\"\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "unknown tag with a url: left as is, refused when strict", "#EXT-X-VENDOR-AD:https://ads.example/a.ts",
            mac = "#EXTM3U\n#EXT-X-VENDOR-AD:https://ads.example/a.ts\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "unknown tag without a url: left as is", "#EXT-X-VENDOR-FLAG:YES",
            mac = "#EXTM3U\n#EXT-X-VENDOR-FLAG:YES\n\n",
            strict = "#EXTM3U\n#EXT-X-VENDOR-FLAG:YES\n\n",
        ),
        RewriteRow(
            "URI twice: left as is, refused when strict", "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"a.m3u8\",URI=\"b.m3u8\"",
            mac = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"a.m3u8\",URI=\"b.m3u8\"\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "comment with a url: left as is", "# see https://evil.example/x",
            mac = "#EXTM3U\n# see https://evil.example/x\n\n",
            strict = "#EXTM3U\n# see https://evil.example/x\n\n",
        ),
        RewriteRow(
            "skd key: left as is, refused when strict", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"",
            mac = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "variable on a uri line: refused when strict", "{\$x}/seg.ts",
            mac = "#EXTM3U\n${P}s/https/cdn.example/v/%7B\$x%7D/seg.ts\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "variant", "#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8",
            mac = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n${P}p/https/cdn.example/v/v.m3u8\n\n",
            strict = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n${P}p/https/cdn.example/v/v.m3u8\n\n",
        ),
        RewriteRow(
            "IPv6 key encoded (the client judges it)", "#EXT-X-KEY:METHOD=AES-128,URI=\"https://[::1]/k\"",
            mac = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"${P}s/https/[::1]/k\"\n\n",
            strict = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"${P}s/https/[::1]/k\"\n\n",
        ),
        RewriteRow(
            "unterminated attribute list: left as is, refused when strict", "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin",
            mac = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "lowercase tag with URI: left as is, refused when strict", "#ext-x-key:METHOD=AES-128,URI=\"k\"",
            mac = "#EXTM3U\n#ext-x-key:METHOD=AES-128,URI=\"k\"\n\n",
            strict = "failed:Unrewritable",
        ),
        RewriteRow(
            "data range asset", "#EXT-X-DATERANGE:ID=\"a\",X-ASSET-URI=\"ad.m3u8\"",
            mac = "#EXTM3U\n#EXT-X-DATERANGE:ID=\"a\",X-ASSET-URI=\"${P}p/https/cdn.example/v/ad.m3u8\"\n\n",
            strict = "#EXTM3U\n#EXT-X-DATERANGE:ID=\"a\",X-ASSET-URI=\"${P}p/https/cdn.example/v/ad.m3u8\"\n\n",
        ),
    )

    @Test
    fun encode() = encodeRows.forEach { assertEquals(it.expected, urls.encode(it.url, GatewayRole.Unknown), it.name) }

    @Test
    fun rewrite() = rewriteRows.forEach {
        assertEquals(it.mac, rewrite(it.line, strict = false), "${it.name} (strict off)")
        assertEquals(it.strict, rewrite(it.line, strict = true), "${it.name} (strict on)")
    }

    /**
     * The one difference between the platforms, by design: a host that is not ASCII. The Mac converts it to punycode
     * (`IDN.toASCII`, as `KMP-113` did); the iPad does not convert it, so it is not a host the rules accept, the line is
     * left as written with `strict` off and the playlist refused with it on (`BUG-111`'s rule for the iPad's client).
     */
    @Test
    fun theOneNamedDivergence() {
        val line = "https://exämple.com/a.ts"
        val converts = UriOps.asciiHost("ex\u00e4mple.com") != null
        if (converts) {
            assertEquals("#EXTM3U\n${P}s/https/xn--exmple-cua.com/a.ts\n\n", rewrite(line, strict = false), "Mac, strict off")
            assertEquals("#EXTM3U\n${P}s/https/xn--exmple-cua.com/a.ts\n\n", rewrite(line, strict = true), "Mac, strict on")
        } else {
            assertEquals("#EXTM3U\n$line\n\n", rewrite(line, strict = false), "iPad, strict off")
            assertEquals("failed:Unrewritable", rewrite(line, strict = true), "iPad, strict on")
        }
    }

    private companion object {
        const val P = "http://127.0.0.1:8123/AAAAAAAAAAAAAAAAAAAAAA/"
    }
}
