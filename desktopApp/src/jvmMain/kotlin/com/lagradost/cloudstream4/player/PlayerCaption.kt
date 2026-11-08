package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.feature.detail.DetailEpisode

/**
 * The line beside the title in the player's top band (`DS-23` M1): the episode's code, `S1:E4`, in the app's own form
 * and translated letters, then its name. Null for a film, or an episode that says nothing about itself.
 */
internal fun episodeMeta(episode: DetailEpisode, seasonShort: String, episodeShort: String): String? {
    val code = listOfNotNull(
        episode.season?.let { "$seasonShort$it" },
        episode.episode?.let { "$episodeShort$it" },
    ).joinToString(":")
    return listOfNotNull(code.ifEmpty { null }, episode.name?.trim()?.ifEmpty { null })
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
