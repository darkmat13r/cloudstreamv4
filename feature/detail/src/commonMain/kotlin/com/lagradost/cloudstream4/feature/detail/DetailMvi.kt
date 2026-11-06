package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable

/**
 * Where focus goes back to when the page returns from the player or from another title.
 *
 * In state and not in the composition, because the host's view is destroyed while the screen above is
 * open and the ViewModel is what survives.
 *
 * @param ready whether the screen has actually come back, which [DetailAction.Reappeared] says. Focus
 *   waits for it: a host composes its view well before it resumes, and focus taken in that gap is gone
 *   by the time the screen above has finished leaving (measured on `Television_720p` by `KMP-34`).
 */
@Immutable
data class DetailReturn(val target: DetailFocus, val ready: Boolean = false)

/** The stops the page can send focus back to. Entry focus is always [Primary]: boards 7a and 7b. */
enum class DetailFocus {
    Primary,
    Episodes,
    Recommendations,
}

/**
 * @param links null while nobody has asked to play anything. Otherwise the source list replaces the
 *   page, and going back returns to the page without reloading it.
 * @param linksArriving more streams may still join [links], which can already be [DetailLoad.Loaded].
 * @param synopsisOpen whether the full synopsis is showing, which the clamped two-line plot hides.
 * @param watchStatusPicking whether the watch-list picker is showing.
 *
 *   Both panels' open-ness is **state rather than composition state**, and it has to be: `SidePanelHost`
 *   consumes BACK only as a Compose key event on a focused panel, and on this emulator an injected BACK
 *   reaches the activity first and finishes it. Its own KDoc assigns platform back to the host, so the
 *   host needs something to gate an `OnBackPressedCallback` on — which is [panelOpen]. `LibraryState.picking`
 *   is state for the sibling reason: one dismiss path for Escape and for a click outside.
 * @param swappedRoles cast positions whose role line is showing the voice actor instead of the
 *   character. The only press action a cast item has, because no provider gives an actor an id to
 *   open a person page with.
 * @param returnTo set when the page opens something above it, and cleared once focus is back.
 */
@Immutable
data class DetailState(
    val content: DetailLoad<TitleDetail> = DetailLoad.Loading,
    val links: DetailLoad<List<StreamLink>>? = null,
    val linksArriving: Boolean = false,
    val synopsisOpen: Boolean = false,
    val watchStatusPicking: Boolean = false,
    val swappedRoles: Set<Int> = emptySet(),
    val returnTo: DetailReturn? = null,
) {
    /** The title, where one is loaded. */
    val detail: TitleDetail? get() = (content as? DetailLoad.Loaded)?.value

    /** Whether a side panel is showing, which is what a host gates its platform back on. */
    val panelOpen: Boolean get() = synopsisOpen || watchStatusPicking
}

/** How far a request got, with no words in it. The screens add them: see `toPageUiState`, `toLinksUiState`. */
sealed interface DetailLoad<out T> {
    data object Loading : DetailLoad<Nothing>
    data class Loaded<T>(val value: T) : DetailLoad<T>

    /** The source answered, with nothing playable. */
    data object Empty : DetailLoad<Nothing>

    /** [detail] is the machine-readable line under the words. */
    data class Failed(val detail: String) : DetailLoad<Nothing>
}

sealed interface DetailAction {
    data object Retry : DetailAction

    /**
     * Asks for this title's streams and shows the source list. Desktop's play path, which picks a
     * stream itself; Android goes through [PlaybackRequested] to the host's own player instead.
     */
    data class PlayRequested(val data: String) : DetailAction

    /** Asks for the sources the last [PlayRequested] asked for. */
    data object RetryLinks : DetailAction
    data class LinkActivated(val link: StreamLink) : DetailAction
    data object LinksDismissed : DetailAction

    /**
     * Hands playback to the host, which already has a player.
     *
     * @param fromStart ignore any stored position. "Start over" is not Resume with another label: a
     *   player that is given no position reads the stored one and carries on from it.
     */
    data class PlaybackRequested(val data: String, val fromStart: Boolean) : DetailAction

    data object TrailerRequested : DetailAction

    /** The page came back on screen. Re-reads what another screen may have changed, and frees focus. */
    data object Reappeared : DetailAction
    data object ReturnHandled : DetailAction

    data class WatchStatusPicked(val status: DetailWatchStatus) : DetailAction
    data object FavouriteToggled : DetailAction
    data object SynopsisToggled : DetailAction

    /** Opens the watch-list picker. The pick itself is [WatchStatusPicked]. */
    data object WatchStatusPickerOpened : DetailAction

    /**
     * Closes whichever side panel is showing, and does nothing when none is.
     *
     * Separate from [SynopsisToggled] because a host's back callback fires without knowing which panel is
     * up, and a toggle would *open* the synopsis from a closed page.
     */
    data object PanelDismissed : DetailAction

    /** Swaps one cast position's role line between the character and the voice actor. */
    data class CastMemberActivated(val index: Int) : DetailAction

    data class RecommendationActivated(val id: String) : DetailAction
}

sealed interface DetailEffect {
    /** Desktop: a stream the viewer picked from the source list. */
    data class Play(val title: String, val link: StreamLink) : DetailEffect

    /** Android: the host's own player resolves and plays [data] itself. */
    data class OpenPlayer(val data: String, val fromStart: Boolean) : DetailEffect

    data object PlayTrailer : DetailEffect

    /** Another title, by the opaque id its recommendation carried. */
    data class OpenTitle(val id: String) : DetailEffect
}
