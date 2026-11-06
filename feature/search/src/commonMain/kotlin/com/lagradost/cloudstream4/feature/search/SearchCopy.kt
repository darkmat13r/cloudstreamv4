package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.anime
import com.lagradost.cloudstream4.core.strings.generated.resources.asian_drama
import com.lagradost.cloudstream4.core.strings.generated.resources.cartoons
import com.lagradost.cloudstream4.core.strings.generated.resources.documentaries
import com.lagradost.cloudstream4.core.strings.generated.resources.livestreams
import com.lagradost.cloudstream4.core.strings.generated.resources.movies
import com.lagradost.cloudstream4.core.strings.generated.resources.nsfw
import com.lagradost.cloudstream4.core.strings.generated.resources.others
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
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_results_type_filter
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_suggestions_action
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_suggestions_body
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_suggestions_detail
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_no_suggestions_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_recent_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_result_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_count
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_panel_meta
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_suggestion_recent
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_suggestion_recent_suggested
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_suggestion_sources
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_suggestion_suggested
import com.lagradost.cloudstream4.core.strings.generated.resources.torrent
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series
import com.lagradost.cloudstream4.core.strings.resolve
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * [SearchStatus] as the design system draws it, in the composition's locale.
 *
 * The buttons are stable across recompositions, because `UiState` compares by value and a new lambda
 * on every pass would read as a new state.
 *
 * @param idleBody what the idle screen says about where results go, which is the one thing here that
 *   depends on the host's layout. The default describes the TV screen's rails.
 * @param narrowedBy the lit type chips, which 8c's second sentence names. Empty for a host with no chips.
 */
@Composable
fun SearchStatus.toUiState(
    onAction: (SearchAction) -> Unit,
    idleBody: StringResource = Res.string.search_state_idle_body,
    narrowedBy: Set<SearchTypeFilter> = emptySet(),
): UiState<SearchContent> {
    val latest by rememberUpdatedState(onAction)
    val retry = remember { { latest(SearchAction.Retry) } }
    val browse = remember { { latest(SearchAction.BrowseRequested) } }
    val everything = remember { { latest(SearchAction.SearchEverything) } }
    val submit = remember { { latest(SearchAction.Submit) } }
    return when (this) {
        SearchStatus.Loading -> UiState.Loading
        is SearchStatus.Showing -> UiState.Content(content)
        is SearchStatus.Partial -> UiState.Partial(
            value = content,
            notice = FailureNotice(
                headline = UiString.Plural(Res.plurals.search_state_partial_headline, total - answered).resolve(),
                detail = detail,
                retry = StateAction(stringResource(Res.string.search_state_retry_failed), CloudStreamIcons.Retry, retry),
            ),
        )

        SearchStatus.Idle -> UiState.Empty(
            StatePrompt(
                headline = stringResource(Res.string.search_state_idle_headline),
                body = stringResource(idleBody),
                action = StateAction(stringResource(Res.string.search_state_idle_action), CloudStreamIcons.Home, browse),
            ),
        )

        is SearchStatus.NoResults -> UiState.Empty(
            prompt = StatePrompt(
                headline = stringResource(Res.string.search_state_no_results_headline),
                // Zero while the source list is still being read, which has no number worth quoting.
                body = listOfNotNull(
                    if (queried > 0) {
                        UiString.Plural(Res.plurals.search_state_no_results_body, queried).resolve()
                    } else {
                        stringResource(Res.string.search_state_no_results_body_unknown)
                    },
                    narrowingSentence(narrowedBy),
                ).joinToString("\n"),
                // 8c's note: the label names both halves of what it clears, so the binding has to clear both.
                action = StateAction(stringResource(Res.string.search_state_no_results_action), CloudStreamIcons.Search, everything),
            ),
            // 8c's mono line. Every number is final and the arithmetic closes: this status means every
            // queried source answered and none failed.
            detail = UiString.Resource(
                Res.string.search_state_no_results_detail,
                listOf(queried, queried, 0, 0),
            ).resolve(),
        )

        // 8b. The field keeps focus and its OK opens the keyboard (`BUG-30`), so the body names the keyboard's key
        // where the board said "Press OK on the field". The action rides the chip row, as 8c's does.
        is SearchStatus.NoSuggestions -> UiState.Empty(
            prompt = StatePrompt(
                headline = UiString.Resource(Res.string.search_state_no_suggestions_headline, listOf(query)).resolve(),
                body = UiString.Plural(Res.plurals.search_state_no_suggestions_body, selected).resolve(),
                action = StateAction(
                    UiString.Plural(Res.plurals.search_state_no_suggestions_action, selected).resolve(),
                    CloudStreamIcons.Search,
                    submit,
                ),
            ),
            detail = stringResource(Res.string.search_state_no_suggestions_detail),
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

/** 8c's *"Movies only — your type filter is narrowing the search."* `null` with no chip lit. */
@Composable
private fun narrowingSentence(types: Set<SearchTypeFilter>): String? {
    if (types.isEmpty()) return null
    val names = SearchTypeFilter.entries.filter { it in types }.map { typeFilterLabel(it) }
    // Through `UiString`, because `stringResource` only substitutes `%1$s` and the authored key has `%s`.
    return UiString.Resource(Res.string.search_state_no_results_type_filter, listOf(names.joinToString(", "))).resolve()
}

/** A type chip's word. Existing keys, the same ones the old screen's chips use. */
@Composable
internal fun typeFilterLabel(type: SearchTypeFilter): String = stringResource(
    when (type) {
        SearchTypeFilter.Movies -> Res.string.movies
        SearchTypeFilter.TvSeries -> Res.string.tv_series
        SearchTypeFilter.Anime -> Res.string.anime
        SearchTypeFilter.AsianDrama -> Res.string.asian_drama
        SearchTypeFilter.Cartoons -> Res.string.cartoons
        SearchTypeFilter.Documentaries -> Res.string.documentaries
        SearchTypeFilter.Live -> Res.string.livestreams
        SearchTypeFilter.Torrents -> Res.string.torrent
        SearchTypeFilter.Nsfw -> Res.string.nsfw
        SearchTypeFilter.Others -> Res.string.others
    },
)

/** 8a's second line: where the row came from. */
@Composable
internal fun suggestionOriginText(row: SearchSuggestion): String = when {
    row.recentWhen != null && row.suggested -> stringResource(Res.string.search_tv_suggestion_recent_suggested)
    row.recentWhen != null -> UiString.Resource(Res.string.search_tv_suggestion_recent, listOf(row.recentWhen)).resolve()
    else -> stringResource(Res.string.search_tv_suggestion_suggested)
}

/**
 * 8a's count with its word, `7 SOURCES`. One string rather than the board's number over a word: a plural word without its
 * number reads wrong wherever one form covers several numbers (lint's `ImpliedQuantity`, 19 locales).
 */
@Composable
internal fun suggestionSourcesText(count: Int): String =
    UiString.Plural(Res.plurals.search_tv_suggestion_sources, count).resolve()

/** The panel's mono line: `12 OF 48 ENABLED · CHANGES APPLY ON DONE`. */
@Composable
internal fun sourcePanelMetaText(enabled: Int, total: Int): String =
    UiString.Resource(Res.string.search_tv_sources_panel_meta, listOf(enabled, total)).resolve()

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
