package com.lagradost.cloudstream4.media.download

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class HashingJvmTest {
    private fun jdk(name: String, data: ByteArray): String = MessageDigest.getInstance(name).digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `sha256 and md5 agree with the jdk on random data fed in random chunks`() {
        val random = Random(20261105)
        repeat(50) {
            val data = random.nextBytes(random.nextInt(0, 200_000))
            for ((algorithm, jdkName) in listOf(HashAlgorithm.SHA256 to "SHA-256", HashAlgorithm.MD5 to "MD5")) {
                val hash = newStreamingHash(algorithm)
                var at = 0
                while (at < data.size) {
                    val take = random.nextInt(0, minOf(70_000, data.size - at) + 1)
                    hash.update(data, at, take)
                    at += take
                }
                assertEquals(jdk(jdkName, data), hash.finishHex(), "$algorithm over ${data.size} bytes")
            }
        }
    }

    @Test
    fun `an empty input matches the jdk`() {
        assertEquals(jdk("SHA-256", ByteArray(0)), newStreamingHash(HashAlgorithm.SHA256).finishHex())
    }
}
