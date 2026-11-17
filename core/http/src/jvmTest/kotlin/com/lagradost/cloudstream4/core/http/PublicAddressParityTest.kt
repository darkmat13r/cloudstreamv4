package com.lagradost.cloudstream4.core.http

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.lagradost.cloudstream4.media.download.isPublicAddress as downloadsIsPublicAddress

/**
 * `BUG-111`: [isPublicAddress] is `:media:download`'s rule, restated (that module depends on this one). They must give
 * the same answer for every address; this holds them to it over every IPv4 /16 and every leading IPv6 pair.
 */
class PublicAddressParityTest {

    @Test
    fun everyIpv4AddressGetsTheSameAnswer() {
        for (a in 0..255) for (b in 0..255) for (c in listOf(0, 1, 2, 99, 100, 255)) for (d in listOf(0, 1, 254, 255)) {
            val address = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
            assertEquals(downloadsIsPublicAddress(address), isPublicAddress(address), "$a.$b.$c.$d")
        }
    }

    @Test
    fun everyIpv6PrefixGetsTheSameAnswer() {
        val random = Random(111)
        for (a in 0..255) for (b in 0..255) {
            repeat(4) { variant ->
                val address = ByteArray(16)
                address[0] = a.toByte()
                address[1] = b.toByte()
                if (variant > 0) random.nextBytes(address, 2, 16)
                assertEquals(downloadsIsPublicAddress(address), isPublicAddress(address), address.joinToString(":"))
            }
        }
        // The forms that hide IPv4: mapped (every v4 /16), compatible, and the well-known NAT64 prefix.
        for (a in 0..255) for (b in 0..255) {
            for (prefix in listOf(MAPPED, COMPATIBLE, NAT64)) {
                val address = prefix + byteArrayOf(a.toByte(), b.toByte(), 0, 1)
                assertEquals(downloadsIsPublicAddress(address), isPublicAddress(address), address.joinToString(":"))
            }
        }
    }

    @Test
    fun anyOtherLengthIsNotPublic() {
        for (size in listOf(0, 3, 5, 15, 17)) assertFalse(isPublicAddress(ByteArray(size) { 8 }))
    }

    /** The one place the gate differs: an address under 64:ff9b::/96 is judged by the IPv4 address inside it. */
    @Test
    fun aWellKnownNat64AddressIsJudgedByTheIpv4AddressInside() {
        assertTrue(isPublicDestination(NAT64 + byteArrayOf(8, 8, 8, 8)))
        for (inside in listOf(byteArrayOf(127, 0, 0, 1), byteArrayOf(10, 0, 0, 1), byteArrayOf(-64, -88, 1, 1), byteArrayOf(-87, -2, -87, -2), byteArrayOf(0, 0, 0, 0))) {
            assertFalse(isPublicDestination(NAT64 + inside), inside.joinToString("."))
        }
        // Not the well-known /96: the local-use 64:ff9b:1::/48 stays refused, as the shared rule has it.
        val localUse = NAT64.copyOf().also { it[5] = 1 } + byteArrayOf(8, 8, 8, 8)
        assertFalse(isPublicDestination(localUse))
        // Everywhere else, the shared rule.
        assertTrue(isPublicDestination(byteArrayOf(8, 8, 8, 8)))
        assertFalse(isPublicDestination(byteArrayOf(127, 0, 0, 1)))
        assertFalse(isPublicDestination(MAPPED + byteArrayOf(127, 0, 0, 1)))
    }

    private companion object {
        val MAPPED = ByteArray(10) + byteArrayOf(-1, -1)
        val COMPATIBLE = ByteArray(12)
        val NAT64 = byteArrayOf(0, 0x64, -1, -101) + ByteArray(8)
    }
}
