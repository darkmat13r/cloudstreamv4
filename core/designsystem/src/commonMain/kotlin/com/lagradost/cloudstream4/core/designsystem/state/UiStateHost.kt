package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes

/**
 * Renders a [UiState] as the state component the design owes it, and will not compile if a state
 * is missing.
 *
 * A screen calls this instead of writing its own `if (loading) … else if (error) …`, which is
 * how the app arrived at failures that were Toasts and one branch that was literally
 * `// No user indication it failed :(`. The `when` below is exhaustive over a sealed interface,
 * so the six states are not a convention anyone has to remember.
 *
 * **What it will not do: place the [FailureBanner].** [UiState.Partial] renders its content in
 * full and nothing else, because the banner belongs at the trailing end of a chip row that this
 * host does not own — a band of its own would cost 120px of vertical budget, which is what
 * pushed a rail past the focus band in the first place. Read the notice in your chip row:
 *
 * ```kotlin
 * Column {
 *     ChipRow { filters(); state.failureNotice?.let { FailureBanner(it) } }
 *     UiStateHost(state = state, skeleton = { SkeletonRail(CloudStreamCardSizes.LandscapeLarge, it) }) { rows ->
 *         Rails(rows)
 *     }
 * }
 * ```
 *
 * @param state the screen's state.
 * @param modifier applied to the state components this host draws — the prompt and the skeleton.
 *   Your [content] gets none: it is your composable and carries its own.
 * @param glyph decoration for the three prompt states, chosen by the caller from the state it is
 *   handed. Not focusable; the design system ships no icon set.
 * @param skeleton what [UiState.Loading] draws, handed the host's [modifier]. **Pass one shaped
 *   like your content** — the
 *   default is a generic landscape rail, and a screen that is not a rail of landscape cards will
 *   visibly jump when its content lands.
 * @param content your loaded UI, used for both [UiState.Content] and [UiState.Partial].
 */
@Composable
fun <T> UiStateHost(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    glyph: @Composable (UiState<T>) -> Unit = {},
    skeleton: @Composable (Modifier) -> Unit = {
        SkeletonRail(rung = CloudStreamCardSizes.LandscapeLarge, modifier = it)
    },
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> skeleton(modifier)
        is UiState.Empty -> Prompt(state.prompt, modifier) { glyph(state) }
        is UiState.Offline -> Prompt(state.prompt, modifier) { glyph(state) }
        is UiState.Error -> Prompt(state.prompt, modifier, state.detail) { glyph(state) }
        is UiState.Partial -> content(state.value)
        is UiState.Content -> content(state.value)
    }
}

/** One spelling of [StateView], so the branches above stay one line each. */
@Composable
private fun Prompt(
    prompt: StatePrompt,
    modifier: Modifier = Modifier,
    detail: String? = null,
    glyph: @Composable () -> Unit,
) {
    StateView(prompt = prompt, modifier = modifier, detail = detail, glyph = glyph)
}

private val PreviewState: UiState<Nothing> = UiState.Offline(
    prompt = StatePrompt(
        headline = "You are offline",
        body = "Downloads are still available. Everything else needs a connection.",
        action = StateAction(label = "Try again", onActivate = {}),
    ),
)

@Preview
@Composable
private fun UiStateHostOfflinePreview() {
    StatePreviewSurface {
        UiStateHost(state = PreviewState) { }
    }
}

@Preview
@Composable
private fun UiStateHostLoadingPreview() {
    StatePreviewSurface {
        UiStateHost(state = UiState.Loading) { }
    }
}
