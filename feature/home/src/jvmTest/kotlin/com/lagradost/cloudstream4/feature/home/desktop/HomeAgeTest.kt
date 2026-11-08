package com.lagradost.cloudstream4.feature.home.desktop

import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_hours_minutes_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_day_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_min_format
import kotlin.test.Test
import kotlin.test.assertEquals

/** H4's age (`KMP-55`), in the units the library's sync line counts in: `4m`, `2h 5m`, `1d 2h 5m`. */
class HomeAgeTest {
    private val minute = 60_000L

    @Test
    fun `minutes under an hour, hours and minutes under a day, then days`() {
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(0)), homeAge(59_999))
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(12)), homeAge(12 * minute))
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(59)), homeAge(60 * minute - 1))
        assertEquals(UiString.Resource(Res.string.duration_hours_minutes_format, listOf(1, 0)), homeAge(60 * minute))
        assertEquals(UiString.Resource(Res.string.duration_hours_minutes_format, listOf(2, 5)), homeAge(125 * minute))
        assertEquals(UiString.Resource(Res.string.next_episode_time_day_format, listOf(1, 2, 5)), homeAge((26 * 60 + 5) * minute))
    }

    @Test
    fun `a clock behind the record reads as now`() {
        assertEquals(UiString.Resource(Res.string.next_episode_time_min_format, listOf(0)), homeAge(-5 * minute))
    }
}
