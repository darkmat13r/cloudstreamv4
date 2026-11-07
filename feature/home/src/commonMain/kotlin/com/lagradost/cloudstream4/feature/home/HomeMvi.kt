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
    /** The title at the top of the page (`TV Home` 3c). Shown only beside rows of the source it came from. */
    val hero: HomeHero = HomeHero.None,
    /** Continue watching and progress, from this device. Kept whatever the source is doing, offline included. */
    val history: HomeHistory = HomeHistory(),
    /** The bookmarks rail and its filter (`TV Home Extras` 3e), from this device. Kept whatever the source is doing, too. */
    val bookmarks: HomeBookmarks = HomeBookmarks(),
    /** A reload is in flight (3j). The rows stay on screen while it runs, and another press is ignored. */
    val reloading: Boolean = false,
) {
    val selectedOption: HomeSourceOption? get() = sources.firstOrNull { it.id == selected }

    /** `None` is chosen (3i): no source, so only what the device keeps. */
    val unsourced: Boolean get() = selected == HomeSourceId.None
}

/** The hero, which needs one more request than the rows it comes from. */
sealed interface HomeHero {
    /** No hero: no rows, a host that cannot ask, or nothing it asked for answered. */
    data object None : HomeHero

    /** Rows are here and the hero is being asked for. Focus waits for it, so it does not land twice. */
    data object Loading : HomeHero

    /** @property eyebrow the name of the row the title was first found on, as the source names it. */
    data class Shown(val featured: HomeFeatured, val eyebrow: String? = null) : HomeHero
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
data class HomeRowKey(val section: Int, val name: String) {
    companion object {
        /** The hero, for [HomeReturn]. Not a section, so no section index is negative. */
        val Hero: HomeRowKey = HomeRowKey(section = -1, name = "")

        /** Continue watching, for [HomeReturn]. Not a section either. */
        val Continue: HomeRowKey = HomeRowKey(section = -2, name = "")

        /** The bookmarks rail, for [HomeReturn]. Not a section either. */
        val Bookmarks: HomeRowKey = HomeRowKey(section = -3, name = "")

        /** The chrome's cells, for [HomeReturn]: Random opens a title, and focus comes back to it (3j). */
        val Chrome: HomeRowKey = HomeRowKey(section = -4, name = "")
    }
}

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

    /** `None` is chosen (3i). Nothing was asked of a source, so this is neither empty nor a failure. */
    data object Unsourced : HomeStatus

    /**
     * A reload (3j) that failed after the source's rows had loaded (`KMP-68`). [failure] is what the reload said, a
     * [Failed] or [Offline], and is all a screen that draws 3m shows: the rows are gone and the failure stands where they
     * were. [kept] are the rows from before, held in memory only and never written anywhere, with [loadedAt], when they
     * loaded, in epoch milliseconds, for a screen that keeps them and says how old they are (the Mac's H4, `DS-21`).
     *
     * [rows] is empty, as it is for [failure], so everything that reads rows behaves as it did before this existed.
     */
    data class Stale(val failure: HomeStatus, val kept: List<HomeRow>, val loadedAt: Long) : HomeStatus {
        init {
            require(failure is Failed || failure == Offline) { "a reload fails as Failed or Offline" }
        }
    }
}

/** What a screen drawing 3m shows for this status: a [HomeStatus.Stale] is its failure, anything else is itself. */
val HomeStatus.shown: HomeStatus get() = if (this is HomeStatus.Stale) failure else this

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
     * A bookmarks chip was pressed (3e). The chosen one clears the filter (3f); any other becomes the one. Kept by the host.
     */
    data class BookmarkFilterPressed(val status: HomeWatchStatus) : HomeAction

    /**
     * The chrome's Reload (3j): ask every section again, keeping the rows on screen until the answer replaces them. A
     * second press while one runs is ignored. Only with rows loaded, so nothing to reload is not a reload.
     */
    data object ReloadRequested : HomeAction

    /**
     * The chrome's Random (3j): open one title chosen from what is loaded, as the old button did. Nothing loaded opens
     * nothing.
     *
     * @param from where focus comes back to, the Random cell.
     */
    data class RandomRequested(val from: HomeReturn? = null) : HomeAction

    /** The chrome's Search (3j): the only search on Home, of this source alone. Becomes [HomeEffect.OpenSourceSearch]. */
    data object SourceSearchRequested : HomeAction

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

    /** Search [source] alone, with nothing typed yet, as the old home's search button did. */
    data class OpenSourceSearch(val source: HomeSourceId) : HomeEffect
}
