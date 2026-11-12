package com.lagradost.cloudstream4.feature.detail.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** `DS-26`'s arithmetic (`KMP-104`): how many people and cards a line, and a person's initials. */
class DetailCastDesktopTest {

    @Test
    fun `four people and five cards a line in the 1280 window's page, two and three in the 800 window's`() {
        val wide = 1012.dp // 1060 beside the sidebar, less the 24 margin each side
        val narrow = 532.dp // 580, likewise
        assertEquals(4, DetailCastDesktopDefaults.columns(wide, DetailCastDesktopDefaults.PersonMinWidth))
        assertEquals(5, DetailCastDesktopDefaults.columns(wide, DetailCastDesktopDefaults.CardMinWidth))
        assertEquals(2, DetailCastDesktopDefaults.columns(narrow, DetailCastDesktopDefaults.PersonMinWidth))
        assertEquals(3, DetailCastDesktopDefaults.columns(narrow, DetailCastDesktopDefaults.CardMinWidth))
        assertEquals(1, DetailCastDesktopDefaults.columns(40.dp, DetailCastDesktopDefaults.CardMinWidth), "never none")
    }

    @Test
    fun `initials are the first letter of the first two words, whatever the script`() {
        assertEquals("IM", initials("Ines Marlowe"))
        assertEquals("LF", initials("Lucía  Ferrer de la Vega"))
        assertEquals("É", initials("élodie"))
        assertEquals("葛", initials("葛飾"))
        assertEquals("🌟", initials("🌟 star").take(2), "a letter outside the BMP is kept whole")
        assertEquals("", initials("   "))
    }
}
