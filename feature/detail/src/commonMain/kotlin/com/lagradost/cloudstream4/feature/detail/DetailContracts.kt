package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable

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

/** One playable stream. The host has already refused any URL it will not play. */
@Immutable
data class StreamLink(
    val label: String,
    val qualityLabel: String?,
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val audioUrl: String?,
)

/**
 * Where a title and its streams come from. Both calls reach third-party code, so both are expected
 * to fail, and they report it by throwing.
 */
interface DetailSource {
    suspend fun details(id: String): TitleDetail
    suspend fun links(id: String, data: String): List<StreamLink>
}
