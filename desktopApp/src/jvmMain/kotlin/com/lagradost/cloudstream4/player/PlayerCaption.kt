package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.detail.DetailEpisode

/**
 * The line beside the title in the player's top band (`DS-23` M1): the episode's code, `S1:E4`, in the app's own form
 * and translated letters, then its name. Null for a film, or an episode that says nothing about itself.
 */
internal fun episodeMeta(episode: DetailEpisode, seasonShort: String, episodeShort: String): String? =
    episodeMeta(episode.season, episode.episode, episode.name, seasonShort, episodeShort)

/** The same line from its parts, for an episode that is not a page's, such as a download (`KMP-74`). */
internal fun episodeMeta(season: Int?, episode: Int?, name: String?, seasonShort: String, episodeShort: String): String? {
    val code = listOfNotNull(
        season?.let { "$seasonShort$it" },
        episode?.let { "$episodeShort$it" },
    ).joinToString(":")
    return listOfNotNull(code.ifEmpty { null }, name?.trim()?.ifEmpty { null })
        .joinToString(CAPTION_SEPARATOR)
        .ifEmpty { null }
}

/**
 * What the top band says beside the title. The source and its quality are the Source selector's value; with no
 * selector (a host that cannot switch), they join this line instead, so the band never says less than it used to.
 */
internal fun hudMeta(meta: String?, sourceName: String?, hasSourceSelector: Boolean): String? =
    listOfNotNull(meta, sourceName?.takeUnless { hasSourceSelector })
        .joinToString(CAPTION_SEPARATOR)
        .ifEmpty { null }

internal const val CAPTION_SEPARATOR = " · "
