package com.lagradost.cloudstream4.feature.home

import androidx.compose.runtime.Immutable

@Immutable
data class HomeState(
    val sources: List<HomeSourceOption> = emptyList(),
    val selected: HomeSourceId? = null,
    /** The picker is open. In state, so Escape and an outside click close the same thing. */
    val pickerOpen: Boolean = false,
    val status: HomeStatus = HomeStatus.Loading,
) {
    val selectedOption: HomeSourceOption? get() = sources.firstOrNull { it.id == selected }
}

/** Identifies a row across pages. Sections can answer with several rows, told apart by name. */
@Immutable
data class HomeRowKey(val section: Int, val name: String)

/**
 * One row on screen.
 *
 * Paging belongs to the section, because that is what a provider pages: a press on one row can grow its
 * siblings too. [page], [hasMore] and [more] are therefore the same on every row of a section.
 *
 * @property lines how many lines of cards to show. A line is as many cards as fit, which only the
 *   screen knows, so the row stores this and not a card count.
 */
@Immutable
data class HomeRow(
    val key: HomeRowKey,
    val landscape: Boolean,
    val items: List<HomeItem>,
    val lines: Int = 1,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val more: HomeMore = HomeMore.Idle,
) {
    val title: String get() = key.name
}

/** What asking for a row's next page is doing. */
sealed interface HomeMore {
    data object Idle : HomeMore
    data object Loading : HomeMore
    data class Failed(val detail: String) : HomeMore
}

/**
 * What the page reached, with no words in it. [toUiState] turns this into a `UiState` where it can read
 * the locale, so a language change reaches a state that is already on screen.
 */
sealed interface HomeStatus {
    data object Loading : HomeStatus

    /** Nothing here has a main page. [installed] says whether anything is installed at all. */
    data class NoSources(val installed: Int) : HomeStatus

    data class Showing(val rows: List<HomeRow>) : HomeStatus

    /**
     * Some sections failed. What arrived is shown, and the shortfall rides a banner.
     *
     * @property failedSections what a retry asks for again, leaving the rest and their pages alone.
     * @property total how many sections there are.
     */
    data class Partial(
        val rows: List<HomeRow>,
        val failedSections: List<Int>,
        val total: Int,
        val detail: String,
        val retrying: Boolean = false,
    ) : HomeStatus

    /** The source answered and has nothing. Not a failure. */
    data object Empty : HomeStatus

    data class Failed(val detail: String) : HomeStatus

    data object Offline : HomeStatus
}

sealed interface HomeAction {
    data object PickerOpened : HomeAction
    data object PickerDismissed : HomeAction
    data class SourceSelected(val id: HomeSourceId) : HomeAction

    /** A state's own button or the banner's. A partial page retries only what failed. */
    data object Retry : HomeAction

    /**
     * Show more of a row, fetching the next page only if that runs out of what is loaded.
     *
     * @param columns how many cards fit on a line right now.
     */
    data class RowMoreRequested(val key: HomeRowKey, val columns: Int) : HomeAction

    data class TitleActivated(val id: String) : HomeAction

    /** The no-main-page prompt's button. Becomes [HomeEffect.OpenSearch]. */
    data object SearchRequested : HomeAction
}

sealed interface HomeEffect {
    data class OpenTitle(val id: String) : HomeEffect
    data object OpenSearch : HomeEffect
}
