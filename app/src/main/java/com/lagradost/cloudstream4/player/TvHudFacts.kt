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
 * reads as an address**. A provider can put whatever it likes in its strings, so one that looks like a URL is dropped
 * rather than shown: a scheme (`https://`, `content:`, `file:`), an absolute path, or a bare host (`cdn.host.to/v/…`,
 * `host.com:8443`).
 *
 * For titles and episode names. A name the HUD shows beside a stream, a source's or a subtitle's, goes through the
 * stricter [hudLabel].
 */
internal fun hudText(text: String?): String? {
    val clean = text?.filterNot { it.isISOControl() }?.trim()?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotBlank() }
        ?: return null
    return clean.takeUnless { looksLikeAddress(it, HOST_WITH_TAIL) }
}

/**
 * A source's or a subtitle's name: [hudText], and also nothing with `?`, `=`, `/` or `\` in it, which is where a query
 * string, a token or a path shows. A real name like `Server 1/2` is dropped too, and the row falls back to the quality
 * alone or to the localised fallback: a lost label is the cheap side of that trade (review of `KMP-64`).
 */
internal fun hudLabel(text: String?): String? =
    hudText(text)?.takeUnless { label -> label.any { it in STRICT_CHARACTERS } || looksLikeAddress(label, BARE_HOST) }

private fun looksLikeAddress(text: String, host: Regex): Boolean {
    val lower = text.lowercase()
    return "://" in lower || lower.startsWith("/") || lower.startsWith("content:") || lower.startsWith("file:") ||
        host.containsMatchIn(lower)
}

/**
 * A host at the start, with an optional port, then a path, query or fragment or the end: `cdn.host.to/v`, `a.com:8443`,
 * `stream.example.to`. For a source's or subtitle's name only: as a title it would blank `Dr.Stone` and `Mr.Robot`.
 */
private val BARE_HOST = Regex("""^[\w.-]+\.[a-z]{2,}(:\d+)?([/?#]|$)""")

/** The same host, but only with a port, path, query or fragment after it: a title is never just `Dr.Stone`'s shape. */
private val HOST_WITH_TAIL = Regex("""^[\w.-]+\.[a-z]{2,}(:\d+|[/?#])""")
private val STRICT_CHARACTERS = setOf('?', '=', '/', '\\')

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
        listOfNotNull(hudLabel(link?.name), quality).joinToString(" · ").ifBlank { null }
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
        subtitles = hudLabel(subtitle),
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
