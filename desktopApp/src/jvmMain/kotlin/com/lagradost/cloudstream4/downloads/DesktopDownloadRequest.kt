package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink

/**
 * Where one episode's bytes come from, as a plugin resolved it.
 *
 * Not a data class, and [toString] names none of [url], [headers] or [referer]: a signed URL is a credential, and
 * so can a header be.
 *
 * @param type only [ExtractorLinkType.VIDEO] is downloaded; [DesktopDownloads.enqueue] refuses the rest.
 */
class DesktopDownloadLink(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val type: ExtractorLinkType = ExtractorLinkType.VIDEO,
) {
    override fun toString(): String = "DesktopDownloadLink(type=$type)"

    companion object {
        /**
         * The link the sandboxed plugin host returned. It carries a user agent and a referer and no other header
         * (see `RemoteSubtitle`). A [RemoteLink.type] that is not an [ExtractorLinkType] name is null, and so is
         * refused rather than guessed at.
         */
        fun of(link: RemoteLink): DesktopDownloadLink? {
            val type = ExtractorLinkType.entries.firstOrNull { it.name == link.type } ?: return null
            return DesktopDownloadLink(
                url = link.url,
                headers = link.userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }.orEmpty(),
                referer = link.referer,
                type = type,
            )
        }
    }
}

/**
 * What the extension needs to be asked for an episode's links again: the provider's name and the episode's data,
 * exactly what the desktop player passes to `SandboxedProvider.links`. This is what the downloads file keeps, in
 * place of a link.
 *
 * [episodeData] is the provider's own string. It is often a URL (an episode page, not a media link) and can be a
 * JSON blob, so [toString] leaves it out.
 */
class DownloadSourceRef(val providerName: String, val episodeData: String) {
    override fun equals(other: Any?): Boolean =
        other is DownloadSourceRef && other.providerName == providerName && other.episodeData == episodeData

    override fun hashCode(): Int = 31 * providerName.hashCode() + episodeData.hashCode()

    override fun toString(): String = "DownloadSourceRef(providerName=$providerName)"
}

/**
 * Asks the extension for an episode's links again, for a resume after a restart and for every retry. Best first;
 * [DesktopDownloads] takes the first [ExtractorLinkType.VIDEO] one. Throwing, or returning none, fails the episode
 * and keeps its parts.
 */
fun interface LinkResolver {
    suspend fun resolve(ref: DownloadSourceRef): List<DesktopDownloadLink>
}

/**
 * One episode, or a film's one file, to download.
 *
 * @param episodeId the episode's id. Stable across restarts; one record per id.
 * @param titleId the film's or series' id. Episodes with the same one are one row on the list.
 * @param titleName what the row says, and the name of the folder the file goes in.
 * @param source how to ask for the links again. Written to the downloads file.
 * @param link the link to start with, when the caller already has one. Kept in memory only, never written; without
 *   one, the first run asks [source].
 * @param posterUrl the title's poster, for the row and D4's header (`KMP-99`). Written to the downloads file, as the
 *   library keeps its titles' posters: a picture's address, not a link to the media. Only plain http(s) is kept.
 */
class DesktopDownloadRequest(
    val episodeId: Int,
    val titleId: Int,
    val titleName: String,
    val kind: DownloadTitleKind,
    val season: Int?,
    val episode: Int?,
    val episodeName: String?,
    val source: DownloadSourceRef,
    val link: DesktopDownloadLink? = null,
    val posterUrl: String? = null,
) {
    override fun toString(): String =
        "DesktopDownloadRequest(episodeId=$episodeId, titleId=$titleId, kind=$kind, season=$season, episode=$episode)"
}

/** What [DesktopDownloads.enqueue] did. */
sealed interface EnqueueResult {
    /** In line, or running already if a slot was free. */
    data object Queued : EnqueueResult

    /** This episode id is already done, running or waiting. Nothing changed. */
    data object AlreadyPresent : EnqueueResult

    data class Refused(val reason: EnqueueRefusal) : EnqueueResult
}

enum class EnqueueRefusal {
    /** HLS, DASH, a torrent or a magnet link: the engine fetches one file over ranged HTTP and nothing else. */
    UnsupportedLinkType,

    /** Not an absolute `http` or `https` URL with a host, or one carrying user information. */
    InvalidUrl,

    /** No provider name or no episode data, so the links could never be asked for again. */
    NoSource,

    /** The downloads file could not be read at start-up, so nothing is written over it. */
    StoreUnreadable,
}
