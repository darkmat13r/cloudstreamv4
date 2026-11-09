package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.anime_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.asian_drama_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_book_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.cartoons_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.custom_media_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.documentaries_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.live_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.movies_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.music_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.nsfw_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.other_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.ova_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.podcast_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_4k
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_blueray
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_cam
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_cam_hd
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_cam_rip
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_dvd
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_hd
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_hdr
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_hq
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_sd
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_sdr
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_tc
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_ts
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_uhd
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_webrip
import com.lagradost.cloudstream4.core.strings.generated.resources.quality_workprint
import com.lagradost.cloudstream4.core.strings.generated.resources.torrent_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.video_singular
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * A search card's second line (`KMP-101`, board S2): a result's type and quality, as `Movie · 4K`.
 *
 * The same keys Android's search uses, through `SettingsProvidersScreen.toStringRes()` and `SettingsUIScreen.toStringRes()`
 * in `:app`. Those return `R` ids, which this module cannot see, so the two tables are restated here against the
 * Compose resources the keys are authored in. They are resolved in the locale the app runs in, when the results arrive.
 */
internal open class DesktopResultLabels {
    open suspend fun type(type: TvType): String? = getString(typeKey(type))

    open suspend fun quality(quality: SearchQuality): String? = getString(qualityKey(quality))

    internal companion object {
        fun typeKey(type: TvType): StringResource = when (type) {
            TvType.TvSeries -> Res.string.tv_series_singular
            TvType.Anime -> Res.string.anime_singular
            TvType.OVA -> Res.string.ova_singular
            TvType.AnimeMovie -> Res.string.movies_singular
            TvType.Cartoon -> Res.string.cartoons_singular
            TvType.Documentary -> Res.string.documentaries_singular
            TvType.Movie -> Res.string.movies_singular
            TvType.Torrent -> Res.string.torrent_singular
            TvType.AsianDrama -> Res.string.asian_drama_singular
            TvType.Live -> Res.string.live_singular
            TvType.Others -> Res.string.other_singular
            TvType.NSFW -> Res.string.nsfw_singular
            TvType.Music -> Res.string.music_singular
            TvType.AudioBook -> Res.string.audio_book_singular
            TvType.CustomMedia -> Res.string.custom_media_singular
            TvType.Audio -> Res.string.audio_singular
            TvType.Podcast -> Res.string.podcast_singular
            TvType.Video -> Res.string.video_singular
        }

        fun qualityKey(quality: SearchQuality): StringResource = when (quality) {
            SearchQuality.BlueRay -> Res.string.quality_blueray
            SearchQuality.Cam -> Res.string.quality_cam
            SearchQuality.CamRip -> Res.string.quality_cam_rip
            SearchQuality.DVD -> Res.string.quality_dvd
            SearchQuality.HD -> Res.string.quality_hd
            SearchQuality.HQ -> Res.string.quality_hq
            SearchQuality.HdCam -> Res.string.quality_cam_hd
            SearchQuality.Telecine -> Res.string.quality_tc
            SearchQuality.Telesync -> Res.string.quality_ts
            SearchQuality.WorkPrint -> Res.string.quality_workprint
            SearchQuality.SD -> Res.string.quality_sd
            SearchQuality.FourK -> Res.string.quality_4k
            SearchQuality.UHD -> Res.string.quality_uhd
            SearchQuality.SDR -> Res.string.quality_sdr
            SearchQuality.HDR -> Res.string.quality_hdr
            SearchQuality.WebRip -> Res.string.quality_webrip
        }
    }
}
