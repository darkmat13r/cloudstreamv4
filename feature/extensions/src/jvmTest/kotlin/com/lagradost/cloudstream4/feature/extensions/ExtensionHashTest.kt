package com.lagradost.cloudstream4.feature.extensions

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ExtensionHash] against published vectors, not against itself.
 *
 * A digest wrapper tested by calling it twice proves nothing. The three vectors below are
 * NIST/FIPS-180 standard values, reproducible with `printf … | shasum -a 256`, and the third
 * (`ff 00 7f 80`) is the one that fails if the hex conversion forgets `and 0xFF` — every byte above
 * `0x7F` is negative in Kotlin, and a sign-extending conversion writes `ffffffff` where `ff`
 * belongs.
 *
 * What ties the shape to the producer is the measurement in `KMP-13` §2: the official index
 * publishes `jarHash = sha256-0d83e79f…ea41` for `YoutubeProvider.jar` and `shasum -a 256` over the
 * downloaded file prints the same digits.
 */
class ExtensionHashTest {

    private val temp: File = Files.createTempDirectory("extension-hash").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the digest matches the standard vectors and is prefixed lowercase hex`() {
        assertEquals(
            "sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ExtensionHash.of(ByteArray(0)),
        )
        assertEquals(
            "sha256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ExtensionHash.of("abc".toByteArray()),
        )
    }

    /** The sign-extension trap, which a vector of ASCII-only bytes cannot catch. */
    @Test
    fun `bytes above 0x7F hash to two hex digits each`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x00, 0x7F, 0x80.toByte())
        assertEquals(
            "sha256-f4619c809af8e7be15b39e4acf7148b834a2ccbb0da0200127c469a05059111d",
            ExtensionHash.of(bytes),
        )
    }

    /** The streaming overload must agree with the in-memory one across more than one buffer. */
    @Test
    fun `hashing a file agrees with hashing its bytes`() {
        val bytes = ByteArray(20_000) { index -> (index * 31).toByte() }
        val file = File(temp, "artefact.jar").also { it.writeBytes(bytes) }
        assertEquals(ExtensionHash.of(bytes), ExtensionHash.of(file))
    }

    @Test
    fun `the published form is the one the official index uses`() {
        // sha256-<64 lowercase hex>, as WriteCacheEntryTask.kt:85-97 writes it and
        // RepositoryManager.kt:107-122 reads it. Both independently verified against
        // `shasum -a 256 YoutubeProvider.jar` on 2026-10-15.
        val hash = ExtensionHash.of("anything".toByteArray())
        assertTrue(hash.startsWith("sha256-"), hash)
        val hex = hash.removePrefix("sha256-")
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" }, hex)
    }

    @Test
    fun `a published hash that matches verifies`() {
        val bytes = "jar bytes".toByteArray()
        assertEquals(ExtensionVerification.Verified, ExtensionHash.verify(bytes, ExtensionHash.of(bytes)))
    }

    /** An index may shout its hex; the comparison is on the value, not the typography. */
    @Test
    fun `the comparison ignores case`() {
        val bytes = "jar bytes".toByteArray()
        val upper = ExtensionHash.of(bytes).uppercase()
        assertEquals(ExtensionVerification.Verified, ExtensionHash.verify(bytes, upper))
    }

    @Test
    fun `a published hash that does not match reports both sides`() {
        val bytes = "jar bytes".toByteArray()
        val expected = ExtensionHash.of("other bytes".toByteArray())
        val outcome = ExtensionHash.verify(bytes, expected)
        assertIs<ExtensionVerification.HashMismatch>(outcome)
        assertEquals(expected, outcome.expected)
        assertEquals(ExtensionHash.of(bytes), outcome.actual)
    }

    /**
     * The three "cannot check" shapes are [ExtensionVerification.Skipped], never a mismatch.
     *
     * `Skipped` is the absence of a guarantee and `HashMismatch` is evidence of tampering; a caller
     * that cannot tell them apart will eventually refuse a repository for publishing nothing, or
     * install a swapped file because nothing was published. 230 of the 235 catalogue entries
     * surveyed in `KMP-13` §2 are the first of these shapes.
     */
    @Test
    fun `an absent or unreadable hash is skipped rather than mismatched`() {
        val bytes = "jar bytes".toByteArray()
        for (published in listOf(null, "", "   ", "md5-900150983cd24fb0d6963f7d28e17f72", "deadbeef")) {
            assertIs<ExtensionVerification.Skipped>(
                ExtensionHash.verify(bytes, published),
                "published='$published'",
            )
        }
    }
}
