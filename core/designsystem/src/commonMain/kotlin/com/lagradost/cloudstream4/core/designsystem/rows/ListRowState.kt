package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * [ContentHeight] and [InnerGap] are **derived** from existing tokens rather than invented, so
 * they cannot drift: change [CloudStreamDimens.ListRowHeight] or
 * [CloudStreamDimens.ListRowPadding] and they follow. They are declared here only because this
 * module must not edit `:core:theme`; see the return value of the task that created this file for
 * the promotion list.
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
     * The step a row's height is a whole number of: 32px on the artboard, halved.
     *
     * [CloudStreamDimens.ListRowHeight] is 48dp, which is three of these, and a row that grows a
     * line taller is 64dp, which is four. **Both centre their text stack on the 4dp step**, which
     * is the reason the step is 16dp and not 4dp: the stack's own total is a multiple of 16
     * (`ListRowTextStack.kt`'s rule 5), so the slack left over in the content box is a multiple of
     * 8 and the stack lands at a multiple of 4 from the row's top. A 4dp step would let a row be
     * 52dp, whose 36dp content box leaves 2dp above a 32dp stack.
     *
     * It is also what rule 5 says about the row itself: *"a text stack must total a multiple of
     * 16px to centre in a 96px row"*. The row is the other half of that sentence.
     *
     * **Should become `CloudStreamDimens.ListRowHeightStep`.**
     */
    val HeightStep: Dp = 16.dp

    /**
     * How many lines a row's title takes in a list wide enough for one: every list outside a side
     * panel, which is every list the design draws at 640dp or more.
     */
    const val TITLE_LINES: Int = 1

    /**
     * How many lines a row's title may take **inside a side panel**. See `ListRowTextStack.kt` —
     * the second line exists because a 280dp panel cannot hold every language's `sort_*` label on
     * one (`BUG-62`).
     */
    const val TITLE_LINES_IN_PANEL: Int = 2

    /** The subtitle is one line, always: it is the secondary fact and it may be cut. */
    const val SUBTITLE_MAX_LINES: Int = 1

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

/**
 * How many lines the row being drawn here may give its title.
 *
 * **It is the surface that decides, not the row.** A `sort_*` label does not become too long for a
 * row; it becomes too long for a 280dp side panel, and the same string in a 640dp account list or
 * a 1280dp downloads list fits on one line with room to spare. So a row asks where it is rather
 * than measuring its own string: `SidePanel` provides [ListRowDefaults.TITLE_LINES_IN_PANEL] for
 * the rows it lays out, and everywhere else this stays at [ListRowDefaults.TITLE_LINES].
 *
 * The alternative — every row wrapping whenever its title does not fit — was measured and is worse
 * (`BUG-62`). A downloads row with a long file name took a second line and its list's row pitch
 * changed; `aLongNameIsEllipsizedOnOneLine` and `focusOnARowThatFitsDoesNotMoveTheList` both say in
 * their names what those lists have decided, and a search suggestion column composed one row fewer
 * because the ones above it were taller. A list that is wide enough wants the ellipsis.
 *
 * It is `internal` on purpose: the only provider is [SidePanel][com.lagradost.cloudstream4.core.designsystem.panel.SidePanel],
 * so "inside a panel" is a fact about the layout rather than a flag a call site can pass.
 */
internal val LocalListRowTitleLines = staticCompositionLocalOf { ListRowDefaults.TITLE_LINES }
