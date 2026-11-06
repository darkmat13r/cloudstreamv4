package com.lagradost.cloudstream4.search

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen
import com.lagradost.cloudstream3.ui.settings.SettingsUIScreen
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSourceChoice
import com.lagradost.cloudstream4.feature.search.SearchSourceHealth
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import com.lagradost.cloudstream4.feature.search.SearchTypeFilters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * [SearchSource] over the app's real providers: step 3 of Phase 7's recipe for the search vertical.
 *
 * `:feature:search` restates the half of `SearchResponse` a card renders so that it needs no
 * `:library` edge and therefore no iOS-canary exemption (see `SearchResultItem`'s KDoc). The cost of
 * that is one mapping step, and this is it — the only place in the tree where `SearchResponse`,
 * `TvType`, `SearchQuality` and `R` meet `SearchResultItem`.
 *
 * On the TV layout this is the live path (`SearchTvFragment`). `ui/search/SearchFragment` and
 * `ui/search/SearchViewModel` still own search everywhere else until step 5 deletes them.
 *
 * ## Why `internal`, and why that is still not free
 *
 * `app/api/classes-jar.api` dumps every entry under `com/lagradost/` (see
 * `cloudstream.extension-api-jar.gradle.kts`'s `extensionApiDumpPackages`), and the dump reads raw
 * `ACC_PUBLIC` rather than Kotlin metadata — a Kotlin `internal class` is still a public JVM class.
 * So `internal` is the right declaration (nothing outside `:app` constructs this) but it does not
 * keep the class out of the dump: `:app:dumpExtensionApi` has to be re-run and the diff reviewed.
 * The three `override`s cannot be `internal` at all — Kotlin forbids narrowing an override — so they
 * are public by force of the interface, not by choice.
 *
 * ## Why a `Context` in the constructor
 *
 * [SearchResultItem.typeLabel] and [SearchResultItem.qualityLabel] are *already-resolved* display
 * strings, so resolving them needs a `Resources`. The alternative — passing a `(Int) -> String`
 * resolver in — only moves the same `Context` one frame up and loses `R` type-safety on the way.
 * This must be the **application** context: a `SearchSource` outlives the Fragment that made it for
 * as long as the ViewModel does, and holding an Activity there is a leak with a retained ViewModel.
 * It is also the wrong language on its own, see [labelContext].
 *
 * ## Why the repositories are rebuilt per call
 *
 * `SearchViewModel.kt:52` caches `repos` and needs `reloadRepos()` (`:82`) called by hand after a
 * plugin reload — a staleness bug waiting on a caller. Reading `apis` under its own lock on every
 * call removes that second entry point, and it is affordable because an `APIRepository` is a thin
 * wrapper: its load cache is a companion property, and since PR #68 the plugin-reload listener is
 * subscribed once in `CloudStreamApp` rather than per instance (`APIRepository.onPluginsLoaded`), so
 * instances are cheap and no longer leak an observer each.
 */
internal class ApiRepositorySearchSource(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SearchSource {

    /**
     * `selected` is what a full search will actually query, counted by the same function the search
     * uses, so the chrome row cannot disagree with the search — the defect `SearchSources`' KDoc
     * cites, where 8c's no-results copy and the mono status line quote the same number.
     */
    override suspend fun sources(): SearchSources = withContext(io) {
        val selected = selectedProviders(context.getApiSettings())
        SearchSources(
            selected = selected.size,
            total = apis.size,
            quickSearchable = selected.any { it.hasQuickSearch },
        )
    }

    /**
     * The panel's rows: what is eligible under the lit type chips, as the old screen's `updateList`, so a
     * row's light and the chrome row's count are the same fact. Pinned first, as the rails are.
     */
    override suspend fun choices(): List<SearchSourceChoice> = withContext(io) {
        val selected = selectedProviders(context.getApiSettings()).mapTo(HashSet()) { it.name }
        val rows = providersUnderTypes(eligibleProviders(context.getApiSettings()), DataStoreHelper.searchPreferenceTags)
            .sortedBy { it.name.lowercase() }
        val labels = labelContext()
        val pinned = DataStoreHelper.pinnedProviders
        pinnedFirst(rows, pinned) { it.name }.map { api ->
            val health = providerHealth[api.name]
            SearchSourceChoice(
                id = api.name,
                name = cleanProviderText(api.name),
                detail = api.choiceDetail(labels, isPinned = api.name in pinned, failedAt = health?.failedAt),
                selected = api.name in selected,
                health = when {
                    health == null -> SearchSourceHealth.Unknown
                    health.failedAt != null -> SearchSourceHealth.Failed
                    else -> SearchSourceHealth.Ok
                },
            )
        }
    }

    /**
     * Keeps what the panel did not offer: a row the type chips hid stays as it was. Stored the old
     * screen's way, per account. An empty list reads back as "every provider with a home page", which
     * [selectSearchProviders] then treats as the fallback.
     */
    override suspend fun selectSources(ids: Set<String>) = withContext(io) {
        val offered = choices().mapTo(HashSet()) { it.id }
        val kept = DataStoreHelper.searchPreferenceProviders.filterNot { it in offered }
        DataStoreHelper.searchPreferenceProviders = kept + ids
    }

    override suspend fun typeFilters(): SearchTypeFilters = withContext(io) {
        val served = eligibleProviders(context.getApiSettings()).flatMapTo(HashSet()) { it.supportedTypes }
        val lit = DataStoreHelper.searchPreferenceTags
        SearchTypeFilters(
            available = SearchTypeFilter.entries.filter { filter -> filter.tvTypes().any { it in served } },
            selected = SearchTypeFilter.entries.filterTo(HashSet()) { filter -> filter.tvTypes().any { it in lit } },
        )
    }

    override suspend fun selectTypes(types: Set<SearchTypeFilter>) = withContext(io) {
        DataStoreHelper.searchPreferenceTags = types.flatMap { it.tvTypes() }
    }

    /**
     * Every eligible name rather than an empty list, because the empty list reads back as those with a
     * home page only, and a provider that can only search is what a user asking for everything wants too.
     */
    override suspend fun clearFilters() = withContext(io) {
        DataStoreHelper.searchPreferenceProviders = eligibleProviders(context.getApiSettings()).map { it.name }
        DataStoreHelper.searchPreferenceTags = emptyList()
    }

    /** `EN · MOVIE, SERIES · PINNED · FAILED 2 MINUTES AGO`. Provider text is third party, so it is cleaned. */
    @Suppress("DEPRECATION")
    private fun MainAPI.choiceDetail(labels: Context, isPinned: Boolean, failedAt: Long?): String {
        val types = supportedTypes.sorted().take(MAX_DETAIL_TYPES).joinToString(", ") { type ->
            labels.getString(with(SettingsProvidersScreen) { type.toStringRes() })
        }
        val parts = listOfNotNull(
            cleanProviderText(lang).ifBlank { null },
            types.ifBlank { null },
            if (isPinned) labels.getString(R.string.search_tv_source_pinned) else null,
            failedAt?.let { labels.getString(R.string.search_tv_source_failed, relativeTime(it)) },
        )
        return parts.joinToString(" · ").uppercase(Locale.getDefault())
    }

    override suspend fun search(query: String): SearchOutcome = fanOut(query, quick = false)

    override suspend fun quickSearch(query: String): SearchOutcome = fanOut(query, quick = true)

    /**
     * One rail's next page. Among what a search would ask right now, so a provider since turned off is not
     * asked again. Quick search has no pages, so only a full search's results name a provider with more.
     */
    override suspend fun searchMore(query: String, providerName: String, page: Int): SearchOutcome = withContext(io) {
        val api = selectedProviders(context.getApiSettings()).firstOrNull { it.name == providerName }
            ?: return@withContext SearchOutcome.Complete(emptyList())
        val repo = APIRepository(api)
        fold(listOf(repo to repo.search(query, page)))
    }

    /**
     * What the user's settings say to query, read per call. See [selectSearchProviders].
     *
     * `searchPreferenceProviders` already falls back to "every provider of a preferred type" when
     * nothing was ever picked, and it is per account, which is why none of this is cached.
     */
    private fun selectedProviders(enabled: Set<String>): List<MainAPI> = selectSearchProviders(
        installed = apis.withLock { apis.mapNotNull { it } },
        languageEnabled = enabled,
        preferredTypes = preferredTypes(),
        picked = DataStoreHelper.searchPreferenceProviders.toSet(),
        types = DataStoreHelper.searchPreferenceTags,
    )

    private fun eligibleProviders(enabled: Set<String>): List<MainAPI> = eligibleSearchProviders(
        installed = apis.withLock { apis.mapNotNull { it } },
        languageEnabled = enabled,
        preferredTypes = preferredTypes(),
    )

    private fun preferredTypes(): Set<Int> {
        val defaultTypes = TvType.entries.filter { it != TvType.NSFW }.map { it.ordinal.toString() }.toSet()
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(context.getString(R.string.prefer_media_type_key), defaultTypes)
            ?.ifEmpty { defaultTypes }
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    /** "2 minutes ago", the way history rows say it. */
    private fun relativeTime(at: Long): String =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /**
     * One concurrent round over the selected providers, folded into a [SearchOutcome].
     *
     * `amap` rather than a sequential loop for the same reason `SearchViewModel.kt:252` uses it: a
     * dozen providers answer in the time of the slowest, not the sum. Each reply is already
     * individually timed out and wrapped by `APIRepository.safeApiCall`, so nothing here throws.
     *
     * Pinned providers go first because `groupByProvider` keeps first-appearance order and `amap`
     * keeps input order, so this one sort is what decides the order of the rails.
     */
    private suspend fun fanOut(query: String, quick: Boolean): SearchOutcome = withContext(io) {
        val selected = selectedProviders(context.getApiSettings())
        val targets = pinnedFirst(selected, DataStoreHelper.pinnedProviders) { it.name }
            .filter { !quick || it.hasQuickSearch }
            .map { APIRepository(it) }

        if (targets.isEmpty()) return@withContext noTargets(quick, selected.mapTo(HashSet()) { it.name })

        val replies: List<Pair<APIRepository, Resource<SearchResponseList>>> = targets.amap { repo ->
            repo to if (quick) repo.quickSearch(query) else repo.search(query, FIRST_PAGE)
        }
        fold(replies)
    }

    /**
     * Nothing to ask. Not [SearchOutcome.Offline] — the radio is irrelevant — and not an empty
     * [SearchOutcome.Complete], because the ViewModel renders that through
     * `SearchStatus.NoResults(queried)`, whose drawn copy reads *"All nine selected sources
     * answered and none of them carry it"*. With nothing queried that sentence blames the catalogue
     * for a configuration problem.
     *
     * [SearchOutcome.Failed] is honest and it is also useful: the failure state's `StateAction` is
     * `SearchAction.Retry`, which the ViewModel routes to `submitCurrentQuery()` — a **full** search.
     * So the no-quick-search case escapes itself on one press, rather than being the dead end
     * a failure state must never be. It is only reachable in the instant before [sources] answers:
     * after that `SearchSources.quickSearchable` stops the ViewModel asking.
     */
    private fun noTargets(quick: Boolean, selected: Set<String>): SearchOutcome {
        return SearchOutcome.Failed(
            // A status code, not a sentence. `SearchOutcome.Failed.detail` is drawn in the mono
            // ramp beside "TIMEOUT 10S" (artboard 4b) and is the line a user pastes into a bug
            // report, so it is deliberately not translated — the same reason an HTTP status is
            // not. Prose that needed translating would belong in the prompt above it.
            if (quick) "NO_QUICK_SEARCH · 0/${selected.size} SELECTED"
            else "NO_SOURCES_SELECTED · 0/${apis.size} INSTALLED",
        )
    }

    /**
     * The four-way split the seam exists for.
     *
     * `Resource.Loading` is counted as a non-answer that is not a network error. `APIRepository`'s
     * `safeApiCall` never returns it, so this is a total-function obligation rather than a reachable
     * state — but folding it into "answered" would silently report an empty provider as a success.
     */
    private fun fold(replies: List<Pair<APIRepository, Resource<SearchResponseList>>>): SearchOutcome {
        val now = System.currentTimeMillis()
        replies.forEach { (repo, reply) ->
            providerHealth[repo.name] = ProviderHealth(failedAt = now.takeIf { reply !is Resource.Success })
        }
        val labels = labelContext()
        val items = replies
            .flatMap { (_, reply) -> if (reply is Resource.Success) reply.value.items else emptyList() }
            // The user's "hide these qualities" setting, the same call the old screen makes per rail.
            .let { context.filterSearchResultByFilmQuality(it) }
            .map { it.toItem(labels) }
        val morePages = replies.mapNotNullTo(HashSet()) { (repo, reply) ->
            repo.name.takeIf { reply is Resource.Success && reply.value.hasNext }
        }
        val failures = replies.filter { (_, reply) -> reply !is Resource.Success }
        val answered = replies.size - failures.size
        val allNetwork = failures.all { (_, reply) -> reply is Resource.Failure && reply.isNetworkError }

        return when {
            failures.isEmpty() -> SearchOutcome.Complete(items, morePages)
            answered > 0 -> SearchOutcome.Partial(items, answered, replies.size, detailOf(failures), morePages)
            allNetwork -> SearchOutcome.Offline
            else -> SearchOutcome.Failed(detailOf(failures))
        }
    }

    /**
     * A context that answers in the in-app language, rebuilt per round.
     *
     * `CommonActivity.setLocale` calls `Locale.setDefault` and then updates the resources of the
     * context it is handed, which is always an Activity. The application context held here never
     * learns of the choice, so `context.getString` gives a user with a non-system app language the
     * device's words on otherwise translated cards. `Locale.setDefault` is the process-wide half of
     * that pair, and it is also what `:core:strings` reads: Compose Resources resolves through
     * `Locale.current`, which on Android is `LocaleList.getDefault()`, whose first entry follows
     * `Locale.setDefault`. Building from it keeps these labels in the same language as the rest of
     * the screen.
     *
     * Per round rather than held in a field, because a held one is stale after a language change and
     * this class outlives that Activity. A search is network work, so the allocation does not show.
     */
    private fun labelContext(): Context {
        val configuration = Configuration(context.resources.configuration)
        // setLocale rather than setLocales: `LocaleList` is API 24 and minSdk is 23.
        configuration.setLocale(Locale.getDefault())
        return context.createConfigurationContext(configuration)
    }

    /**
     * The shortfall line, built from the failures rather than written as prose.
     *
     * It lands in a mono line the user is expected to paste into a bug report, so it carries the two
     * facts a maintainer needs — which provider, and what it said — and nothing else. Grouped by
     * reason because twelve providers behind one dead DoH resolver produce one reason, not twelve,
     * and a line that repeats it twelve times is unreadable at 10 feet. This is the field that
     * replaces the `Toast` the old screen dropped on the floor.
     */
    private fun detailOf(failures: List<Pair<APIRepository, Resource<SearchResponseList>>>): String {
        val byReason = failures.groupBy { (_, reply) ->
            val reason = if (reply is Resource.Failure) reply.errorString else ""
            reason.lineSequence().firstOrNull()?.trim()?.take(MAX_REASON_CHARS)
                ?.ifBlank { null } ?: "NO RESPONSE"
        }
        val rendered = byReason.entries.take(MAX_REASONS)
            .joinToString(" · ") { (reason, group) ->
                group.joinToString(", ") { (repo, _) -> repo.name } + ": " + reason
            }
        val omitted = byReason.size - MAX_REASONS
        return if (omitted > 0) "$rendered · +$omitted MORE" else rendered
    }

    /**
     * `SearchResponse` -> `SearchResultItem`, the whole reason this class exists.
     *
     * `name`/`posterUrl`/`apiName` are carried straight across. Everything else is a decision, and
     * each is justified where it is made.
     *
     * Both label mappings are **referenced, not re-copied**, per `.claude/rules/code-reuse.md`:
     * `SettingsUIScreen.toStringRes()` for `SearchQuality` and `SettingsProvidersScreen.toStringRes()`
     * for `TvType` are the two that are already extracted as callable extensions. The other two
     * copies — `SearchResultBuilder.kt:107`-`:128` and `ResultViewModel2.kt:309`-`:328` — are inline
     * `when` expressions inside long functions and cannot be called; extracting either would edit the
     * live search path, which this step must not touch. `SettingsProvidersScreen` is `@Deprecated`
     * as a *settings screen*; the mapping on it is not, hence the narrow suppression.
     */
    @Suppress("DEPRECATION")
    private fun SearchResponse.toItem(labels: Context): SearchResultItem = SearchResultItem(
        id = encodeId(apiName = apiName, url = url),
        title = name,
        posterUrl = posterUrl,
        posterHeaders = posterHeaders,
        providerName = apiName,
        typeLabel = type?.let { labels.getString(with(SettingsProvidersScreen) { it.toStringRes() }) },
        qualityLabel = quality?.let { labels.getString(with(SettingsUIScreen) { it.toStringRes() }) },
        dubLabel = dubLabel(labels),
        watchProgress = watchProgress(),
    )

    /**
     * One badge, so one fact.
     *
     * `SearchResultBuilder` has two `TextView`s (`text_is_dub`, `text_is_sub`, `:271`-`:302`) and can
     * show both plus an episode count; the contract here has a single [SearchResultItem.dubLabel],
     * and the drawn card has one slot. Dubbed wins when both are present because that is the axis a
     * viewer selects on. The episode count is dropped on purpose: it is a second fact, and a badge
     * that sometimes reads "Dub · 12" and sometimes "Dub" changes width per card.
     */
    private fun SearchResponse.dubLabel(labels: Context): String? {
        val status = (this as? AnimeSearchResponse)?.dubStatus ?: return null
        return when {
            DubStatus.Dubbed in status -> labels.getString(R.string.app_dubbed_text)
            DubStatus.Subbed in status -> labels.getString(R.string.app_subbed_text)
            else -> null
        }
    }

    /**
     * Resume progress, `0f..1f`, or `null` for a card with no rule on it.
     *
     * **Known defect, deliberately not fixed here:** `ResultEpisode.getWatchProgress()`
     * (`ui/result/ResultFragment.kt:133`) is `(position / 1000) / (duration / 1000)` with no guard,
     * so a stored `duration` of 0 yields `NaN` and anything under 1000 ms yields `0 / 0`. It takes a
     * `ResultEpisode` and so is not callable from a `SearchResponse`, but the division is the same
     * one, so this computes it with the guard the original is missing and clamps the result.
     *
     * [DataStoreHelper.fixVisual] is reused rather than reimplemented — it is what the old card calls
     * (`SearchResultBuilder.kt:255`), so the new rail and the old one snap at the same 1% and 95%
     * thresholds instead of drifting apart. A snapped-to-zero position returns `null` so the card
     * draws nothing at all rather than an empty rule.
     *
     * Most provider hits leave `SearchResponse.id` null — a `var` with a `null` default that only
     * some providers set — so in practice this is usually `null`, and correctly so: with no id there
     * is no stored position to look up.
     */
    private fun SearchResponse.watchProgress(): Float? {
        val pos = DataStoreHelper.getViewPos(id)?.fixVisual() ?: return null
        if (pos.duration <= 0L || pos.position <= 0L) return null
        return (pos.position.toDouble() / pos.duration.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        /** `APIRepository.search` is paged; the screen shows page one and has no paging yet. */
        const val FIRST_PAGE = 1

        /** Distinct reasons rendered in a [detailOf] line before it collapses to a count. */
        const val MAX_REASONS = 3

        /** Per-reason cap. Provider error strings can be a whole stack trace. */
        const val MAX_REASON_CHARS = 60

        /** Types on a panel row's mono line before the line is too long for the panel. */
        const val MAX_DETAIL_TYPES = 3

        /**
         * How each provider last answered, for the panel's marker and its `FAILED 2 MINUTES AGO`. In memory,
         * so it starts empty each run, and shared, because a search screen's ViewModel does not outlive it.
         */
        val providerHealth = ConcurrentHashMap<String, ProviderHealth>()
    }
}

/**
 * Which providers a search asks: the old screen's `SearchFragment.search`.
 *
 * Eligible ([eligibleSearchProviders]), among the user's picks, serving a lit type chip.
 *
 * Each narrowing that would leave nothing is dropped, so a search always has somewhere to go. **When no
 * pick survives it falls back to the eligible providers, not to every installed one**, which is a
 * difference on purpose: the old `SearchViewModel.search` read an empty set as "search everything", NSFW
 * included. When no chip matches, the picks stand, as the old screen's `ifEmpty` did.
 *
 * @param types the lit chips' types. Empty is no narrowing at all.
 */
internal fun selectSearchProviders(
    installed: List<MainAPI>,
    languageEnabled: Set<String>,
    preferredTypes: Set<Int>,
    picked: Set<String>,
    types: List<TvType>,
): List<MainAPI> = providersUnderTypes(selectSearchProviders(installed, languageEnabled, preferredTypes, picked), types)

/** The same with no chips. A separate function because this signature is in `classes.jar`. */
internal fun selectSearchProviders(
    installed: List<MainAPI>,
    languageEnabled: Set<String>,
    preferredTypes: Set<Int>,
    picked: Set<String>,
): List<MainAPI> {
    val eligible = eligibleSearchProviders(installed, languageEnabled, preferredTypes)
    return eligible.filter { it.name in picked }.ifEmpty { eligible }
}

/** Installed, in an enabled language, serving a preferred media type: what keeps NSFW out unless asked for. */
internal fun eligibleSearchProviders(
    installed: List<MainAPI>,
    languageEnabled: Set<String>,
    preferredTypes: Set<Int>,
): List<MainAPI> = installed.filter { api ->
    api.name in languageEnabled && api.supportedTypes.any { it.ordinal in preferredTypes }
}

/** [providers] serving one of [types], or all of them when that is nobody or [types] is empty. */
internal fun providersUnderTypes(providers: List<MainAPI>, types: List<TvType>): List<MainAPI> =
    providers.filter { api -> api.supportedTypes.any { it in types } }.ifEmpty { providers }

/** Which `TvType`s a chip stands for: the old screen's `HomeFragment.getPairList`, which is private there. */
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

/**
 * Provider text on its way to a row: third party, so control characters go and the length is capped. A
 * name with a newline in it would otherwise break the row it is on.
 */
internal fun cleanProviderText(text: String): String =
    text.filterNot { it.isISOControl() }.trim().take(MAX_PROVIDER_TEXT_CHARS)

private const val MAX_PROVIDER_TEXT_CHARS = 60

/** @property failedAt when it last failed, or `null` if its last answer was a good one. */
internal data class ProviderHealth(val failedAt: Long?)

/**
 * The old screen's rail order (`SearchFragment.kt:500`): pinned providers first, in reverse pin order,
 * and everything else keeping the order it came in, which `sortedBy` guarantees by being stable.
 */
internal fun <T> pinnedFirst(items: List<T>, pinned: Array<String>, nameOf: (T) -> String): List<T> {
    val order = pinned.reversedArray()
    return items.sortedBy { item ->
        val index = order.indexOf(nameOf(item))
        if (index == -1) Int.MAX_VALUE else index
    }
}

/**
 * What a [SearchResultId] decodes back to: everything `:app` needs to reopen a hit.
 *
 * `ResultFragment.newInstance` (`ui/result/ResultFragment.kt:149`) bundles `url` and `apiName`, so
 * those two are the whole requirement; it also bundles `name`, but only as a placeholder title that
 * [SearchResultItem.title] already carries.
 */
internal data class SearchResultTarget(val apiName: String, val url: String)

/**
 * Encodes the pair a hit is reopened from, length-prefixed: `"<apiName.length>:<apiName><url>"`.
 *
 * ## Why the id is opaque at all
 *
 * `SearchResultId`'s KDoc states the rule: `:app` needs an `apiName` *and* a `url`, both of which
 * are `:library`'s shapes, and a structured id in `:feature:search` would either leak those types —
 * reintroducing exactly the `:library` edge that keeps the module out of `IOS_CANARY_EXEMPT` — or
 * invent a parallel pair that has to be kept in step with them. So the producer owns the format and
 * the screen only ever passes it back. [decodeId] lives in this file beside [encodeId] for the same
 * reason: a codec split across two files drifts.
 *
 * Length-prefixed rather than delimited: a provider URL may contain any character — `:`, `|`, a
 * newline, a NUL — so no delimiter is safe against one that emits it, and a merely *unlikely*
 * separator fails as a wrong-provider navigation, which reads as a broken extension rather than a
 * broken id. A length prefix round-trips any pair, and the count is in UTF-16 code units, the unit
 * `substring` indexes in, so a surrogate pair cannot shift the split.
 */
internal fun encodeId(apiName: String, url: String): SearchResultId =
    SearchResultId("${apiName.length}:$apiName$url")

/** Inverse of [encodeId]. `null` for anything this file did not write. */
internal fun decodeId(id: SearchResultId): SearchResultTarget? {
    val split = id.value.indexOf(':')
    if (split < 1) return null
    val nameLength = id.value.substring(0, split).toIntOrNull() ?: return null
    val nameStart = split + 1
    // `nameStart + nameLength` overflows negative near Int.MAX_VALUE, so bound the remainder instead.
    if (nameLength < 0 || nameLength > id.value.length - nameStart) return null
    val urlStart = nameStart + nameLength
    return SearchResultTarget(
        apiName = id.value.substring(nameStart, urlStart),
        url = id.value.substring(urlStart),
    )
}
