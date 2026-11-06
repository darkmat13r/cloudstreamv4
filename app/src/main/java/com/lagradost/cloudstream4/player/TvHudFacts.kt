package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.feature.player.HudBanner
import com.lagradost.cloudstream4.feature.player.HudSkip
import com.lagradost.cloudstream4.feature.player.PlayerHudState

/**
 * What the player knows at one moment, as the TV HUD needs it (`KMP-64`). `GeneratorPlayer` fills it; [toHudState] is
 * the only place it becomes words.
 *
 * It carries the [link] itself, because the source's name and quality come from it, and that is exactly why
 * [toHudState] is the security boundary: **a stream's URL, its headers and its referer never leave it.** Only the
 * provider's display name and the quality do, cleaned. A file on the device comes in as [downloaded] alone, so its path
 * never enters at all.
 *
 * @property sourceFallback what SOURCE says for a file on the device, already in the user's language.
 */
internal class TvHudFacts(
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val episodeName: String?,
    val videoHeight: Int?,
    val videoCodec: String?,
    val audioLanguage: String?,
    val audioChannels: String?,
    val audioCodec: String?,
    val link: ExtractorLink?,
    val downloaded: Boolean,
    val sourceFallback: String,
    val subtitle: String?,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val speed: Float,
    val marks: List<Long>,
    val skipLabel: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val nextSeason: Int?,
    val nextEpisode: Int?,
    val nextName: String?,
    val failed: Boolean,
    val offline: Boolean,
)

/** `S1:E4`, the board's form. */
private fun episodeLabel(season: Int?, episode: Int?): String? = when {
    episode == null -> null
    season == null -> "E$episode"
    else -> "S$season:E$episode"
}

/**
 * Text from a provider or a file on its way to the screen: no control characters, capped, and **never anything that
 * reads as an address**. A provider can put whatever it likes in a link's name, so one that looks like a URL is
 * dropped rather than shown.
 */
internal fun hudText(text: String?): String? {
    val clean = text?.filterNot { it.isISOControl() }?.trim()?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotBlank() }
        ?: return null
    return clean.takeUnless { "://" in it || it.startsWith("/") || it.startsWith("content:") || it.startsWith("file:") }
}

internal fun TvHudFacts.toHudState(): PlayerHudState {
    val quality = videoHeight?.let { Qualities.getStringByInt(it) }?.takeIf { it.isNotBlank() }
        ?: link?.quality?.let { Qualities.getStringByInt(it) }?.takeIf { it.isNotBlank() }
    val meta = listOfNotNull(
        listOfNotNull(episodeLabel(season, episode), hudText(episodeName)).joinToString(" · ").ifBlank { null },
        quality,
        hudText(videoCodec),
        listOfNotNull(hudText(audioLanguage), hudText(audioChannels)).joinToString(" ").ifBlank { null },
    ).joinToString(" · ")
    val source = if (downloaded) {
        listOfNotNull(sourceFallback, quality).joinToString(" · ")
    } else {
        listOfNotNull(hudText(link?.name), quality).joinToString(" · ").ifBlank { null }
    }
    val upNext = if (!hasNext) {
        null
    } else {
        listOfNotNull(episodeLabel(nextSeason, nextEpisode), hudText(nextName)).joinToString(" ").ifBlank { null }
    }
    return PlayerHudState(
        title = hudText(title).orEmpty(),
        meta = meta,
        downloaded = downloaded,
        playing = playing,
        buffering = buffering,
        positionMs = positionMs.coerceAtLeast(0),
        durationMs = durationMs.coerceAtLeast(0),
        bufferedMs = bufferedMs.coerceAtLeast(0),
        marks = marks.filter { it in 1 until durationMs.coerceAtLeast(1) },
        skip = hudText(skipLabel)?.let(::HudSkip),
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        upNext = upNext,
        source = source,
        subtitles = hudText(subtitle),
        audio = listOfNotNull(hudText(audioLanguage), hudText(audioChannels), hudText(audioCodec))
            .joinToString(" · ").ifBlank { null },
        speed = speed,
        banner = when {
            failed -> HudBanner.Failed
            offline && downloaded -> HudBanner.OfflineCopy
            else -> null
        },
    )
}

private const val MAX_TEXT_CHARS = 80
