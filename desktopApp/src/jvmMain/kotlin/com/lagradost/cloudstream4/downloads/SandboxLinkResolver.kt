package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.detail.isPublic
import com.lagradost.cloudstream4.feature.detail.rankOf
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import java.io.IOException

/**
 * Asks the sandboxed extension for an episode's links again, the way the desktop player gets them.
 *
 * - **The provider:** the [SandboxedProvider] in `APIHolder.apis` with the stored name, as
 *   `DesktopDetailSource.providerOf` finds it. None loaded is an [IOException], so the episode fails rather than waits.
 * - **The call:** `SandboxedProvider.links(episodeData)`, the whole answer. The player's `linksAsTheyArrive` is the
 *   same call delivered in pieces; a download has nothing to show meanwhile.
 * - **The filter:** `RemoteLink.isPublic`, the player's own check: http(s) to a public address.
 * - **The order:** the player's `bestFirst`, by `rankOf(quality)`, highest first. A stable sort, so equal ranks keep
 *   the plugin's order.
 *
 * The caller takes the first VIDEO link; HLS and the rest are mapped too and skipped there.
 */
class SandboxLinkResolver : LinkResolver {
    override suspend fun resolve(ref: DownloadSourceRef): List<DesktopDownloadLink> {
        val provider = APIHolder.apis.withLock { APIHolder.apis.filterIsInstance<SandboxedProvider>() }
            .firstOrNull { it.name == ref.providerName }
            ?: throw IOException("the extension for this download is not loaded")
        return provider.links(ref.episodeData).links
            .filter { it.isPublic() }
            .sortedByDescending { rankOf(it.quality) }
            .mapNotNull { DesktopDownloadLink.of(it) }
    }
}
