package com.lagradost.cloudstream3.ui.result

/**
 * Converts an episode runtime in minutes to whole seconds for display.
 *
 * The value is supplied by an extension provider, so it is untrusted: the product is taken in
 * [Long] and clamped to the [Int] range rather than truncated, which would wrap a large input
 * into a negative or unrelated duration.
 */
internal fun runTimeMinutesToSeconds(minutes: Int?): Int? =
    minutes?.times(60L)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
