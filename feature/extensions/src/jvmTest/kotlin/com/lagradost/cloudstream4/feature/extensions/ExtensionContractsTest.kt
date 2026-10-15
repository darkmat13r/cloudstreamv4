package com.lagradost.cloudstream4.feature.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The contracts' two load-bearing behaviours: the status mapping is total, and the unsupported
 * runtime refuses everything rather than quietly succeeding.
 *
 * Data classes are not tested for being data classes. What is worth pinning is the behaviour a
 * future change could silently break.
 */
class ExtensionContractsTest {

    /** Mirrors the mapping `:app` will own at the edge; the wire codes are in `SitePlugin`'s KDoc. */
    private fun fromWire(code: Int): ExtensionStatus = when (code) {
        0 -> ExtensionStatus.Down
        1 -> ExtensionStatus.Ok
        2 -> ExtensionStatus.Slow
        3 -> ExtensionStatus.BetaOnly
        else -> ExtensionStatus.Unrecognised(code)
    }

    @Test
    fun `the four documented wire codes map to named cases`() {
        assertEquals(ExtensionStatus.Down, fromWire(0))
        assertEquals(ExtensionStatus.Ok, fromWire(1))
        assertEquals(ExtensionStatus.Slow, fromWire(2))
        assertEquals(ExtensionStatus.BetaOnly, fromWire(3))
    }

    @Test
    fun `an unknown wire code is kept, not collapsed into a known case`() {
        for (code in listOf(4, 99, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(ExtensionStatus.Unrecognised(code), fromWire(code), "code $code")
        }
    }

    /**
     * The reason `Skipped` exists: a caller must not be able to read "no hash was published" as
     * "the hash matched". Three distinct outcomes, three distinct types.
     */
    @Test
    fun `a skipped verification is not a verified one`() {
        val skipped: ExtensionVerification = ExtensionVerification.Skipped("repository publishes no hash")
        assertTrue(skipped != ExtensionVerification.Verified)
        assertTrue(skipped !is ExtensionVerification.HashMismatch)
    }

    @Test
    fun `the unsupported runtime refuses every call and loads nothing`() = runTest {
        val runtime = UnsupportedExtensionRuntime("no dynamic loading on this platform")
        val listing = ExtensionListing(
            internalName = "example",
            name = "Example",
            downloadUrl = "https://example.invalid/example.cs3",
            status = ExtensionStatus.Ok,
            version = 3,
            apiVersion = 1,
        )
        val installed = InstalledExtension(
            internalName = "example",
            location = "opaque",
            version = 3,
            isFromRepository = true,
            sourceUrl = listing.downloadUrl,
        )

        assertEquals(emptyList(), runtime.loaded())
        for (result in listOf(
            runtime.install(listing, ByteArray(0), ExtensionVerification.Verified),
            runtime.load(installed),
            runtime.unload(installed),
            runtime.uninstall(installed),
        )) {
            assertTrue(result.isFailure, "every call must refuse")
            assertFailsWith<UnsupportedOperationException> { result.getOrThrow() }
            assertEquals("no dynamic loading on this platform", result.exceptionOrNull()?.message)
        }
    }
}
