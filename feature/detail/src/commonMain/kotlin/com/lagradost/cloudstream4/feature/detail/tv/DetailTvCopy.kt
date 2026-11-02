package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.runtime.Immutable

/** Every word on the screen, resolved by the host, as for `SearchTvCopy`. */
@Immutable
data class DetailTvCopy(
    val back: String,
    val playMovie: String,
    val playEpisode: String,
    val episodes: String,
    val noEpisodes: String,
    val sources: String,
    val seasonShort: String,
    val episodeShort: String,
    /** A provider type name as words, or null for one with no copy, which then shows nothing. */
    val typeLabel: (String) -> String?,
)
