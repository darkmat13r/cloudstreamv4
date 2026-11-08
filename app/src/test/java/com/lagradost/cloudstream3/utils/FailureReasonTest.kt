package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** `BUG-63`: the one line search, detail and downloads make of a provider's failure. */
class FailureReasonTest {

    @Test
    fun noCaseLeavesATokenAPathOrAnInvisibleCharacter() {
        // Every case is tried, and every one that leaks is named, so a failure shows all of them at once.
        val leaks = HostileFailures.cases.mapNotNull { (label, text) ->
            val shown = redactedReason(text, 60)
            HostileFailures.leakIn(shown)?.let { "$label: $it in \"$shown\"" }
        }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test
    fun theReasonAndTheHostStay() {
        assertEquals("Refused https://cdn.example/<…>", redactedReason("Refused https:\u200B//cdn.example/a?token=S", 60))
    }

    @Test
    fun linesThatOnlyLookLikeLinksAreUnchanged() {
        for (plain in HostileFailures.plain) assertEquals(plain, redactedReason(plain, 60))
    }

    @Test
    fun theLineIsBounded() {
        assertEquals(60, redactedReason("x".repeat(500), 60).length)
    }
}
