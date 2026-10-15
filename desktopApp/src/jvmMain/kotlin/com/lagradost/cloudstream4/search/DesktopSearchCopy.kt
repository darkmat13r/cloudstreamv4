package com.lagradost.cloudstream4.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_idle_action
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_idle_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_idle_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_action
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_body_unknown
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_detail
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_clear_all
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_field_placeholder
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_result_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_label
import com.lagradost.cloudstream4.feature.search.SearchPrompts
import com.lagradost.cloudstream4.feature.search.tv.SearchTvCopy
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Desktop's copy for the search vertical, resolved from `:core:strings`.
 *
 * ## The finding this file exists to record
 *
 * `SearchPrompts` is injected into a ViewModel and returns **already-resolved** display strings.
 * That works on Android, where `Context.resources.getString` is a synchronous call anything can
 * make. It does **not** work on desktop. Compose resources — which is what `:core:strings` is —
 * publishes exactly two accessors, and they are
 * `@Composable fun stringResource(...)` and `suspend fun getString(...)`
 * (verified in `components-resources-1.12.0-sources.jar`, `StringResources.kt`). There is no
 * synchronous, non-composable path. A ViewModel is neither a composition nor a coroutine, so it
 * cannot resolve a string at the moment it needs one.
 *
 * **That is a measurement, not an opinion, and it answers the open question about this pattern.**
 * `docs/plans/kmp-migration.md` Phase 7 says a ViewModel should expose `UiString` and let the
 * screen resolve it. It is right, and the injected-copy pattern is Android-shaped: it survives
 * only where a synchronous resource lookup exists. `I18N-01` is the task that fixes it properly.
 *
 * ## What this file does in the meantime
 *
 * Composition resolves every string it can and writes them into a [Holder]; the [SearchPrompts]
 * implementation reads the holder. Three consequences, all deliberate:
 *
 * - **Format strings are resolved as templates** and filled with `String.format` at call time.
 *   That is safe: the *template* comes from the locale, only the substitution is late.
 * - **Plurals cannot be.** Choosing between `one` and `other` is locale logic that happens inside
 *   the resolver, so a plural needs its count at resolution time. The counts the screen knows in
 *   advance (how many sources are selected) are resolved; the one it does not — how many providers
 *   failed, which arrives with the reply — falls back to count-free copy. Artboard 4b draws
 *   "3 sources failed"; desktop currently says it without the 3.
 * - [SearchTvCopy]'s three count functions are **called from inside composition**, by the screen,
 *   so they resolve plurals correctly. Only the ViewModel-side prompts are affected.
 */
class DesktopSearchCopy {

    /** What composition last resolved. Written by [rememberDesktopSearchCopy], read by the VM. */
    internal class Holder {
        @Volatile var idleHeadline: String = ""
        @Volatile var idleBody: String = ""
        @Volatile var idleAction: String = ""
        @Volatile var noResultsHeadline: String = ""
        @Volatile var noResultsBody: String = ""
        @Volatile var noResultsAction: String = ""
        @Volatile var noResultsDetailTemplate: String = ""
        @Volatile var failedHeadline: String = ""
        @Volatile var failedBody: String = ""
        @Volatile var offlineHeadline: String = ""
        @Volatile var offlineBody: String = ""
        @Volatile var retry: String = ""
        @Volatile var retryFailed: String = ""
        @Volatile var partialHeadline: String = ""
    }
}

/** The [SearchPrompts] the ViewModel is constructed with. Reads whatever composition last wrote. */
internal class HolderBackedPrompts(
    private val holder: DesktopSearchCopy.Holder,
) : SearchPrompts {

    override fun idle() = UiState.Empty(
        StatePrompt(holder.idleHeadline, holder.idleBody, StateAction(holder.idleAction) {}),
    )

    override fun noResults(query: String, queried: Int, onRetry: () -> Unit) = UiState.Empty(
        prompt = StatePrompt(
            headline = holder.noResultsHeadline,
            body = holder.noResultsBody,
            action = StateAction(holder.noResultsAction, onRetry),
        ),
        // 8c's mono line. Every number is final and the arithmetic closes: a Complete-but-empty
        // outcome means every queried source answered and none failed.
        detail = format(holder.noResultsDetailTemplate, queried, queried, 0, 0),
    )

    override fun failed(detail: String, onRetry: () -> Unit) = UiState.Error(
        prompt = StatePrompt(
            headline = holder.failedHeadline,
            body = holder.failedBody,
            action = StateAction(holder.retry, onRetry),
        ),
        detail = detail,
    )

    override fun offline(onRetry: () -> Unit) = UiState.Offline(
        StatePrompt(holder.offlineHeadline, holder.offlineBody, StateAction(holder.retry, onRetry)),
    )

    override fun partial(answered: Int, total: Int, detail: String, onRetry: () -> Unit) =
        FailureNotice(
            headline = holder.partialHeadline,
            detail = detail,
            retry = StateAction(holder.retryFailed, onRetry),
        )

    private fun format(template: String, vararg args: Any): String =
        runCatching { String.format(template, *args) }.getOrDefault(template)
}

/**
 * Resolves the copy for the current locale and keeps [prompts] in step with it.
 *
 * Returns the pair the host needs: the screen's words, and the prompts the ViewModel holds.
 */
@Composable
fun rememberDesktopSearchCopy(): Pair<SearchTvCopy, SearchPrompts> {
    val holder = remember { DesktopSearchCopy.Holder() }
    val prompts = remember(holder) { HolderBackedPrompts(holder) }

    val idleHeadline = stringResource(Res.string.search_state_idle_headline)
    val idleBody = stringResource(Res.string.search_state_idle_body)
    val idleAction = stringResource(Res.string.search_state_idle_action)
    val noResultsHeadline = stringResource(Res.string.search_state_no_results_headline)
    val noResultsBody = stringResource(Res.string.search_state_no_results_body_unknown)
    val noResultsAction = stringResource(Res.string.search_state_no_results_action)
    val noResultsDetail = stringResource(Res.string.search_state_no_results_detail)
    val failedHeadline = stringResource(Res.string.search_state_failed_headline)
    val failedBody = stringResource(Res.string.search_state_failed_body)
    val offlineHeadline = stringResource(Res.string.search_state_offline_headline)
    val offlineBody = stringResource(Res.string.search_state_offline_body)
    val retry = stringResource(Res.string.search_state_retry)
    val retryFailed = stringResource(Res.string.search_state_retry_failed)
    val partialHeadline = stringResource(Res.string.search_state_failed_headline)

    SideEffect {
        holder.idleHeadline = idleHeadline
        holder.idleBody = idleBody
        holder.idleAction = idleAction
        holder.noResultsHeadline = noResultsHeadline
        holder.noResultsBody = noResultsBody
        holder.noResultsAction = noResultsAction
        holder.noResultsDetailTemplate = noResultsDetail
        holder.failedHeadline = failedHeadline
        holder.failedBody = failedBody
        holder.offlineHeadline = offlineHeadline
        holder.offlineBody = offlineBody
        holder.retry = retry
        holder.retryFailed = retryFailed
        holder.partialHeadline = partialHeadline
    }

    val copy = SearchTvCopy(
        fieldPlaceholder = stringResource(Res.string.search_tv_field_placeholder),
        sourcesLabel = stringResource(Res.string.search_tv_sources_label),
        // Resolved per call inside composition, which is where the screen calls them — so these
        // three get correct plural selection even though the prompts above cannot. That asymmetry
        // is the finding in this file's KDoc.
        sourcesCount = { selected, total ->
            pluralStringResource(Res.plurals.search_tv_sources_count, total, selected, total)
        },
        recentHeadline = stringResource(Res.string.search_tv_recent_headline),
        recentCount = { count ->
            pluralStringResource(Res.plurals.search_tv_recent_count, count, count)
        },
        clearAll = stringResource(Res.string.search_tv_clear_all),
        resultCount = { count ->
            pluralStringResource(Res.plurals.search_tv_result_count, count, count)
        },
    )
    return copy to prompts
}
