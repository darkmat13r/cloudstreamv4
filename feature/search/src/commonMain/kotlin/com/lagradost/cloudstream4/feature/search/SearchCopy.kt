package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_idle_action
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_idle_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_idle_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_action
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_body_unknown
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_detail
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_partial_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_result_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_count
import com.lagradost.cloudstream4.core.strings.resolve
import org.jetbrains.compose.resources.stringResource

/**
 * [SearchStatus] as the design system draws it, in the composition's locale.
 *
 * The buttons are stable across recompositions, because `UiState` compares by value and a new lambda
 * on every pass would read as a new state.
 */
@Composable
fun SearchStatus.toUiState(onAction: (SearchAction) -> Unit): UiState<SearchContent> {
    val latest by rememberUpdatedState(onAction)
    val retry = remember { { latest(SearchAction.Retry) } }
    val browse = remember { { latest(SearchAction.BrowseRequested) } }
    return when (this) {
        SearchStatus.Loading -> UiState.Loading
        is SearchStatus.Showing -> UiState.Content(content)
        is SearchStatus.Partial -> UiState.Partial(
            value = content,
            notice = FailureNotice(
                headline = UiString.Plural(Res.plurals.search_state_partial_headline, total - answered).resolve(),
                detail = detail,
                retry = StateAction(stringResource(Res.string.search_state_retry_failed), retry),
            ),
        )

        SearchStatus.Idle -> UiState.Empty(
            StatePrompt(
                headline = stringResource(Res.string.search_state_idle_headline),
                body = stringResource(Res.string.search_state_idle_body),
                action = StateAction(stringResource(Res.string.search_state_idle_action), browse),
            ),
        )

        is SearchStatus.NoResults -> UiState.Empty(
            prompt = StatePrompt(
                headline = stringResource(Res.string.search_state_no_results_headline),
                // Zero while the source list is still being read, which has no number worth quoting.
                body = if (queried > 0) {
                    UiString.Plural(Res.plurals.search_state_no_results_body, queried).resolve()
                } else {
                    stringResource(Res.string.search_state_no_results_body_unknown)
                },
                action = StateAction(stringResource(Res.string.search_state_no_results_action), retry),
            ),
            // 8c's mono line. Every number is final and the arithmetic closes: this status means every
            // queried source answered and none failed.
            detail = UiString.Resource(
                Res.string.search_state_no_results_detail,
                listOf(queried, queried, 0, 0),
            ).resolve(),
        )

        is SearchStatus.Failed -> UiState.Error(
            prompt = StatePrompt(
                headline = stringResource(Res.string.search_state_failed_headline),
                body = stringResource(Res.string.search_state_failed_body),
                action = StateAction(stringResource(Res.string.search_state_retry), retry),
            ),
            detail = detail,
        )

        SearchStatus.Offline -> UiState.Offline(
            StatePrompt(
                headline = stringResource(Res.string.search_state_offline_headline),
                body = stringResource(Res.string.search_state_offline_body),
                action = StateAction(stringResource(Res.string.search_state_retry), retry),
            ),
        )
    }
}

/** The source chip's count, e.g. "12 / 48". Both numbers, because the ratio is the point. */
@Composable
internal fun sourcesCountText(sources: SearchSources): String =
    UiString.Plural(
        Res.plurals.search_tv_sources_count,
        sources.total,
        listOf(sources.selected, sources.total),
    ).resolve()

/** A section's count, e.g. "21 RESULTS". */
@Composable
internal fun resultCountText(count: Int): String = UiString.Plural(Res.plurals.search_tv_result_count, count).resolve()

/** The history header's count, e.g. "8 SAVED". */
@Composable
internal fun recentCountText(count: Int): String = UiString.Plural(Res.plurals.search_tv_recent_count, count).resolve()
