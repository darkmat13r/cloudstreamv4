package com.lagradost.cloudstream4.player.gateway

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `BUG-114`: [PortableUri], the iPad's parser, reads every url as `java.net.URI` does, and resolves every reference as
 * `URI.resolve` does. Held to the JDK itself here, on the JVM, over generated hostile input ([Fuzz]), since the iPad has
 * no JDK to compare with. Every field the gateway reads is compared, and so is whether it parses at all.
 */
class PortableUriParityTest {

    private fun jdk(text: String) = runCatching { URI(text) }.getOrNull()?.let { ParsedUri(it.scheme, it.rawUserInfo, it.host, it.port, it.rawAuthority, it.rawPath, it.rawQuery) }

    private fun ParsedUri?.fields() = this?.let { listOf(scheme, rawUserInfo, host, port, rawAuthority, rawPath, rawQuery) }

    @Test
    fun everyUrlParsesAsJavaNetUriParsesIt() {
        val fuzz = Fuzz(2114)
        repeat(200_000) {
            val text = if (it % 2 == 0) fuzz.url() else fuzz.reference()
            assertEquals(jdk(text).fields(), PortableUri.parse(text).fields(), text)
        }
    }

    @Test
    fun everyLenientReferenceParsesAsJavaNetUriParsesIt() {
        val fuzz = Fuzz(2115)
        repeat(100_000) {
            val text = HlsRewriter.lenient(fuzz.reference())
            assertEquals(jdk(text).fields(), PortableUri.parse(text).fields(), text)
        }
    }

    @Test
    fun everyReferenceResolvesAsJavaNetUriResolvesIt() {
        val fuzz = Fuzz(2116)
        repeat(200_000) {
            val base = HlsRewriter.lenient(fuzz.base())
            val reference = HlsRewriter.lenient(fuzz.reference())
            val expected = runCatching { URI(base).resolve(URI(reference)) }.getOrNull()
                ?.let { ParsedUri(it.scheme, it.rawUserInfo, it.host, it.port, it.rawAuthority, it.rawPath, it.rawQuery) }
            assertEquals(expected.fields(), PortableUri.resolve(base, reference).fields(), "$base + $reference")
        }
    }
}
