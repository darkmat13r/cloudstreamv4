package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSourceChoice
import com.lagradost.cloudstream4.feature.search.SearchSourceHealth
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import com.lagradost.cloudstream4.feature.search.SearchTypeFilters
import com.lagradost.cloudstream4.detail.withoutUrls
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * `SearchSource` for the desktop app, over the same `APIRepository` the Android app uses.
 *
 * ## Why this exists separately from `:app`'s implementation
 *
 * `APIRepository` lives in `core/data/src/jvmCommonMain` (`KMP-05`), so it is reachable from
 * desktop as well as Android — that placement, chosen to keep `:core:data` out of
 * `IOS_CANARY_EXEMPT`, pays off here by accident. What is **not** reachable is everything Android
 * about `:app`'s version: `Context.getApiSettings()` decides which providers are selected, and
 * resolving a `SearchQuality` to a label needs `Context.resources`. Neither exists on desktop.
 *
 * ## Where desktop's providers come from (`KMP-13`)
 *
 * `APIHolder.apis` starts **empty** and is only filled by `APIHolder.addPluginMapping`, which only
 * `BasePlugin.registerMainAPI` calls, which only a loaded plugin calls. Until `KMP-13` the only
 * thing that loaded one was `:app`'s `PluginManager`, over a `PathClassLoader` — an Android class —
 * so this source queried nothing on desktop and the chrome row read `Sources 0 / 0`.
 *
 * It is now filled by `SandboxedExtensions` (`KMP-14`), which starts what is in
 * `~/.cloudstream4/extensions` in sandboxed child processes and registers a proxy for each provider.
 * Nothing here changed to make that work, and nothing here knows about it: this source reads
 * `APIHolder.apis`, and every entry in it answers by calling across the process boundary.
 *
 * **It is still empty for a user who has installed nothing**, and the `NO_SOURCES` outcome below is
 * the state that says so.
 *
 * ## One state the contracts do not have
 *
 * "No sources are installed" is not "no results", and `SearchOutcome` cannot say it. Both
 * implementations of this seam — this one and `:app`'s — hit it in the same week and both chose
 * [SearchOutcome.Failed] with a status-code detail, because `Complete(emptyList())` routes to the
 * no-results copy and that sentence blames the catalogue for a configuration problem. Two
 * independent workarounds for the same missing case is the signal that the case should exist. The
 * onboarding cluster draws it (`design/TV Onboarding Extensions.dc.html` 8a, "First run — no
 * sources"); the contracts should gain it when desktop grows a runtime.
 */
class DesktopSearchSource : SearchSource {

    /**
     * The panel's picks (`DS-22` S4), by provider name, or `null` for every provider: what a first launch asks.
     *
     * Kept for as long as the app runs (`KMP-56`). There is no desktop preference store yet to keep it in between
     * launches, as with Home's bookmark filter. Android keeps both in the account's data store.
     */
    @Volatile
    private var picked: Set<String>? = null

    /** The lit type chips (S1). None lit is no narrowing. */
    @Volatile
    private var types: Set<SearchTypeFilter> = emptySet()

    /** Each provider's last answer, by name, for the panel's marker: `true` if it failed. */
    private val failed = ConcurrentHashMap<String, Boolean>()

    /**
     * How many providers a search asks, out of every installed one.
     *
     * Desktop has no language preference, so every installed provider is eligible: what Android narrows by
     * `getApiSettings()` before the picks, desktop does not.
     */
    override suspend fun sources(): SearchSources =
        SearchSources(selected = selected().size, total = installed().size)

    /** Every provider the chips leave, by name, each saying whether a search asks it. */
    override suspend fun choices(): List<SearchSourceChoice> {
        val asked = selected().mapTo(HashSet()) { it.name }
        return underTypes(installed(), types).sortedBy { it.name.lowercase() }.map { api ->
            SearchSourceChoice(
                id = api.name,
                name = api.name.cleanText(MAX_NAME_CHARS),
                selected = api.name in asked,
                health = when (failed[api.name]) {
                    null -> SearchSourceHealth.Unknown
                    true -> SearchSourceHealth.Failed
                    false -> SearchSourceHealth.Ok
                },
            )
        }
    }

    /** A row the chips hid keeps its state, as on Android: hiding one must not turn it off. */
    override suspend fun selectSources(ids: Set<String>) {
        val offered = choices().mapTo(HashSet()) { it.id }
        val before = picked ?: installed().mapTo(HashSet()) { it.name }
        picked = before.filterNotTo(HashSet()) { it in offered } + ids
    }

    override suspend fun typeFilters(): SearchTypeFilters {
        val served = installed().flatMapTo(HashSet()) { it.supportedTypes }
        return SearchTypeFilters(
            available = SearchTypeFilter.entries.filter { filter -> filter.tvTypes().any { it in served } },
            selected = types,
        )
    }

    override suspend fun selectTypes(types: Set<SearchTypeFilter>) {
        this.types = types
    }

    /** 8c's *Search everything*: every provider, no chip. */
    override suspend fun clearFilters() {
        picked = null
        types = emptySet()
    }

    override suspend fun search(query: String): SearchOutcome =
        fanOut(query) { repo -> repo.search(query, FIRST_PAGE) }

    /**
     * What typing runs, and it asks every source, because the field says it searches all of them.
     *
     * A provider without quick search gets a full one. So does one that declares quick search and then
     * fails it: `MainAPI.quickSearch` throws `NotImplementedError` by default, so a declaration is not
     * proof. Asking only the providers that declare it told a user typing "Search failed", or with none
     * of them installed, `NO_SOURCES`, while Return found results (`BUG-12`).
     */
    override suspend fun quickSearch(query: String): SearchOutcome =
        fanOut(query) { repo ->
            val quick = if (repo.hasQuickSearch) repo.quickSearch(query) else null
            quick as? Resource.Success ?: repo.search(query, FIRST_PAGE)
        }

    private fun installed(): List<MainAPI> = APIHolder.apis.withLock { APIHolder.apis.toList() }

    /**
     * The picks under the lit chips. A pick that no longer matches anything falls back to every provider, and chips
     * nobody serves narrow nothing: the rules Android's `selectSearchProviders` keeps.
     */
    private fun selected(): List<MainAPI> {
        val all = installed()
        val picks = picked
        val chosen = if (picks == null) all else all.filter { it.name in picks }.ifEmpty { all }
        return underTypes(chosen, types)
    }

    private fun repositories(): List<APIRepository> = selected().map { APIRepository(it) }

    /**
     * Asks every selected provider at once and folds the replies into one [SearchOutcome].
     *
     * The fold is the whole point of the seam: a search over a dozen providers **partially
     * failing is the normal case**, so the result has to be able to say "9 of 12 answered" rather
     * than succeed-or-throw. `:app`'s old `SearchViewModel` dropped failures on the floor — the
     * `// No user indication it failed :(` branch that `.claude/rules/mvi-viewmodel.md` cites.
     */
    private suspend fun fanOut(
        query: String,
        request: suspend (APIRepository) -> Resource<com.lagradost.cloudstream3.SearchResponseList>,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val repos = repositories()
        // Not Complete(emptyList()): that routes to `SearchStatus.NoResults`, whose drawn 8c copy
        // says "All nine selected sources answered and none of them carry it" — with nothing
        // queried that sentence blames the catalogue for a configuration problem. `:app`'s
        // implementation reached the same conclusion independently.
        if (installed().isEmpty()) {
            return@withContext SearchOutcome.Failed("NO_SOURCES · 0 INSTALLED")
        }

        val replies = coroutineScope {
            repos.map { repo -> async { repo to request(repo) } }.awaitAll()
        }

        replies.forEach { (repo, reply) -> failed[repo.name] = reply is Resource.Failure }
        val answered = replies.filter { it.second is Resource.Success }
        val failures = replies.mapNotNull { it.second as? Resource.Failure }
        val items = answered.flatMap { (repo, reply) ->
            (reply as Resource.Success).value.items.map { it.toItem(repo.name) }
        }

        when {
            failures.isEmpty() -> SearchOutcome.Complete(items)
            answered.isNotEmpty() -> SearchOutcome.Partial(
                items = items,
                answered = answered.size,
                total = repos.size,
                detail = failures.detail(),
            )
            failures.all { it.isNetworkError } -> SearchOutcome.Offline
            else -> SearchOutcome.Failed(failures.detail())
        }
    }

    /**
     * The machine-readable line a user can paste into a bug report.
     *
     * Distinct error strings rather than a count, because three providers failing for the same
     * reason and three failing for three reasons are different problems, and the screen has one
     * mono line to say which.
     */
    private fun List<Resource.Failure>.detail(): String =
        // The first line: the rest of errorString is a stack trace, which is not for this screen.
        // A plugin's words reach the screen and a pasted bug report: no address, no home directory (`KMP-56`, as `#200`).
        map { it.errorString.trim().lineSequence().first().withoutUrls().withoutHome().cleanText(MAX_REASON_CHARS).ifEmpty { UNKNOWN_ERROR } }
            .distinct()
            .take(MAX_DETAIL_REASONS)
            .joinToString(DETAIL_SEPARATOR)

    private fun SearchResponse.toItem(providerName: String) = SearchResultItem(
        id = SearchResultId(encodeId(apiName, url)),
        title = name,
        posterUrl = posterUrl,
        providerName = providerName,
        // Resolving TvType and SearchQuality to labels needs string resources, and on desktop
        // those are Compose resources with no synchronous accessor. Left null rather than
        // hardcoding English, which `.claude/rules` forbid. See DesktopSearchCopy's KDoc.
        typeLabel = null,
        qualityLabel = null,
        dubLabel = null,
        watchProgress = null,
    )

    private fun String.withoutHome(): String =
        System.getProperty("user.home")?.takeIf { it.length > 1 }?.let { replace(it, "~") } ?: this

    /** Third-party text on its way to the screen: no control or format characters, and capped. */
    private fun String.cleanText(max: Int): String =
        filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(max)

    companion object {
        private const val FIRST_PAGE = 1
        private const val MAX_NAME_CHARS = 60
        private const val MAX_REASON_CHARS = 200
        private const val UNKNOWN_ERROR = "UNKNOWN"
        private const val MAX_DETAIL_REASONS = 3
        private const val DETAIL_SEPARATOR = " · "
        private const val ID_SEPARATOR = "\u0000"

        /**
         * [SearchResultId] is opaque by contract, and this is the producer that gives it meaning.
         *
         * A result is reopened with a provider name **and** a url, both `:library` shapes. The
         * encoder and decoder live together so they cannot drift — the separator is `NUL`, which
         * cannot occur in either half of a real value.
         */
        fun encodeId(apiName: String, url: String): String = apiName + ID_SEPARATOR + url

        /** The inverse of [encodeId]; `null` when the id did not come from this source. */
        fun decodeId(id: SearchResultId): Pair<String, String>? {
            val parts = id.value.split(ID_SEPARATOR)
            return if (parts.size == 2) parts[0] to parts[1] else null
        }
    }
}

/** [providers] serving one of [types]' media, or all of them when that is nobody or [types] is empty. */
private fun underTypes(providers: List<MainAPI>, types: Set<SearchTypeFilter>): List<MainAPI> {
    if (types.isEmpty()) return providers
    val media = types.flatMapTo(HashSet()) { it.tvTypes() }
    return providers.filter { api -> api.supportedTypes.any { it in media } }.ifEmpty { providers }
}

/**
 * Which `TvType`s a chip stands for. A copy of `:app`'s `SearchTypeFilter.tvTypes()` in `ApiRepositorySearchSource.kt`,
 * which desktop cannot reach: keep the two the same.
 */
internal fun SearchTypeFilter.tvTypes(): List<TvType> = when (this) {
    SearchTypeFilter.Movies -> listOf(TvType.Movie)
    SearchTypeFilter.TvSeries -> listOf(TvType.TvSeries)
    SearchTypeFilter.Anime -> listOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)
    SearchTypeFilter.AsianDrama -> listOf(TvType.AsianDrama)
    SearchTypeFilter.Cartoons -> listOf(TvType.Cartoon)
    SearchTypeFilter.Documentaries -> listOf(TvType.Documentary)
    SearchTypeFilter.Live -> listOf(TvType.Live)
    SearchTypeFilter.Torrents -> listOf(TvType.Torrent)
    SearchTypeFilter.Nsfw -> listOf(TvType.NSFW)
    SearchTypeFilter.Others -> listOf(TvType.Others)
}
