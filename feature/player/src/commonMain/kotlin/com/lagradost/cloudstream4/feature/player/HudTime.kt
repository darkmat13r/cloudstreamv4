package com.lagradost.cloudstream4.feature.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 7d's times: `42:13`, `1:01:44`, `-19:31`. Minutes and seconds always two digits after the first field; hours only when
 * there are any. Numbers, not words, so nothing here is translated. Public so `:app` writes 7e's `keeps 42:13` the same way.
 */
object HudTime {
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** `-19:31`: what is left, with the board's hyphen. */
    fun remaining(positionMs: Long, durationMs: Long): String = "-" + clock(durationMs - positionMs)

    /** The readout's step, `+00:30` or `-01:00`, always minutes and seconds so a burst reads the same as it grows. */
    fun delta(ms: Long): String {
        val sign = if (ms < 0) "-" else "+"
        val total = abs(ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return sign + (if (h > 0) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}")
    }

    /** `1.0×`, `1.25×`: one decimal at least, two when the speed has them. */
    fun speed(speed: Float): String {
        val hundredths = (speed * 100).roundToLong()
        val whole = hundredths / 100
        val rest = hundredths % 100
        val fraction = if (rest % 10 == 0L) "${rest / 10}" else two(rest)
        return "$whole.$fraction×"
    }

    private fun two(n: Long): String = if (n < 10) "0$n" else "$n"
}
