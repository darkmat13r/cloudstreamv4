package com.lagradost.cloudstream4.player.gateway

import kotlin.random.Random

/**
 * Deterministic hostile input for the parity tests (`BUG-114`): urls and references assembled from the pieces that
 * `java.net.URI` treats differently from everything else, and playlists built from them. Seeded, so a failure repeats.
 */
internal class Fuzz(seed: Int) {
    private val random = Random(seed)

    private fun <T> pick(vararg items: T): T = items[random.nextInt(items.size)]
    private fun chance(percent: Int) = random.nextInt(100) < percent

    private val chars = listOf(
        "a", "Z", "0", "9", ".", "-", "_", "~", "!", "$", "&", "'", "(", ")", "*", "+", ",", ";", "=", ":", "@", "/",
        "?", "#", "[", "]", "%", "%2e", "%2E", "%2F", "%25", "%41", "%zz", "%0", "%00", "%7f", " ", "\t", "\n", "\r",
        "\\", "|", "^", "{", "}", "`", "\"", "<", ">", "é", "中", "\uD83C\uDFAC", "\uD800", "\u00A0", "\u2028",
        "\u0080", "\u00FF", "..", "//", "{\$x}",
    )

    fun junk(max: Int = 6): String = buildString { repeat(random.nextInt(max + 1)) { append(pick(*chars.toTypedArray())) } }

    private fun host(): String = pick(
        "example.com", "EXAMPLE.com", "cdn.example", "a-b.example", "-a.example", "a-.example", "a..b", "a.b.",
        "1.2.3.4", "001.002.003.004", "256.1.1.1", "1.2.3", "1.2.3.4.5", "2130706433", "0x7f000001", "127.1",
        "[::1]", "[::ffff:127.0.0.1]", "[fe80::1%25en0]", "[fe80::1%en0]", "[2001:db8::1]", "[1:2:3:4:5:6:7:8]",
        "[1:2:3:4:5:6:7:8:9]", "[::]", "[:::]", "[g::1]", "[12345::1]", "[1.2.3.4]", "[]", "[::1",
        "exämple.com", "例え.テスト", "xn--bcher-kva.example", "host_name.example", "a.123", "localhost", "",
        "%65xample.com", "ex%2ample.com", "user@host", "a@b@c", "a:b@host", "host:", "host:8080", "host:99999999999",
        "host:-1", "host:0", "h", "a.b.c.d.e.f", "host:443", "host:80", "HOST.example:443", "[::1]:443", "[::1]:80",
        "cdn.example:0443", "1.2.3.4:443",
    ) + if (chance(15)) junk(2) else ""

    private fun path(): String = buildString {
        repeat(random.nextInt(5)) {
            append(pick("/", "/", "//", "\\"))
            append(pick("a", "b.ts", "seg 1.ts", ".", "..", "%2e", "%2e%2E", "...", "x|y", "a^b", "", "é", "%41", "%zz"))
            if (chance(20)) append(junk(2))
        }
    }

    fun url(): String {
        val scheme = pick("https://", "http://", "HTTPS://", "ftp://", "data:", "file://", "//", "", "https:", "javascript:", "1http://", " https://")
        val query = if (chance(30)) "?" + pick("a=1", "sig=%2F%3D", "x=|y", "", "q=a#b", "?") + if (chance(20)) junk(2) else "" else ""
        val fragment = if (chance(15)) "#" + pick("f", "", "a#b", "%2F") else ""
        val core = scheme + (if (scheme.endsWith("//")) host() else "") + path() + query + fragment
        return if (chance(10)) pick(" ", "\t", "\n") + core + pick("", " ", "\r") else core
    }

    /** A reference as a playlist names one: absolute, scheme-relative, root-relative, relative, or odd. */
    fun reference(): String = pick(
        { url() },
        { "//" + host() + path() },
        { path().ifEmpty { "/" } },
        { pick("a.ts", "../a.ts", "../../../a.ts", "./a.ts", "sub/../a.ts", "?q=1", "#f", "", ";p", "a:b", "%2e%2e/a") + junk(2) },
    )()

    fun base(): String = pick(
        "https://cdn.example/v/master.m3u8", "https://cdn.example", "https://cdn.example?x=1", "http://h.example/a/b/c.m3u8",
        "https://[2001:db8::1]:8443/x/y.m3u8", "https://cdn.example/a%20b/c.m3u8?sig=1", "https://cdn.example/",
        "https://exämple.com/a.m3u8", "https://user@cdn.example/a.m3u8",
    )

    /** A playlist line by line, from tags the rewriter knows, tags it does not, comments and uri lines. */
    fun playlist(): String = buildString {
        if (chance(90)) append("#EXTM3U\n")
        repeat(random.nextInt(12)) {
            val ref = reference()
            append(
                pick(
                    "#EXTINF:4,\n$ref", "#EXT-X-STREAM-INF:BANDWIDTH=1\n$ref", "#EXT-X-KEY:METHOD=AES-128,URI=\"$ref\"",
                    "#EXT-X-MAP:URI=\"$ref\"", "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"$ref\",URI=\"$ref\"", "#EXT-X-MAP:URI=$ref",
                    "#EXT-X-DEFINE:NAME=\"x\",VALUE=\"$ref\"", "#EXT-X-UNKNOWN:URI=\"$ref\"", "# comment $ref",
                    "#EXT-X-DATERANGE:ID=\"a\",X-ASSET-URI=\"$ref\"", "#EXT-X-SESSION-DATA:DATA-ID=\"d\",URI=\"$ref\"",
                    "#EXT-X-KEY:METHOD=NONE", "#EXT-X-KEY", "#EXT-X-PART:URI=\"$ref\",DURATION=1", "\r", "",
                    "#EXT-X-CONTENT-STEERING:SERVER-URI=\"$ref\"", "#EXT-X-KEY:METHOD=AES-128,URI=\"$ref",
                    ref, "#EXT-X-VENDOR:${junk(3)}",
                ),
            )
            append(pick("\n", "\r\n"))
        }
    }
}
