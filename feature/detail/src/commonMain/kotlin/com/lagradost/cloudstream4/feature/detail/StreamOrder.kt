package com.lagradost.cloudstream4.feature.detail

import kotlin.math.abs

/**
 * Best first, as the Android player orders a title's links when nobody has changed its quality profile
 * (`PlayerGeneratorViewModel.sortLinks`, `QualityDataHelper.getLinkPriority`).
 *
 * There, a link's priority is its source's priority plus its quality's. The source's is 1 for every source until
 * someone sets one, so the order is the quality's alone: the `Qualities` entry nearest the link's number, and that
 * entry's `defaultPriority`. Equal priorities keep the order the links arrived in, because the sort is stable.
 *
 * What that means for a link whose quality nobody knows: a plugin says so with `Qualities.Unknown`, whose number is
 * 400, and its priority is 4, the same as 480p. So an unknown link ranks with 480p, below 720p and above 360p, and
 * this does the same rather than putting it last. A link with no number at all, null here, is also unknown, as
 * `closestQuality(null)` is there. A 0, which `Qualities` calls Auto, is nearest 144p and ranks last.
 *
 * Common code with no `:library`, so the table is restated rather than read from `Qualities`; a desktop test reads
 * the real enum and holds the two together.
 */
fun List<StreamLink>.bestFirst(): List<StreamLink> = sortedByDescending { rankOf(it.quality) }

/** The Android player's default priority for a link of this quality. Higher plays first. */
fun rankOf(quality: Int?): Int {
    if (quality == null) return UNKNOWN_RANK
    // `minBy` keeps the first of two equally near entries, and so does this: the table is in `Qualities`' order.
    return QUALITY_RANKS.minBy { (value, _) -> abs(value - quality) }.second
}

/**
 * `Qualities`' entries in declaration order, as value to `defaultPriority`. Mirrors `library`'s `ExtractorApi.kt`.
 */
private val QUALITY_RANKS = listOf(
    400 to 4, // Unknown
    144 to 0,
    240 to 2,
    360 to 3,
    480 to 4,
    720 to 5,
    1080 to 6,
    1440 to 7,
    2160 to 8,
)

private const val UNKNOWN_RANK = 4

/** 2160p's, the highest any quality can rank: the Android player starts at once at its own top (`AUTO_SKIP_PRIORITY`). */
const val TOP_RANK = 8

/**
 * Which stream to play, best first, and which next when one fails, for a player that is given a title's links as
 * they are found. Desktop's player uses it; Android TV's can, because it knows nothing of either.
 *
 * Each link is tried by itself at most once, so a title whose links all fail ends instead of going round again.
 * A person can still choose any of them, failed or not: [chosen] records that without asking.
 *
 * Not thread-safe: one screen's state, used where that screen's state is.
 */
class StreamQueue(first: StreamLink) {
    private val tried = mutableListOf(first)

    /** Someone chose [link], or the queue did. */
    fun chosen(link: StreamLink) {
        if (tried.none { it.isSame(link) }) tried += link
    }

    /**
     * The best of [found] not yet tried, or null when there is none. A better link that arrived after the one that
     * failed comes first, where the Android player's index would have skipped it.
     */
    fun next(found: List<StreamLink>): StreamLink? = found.bestFirst().firstOrNull { link -> tried.none { it.isSame(link) } }

    /** The player's own test for one stream: a later answer carries the same stream with more subtitles. */
    private fun StreamLink.isSame(other: StreamLink) = url == other.url && audioUrl == other.audioUrl
}
