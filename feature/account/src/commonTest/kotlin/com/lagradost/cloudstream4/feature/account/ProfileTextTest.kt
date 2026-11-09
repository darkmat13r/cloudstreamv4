package com.lagradost.cloudstream4.feature.account

import kotlin.test.Test
import kotlin.test.assertEquals

/** `KMP-95a`: a profile's initials, which TV's cards and the Mac's share. */
class ProfileTextTest {

    @Test
    fun aNameGivesUpToTwoInitialsInCapitals() {
        assertEquals("A", profileInitials("Alex"))
        assertEquals("AL", profileInitials("ana  lima"))
        assertEquals("AL", profileInitials("Ana Lima Costa"))
        assertEquals("", profileInitials("   "))
    }

    /** The reviewer of `#278`: an initial is a code point, so one outside the BMP is not cut to half a surrogate pair. */
    @Test
    fun anInitialOutsideTheBmpIsKeptWhole() {
        val script = "𝒜" // U+1D49C MATHEMATICAL SCRIPT CAPITAL A
        assertEquals(script, profileInitials("${script}lex"))
        assertEquals("${script}B", profileInitials("${script}lex Bo"))
    }
}
