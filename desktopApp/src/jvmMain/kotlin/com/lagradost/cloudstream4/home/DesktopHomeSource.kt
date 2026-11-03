package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeRowPage
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.search.DesktopSearchSource

/**
 * `HomeSource` for the desktop app, over the same `APIRepository` the Android app uses.
 *
 * Every entry behind [providers] answers across the sandbox boundary, where lengths, counts, control
 * characters and poster addresses have already been checked. What is added here is what only a screen
 * cares about: an invisible format character is what lets a title reorder the text around it (U+202E), and
 * a title of nothing is a card that cannot be told from a loading one.
 *
 * @param providers what is installed. Replaceable, so a test need not register anything in `APIHolder`.
 */
class DesktopHomeSource(
    private val providers: () -> List<MainAPI> = {
        APIHolder.apis.withLock { APIHolder.apis.filterIsInstance<SandboxedProvider>() }
    },
) : HomeSource {

    override suspend fun sources(): HomeSources {
        val installed = providers()
        return HomeSources(
            options = installed.filter { it.hasMainPage }.distinctBy { it.name }.map { api ->
                HomeSourceOption(
                    id = HomeSourceId(api.name),
                    name = api.name.safeText(),
                    language = api.lang.safeText(),
                    sections = api.mainPage.size,
                    sequentialDelayMillis = api.sequentialMainPageDelay.takeIf { api.sequentialMainPage },
                )
            },
            installed = installed.size,
        )
    }

    override suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply {
        val api = providers().firstOrNull { it.name == source.value }
            ?: return HomePageReply.Failed("$NOT_LOADED · ${source.value.safeText()}")
        // `getMainPage` reads an index it cannot find as "every section", which is not what was asked.
        if (section !in api.mainPage.indices) return HomePageReply.Failed("$BAD_SECTION · $section")
        return when (val reply = APIRepository(api).getMainPage(page, section)) {
            is Resource.Success -> {
                val pages = reply.value.filterNotNull()
                HomePageReply.Loaded(
                    rows = pages.flatMap { it.items }.map { it.toRow(api.name.safeText()) },
                    hasNext = pages.any { it.hasNext },
                )
            }
            is Resource.Failure -> reply.toReply()
            is Resource.Loading -> HomePageReply.Failed(UNKNOWN_ERROR)
        }
    }

    private fun Resource.Failure.toReply(): HomePageReply {
        // The first line: the rest of errorString is a stack trace, which is not for this screen.
        val reason = errorString.trim().lineSequence().first().safeText().ifEmpty { UNKNOWN_ERROR }
        // A host reports its failure as text, so a plugin's lost connection never arrives as a network
        // error. A plugin can lie with this, and all it gains is showing the offline state.
        return if (isNetworkError || NETWORK_FAILURES.any { it in reason }) {
            HomePageReply.Offline
        } else {
            HomePageReply.Failed(reason)
        }
    }

    private fun HomePageList.toRow(sourceName: String) = HomeRowPage(
        // A section may leave its row unnamed, and a header of nothing looks like a layout bug.
        title = name.safeText().ifEmpty { sourceName },
        landscape = isHorizontalImages,
        items = list.mapNotNull { it.toItem() },
    )

    private fun SearchResponse.toItem(): HomeItem? {
        val title = name.safeText().ifEmpty { return null }
        return HomeItem(
            // What DesktopDetailSource resolves, so opening a title is the same flow as from search.
            id = DesktopSearchSource.encodeId(apiName, url),
            title = title,
            // Already checked at the boundary for a sandboxed provider. This is what keeps a `file:` away
            // from the image loader if anything else ever lands in `APIHolder`.
            posterUrl = posterUrl?.takeIf { url -> HTTP_PREFIXES.any { url.startsWith(it, ignoreCase = true) } },
        )
    }

    private fun String.safeText(): String =
        filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(MAX_TEXT_CHARS)

    private companion object {
        const val NOT_LOADED = "NOT_LOADED"
        const val BAD_SECTION = "BAD_SECTION"
        const val UNKNOWN_ERROR = "UNKNOWN"
        const val MAX_TEXT_CHARS = 200
        val HTTP_PREFIXES = listOf("http://", "https://")
        val NETWORK_FAILURES = listOf(
            "UnknownHostException",
            "ConnectException",
            "NoRouteToHostException",
            "SocketTimeoutException",
        )
    }
}
