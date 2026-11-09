package com.lagradost.cloudstream4.account

import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `KMP-95d`: A11's QR is drawn from the page's address, and only from it. */
class PairQrTest {
    private fun pixels(url: String): List<Int> {
        val bitmap = assertNotNull(pairQr(url))
        assertEquals(bitmap.width, bitmap.height, "a QR is square")
        val map = bitmap.toPixelMap()
        return (0 until bitmap.height).flatMap { y -> (0 until bitmap.width).map { x -> map[x, y].hashCode() } }
    }

    @Test
    fun `the page's address draws a QR of dark and light cells, the same each time, and another address another`() {
        val page = pixels("https://simkl.com/pin")
        assertTrue(page.distinct().size >= 2, "dark and light cells")
        assertEquals(page, pixels("https://simkl.com/pin"), "the same address, the same QR")
        assertNotEquals(page, pixels("https://simkl.com/pin/FAKE00"), "another address, another QR")
    }
}
