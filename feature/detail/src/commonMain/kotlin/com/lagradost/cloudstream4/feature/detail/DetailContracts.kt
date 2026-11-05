package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import kotlinx.coroutines.flow.Flow

/** One episode of a series. [data] is opaque, and goes back to [DetailSource.links] unread. */
@Immutable
data class DetailEpisode(
    val name: String?,
    val data: String,
    val season: Int?,
    val episode: Int?,
)

/**
 * A title, as the screen needs it.
 *
 * @param playData what to ask for links with when the title is itself playable. Null for a series,
 *   and also null for a title the provider gave nothing playable for.
 */
@Immutable
data class TitleDetail(
    val title: String,
    val providerName: String,
    /** The provider's type name, such as `TvSeries`. An identifier: the copy turns it into words. */
    val typeName: String?,
    val year: Int?,
    val plot: String?,
    val posterUrl: String?,
    val playData: String?,
    val episodes: List<DetailEpisode>,
) {
    /** What the primary button plays: the title, or else the first episode. */
    val primaryData: String? get() = playData ?: episodes.firstOrNull()?.data
}

/**
 * One playable stream. The host has already refused any URL it will not play.
 *
 * @param subtitles what the provider had offered in the same call by the time this was read. A provider's
 *   subtitle list belongs to the title rather than to one stream, so every link of one answer carries the same
 *   list.
 * @param quality the plugin's own number for it, `ExtractorLink.quality`, unread except to rank it (see [bestFirst]).
 *   Null where nothing said.
 */
@Immutable
data class StreamLink(
    val label: String,
    val qualityLabel: String?,
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val audioUrl: String?,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val quality: Int? = null,
)

/**
 * Where a title and its streams come from. Both calls reach third-party code, so both are expected
 * to fail, and they report it by throwing.
 */
interface DetailSource {
    suspend fun details(id: String): TitleDetail

    /**
     * Every stream so far, each time there are more. A later value only adds to the end of an earlier one, so a
     * row that is on screen stays where it is. It completes when the source has nothing more to say, having
     * emitted nothing if it found nothing, and cancelling it stops the work behind it where that can be stopped.
     */
    fun links(id: String, data: String): Flow<List<StreamLink>>
}
