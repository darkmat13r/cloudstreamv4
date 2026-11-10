package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * `KMP-109`: the request a title page's Download sends for one episode.
 *
 * It carries no link. [DesktopDownloads] asks the extension for one through [DesktopDownloadRequest.source], the same
 * provider name and episode data the player passes, so the plugin's address goes through the downloads path's own
 * checks and nowhere else.
 *
 * @param titleKey the page's title id, the string the Mac opens a title by (`DesktopSearchSource.encodeId`).
 * @param titleIdOf [EpisodeDownloadIds.titleId]; a test passes one that collides on purpose.
 */
internal fun episodeDownloadRequest(
    titleKey: String,
    detail: TitleDetail,
    episode: DetailEpisode,
    titleIdOf: (String) -> Int = EpisodeDownloadIds::titleId,
): DesktopDownloadRequest =
    DesktopDownloadRequest(
        episodeId = EpisodeDownloadIds.episodeId(detail.providerName, episode.data),
        titleId = titleIdOf(titleKey),
        titleName = detail.title,
        kind = DownloadTitleKind.Series,
        season = episode.season,
        episode = episode.episode,
        episodeName = episode.name,
        source = DownloadSourceRef(detail.providerName, episode.data),
        posterUrl = detail.posterUrl,
    )

/**
 * The downloads' 32-bit ids, from what names a title and an episode. The same strings give the same id on every run
 * and every machine: the first 4 bytes of SHA-256 over the parts, each written as its UTF-8 length then its bytes, so
 * no two lists of parts share an encoding. Not `String.hashCode`, which gives `Aa` and `BB` one value.
 *
 * Two titles or two episodes can still share 32 bits, by chance or because a plugin chose its strings to.
 * [DesktopDownloads.enqueue] refuses the second with [EnqueueRefusal.IdTaken].
 */
internal object EpisodeDownloadIds {
    fun titleId(titleKey: String): Int = idOf(titleKey)

    fun episodeId(providerName: String, episodeData: String): Int = idOf(providerName, episodeData)

    fun idOf(vararg parts: String): Int {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            parts.forEach { part ->
                val utf8 = part.toByteArray(Charsets.UTF_8)
                out.writeInt(utf8.size)
                out.write(utf8)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return (digest[0].toInt() and BYTE shl 24) or
            (digest[1].toInt() and BYTE shl 16) or
            (digest[2].toInt() and BYTE shl 8) or
            (digest[3].toInt() and BYTE)
    }

    private const val BYTE = 0xFF
}
