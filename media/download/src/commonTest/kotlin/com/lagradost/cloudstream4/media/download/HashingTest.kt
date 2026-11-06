package com.lagradost.cloudstream4.media.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HashingTest {
    private fun sha256(vararg chunks: String): String = newStreamingHash(HashAlgorithm.SHA256).run {
        chunks.forEach { update(bytesOf(it)) }
        finishHex()
    }

    private fun md5(vararg chunks: String): String = newStreamingHash(HashAlgorithm.MD5).run {
        chunks.forEach { update(bytesOf(it)) }
        finishHex()
    }

    @Test
    fun `sha256 known vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            sha256("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"),
        )
    }

    @Test
    fun `sha256 of a million a in many updates`() {
        val hash = newStreamingHash(HashAlgorithm.SHA256)
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { hash.update(chunk) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hash.finishHex())
    }

    @Test
    fun `splitting the input anywhere gives the same digest`() {
        val whole = sha256("abc")
        assertEquals(whole, sha256("a", "bc"))
        assertEquals(whole, sha256("ab", "c"))
        assertEquals(whole, sha256("a", "b", "c"))
        assertEquals(whole, sha256("", "abc", ""))
        assertEquals(whole, sha256("a", "", "b", "", "c"))
    }

    @Test
    fun `an offset and a length select a window of the array`() {
        val data = bytesOf("xxabcyy")
        val hash = newStreamingHash(HashAlgorithm.SHA256)
        hash.update(data, 2, 3)
        assertEquals(sha256("abc"), hash.finishHex())
        val partial = newStreamingHash(HashAlgorithm.SHA256)
        partial.update(data, 2, 1)
        partial.update(data, 3, 2)
        partial.update(data, 7, 0)
        assertEquals(sha256("abc"), partial.finishHex())
    }

    @Test
    fun `a window outside the array throws and does not change the digest`() {
        val hash = newStreamingHash(HashAlgorithm.SHA256)
        val data = bytesOf("abc")
        assertFailsWith<IndexOutOfBoundsException> { hash.update(data, 2, 2) }
        assertFailsWith<IndexOutOfBoundsException> { hash.update(data, -1, 1) }
        assertFailsWith<IndexOutOfBoundsException> { hash.update(data, 0, -1) }
        hash.update(data)
        assertEquals(sha256("abc"), hash.finishHex())
    }

    @Test
    fun `high bytes are hashed as unsigned`() {
        val hash = newStreamingHash(HashAlgorithm.SHA256)
        hash.update(ByteArray(256) { it.toByte() })
        assertEquals("40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880", hash.finishHex())
    }

    @Test
    fun `md5 known vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5("abc"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5("a", "b", "c"))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", md5("1234567890".repeat(8)))
    }

    @Test
    fun `hex is lower case and fixed width`() {
        assertEquals("000f10ff", byteArrayOf(0, 15, 16, -1).toHex())
        assertEquals("", ByteArray(0).toHex())
    }
}
