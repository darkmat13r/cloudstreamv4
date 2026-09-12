package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.runtime.Immutable

/**
 * The one thing a state lets a person do.
 *
 * **There is exactly one per state, and it is never optional.** The audit that produced this
 * group found 18 of 20 screens whose failure or empty state either had no action at all, or had
 * one that was not in the focus order — on a TV that is a dead end with no way out but Back. So
 * [StateAction] is a required constructor parameter of [StatePrompt] and of [FailureNotice],
 * and every component that renders one renders it as a real focus stop. There is no code path
 * through this package that produces a state a D-pad cannot escape.
 *
 * @property label the button's text. User-facing: the caller resolves it from
 *   `stringResource(...)`; this module never holds display strings.
 * @property onActivate what pressing it does. Held in state rather than passed alongside it so
 *   that the two cannot drift apart — a label without a behaviour is exactly the defect above.
 */
@Immutable
data class StateAction(
    val label: String,
    val onActivate: () -> Unit,
)

/**
 * What a full-surface state says: a headline, a line of body copy, and its single [action].
 *
 * Rendered by `StateView`. Strings are user-facing and come from the caller already resolved.
 *
 * @property headline the 32sp line. Short — it is read at three metres.
 * @property body the 14sp explanation. One or two sentences; `StateView` measures it no wider
 *   than `StateViewDefaults.MaxTextWidth`.
 * @property action see [StateAction]. Required.
 */
@Immutable
data class StatePrompt(
    val headline: String,
    val body: String,
    val action: StateAction,
)

/**
 * What a *partial* failure says — the case where content did arrive, but not all of it.
 *
 * This is deliberately not a [StatePrompt]: it has no body copy, because it does not get a band
 * of its own. A full-width banner costs 120px of vertical budget on the artboard, and that is
 * what pushed a rail past the focus band. `FailureBanner` rides the trailing end of a chip row
 * that already exists instead.
 *
 * @property headline the plain-language line, e.g. "3 of 12 providers failed".
 * @property detail the machine-readable detail, set in the mono ramp: a host, a status code, a
 *   provider name. This is the field that replaces the `// No user indication it failed :(`
 *   branch — it is the part a user can paste into a bug report.
 * @property retry the recovery action. Required, and rendered as a focus stop in the chip row.
 */
@Immutable
data class FailureNotice(
    val headline: String,
    val detail: String,
    val retry: StateAction,
)
