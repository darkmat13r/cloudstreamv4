package com.lagradost.cloudstream4.home

import android.content.Context
import android.content.res.Configuration
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.settings.SettingsUIScreen
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream4.feature.home.HomeFeatured
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeRowPage
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.decodeId
import com.lagradost.cloudstream4.search.encodeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * [HomeSource] over the app's real providers, the Android half of what `DesktopHomeSource` is.
 *
 * ## Nothing here has been checked for it
 *
 * On desktop a plugin answers across a sandbox boundary that has already bounded its text and its poster addresses. On
 * Android it answers from inside this process, so every limit is applied here. It is not a defence against the plugin,
 * which already runs here with the app's rights. It keeps a wrong or hostile answer from breaking the screen:
 *
 * - A format character such as U+202E lets a title reorder the text around it, and a title of nothing is a card that
 *   cannot be told from a loading one.
 * - Coil loads `file:`, `content:` and `android.resource:` as readily as `https:`, so only http(s) reaches it.
 * - A header with a line break in it is a request the image client refuses, or worse, one it sends.
 * - A title's address goes through a `Bundle` to reach the result page, and one big enough fails that transaction.
 * - Rows and items are bounded, because a section can answer with anything.
 * - A synopsis may be HTML, as the old hero renders it. Here it is plain text: tags dropped, the common entities decoded,
 *   and runs of space and line breaks made one space, before the same cleaning as any other text.
 *
 * @param providers the sources a person may browse, by the setting the old screen's picker reads.
 * @param installed how many there are of any kind, so "none has a main page" can differ from "nothing is installed".
 * @param stillLoading whether a source missing now may yet arrive. See [pluginsStillLoading].
 * @param keepOnRow applies the user's "hide these qualities" setting to a row.
 * @param labelsFor what a page's items say on their badge.
 */
internal class AndroidHomeSource(
    private val providers: () -> List<MainAPI>,
    private val installed: () -> Int,
    private val stillLoading: () -> Boolean,
    private val keepOnRow: (HomePageList) -> HomePageList = { it },
    private val labelsFor: () -> (SearchResponse) -> String? = { { null } },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HomeSource {

    override suspend fun sources(): HomeSources = withContext(io) {
        HomeSources(
            options = providers().distinctBy { it.name }.map { api ->
                HomeSourceOption(
                    id = HomeSourceId(api.name),
                    name = api.name.safeText(),
                    language = api.lang.safeText(),
                    // A section is one request, and asking for them is what the page does first.
                    sections = api.mainPage.size.coerceAtMost(MAX_SECTIONS),
                    sequentialDelayMillis = api.sequentialMainPageDelay.takeIf { api.sequentialMainPage },
                )
            },
            installed = installed(),
            stillLoading = stillLoading(),
        )
    }

    override suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply = withContext(io) {
        val api = providers().firstOrNull { it.name == source.value }
            ?: return@withContext HomePageReply.Failed("$NOT_LOADED · ${source.value.safeText()}")
        // `getMainPage` reads an index it cannot find as "every section", which is not what was asked.
        if (section !in api.mainPage.indices) return@withContext HomePageReply.Failed("$BAD_SECTION · $section")
        when (val reply = APIRepository(api).getMainPage(page, section)) {
            is Resource.Success -> {
                val pages = reply.value.filterNotNull()
                val labels = labelsFor()
                HomePageReply.Loaded(
                    rows = pages.flatMap { it.items }
                        .take(MAX_ROWS_PER_PAGE)
                        .map { keepOnRow(it).toRow(api.name.safeText(), labels) },
                    hasNext = pages.any { it.hasNext },
                )
            }
            is Resource.Failure -> reply.toReply()
            is Resource.Loading -> HomePageReply.Failed(UNKNOWN_ERROR)
        }
    }

    /**
     * The title's own page, as the old hero asks for it (`HomeViewModel.kt:314`): `APIRepository.load`, so the same cache
     * and the same failure handling. Any failure is no hero.
     */
    override suspend fun featured(item: HomeItem): HomeFeatured? = withContext(io) {
        val target = decodeId(SearchResultId(item.id)) ?: return@withContext null
        val api = providers().firstOrNull { it.name == target.apiName } ?: return@withContext null
        (APIRepository(api).load(target.url) as? Resource.Success)?.value?.toFeatured(item.id)
    }

    private fun LoadResponse.toFeatured(id: String): HomeFeatured? {
        val title = name.safeText().ifEmpty { return null }
        return HomeFeatured(
            id = id,
            title = title,
            backdropUrl = backgroundPosterUrl?.safePosterUrl() ?: posterUrl?.safePosterUrl(),
            backdropHeaders = posterHeaders?.safeHeaders(),
            year = year?.takeIf { it in YEARS },
            durationMinutes = duration?.takeIf { it in 1..MAX_MINUTES },
            genres = tags.orEmpty().asSequence()
                .map { it.safeText().take(MAX_BADGE_CHARS * 2) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_GENRES)
                .toList(),
            // As the old hero writes it: one decimal out of ten, and nothing under 0.1, which is "no score".
            score = runCatching { score?.toStringNull(0.1, 10, 1, false) }.getOrNull()?.safeText()
                ?.take(MAX_BADGE_CHARS)?.ifEmpty { null },
            contentRating = contentRating?.safeText()?.take(MAX_BADGE_CHARS)?.ifEmpty { null },
            synopsis = plot?.plainText()?.safeText(MAX_SYNOPSIS_CHARS)?.ifEmpty { null },
        )
    }

    private fun String.plainText(): String = take(MAX_SYNOPSIS_CHARS * 4)
        .replace(TAG, " ")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace(SPACE, " ")

    private fun Resource.Failure.toReply(): HomePageReply {
        // The first line: the rest of errorString is a stack trace, which is not for this screen.
        val reason = errorString.trim().lineSequence().first().safeText().ifEmpty { UNKNOWN_ERROR }
        return if (isNetworkError) HomePageReply.Offline else HomePageReply.Failed(reason)
    }

    private fun HomePageList.toRow(sourceName: String, labels: (SearchResponse) -> String?) = HomeRowPage(
        // A section may leave its row unnamed, and a header of nothing looks like a layout bug.
        title = name.safeText().ifEmpty { sourceName },
        landscape = isHorizontalImages,
        items = list.asSequence().take(MAX_ITEMS_PER_PAGE).mapNotNull { it.toItem(labels) }.toList(),
    )

    private fun SearchResponse.toItem(labels: (SearchResponse) -> String?): HomeItem? {
        val title = name.safeText().ifEmpty { return null }
        // Blank goes nowhere, and over-long would fail the transaction that opens the title.
        if (url.isBlank() || url.length > MAX_URL_CHARS || apiName.length > MAX_TEXT_CHARS) return null
        return HomeItem(
            // What `SearchTvFragment` decodes, so opening a title is the same flow as from search.
            id = encodeId(apiName = apiName, url = url).value,
            title = title,
            posterUrl = posterUrl?.safePosterUrl(),
            posterHeaders = posterHeaders?.safeHeaders(),
            badge = labels(this)?.safeText()?.take(MAX_BADGE_CHARS)?.ifEmpty { null },
        )
    }

    private fun String.safePosterUrl(): String? {
        val url = trim()
        val isWeb = HTTP_PREFIXES.any { url.startsWith(it, ignoreCase = true) && url.length > it.length }
        return url.takeIf { isWeb && it.length <= MAX_URL_CHARS && it.none { char -> char.isISOControl() || char == ' ' } }
    }

    private fun Map<String, String>.safeHeaders(): Map<String, String>? =
        entries.asSequence()
            .filter { (name, value) ->
                HEADER_NAME.matches(name) && value.length <= MAX_HEADER_CHARS && value.none(Char::isISOControl)
            }
            .take(MAX_HEADERS)
            .associate { (name, value) -> name to value }
            .ifEmpty { null }

    private fun String.safeText(limit: Int = MAX_TEXT_CHARS): String =
        filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(limit)

    private companion object {
        const val NOT_LOADED = "NOT_LOADED"
        const val BAD_SECTION = "BAD_SECTION"
        const val UNKNOWN_ERROR = "UNKNOWN"
        const val MAX_TEXT_CHARS = 200
        const val MAX_BADGE_CHARS = 16
        const val MAX_URL_CHARS = 4096
        const val MAX_HEADERS = 16
        const val MAX_HEADER_CHARS = 1024
        const val MAX_SECTIONS = 64
        const val MAX_ROWS_PER_PAGE = 64
        const val MAX_ITEMS_PER_PAGE = 500
        const val MAX_GENRES = 3
        const val MAX_MINUTES = 6000
        const val MAX_SYNOPSIS_CHARS = 1000
        val YEARS = 1870..2200
        val TAG = Regex("<[^>]{0,256}>")
        val SPACE = Regex("\\s+")
        val HTTP_PREFIXES = listOf("http://", "https://")
        val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
    }
}

/**
 * The real sources: what the old screen's picker offers (`HomeFragment.kt:384`), so a language that is off and an NSFW
 * source that was never asked for stay out of it.
 */
internal fun androidHomeSource(context: Context, startedAt: Long): AndroidHomeSource = AndroidHomeSource(
    providers = { context.filterProviderByPreferredMedia() },
    installed = { APIHolder.apis.size },
    stillLoading = { pluginsStillLoading(startedAt, System.currentTimeMillis()) },
    keepOnRow = { context.filterHomePageListByFilmQuality(it) },
    labelsFor = { posterLabels(context) },
)

/**
 * Whether a source that is missing may still turn up.
 *
 * Plugins load behind a page that is already drawn, in stages. The installed ones are all up well before
 * `loadedOnlinePlugins` is true, because that waits for the repositories to answer too, and it is never true at all with
 * auto-update off. So this is only ever the answer to "wait, or say it is gone", and it gives up after [PLUGIN_WAIT_MS].
 */
internal fun pluginsStillLoading(startedAt: Long, now: Long): Boolean {
    if (PluginManager.isSafeMode()) return false
    val nothingInstalled = PluginManager.getPluginsOnline().isEmpty() && PluginManager.getPluginsLocal().isEmpty()
    if (nothingInstalled) return false
    val settled = PluginManager.loadedLocalPlugins && PluginManager.loadedOnlinePlugins
    return !settled && now - startedAt < PLUGIN_WAIT_MS
}

/** Long enough for a TV to load what is installed. Past this a source that has not come is treated as gone. */
internal const val PLUGIN_WAIT_MS = 15_000L

/**
 * One badge, so one fact: the quality when there is one, or sub and dub, in the in-app language. Search reads the same
 * two facts, though it shows them differently. An application context does not follow the in-app language by itself, so
 * this is rebuilt from the default locale per page, as `ApiRepositorySearchSource.labelContext` is.
 */
private fun posterLabels(context: Context): (SearchResponse) -> String? {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.getDefault())
    val labels = context.createConfigurationContext(configuration)
    return { response ->
        response.quality?.let { labels.getString(with(SettingsUIScreen) { it.toStringRes() }) }
            ?: (response as? AnimeSearchResponse)?.dubStatus?.let { status ->
                when {
                    DubStatus.Dubbed in status -> labels.getString(R.string.app_dubbed_text)
                    DubStatus.Subbed in status -> labels.getString(R.string.app_subbed_text)
                    else -> null
                }
            }
    }
}
