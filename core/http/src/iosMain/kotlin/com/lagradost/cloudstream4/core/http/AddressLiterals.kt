@file:OptIn(ExperimentalForeignApi::class)

package com.lagradost.cloudstream4.core.http

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSMutableURLRequest
import platform.posix.AF_UNSPEC
import platform.posix.AI_NUMERICHOST
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo

/**
 * `BUG-111`: refuses a request whose host is an IP address rather than a name, before the session sees it.
 *
 * Measured on the iOS simulator: `NSURLSession` sends a request for a loopback or link-local **address** straight to it,
 * past its configured proxy, so the [EgressGate] never sees it. That covers every spelling of 127.0.0.1 (`127.1`,
 * `2130706433`, `0x7f000001`), `0.0.0.0`, `[::1]`, `[::ffff:7f00:1]`, `169.254.169.254`, `[fe80::1]` and `[fd00::1]`.
 * Every **name** went to the gate, `localhost` included. So an address is refused here, public or not, and a name
 * is left to the gate, which judges what it resolves to.
 *
 * Applied with `configureRequest`, which the Darwin engine runs on every request it sends, each redirect's included
 * (it turns off the session's own redirects and follows them itself).
 */
internal fun NSMutableURLRequest.refuseAddressLiterals() {
    val host = URL?.host
    if (host == null || isAddressLiteral(host)) throw RefusedDestination()
}

/** Raised before a request to an address the client refuses. Fixed text: never the address, which a page chose. */
class RefusedDestination : Exception("refused: not a public address")

/**
 * Whether [host] (as `NSURL` parsed it, without brackets) is an IP address in any spelling the system would connect to:
 * anything with a colon (IPv6), anything the system's own numeric parser takes (`inet_aton`'s forms included), and
 * anything whose last label is a number, as the URL standard reads a host (`1.2.3.0x4`, `1.2.3.077`).
 */
internal fun isAddressLiteral(host: String): Boolean {
    if (host.isEmpty() || ':' in host || '[' in host || ']' in host || '%' in host) return true
    if (parsesAsNumeric(host)) return true
    val last = host.removeSuffix(".").substringAfterLast('.')
    if (last.isEmpty()) return true
    return last.all { it in '0'..'9' } || (last.startsWith("0x", ignoreCase = true) && last.drop(2).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
}

private fun parsesAsNumeric(host: String): Boolean = memScoped {
    val hints = zeroed<addrinfo>().apply {
        ai_family = AF_UNSPEC
        ai_flags = AI_NUMERICHOST
    }
    val result = alloc<CPointerVar<addrinfo>>()
    val ok = getaddrinfo(host, null, hints.ptr, result.ptr) == 0
    if (ok) freeaddrinfo(result.value)
    ok
}
