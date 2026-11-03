package com.lagradost.cloudstream4.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UntrustedTextTest {

    @Test
    fun `what would reorder or hide text is removed`() {
        assertEquals("fdp.jar", "‮fdp⁦.jar​".untrustedText())
        assertEquals("evil[2Jline", "evil\u001B[2J\nline ".untrustedText())
        assertEquals("ab", "a\uDC00b".untrustedText())
    }

    @Test
    fun `real text from any script survives`() {
        val mixed = "com/例え/Клас 😀 uses java/io/File"
        assertEquals(mixed, mixed.untrustedText())
    }

    @Test
    fun `it is bounded, and a cut cannot leave half of a pair`() {
        val cut = "😀".repeat(1000).untrustedText(10)
        assertEquals("😀".repeat(10) + "…", cut)
        assertFalse(cut.any { it.isSurrogate() && it == cut.last() })
        assertTrue("x".repeat(100_000).untrustedText().length <= MAX_UNTRUSTED_CODE_POINTS + 1)
    }

    @Test
    fun `markup and format specifiers stay inert text`() {
        assertEquals("<b>%1\$s</b>", "<b>%1\$s</b>".untrustedText())
    }
}
