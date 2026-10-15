package com.lagradost.cloudstream4.search

import android.content.Context
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen
import com.lagradost.cloudstream3.ui.settings.SettingsUIScreen
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSources
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SearchSource] over the app's real providers: step 3 of Phase 7's recipe for the search vertical.
 *
 * `:feature:search` restates the half of `SearchResponse` a card renders so that it needs no
 * `:library` edge and therefore no iOS-canary exemption (see `SearchResultItem`'s KDoc). The cost of
 * that is one mapping step, and this is it — the only place in the tree where `SearchResponse`,
 * `TvType`, `SearchQuality` and `R` meet `SearchResultItem`.
 *
 * Nothing here touches the live path: `ui/search/SearchFragment` and `ui/search/SearchViewModel`
 * still own search until step 5 deletes them.
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
     * `selected` is what a full search will actually query, counted the same way the search counts
     * it, so the chrome row cannot disagree with the search — the defect `SearchSources`' KDoc cites,
     * where 8c's no-results copy and the mono status line quote the same number.
     *
     * Note the one behaviour deliberately *not* carried over: `SearchViewModel.search` treats an
     * empty active set as "search everything" (`providersActive.isEmpty() || ...`, `:243`). Keeping
     * that would make this report `0 / 48` while the search queried 48.
     */
    override suspend fun sources(): SearchSources = withContext(io) {
        val enabled = context.getApiSettings()
        val selected = apis.withLock { apis.count { it.name in enabled } }
        SearchSources(selected = selected, total = apis.size)
    }

    override suspend fun search(query: String): SearchOutcome = fanOut(query, quick = false)

    override suspend fun quickSearch(query: String): SearchOutcome = fanOut(query, quick = true)

    /**
     * One concurrent round over the selected providers, folded into a [SearchOutcome].
     *
     * `amap` rather than a sequential loop for the same reason `SearchViewModel.kt:252` uses it: a
     * dozen providers answer in the time of the slowest, not the sum. Each reply is already
     * individually timed out and wrapped by `APIRepository.safeApiCall`, so nothing here throws.
     */
    private suspend fun fanOut(query: String, quick: Boolean): SearchOutcome = withContext(io) {
        val enabled = context.getApiSettings()
        // mapNotNull under withLock is AtomicList's prescribed idiom; AtomicList.filter re-enters the lock.
        val targets = apis.withLock {
            apis.mapNotNull { if (it.name in enabled) APIRepository(it) else null }
        }.filter { !quick || it.hasQuickSearch }

        if (targets.isEmpty()) return@withContext noTargets(quick, enabled)

        val replies: List<Pair<APIRepository, Resource<SearchResponseList>>> = targets.amap { repo ->
            repo to if (quick) repo.quickSearch(query) else repo.search(query, FIRST_PAGE)
        }
        fold(replies)
    }

    /**
     * Nothing to ask. Not [SearchOutcome.Offline] — the radio is irrelevant — and not an empty
     * [SearchOutcome.Complete], because the ViewModel renders that through
     * `SearchPrompts.noResults(query, queried)`, whose drawn copy reads *"All nine selected sources
     * answered and none of them carry it"*. With nothing queried that sentence blames the catalogue
     * for a configuration problem.
     *
     * [SearchOutcome.Failed] is honest and it is also useful: the failure state's `StateAction` is
     * `SearchAction.Retry`, which the ViewModel routes to `submitCurrentQuery()` — a **full** search.
     * So the no-quick-search case escapes itself on one press, rather than being the dead end
     * `SearchPrompts`' KDoc exists to prevent.
     */
    private fun noTargets(quick: Boolean, enabled: Set<String>): SearchOutcome {
        val selected = apis.withLock { apis.count { it.name in enabled } }
        return SearchOutcome.Failed(
            // A status code, not a sentence. `SearchOutcome.Failed.detail` is drawn in the mono
            // ramp beside "TIMEOUT 10S" (artboard 4b) and is the line a user pastes into a bug
            // report, so it is deliberately not translated — the same reason an HTTP status is
            // not. Prose that needed translating would belong in the prompt above it.
            if (quick) "NO_QUICK_SEARCH · 0/$selected SELECTED"
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
        val items = replies
            .flatMap { (_, reply) -> if (reply is Resource.Success) reply.value.items else emptyList() }
            .map { it.toItem() }
        val failures = replies.filter { (_, reply) -> reply !is Resource.Success }
        val answered = replies.size - failures.size
        val allNetwork = failures.all { (_, reply) -> reply is Resource.Failure && reply.isNetworkError }

        return when {
            failures.isEmpty() -> SearchOutcome.Complete(items)
            answered > 0 -> SearchOutcome.Partial(items, answered, replies.size, detailOf(failures))
            allNetwork -> SearchOutcome.Offline
            else -> SearchOutcome.Failed(detailOf(failures))
        }
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
    private fun SearchResponse.toItem(): SearchResultItem = SearchResultItem(
        id = encodeId(apiName = apiName, url = url),
        title = name,
        posterUrl = posterUrl,
        providerName = apiName,
        typeLabel = type?.let { context.getString(with(SettingsProvidersScreen) { it.toStringRes() }) },
        qualityLabel = quality?.let { context.getString(with(SettingsUIScreen) { it.toStringRes() }) },
        dubLabel = dubLabel(),
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
    private fun SearchResponse.dubLabel(): String? {
        val status = (this as? AnimeSearchResponse)?.dubStatus ?: return null
        return when {
            DubStatus.Dubbed in status -> context.getString(R.string.app_dubbed_text)
            DubStatus.Subbed in status -> context.getString(R.string.app_subbed_text)
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
