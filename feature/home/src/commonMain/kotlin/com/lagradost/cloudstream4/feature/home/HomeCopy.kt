package com.lagradost.cloudstream4.feature.home

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
import com.lagradost.cloudstream4.core.strings.generated.resources.blank_repo_message
import com.lagradost.cloudstream4.core.strings.generated.resources.empty_library_logged_in_message
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_install_first
import com.lagradost.cloudstream4.core.strings.generated.resources.home_search_only_body
import com.lagradost.cloudstream4.core.strings.generated.resources.home_sections_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.loading
import com.lagradost.cloudstream4.core.strings.generated.resources.no_data
import com.lagradost.cloudstream4.core.strings.generated.resources.no_internet_connection
import com.lagradost.cloudstream4.core.strings.generated.resources.plugin_load_fail
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_count
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.strings.generated.resources.title_search
import com.lagradost.cloudstream4.core.strings.resolve
import org.jetbrains.compose.resources.stringResource

/**
 * [HomeStatus] as the design system draws it, in the composition's locale.
 *
 * @param sourceName the selected source, which the failure sentence names.
 */
@Composable
fun HomeStatus.toUiState(sourceName: String?, onAction: (HomeAction) -> Unit): UiState<List<HomeRow>> {
    val latest by rememberUpdatedState(onAction)
    val retry = remember { { latest(HomeAction.Retry) } }
    val search = remember { { latest(HomeAction.SearchRequested) } }
    val tryAgain = StateAction(stringResource(Res.string.search_state_retry), retry)
    return when (this) {
        HomeStatus.Loading -> UiState.Loading
        is HomeStatus.Showing -> UiState.Content(rows)
        is HomeStatus.Partial -> UiState.Partial(
            value = rows,
            notice = FailureNotice(
                headline = UiString.Plural(Res.plurals.home_sections_failed, failedSections.size).resolve(),
                detail = "${
                    UiString.Plural(Res.plurals.search_tv_sources_count, total, listOf(failedSections.size, total)).resolve()
                } · $detail",
                retry = StateAction(
                    stringResource(if (retrying) Res.string.loading else Res.string.search_state_retry_failed),
                    retry,
                ),
            ),
        )

        // Search may still work: a source that only searches is common, so that is where this points.
        is HomeStatus.NoSources -> if (installed > 0) {
            UiState.Empty(
                StatePrompt(
                    headline = stringResource(Res.string.no_data),
                    body = stringResource(Res.string.home_search_only_body),
                    action = StateAction(stringResource(Res.string.title_search), search),
                ),
            )
        } else {
            UiState.Empty(
                StatePrompt(
                    headline = stringResource(Res.string.extension_install_first),
                    body = stringResource(Res.string.blank_repo_message),
                    action = tryAgain,
                ),
            )
        }

        HomeStatus.Empty -> UiState.Empty(
            StatePrompt(
                headline = stringResource(Res.string.no_data),
                body = stringResource(Res.string.empty_library_logged_in_message),
                action = tryAgain,
            ),
        )

        is HomeStatus.Failed -> UiState.Error(
            StatePrompt(
                headline = stringResource(Res.string.source_error),
                // Through UiString: Compose Resources fills only positional arguments, and this is authored `%s`.
                body = UiString.Resource(Res.string.plugin_load_fail, listOf(sourceName.orEmpty())).resolve(),
                action = tryAgain,
            ),
            detail,
        )

        HomeStatus.Offline -> UiState.Offline(
            StatePrompt(
                headline = stringResource(Res.string.search_state_offline_headline),
                body = stringResource(Res.string.no_internet_connection),
                action = tryAgain,
            ),
        )
    }
}
