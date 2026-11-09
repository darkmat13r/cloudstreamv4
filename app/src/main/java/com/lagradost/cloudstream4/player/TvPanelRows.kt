package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Artboard 7e's row words from the player's own objects (`KMP-66`), and the panels' security boundary, as
 * [toHudState] is the HUD's: a link and a subtitle go in with their URL and headers, and only a display name and a
 * quality come out, through [hudLabel]. A row never shows an address, and the old dialog's long-press that copied a
 * source's URL has no counterpart here.
 */
internal object TvPanelRows {

    /**
     * `Source One · 1080p`. A name that is missing or reads as an address gives [fallback] (`Source 2`); a quality the
     * provider did not give is left out, and the row's detail says `quality unknown` instead.
     */
    fun sourceTitle(link: ExtractorLink?, file: ExtractorUri?, fallback: String): String {
        val name = hudLabel(link?.name) ?: hudLabel(file?.displayName) ?: fallback
        return listOfNotNull(name, sourceQuality(link)).joinToString(" · ")
    }

    /** `1080p`, `4K`, or `null` when the provider did not say. A local file's quality is the decoded size instead. */
    fun sourceQuality(link: ExtractorLink?): String? =
        link?.quality?.let { Qualities.getStringByInt(it) }?.takeIf { it.isNotBlank() }

    /** A subtitle group's name, the `originalName` it is grouped by, or [fallback] when that is missing or an address. */
    fun subtitleGroupTitle(subtitle: SubtitleData, fallback: String): String = hudLabel(subtitle.originalName) ?: fallback

    /** A variant's own words, its `nameSuffix` with any markup gone, or [fallback] (its origin: Online, Embedded…). */
    fun subtitleVariantTitle(subtitle: SubtitleData, fallback: String): String =
        hudLabel(subtitle.nameSuffix.replace(MARKUP, " ").trim()) ?: fallback

    private val MARKUP = Regex("<[^>]*>")
}
