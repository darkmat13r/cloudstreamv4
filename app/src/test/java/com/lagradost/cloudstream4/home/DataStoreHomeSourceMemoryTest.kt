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

    /** The old screen can leave Random in the same key, which is not a source, and the owner ruled it out as one. */
    @Test
    fun theOldScreensRandomIsNotAChoice() {
        stored = APIRepository.randomApi.name
        assertNull(memory.last())
    }

    /** `None` is a choice (3h), kept under the old screen's name for it so both screens read it the same. */
    @Test
    fun noneIsKeptUnderTheOldScreensNameAndReadsBackAsNone() {
        memory.remember(HomeSourceId.None)
        assertEquals(APIRepository.noneApi.name, stored)
        assertEquals(HomeSourceId.None, memory.last())
        stored = APIRepository.noneApi.name
        assertEquals(HomeSourceId.None, memory.last())
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
