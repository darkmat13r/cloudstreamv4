package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLinkType

val LOADTYPE_INAPP = setOf(
    ExtractorLinkType.VIDEO,
    ExtractorLinkType.DASH,
    ExtractorLinkType.M3U8,
    ExtractorLinkType.TORRENT,
    ExtractorLinkType.MAGNET
)

val LOADTYPE_INAPP_DOWNLOAD = setOf(
    ExtractorLinkType.VIDEO,
    ExtractorLinkType.M3U8
)

val LOADTYPE_CHROMECAST = setOf(
    ExtractorLinkType.VIDEO,
    ExtractorLinkType.DASH,
    ExtractorLinkType.M3U8
)

val LOADTYPE_ALL = ExtractorLinkType.entries.toSet()
