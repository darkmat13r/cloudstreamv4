package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.TitleIds

/** What a stamp covers (`KMP-86`): Android's `SkipType`, whose words the button uses. */
internal enum class SkipKind { Opening, Ending, Recap, MixedOpening, MixedEnding, Credits, Intro, Preview }

/** One stretch of an episode someone can skip, in milliseconds from its start. Always `0 <= startMs < endMs`. */
internal data class SkipStamp(val kind: SkipKind, val startMs: Long, val endMs: Long)

/**
 * The episode the skip services are asked about (`KMP-86`): what Android's `SkipAPI` reads from its `LoadResponse` and
 * `ResultEpisode`, and nothing more.
 *
 * @param typeName the provider's `TvType` name, which decides which services are asked, as Android's `supportedTypes`.
 * @param movie whether the title plays as a film, as Android's `isMovie()`: TheIntroDB is then asked with no season.
 * @param season,episode the provider's numbers, null where it gave none. A service that needs one is then not asked.
 */
internal data class SkipQuery(
    val typeName: String?,
    val ids: TitleIds,
    val season: Int?,
    val episode: Int?,
    val movie: Boolean,
)

/**
 * What the skip services are asked about [episodeData] of [detail]: Android's `isMovie()` is a film's type, or a title
 * played whole, with no episode.
 */
internal fun skipQuery(detail: TitleDetail, episodeData: String?): SkipQuery {
    val episode = episodeData?.let { data -> detail.episodes.firstOrNull { it.data == data } }
    return SkipQuery(
        typeName = detail.typeName,
        ids = detail.ids,
        season = episode?.season,
        episode = episode?.episode,
        movie = episode == null || detail.typeName in MOVIE_TYPES,
    )
}

/** An episode's stamps, asked for once its length is known. The player's seam: a test answers without a network. */
internal fun interface SkipStampSource {
    suspend fun stamps(durationMs: Long): List<SkipStamp>
}

/**
 * The stamp the playhead is in, Android's rule (`CS3IPlayer.getCurrentTimestamp`): from its start, and until a seek's
 * slack before its end, so a seek to just past the end never lands back in it.
 */
internal fun List<SkipStamp>.at(positionMs: Long): SkipStamp? =
    firstOrNull { it.startMs <= positionMs && positionMs + SEEK_SLACK_MS + 1 < it.endMs }

/**
 * Whether Skip plays the next episode instead of seeking: Android's rule (`SkipAPI.videoStamps`), a stamp ending within
 * [NEXT_EPISODE_WITHIN_MS] of the end, when there is a next episode to play.
 */
internal fun SkipStamp.skipsToNext(durationMs: Long, hasNext: Boolean): Boolean =
    hasNext && durationMs - endMs < NEXT_EPISODE_WITHIN_MS

/** Android's `TvType.isMovieType()`. */
private val MOVIE_TYPES = setOf("AnimeMovie", "Live", "Movie", "Torrent", "Video")

/** Android's `toleranceBeforeUs`, in milliseconds. */
private const val SEEK_SLACK_MS = 300L

private const val NEXT_EPISODE_WITHIN_MS = 20_000L
