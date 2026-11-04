package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens

/**
 * Everything a [ListRow] renders, hoisted.
 *
 * Focus is deliberately **not** in here. Focus is a property of the input device, not of the
 * list's data: a ViewModel that carried `focused` per row would be re-emitting its whole list on
 * every D-pad press. [ListRow] observes its own focus and keeps it local.
 *
 * @property title the row's primary line. Always shown.
 * @property subtitle the secondary line. `null` gives a single-line row, which is laid out
 *   differently — see the text stack in `ListRowTextStack.kt`.
 * @property selected whether the row's selection marker is lit. The marker cell is always
 *   present, so toggling this never moves the text.
 * @property enabled whether the row acts when pressed. A disabled row still occupies its
 *   [CloudStreamDimens.ListRowHeight] and still renders, in the `muted` colour token. By default
 *   it also stops being a focus stop; see [focusableWhenDisabled].
 * @property focusableWhenDisabled when [enabled] is false, whether the row keeps its focus target
 *   and its ring. `TV Library` 5d needs this: the sort criterion a provider cannot do *"stays
 *   visible and disabled with the reason"*, and the reason — carried in [subtitle] — is only
 *   readable at three metres if a D-pad can land on the row. Defaults to false, which is the
 *   unreachable disabled row every caller that predates this property already has. See
 *   `ListRow.kt`'s `rowBehaviour` for what it does and why `selectable` cannot express it.
 */
@Immutable
data class ListRowState(
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focusableWhenDisabled: Boolean = false,
)

/**
 * Measurements [ListRow] needs that `:core:theme` does not name yet.
 *
 * Both are **derived** from existing tokens rather than invented, so they cannot drift: change
 * [CloudStreamDimens.ListRowHeight] or [CloudStreamDimens.ListRowPadding] and these follow. They
 * are declared here only because this module must not edit `:core:theme`; see the return value of
 * the task that created this file for the promotion list.
 */
@Immutable
object ListRowDefaults {

    /**
     * The height of a row's content box: [CloudStreamDimens.ListRowHeight] less the
     * [CloudStreamDimens.ListRowPadding] above and below it — 48 − 2×8 = **32dp**.
     *
     * That 32dp is not a coincidence and is the whole reason the row works: it is exactly the
     * two-line text stack's total ([CloudStreamDimens.StackTitleLineBox] 16 +
     * [CloudStreamDimens.StackGap] 4 + [CloudStreamDimens.StackSubtitleLineBox] 12). The stack
     * therefore fills the content box exactly and lands at +8dp in the row, which is rule 5 of
     * the design falling out of the padding instead of being asserted separately.
     *
     * Should be promoted as `CloudStreamDimens.ListRowContentHeight`.
     */
    val ContentHeight: Dp = CloudStreamDimens.ListRowHeight - CloudStreamDimens.ListRowPadding * 2

    /**
     * The gap between a row's internal parts — marker to disc, disc to text, text to trailing.
     *
     * Mirrors [CloudStreamDimens.ListRowPadding] (8dp), which keeps the whole row on one rhythm:
     * marker starts at 8, disc at 20, text at 52 — every one of them a multiple of 4.
     *
     * This is *not* [CloudStreamDimens.MinFocusGap]: that governs the distance between two
     * focusables, and a row's marker, disc and text are not focusable. Should be promoted as
     * `CloudStreamDimens.ListRowInnerGap`.
     */
    val InnerGap: Dp = CloudStreamDimens.ListRowPadding
}
