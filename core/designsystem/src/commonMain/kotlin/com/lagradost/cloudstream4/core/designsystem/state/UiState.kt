package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.runtime.Immutable

/**
 * The six states every surface in CloudStream 4 owes its user.
 *
 * A screen renders this through `UiStateHost`, whose `when` is exhaustive over this sealed
 * interface — which is the point. A screen cannot forget a state, because leaving one out does
 * not compile, and it cannot ship a state with no way out, because every non-content case
 * carries a required [StateAction].
 *
 * The states are:
 *
 * | State | Content? | What it means |
 * |---|---|---|
 * | [Loading] | not yet | Work in flight. Renders a skeleton shaped like the content, so nothing jumps when the content lands. |
 * | [Empty] | none, and that is correct | The query succeeded and there is genuinely nothing — no history, no results, no downloads. |
 * | [Error] | none, and that is wrong | The work failed. Recoverable by the prompt's action. |
 * | [Offline] | none | Distinguished from [Error] on purpose: the fix is different and so is the copy. |
 * | [Partial] | yes, incomplete | Some sources answered, some did not. Content renders in full; the shortfall rides a `FailureBanner` in the chip row. |
 * | [Content] | yes, complete | |
 *
 * **On `@Immutable` and `T`.** The annotation here is a promise about the *whole* value, and
 * [Content] and [Partial] can only keep it if `T` keeps it too. Pass an immutable payload — an
 * `ImmutableList`, a `data class` of `val`s — never a `MutableList`, or Compose will skip
 * recompositions it should not.
 *
 * @param T the content payload.
 */
@Immutable
sealed interface UiState<out T> {

    /** Work in flight. Renders the caller's skeleton; see `SkeletonRail` and friends. */
    data object Loading : UiState<Nothing>

    /**
     * The work succeeded and there is nothing to show. Not a failure — the action is usually
     * "go find something" rather than "try again".
     */
    data class Empty(val prompt: StatePrompt) : UiState<Nothing>

    /**
     * The work failed outright.
     *
     * @param detail optional machine-readable detail — a host, a status code. Rendered in the
     *   mono ramp under the body copy. This exists so a failure stops being a vanished Toast.
     */
    data class Error(
        val prompt: StatePrompt,
        val detail: String? = null,
    ) : UiState<Nothing>

    /**
     * No network. Separate from [Error] because the user's next move is different, and because
     * "check your connection" shown for a 500 is worse than saying nothing.
     */
    data class Offline(val prompt: StatePrompt) : UiState<Nothing>

    /**
     * Content arrived, but incomplete.
     *
     * The content renders normally; the shortfall is reported by a `FailureBanner` that the
     * screen places at the trailing end of its own chip row. `UiStateHost` cannot place it for
     * you — see the note on that function — so read it with [failureNotice].
     */
    data class Partial<out T>(
        val value: T,
        val notice: FailureNotice,
    ) : UiState<T>

    /** Everything arrived. */
    data class Content<out T>(val value: T) : UiState<T>
}

/**
 * The payload if there is one, whether it is complete ([UiState.Content]) or not
 * ([UiState.Partial]).
 */
val <T> UiState<T>.valueOrNull: T?
    get() = when (this) {
        is UiState.Content -> value
        is UiState.Partial -> value
        else -> null
    }

/**
 * The shortfall to report in the chip row, or `null` when there is nothing to report.
 *
 * ```kotlin
 * ChipRow {
 *     filterChips()
 *     state.failureNotice?.let { FailureBanner(it) }
 * }
 * ```
 */
val UiState<*>.failureNotice: FailureNotice?
    get() = (this as? UiState.Partial<*>)?.notice

/** True while a skeleton is on screen. Handy for suppressing a chip row's own affordances. */
val UiState<*>.isLoading: Boolean
    get() = this is UiState.Loading
