package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_hours_minutes_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_day_format
import com.lagradost.cloudstream4.core.strings.generated.resources.next_episode_time_min_format

/**
 * How long ago something happened, in the units the app already counts time in: `4m` under an hour, `2h 5m` under a
 * day, then `1d 2h 5m`. Seconds are not shown, and a clock behind the record reads as now.
 *
 * The one copy (`KMP-85`), for every line that says how old something is: the library's sync line (`Synced 4m ago`,
 * `Offline · cached 2h ago`) and Home's kept rows (`home_reload_failed`). Each wraps it in its own sentence, so the
 * age sits where each language puts it.
 */
fun elapsedAge(elapsedMillis: Long): UiString {
    val minutes = (elapsedMillis.coerceAtLeast(0L) / MILLIS_PER_MINUTE).toInt()
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        hours == 0 -> UiString.Resource(Res.string.next_episode_time_min_format, listOf(minutes))
        days == 0 -> UiString.Resource(Res.string.duration_hours_minutes_format, listOf(hours, minutes % MINUTES_PER_HOUR))
        else -> UiString.Resource(
            Res.string.next_episode_time_day_format,
            listOf(days, hours % HOURS_PER_DAY, minutes % MINUTES_PER_HOUR),
        )
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
