package com.lagradost.cloudstream4.core.http

/**
 * `BUG-111`: whether a connection may go to [address] (4 or 16 bytes). Any other length is not public.
 *
 * `:media:download`'s `isPublicAddress`, restated here because that module depends on this one, not the other way
 * round. `PublicAddressParityTest` holds the two to the same answers.
 */
internal fun isPublicAddress(address: ByteArray): Boolean = when (address.size) {
    4 -> isPublicIpv4(address)
    16 -> isPublicIpv6(address)
    else -> false
}

/**
 * [isPublicAddress], except that an address under NAT64's well-known prefix `64:ff9b::/96` (RFC 6052) is judged by
 * the IPv4 address inside it.
 *
 * On an IPv6-only network (Apple requires apps to work on one) the system synthesizes such an address for every
 * IPv4-only host, so refusing the prefix outright would refuse most of the internet there. The IPv4 address inside is
 * where the gateway connects, so it is the one that must be public. A network-specific NAT64 prefix cannot be told from
 * an ordinary IPv6 address, and is judged as one.
 */
internal fun isPublicDestination(address: ByteArray): Boolean =
    if (address.size == 16 && isWellKnownNat64(address)) isPublicIpv4(address.copyOfRange(12, 16)) else isPublicAddress(address)

private fun isWellKnownNat64(b: ByteArray): Boolean =
    b.u(0) == 0x00 && b.u(1) == 0x64 && b.u(2) == 0xFF && b.u(3) == 0x9B && (4 until 12).all { b.u(it) == 0 }

private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

private fun isPublicIpv4(b: ByteArray): Boolean {
    val first = b.u(0)
    val second = b.u(1)
    return when {
        first == 0 -> false // 0.0.0.0/8, includes the unspecified address
        first == 10 -> false
        first == 127 -> false
        first == 100 && second in 64..127 -> false // carrier-grade NAT
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 168 -> false
        first == 192 && second == 0 && b.u(2) == 0 -> false // IETF protocol assignments
        first == 198 && second in 18..19 -> false // benchmarking
        first >= 224 -> false // multicast and reserved, including broadcast
        else -> true
    }
}

private fun isPublicIpv6(b: ByteArray): Boolean {
    val first = b.u(0)
    val second = b.u(1)
    val leadingZeros = (0 until 10).all { b.u(it) == 0 }
    if (leadingZeros && b.u(10) == 0xFF && b.u(11) == 0xFF) return isPublicIpv4(b.copyOfRange(12, 16)) // IPv4-mapped
    if ((0 until 12).all { b.u(it) == 0 }) return false // ::, ::1 and the deprecated IPv4-compatible form
    return when {
        first == 0xFF -> false // multicast
        first == 0xFE && (second and 0xC0) == 0x80 -> false // link-local fe80::/10
        first == 0xFE && (second and 0xC0) == 0xC0 -> false // site-local fec0::/10
        (first and 0xFE) == 0xFC -> false // unique local fc00::/7
        first == 0x20 && second == 0x02 -> false // 6to4, an IPv4 address inside
        first == 0x00 && second == 0x64 && b.u(2) == 0xFF && b.u(3) == 0x9B -> false // NAT64
        else -> true
    }
}
