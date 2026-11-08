package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_hours_minutes_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_day_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_min_format
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one age formatter (`KMP-85`), for the library's sync line and Home's kept rows: `4m`, `2h 5m`, `1d 2h 5m`. */
class ElapsedAgeTest {
    private val minute = 60_000L

    @Test
    fun `minutes under an hour, hours and minutes under a day, then days`() {
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(0)), elapsedAge(59_999))
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(12)), elapsedAge(12 * minute))
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(59)), elapsedAge(60 * minute - 1))
        assertEquals(UiString.Resource(Res.string.duration_hours_minutes_format, listOf(1, 0)), elapsedAge(60 * minute))
        assertEquals(UiString.Resource(Res.string.duration_hours_minutes_format, listOf(2, 5)), elapsedAge(125 * minute))
        assertEquals(UiString.Resource(Res.string.next_episode_time_day_format, listOf(1, 2, 5)), elapsedAge((26 * 60 + 5) * minute))
    }

    @Test
    fun `a clock behind the record reads as now`() {
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(0)), elapsedAge(-5 * minute))
    }

    @Test
    fun `in English it reads as the library and Home have always shown it`() {
        assertEquals("12m", english(elapsedAge(12 * minute)))
        assertEquals("2h 5m", english(elapsedAge(125 * minute)))
        assertEquals("1d 2h 5m", english(elapsedAge((26 * 60 + 5) * minute)))
    }

    private fun english(text: UiString): String =
        SystemEnvironment.of(ENGLISH) { HeadlessComposition.frames(ENGLISH, frames = 1) { text.resolve() } }
            .distinct()
            .single()

    private companion object {
        const val ENGLISH = "en"
    }
}
