package com.lagradost.cloudstream4.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.action_remove_from_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.continue_watching
import com.lagradost.cloudstream4.core.strings.generated.resources.empty_library_logged_in_message
import com.lagradost.cloudstream4.core.strings.generated.resources.error
import com.lagradost.cloudstream4.core.strings.generated.resources.favorites_list_name
import com.lagradost.cloudstream4.core.strings.generated.resources.home_offline_source_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.library
import com.lagradost.cloudstream4.core.strings.generated.resources.no_data
import com.lagradost.cloudstream4.core.strings.generated.resources.plugin_load_fail
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.title_search
import com.lagradost.cloudstream4.core.strings.generated.resources.type_completed
import com.lagradost.cloudstream4.core.strings.generated.resources.type_dropped
import com.lagradost.cloudstream4.core.strings.generated.resources.type_on_hold
import com.lagradost.cloudstream4.core.strings.generated.resources.type_plan_to_watch
import com.lagradost.cloudstream4.core.strings.generated.resources.type_watching
import com.lagradost.cloudstream4.core.strings.resolve
import org.jetbrains.compose.resources.stringResource

/**
 * The library's words.
 *
 * **This screen adds no string keys.** Every word below already existed in `:core:strings` and is already
 * translated into the 62 locales the project ships — the bucket names are the same `type_*` keys `:app`'s
 * own `WatchType` points at, so the two screens cannot drift apart in one language and not another. That
 * is worth more than a phrase tuned for desktop: a new key is 62 translations, a regenerated set of
 * `strings.xml` files under `app/src/main/res`, and a re-recorded `classes-jar.api`, because an `R` field
 * is part of the published extension ABI. (That path is written out rather than globbed because a `*`
 * followed by a slash inside a KDoc closes the comment — the trap this very line walked into once.)
 *
 * The one place it shows is the failure line: there is no "could not read your library" string, so this
 * reuses `plugin_load_fail` ("Could not load %s") with the word "Library". **In a language that inflects
 * the object of that verb the result will be grammatically off**, because the noun arrives already
 * translated in its dictionary form. That is a real cost, written down rather than hidden; the fix is a
 * key of its own, which is `I18N-01`-shaped work.
 */
@Composable
fun LibraryTab.label(): String = when (this) {
    LibraryTab.ContinueWatching -> stringResource(Res.string.continue_watching)
    is LibraryTab.Bucket -> when (bucket) {
        LibraryBucket.Watching -> stringResource(Res.string.type_watching)
        LibraryBucket.Completed -> stringResource(Res.string.type_completed)
        LibraryBucket.OnHold -> stringResource(Res.string.type_on_hold)
        LibraryBucket.Dropped -> stringResource(Res.string.type_dropped)
        LibraryBucket.PlanToWatch -> stringResource(Res.string.type_plan_to_watch)
        LibraryBucket.Favourites -> stringResource(Res.string.favorites_list_name)
    }

    // Already in the service's words, from the host.
    is LibraryTab.Synced -> label
}

/** `%1$s needs a connection`, Home's words for the same fact. */
@Composable
internal fun offlineBody(what: String): String =
    UiString.Resource(Res.string.home_offline_source_headline, listOf(what)).resolve()

/** The words on a card's controls, resolved once for the whole screen rather than per card. */
@Composable
fun libraryCardWords(): LibraryCardWords = LibraryCardWords(
    resume = stringResource(Res.string.resume),
    remove = stringResource(Res.string.action_remove_from_bookmarks),
    setList = stringResource(Res.string.action_add_to_bookmarks),
)

/**
 * @property remove "Remove" — `:app`'s own word for taking a title out of a list.
 * @property setList "Set watch status", which is `:app`'s own label for the same control.
 */
data class LibraryCardWords(val resume: String, val remove: String, val setList: String)

/** [LibraryStatus] as the design system draws it, in the composition's locale. */
@Composable
fun LibraryStatus.toUiState(onAction: (LibraryAction) -> Unit): UiState<List<LibraryCard>> {
    val latest by rememberUpdatedState(onAction)
    val retry = remember { { latest(LibraryAction.Retry) } }
    val search = remember { { latest(LibraryAction.SearchRequested) } }
    return when (this) {
        LibraryStatus.Loading -> UiState.Loading
        is LibraryStatus.Showing -> UiState.Content(cards)

        // A list with nothing in it is not a failure, and the way out of it is to find something.
        LibraryStatus.Empty -> UiState.Empty(
            StatePrompt(
                headline = stringResource(Res.string.no_data),
                body = stringResource(Res.string.empty_library_logged_in_message),
                action = StateAction(stringResource(Res.string.title_search), search),
            ),
        )

        is LibraryStatus.Failed -> UiState.Error(
            StatePrompt(
                headline = stringResource(Res.string.error),
                // Through UiString: Compose Resources fills only positional arguments, and this is `%s`.
                body = UiString.Resource(
                    Res.string.plugin_load_fail,
                    listOf(stringResource(Res.string.library)),
                ).resolve(),
                action = StateAction(stringResource(Res.string.search_state_retry), CloudStreamIcons.Retry, retry),
            ),
            detail,
        )

        // Desktop never gets here: it has no connectivity, and no tracking service. A TV screen names the service.
        LibraryStatus.Offline -> UiState.Offline(
            StatePrompt(
                headline = stringResource(Res.string.search_state_offline_headline),
                body = offlineBody(stringResource(Res.string.library)),
                action = StateAction(stringResource(Res.string.search_state_retry), CloudStreamIcons.Retry, retry),
            ),
        )
    }
}
