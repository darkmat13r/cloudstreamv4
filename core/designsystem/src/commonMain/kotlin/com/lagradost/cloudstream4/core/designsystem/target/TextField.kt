package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The CloudStream 4 text field — one line, a placeholder, and a ring.
 *
 * ## Where it came from
 *
 * This is the search screen's `QueryField`, which was `private` in
 * `feature/search/.../tv/SearchChromeRow.kt` and is now here because it was never a search
 * component: `TV Search` 4a–4c, `TV Search Suggestions` 8d's Quick Search panel, the
 * *"Enter a repository URL"* entry on `TV Onboarding Extensions` 8c and the profile editor on 8d
 * all draw the same field. The move is **verbatim** — same height, padding, shape, colours, type
 * and cursor — so the search screen renders identically to the pixel. Only the search-specific
 * pieces became parameters: the action it emits, and its IME action.
 *
 * ## Geometry
 *
 * `TV Search`'s own CSS is the specification: `height: 96px`, `padding: 0 24px`, `border-radius:
 * 8px`, `background: var(--surf)`. Halved onto the artboard's dp: [TextFieldDefaults.Height]
 * (48dp) tall, [TextFieldDefaults.HorizontalPadding] (12dp) each side,
 * `CloudStreamTheme.shapes.medium`, on `CloudStreamTheme.colors.surface`.
 *
 * The width is **not** this component's business — it has none of its own and takes what its
 * parent gives it, which on 4a is the chrome row's remaining space after the Sources chip.
 *
 * The ring is drawn on the field, bleeding [CloudStreamDimens.FocusRingBleed] (4dp) outside its
 * box without changing it, so no ancestor may clip. On TV the field is normally the leading
 * focusable of its screen.
 *
 * ## The hairline here consumes layout, and on the board it does not
 *
 * The board draws the field's edge as `box-shadow: inset 0 0 0 1px var(--line)`, which costs no
 * layout: with `box-sizing: border-box` and `padding: 0 24px`, text starts at exactly 24px. This
 * implementation uses `Modifier.border`, which insets the content box by
 * [CloudStreamDimens.Hairline], so text starts at 13dp rather than 12dp — one dp off the 4dp step,
 * for the reason [GhostTextButton]'s KDoc spells out at length and solves with a draw-only
 * `hairlineOutline`.
 *
 * It is **kept as it was** on purpose: this task moved the component and promised the search
 * screen would look identical, and correcting it is a 1dp visual change that belongs in its own
 * change with its own diff to look at. Reported, not fixed.
 *
 * ## The magnifier is the caller's
 *
 * The board draws a glyph in the leading position (`.fgl`), 32px with a 16px gap, which is [leadingIcon].
 * It is off by default, so a field whose caller does not ask for one renders as it did.
 *
 * @param value the current text. Hoisted; the field holds no state.
 * @param onValueChange invoked on every edit, with the whole new value.
 * @param placeholder already-localised text shown while [value] is empty, in `muted`.
 * @param modifier applied to the field, which is the focusable and the ring's target. The field
 *   imposes no width, so this is normally where its width comes from. Pass
 *   `Modifier.onFocusChanged { … }` here if a parent needs to observe focus.
 * @param imeAction the soft keyboard's action key. [ImeAction.Done] unless the field submits
 *   something, in which case name what it submits — the search screen passes [ImeAction.Search].
 * @param onSubmit invoked when the action key is pressed. Defaults to doing nothing, which is
 *   correct for a field whose value is read from elsewhere.
 * @param keyboardOnOk on a D-pad, landing on the field only highlights it, and OK or Enter opens the
 *   keyboard. Off by default, where landing on it opens the keyboard, which then takes the D-pad until
 *   Back closes it. See [KeyboardOnOk].
 * @param leadingIcon a glyph from `CloudStreamIcons` before the text, as decoration: the placeholder says what
 *   the field is for.
 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    keyboardOnOk: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val keyboard = remember { KeyboardOnOk() }

    Box(
        modifier = modifier
            .height(TextFieldDefaults.Height)
            .tvFocusRing(focused = focused, shape = shape)
            .background(color = colors.surface, shape = shape)
            .border(width = CloudStreamDimens.Hairline, color = colors.line, shape = shape)
            .padding(horizontal = TextFieldDefaults.HorizontalPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        val input: @Composable () -> Unit = {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = if (keyboardOnOk) Modifier.fillMaxWidth().then(keyboard.modifier) else Modifier.fillMaxWidth(),
                readOnly = keyboardOnOk && keyboard.readOnly,
                textStyle = typography.body.copy(color = colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(colors.ink),
                interactionSource = interactionSource,
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onAny = {
                        keyboard.close()
                        onSubmit()
                    },
                ),
            )
            if (value.isEmpty()) {
                BasicText(
                    text = placeholder,
                    style = remember(typography, colors) {
                        typography.body.copy(color = colors.muted)
                    },
                    maxLines = SINGLE_LINE,
                )
            }
        }
        if (leadingIcon == null) {
            input()
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TextFieldDefaults.LeadingIconGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CloudStreamIcon(
                    icon = leadingIcon,
                    contentDescription = null,
                    size = CloudStreamIconSize.Small,
                    tint = colors.muted,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { input() }
            }
        }
    }
}

/** Values [TextField] needs that `:core:theme` does not name yet. */
@Immutable
object TextFieldDefaults {

    /**
     * The field's height: 96px on the 1920x1080 artboard, halved.
     *
     * Numerically `CloudStreamDimens.ChromeRowHeight` (48dp), which is what the field was sized
     * from while it lived in the search screen's chrome row — and that was only ever true because
     * the field *is* the chrome row's height there. A field in `TV Onboarding Extensions` 8c's
     * panel is the same 48dp without a chrome row anywhere near it, so the measurement is the
     * field's own.
     *
     * **Promote this as `CloudStreamDimens.TextFieldHeight`.**
     */
    val Height: Dp = 48.dp

    /**
     * Padding between the field's edge and its text: `padding: 0 24px` on the artboard, halved.
     *
     * Mirrors `CloudStreamDimens.MinFocusGap` (12dp) numerically only — that token governs the
     * distance *between* two focusables and has nothing to do with the inside of one. Not
     * `ChipDefaults.VisualHorizontalPadding` either, which is the same number for a third reason.
     *
     * **Promote this as `CloudStreamDimens.TextFieldHorizontalPadding`.**
     */
    val HorizontalPadding: Dp = 12.dp

    /** Between [TextField]'s glyph and its text: 16px on the artboard, halved. Mirrors `ChipDefaults.CountGap`. */
    val LeadingIconGap: Dp = 8.dp
}

private const val SINGLE_LINE = 1
