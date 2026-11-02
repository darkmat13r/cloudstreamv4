package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteLink
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.DesktopSearchSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Titles and streams, from the sandboxed provider that produced the search result.
 *
 * A link leaves here only if it is http(s) to a public address. libVLC runs in a sandboxed host, which
 * cannot reach this machine but can reach its network, so this is what keeps a plugin from aiming it at
 * the LAN. The check covers only the URL it is given. It cannot follow a redirect inside libVLC, stop a
 * name changing address afterwards, or see the segments a public HLS playlist points at (`KMP-16`).
 */
class DesktopDetailSource : DetailSource {

    private fun providerOf(id: String): Pair<SandboxedProvider, String> {
        val (apiName, url) = DesktopSearchSource.decodeId(SearchResultId(id))
            ?: throw IOException(DETAIL_BAD_ID)
        val provider = APIHolder.apis.withLock { APIHolder.apis.filterIsInstance<SandboxedProvider>() }
            .firstOrNull { it.name == apiName }
            ?: throw IOException("$DETAIL_NOT_LOADED · $apiName")
        return provider to url
    }

    override suspend fun details(id: String): TitleDetail {
        val (provider, url) = providerOf(id)
        val details = provider.details(url)
        return TitleDetail(
            title = details.name,
            providerName = provider.name,
            typeName = details.type,
            year = details.year,
            plot = details.plot?.withoutMarkup(),
            posterUrl = details.posterUrl,
            playData = details.dataUrl,
            episodes = details.episodes.map { DetailEpisode(it.name, it.data, it.season, it.episode) },
        )
    }

    override suspend fun links(id: String, data: String): List<StreamLink> {
        val (provider, _) = providerOf(id)
        val links = provider.links(data).links
        return withContext(Dispatchers.IO) {
            links.filter { link ->
                // Resolving a name cannot be interrupted, so a dismissed list stops between links.
                ensureActive()
                SandboxPolicy.isPublicHttpUrl(link.url) &&
                    link.audioUrl?.let(SandboxPolicy::isPublicAudioUrl) != false
            }.map { it.toStreamLink() }
        }
    }

    /**
     * The plot as plain text. Providers send HTML (`<br>` in a YouTube description) and nothing here
     * renders it, so tags are dropped and never interpreted.
     */
    private fun String.withoutMarkup(): String =
        replace(BREAK_TAG, " ").replace(ANY_TAG, "").replace(RUN_OF_SPACES, " ").trim()

    private fun RemoteLink.toStreamLink() = StreamLink(
        label = name,
        qualityLabel = quality.takeIf { it > 0 }?.let { "${it}p" },
        url = url,
        referer = referer,
        userAgent = userAgent,
        audioUrl = audioUrl,
    )

    private companion object {
        const val DETAIL_BAD_ID = "BAD_ID"
        const val DETAIL_NOT_LOADED = "NOT_LOADED"
        val BREAK_TAG = Regex("(?i)<br\\s*/?>")
        val ANY_TAG = Regex("<[^>]*>")
        val RUN_OF_SPACES = Regex("\\s+")
    }
}
