package com.lagradost.cloudstream4.feature.account

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a profile card's second line says (8d: "current · Kitsu", "locked · PIN", "no sync"). */
class ProfileLineTest {

    @Test
    fun aLockedProfileSaysOnlyThatItIsLocked() {
        val card = ProfileCard(id = 1, name = "Sara", current = true, locked = true, syncedWith = listOf("Kitsu"))
        assertEquals(ProfileLine.Locked, card.line())
    }

    @Test
    fun anUnlockedProfileSaysWhetherItIsCurrentAndWhatItSyncsWith() {
        assertEquals(
            ProfileLine.Status(current = true, services = listOf("Kitsu")),
            ProfileCard(id = 0, name = "Main", current = true, locked = false, syncedWith = listOf("Kitsu")).line(),
        )
        assertEquals(
            ProfileLine.Status(current = false, services = emptyList()),
            ProfileCard(id = 2, name = "Guest", current = false, locked = false).line(),
        )
    }
}
