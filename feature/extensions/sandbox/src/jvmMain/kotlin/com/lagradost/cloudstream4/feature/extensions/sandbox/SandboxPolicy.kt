package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.net.InetAddress

/** Decisions about untrusted names and addresses that the app makes outside any sandbox. */
object SandboxPolicy {

    private val SAFE_EXTENSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * The private directory for the extension called [name], or null when [name] is not safe.
     *
     * Refused rather than cleaned, because cleaning can merge two names into one shared directory.
     * The name comes from a file in a scanned directory: `...jar` has the name `..`, which would make
     * the extensions root its writable directory, and with it the `.store` of scanned jars.
     * The canonical check also catches a symlink planted at the target.
     */
    fun dataDirectory(sandboxes: File, name: String): File? {
        if (!name.matches(SAFE_EXTENSION_NAME) || name.contains("..")) return null
        val directory = File(sandboxes, name)
        val root = runCatching { sandboxes.canonicalFile }.getOrNull() ?: return null
        return directory.takeIf { runCatching { it.canonicalFile.parentFile }.getOrNull() == root }
    }

    /**
     * Whether the app may connect to [address] on a plugin's behalf.
     *
     * JDK checks where they are right, and bytes where they are not: `isSiteLocalAddress` knows only
     * the deprecated `fec0::/10` on IPv6, and nothing knows carrier-grade NAT. An IPv4-mapped IPv6
     * address needs no case of its own, because `InetAddress` already returns it as IPv4.
     */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        val first = bytes[0].toInt() and BYTE_MASK
        val second = bytes[1].toInt() and BYTE_MASK
        return when (bytes.size) {
            IPV4_BYTES -> first != 0 && !(first == CGNAT_FIRST && second in CGNAT_SECOND)
            else -> first and ULA_MASK != ULA_PREFIX
        }
    }

    private const val BYTE_MASK = 0xFF
    private const val IPV4_BYTES = 4
    private const val CGNAT_FIRST = 100
    private val CGNAT_SECOND = 64..127
    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
}
