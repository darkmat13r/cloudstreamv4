package com.lagradost.cloudstream4.feature.home

import androidx.compose.runtime.Immutable

@Immutable
data class HomeState(
    val sources: List<HomeSourceOption> = emptyList(),
    val selected: HomeSourceId? = null,
    /** The picker is open. In state, so Escape and an outside click close the same thing. */
    val pickerOpen: Boolean = false,
    val status: HomeStatus = HomeStatus.Loading,
    /** Set when a title is opened from a rail, and cleared once focus is back on it. */
    val returnTo: HomeReturn? = null,
    /** The screen is resumed. Focus taken before that does not last on a TV: see [HomeReturn.ready]. */
    val appeared: Boolean = false,
    /** Rows have not yet landed since this page opened, so the first card still has first claim on focus. */
    val landingOwed: Boolean = true,
) {
    val selectedOption: HomeSourceOption? get() = sources.firstOrNull { it.id == selected }
}

/**
 * Where a person was when they opened a title, to put them back there.
 *
 * Held here and not in the screen, because the host's view is destroyed while the title's page is on top.
 *
 * @property rowKey which row: one title can be on several.
 * @property ready the screen has come back, which [HomeAction.Reappeared] says. Focus waits for it. On
 *   `Television_720p` a fragment composes its view well before it resumes, and focus taken in that gap was gone by the
 *   time the page above had left: the window handed it to the rail (`KMP-34`, measured).
 */
@Immutable
data class HomeReturn(
    val id: String,
    val rowKey: HomeRowKey,
    val firstVisibleRow: Int = 0,
    val firstVisibleRowOffset: Int = 0,
    val firstVisibleCard: Int = 0,
    val firstVisibleCardOffset: Int = 0,
    val ready: Boolean = false,
)

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

    /** @param from where focus should come back to. A pointer screen has nothing to put back, so it may leave this out. */
    data class TitleActivated(val id: String, val from: HomeReturn? = null) : HomeAction

    /** The no-main-page prompt's button. Becomes [HomeEffect.OpenSearch]. */
    data object SearchRequested : HomeAction

    /** The nothing-installed prompt's button on a TV. Becomes [HomeEffect.OpenExtensions]. */
    data object ExtensionsRequested : HomeAction

    /** The account control on a TV. Becomes [HomeEffect.OpenAccounts]. */
    data object AccountsRequested : HomeAction

    /**
     * Sources may have come or gone: plugins finished a stage of loading, or a setting or the account changed.
     *
     * Rows already on screen for the source that is still chosen are left alone, focus included.
     */
    data object SourcesChanged : HomeAction

    /** The host has resumed. Focus may now be taken. */
    data object Reappeared : HomeAction

    /** The host has paused. */
    data object Disappeared : HomeAction

    /** Focus is back on the title, or that title has gone. */
    data object ReturnHandled : HomeAction

    /** The first landing has happened, whether or not it took focus. */
    data object LandingHandled : HomeAction
}

sealed interface HomeEffect {
    data class OpenTitle(val id: String) : HomeEffect
    data object OpenSearch : HomeEffect
    data object OpenExtensions : HomeEffect
    data object OpenAccounts : HomeEffect
}
