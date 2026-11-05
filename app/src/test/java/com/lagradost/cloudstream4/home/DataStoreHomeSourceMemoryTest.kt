package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Over a stand-in for the key. That the real key is what comes back is `TvHomeNavigationTest`, in the real app. */
class DataStoreHomeSourceMemoryTest {

    private var stored: String? = null
    private val memory = DataStoreHomeSourceMemory(read = { stored }, write = { stored = it })

    @Test
    fun nothingKeptIsNoChoice() = assertNull(memory.last())

    @Test
    fun aChoiceIsKeptUnderTheNameItWasGiven() {
        memory.remember(HomeSourceId("CineTmdb"))
        assertEquals("CineTmdb", stored)
        assertEquals(HomeSourceId("CineTmdb"), memory.last())
    }

    @Test
    fun theLaterChoiceWins() {
        memory.remember(HomeSourceId("One"))
        memory.remember(HomeSourceId("Two"))
        assertEquals(HomeSourceId("Two"), memory.last())
    }

    /** The old screen can leave these in the same key, and neither is a source. */
    @Test
    fun theOldScreensNoneAndRandomAreNotChoices() {
        stored = APIRepository.noneApi.name
        assertNull(memory.last())
        stored = APIRepository.randomApi.name
        assertNull(memory.last())
    }

    @Test
    fun whatCouldNotBeAChoiceIsNeitherReadNorWritten() {
        listOf("", "   ", "line\nbreak", "x".repeat(257)).forEach { bad ->
            stored = bad
            assertNull(bad, memory.last())

            stored = "Kept"
            memory.remember(HomeSourceId(bad))
            assertEquals(bad, "Kept", stored)
        }
    }
}
