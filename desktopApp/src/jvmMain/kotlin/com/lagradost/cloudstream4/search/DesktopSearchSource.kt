package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSources
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
     * Every provider is "selected", because desktop has no provider-selection UI.
     *
     * On Android the selection comes from `Context.getApiSettings()`, which reads the user's
     * language preferences. Artboard 4d draws the picker that would change it; until desktop has
     * one, `selected == total` is the truth rather than a placeholder.
     */
    override suspend fun sources(): SearchSources {
        val total = repositories().size
        return SearchSources(selected = total, total = total)
    }

    override suspend fun search(query: String): SearchOutcome =
        fanOut(query) { repo -> repo.search(query, FIRST_PAGE) }

    override suspend fun quickSearch(query: String): SearchOutcome =
        fanOut(query, quickOnly = true) { repo -> repo.quickSearch(query) }

    private fun repositories(): List<APIRepository> =
        APIHolder.apis.withLock { APIHolder.apis.map { APIRepository(it) } }

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
        quickOnly: Boolean = false,
        request: suspend (APIRepository) -> Resource<com.lagradost.cloudstream3.SearchResponseList>,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val repos = repositories().filter { !quickOnly || it.hasQuickSearch }
        // Not Complete(emptyList()): that routes to `SearchPrompts.noResults`, whose drawn 8c copy
        // says "All nine selected sources answered and none of them carry it" — with nothing
        // queried that sentence blames the catalogue for a configuration problem. `:app`'s
        // implementation reached the same conclusion independently.
        if (repos.isEmpty()) {
            return@withContext SearchOutcome.Failed("NO_SOURCES · 0 INSTALLED")
        }

        val replies = coroutineScope {
            repos.map { repo -> async { repo to request(repo) } }.awaitAll()
        }

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
        map { it.errorString.trim().ifEmpty { UNKNOWN_ERROR } }
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

    companion object {
        private const val FIRST_PAGE = 1
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
