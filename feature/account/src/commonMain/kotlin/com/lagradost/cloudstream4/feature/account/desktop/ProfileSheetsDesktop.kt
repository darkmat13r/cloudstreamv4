package com.lagradost.cloudstream4.feature.account.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_add_profile
import com.lagradost.cloudstream4.core.strings.generated.resources.account_delete_body
import com.lagradost.cloudstream4.core.strings.generated.resources.account_delete_profile
import com.lagradost.cloudstream4.core.strings.generated.resources.account_delete_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_edit_profile
import com.lagradost.cloudstream4.core.strings.generated.resources.account_undo_warning
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.delete
import com.lagradost.cloudstream4.core.strings.generated.resources.duplicate_add
import com.lagradost.cloudstream4.core.strings.generated.resources.name
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_save
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.stringResource

/**
 * `DS-25` A6, A6b and A6c: a profile's editor, as a sheet hung from the window's top edge over a dimmed window
 * (`KMP-95b`). The name, and Delete apart at the lead when [onDelete] is given. Save (or Add) is the one primary.
 *
 * - The name opens with its words selected, so typing replaces them; Return in it is Save, Escape anywhere is Cancel.
 * - A name is at most [ProfileSheetsDesktopDefaults.MaxNameChars] characters; the store cleans it again.
 * - **Initials only** (the PM, 2026-11-08): the boards' picture row and "Use an image address" are `KMP-95g`'s.
 * - The PIN row comes with `KMP-95c`, A6c's "can't be locked" line with it.
 *
 * @param adding A6b: the title is Add profile and the primary is Add.
 * @param onSave given the name as typed; the host decides what a blank one means.
 * @param onDelete opens A8. Null for the first profile, which is never deleted, and while adding.
 */
@Composable
fun ProfileEditorSheet(
    name: String,
    adding: Boolean,
    onSave: (name: String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    var field by remember { mutableStateOf(TextFieldValue(name, selection = TextRange(0, name.length))) }
    val fieldFocus = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fieldLabel = stringResource(Res.string.name)
    SheetFrame(onEscape = onCancel) {
        BasicText(
            stringResource(if (adding) Res.string.account_add_profile else Res.string.account_edit_profile),
            style = typography.railTitle.copy(color = colors.ink),
        )
        Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
            BasicText(fieldLabel, style = typography.label.copy(color = colors.muted))
            val shape = CloudStreamTheme.shapes.medium
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DesktopDimens.ToolbarFieldHeight)
                    .background(colors.bg, shape)
                    .border(CloudStreamDimens.Hairline, if (focused) colors.ink2 else colors.line, shape)
                    .padding(horizontal = DesktopDimens.RowPadding),
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { next -> if (next.text.length <= ProfileSheetsDesktopDefaults.MaxNameChars) field = next },
                    textStyle = typography.body.copy(color = colors.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.ink),
                    interactionSource = source,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .semantics { contentDescription = fieldLabel }
                        .onPreviewKeyEvent { event ->
                            val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            if (enter) onSave(field.text)
                            enter
                        },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.fillMaxWidth().padding(top = DesktopDimens.RelatedGap),
        ) {
            if (onDelete != null) {
                // A6 draws it quiet; `DesktopButton` has no quiet tone, so it is the secondary, still apart at the lead.
                DesktopButton(label = stringResource(Res.string.account_delete_profile), onClick = onDelete, icon = CloudStreamIcons.Delete)
            }
            Spacer(Modifier.weight(1f))
            DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel)
            DesktopButton(
                label = stringResource(if (adding) Res.string.duplicate_add else Res.string.sort_save),
                onClick = { onSave(field.text) },
                primary = true,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
}

/**
 * `DS-25` A8: asks before a profile is deleted. **Cancel is the primary** (`DEC-19`), and Return and Escape both land
 * on it; only a click deletes. The words say its watch history, bookmarks and sign-ins leave this Mac with it.
 */
@Composable
fun DeleteProfileSheet(name: String, onDelete: () -> Unit, onCancel: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val sheetFocus = remember { FocusRequester() }
    SheetFrame(
        onEscape = onCancel,
        modifier = Modifier
            .focusRequester(sheetFocus)
            .onPreviewKeyEvent { event ->
                val safe = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
                if (safe) onCancel()
                safe
            }
            .focusable(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
            ProfileDisc(name, ProfileSheetsDesktopDefaults.DiscSize, fill = colors.line, style = typography.body)
            Column(verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap)) {
                BasicText(UiString.Resource(Res.string.account_delete_title, listOf(name)).resolve(), style = typography.railTitle.copy(color = colors.ink))
                BasicText(stringResource(Res.string.account_undo_warning), style = typography.label.copy(color = colors.muted))
            }
        }
        BasicText(UiString.Resource(Res.string.account_delete_body, listOf(name)).resolve(), style = typography.body.copy(color = colors.ink2))
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End),
            modifier = Modifier.fillMaxWidth().padding(top = DesktopDimens.RelatedGap),
        ) {
            DesktopButton(label = stringResource(Res.string.delete), onClick = onDelete, icon = CloudStreamIcons.Delete)
            DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel, primary = true)
        }
    }
    // The sheet takes focus, not Cancel, as the downloads sheet does: Cancel's fill already marks it the default.
    LaunchedEffect(Unit) { runCatching { sheetFocus.requestFocus() } }
}

/**
 * What both sheets share: the dimmed window, which swallows clicks, and a 440 sheet hung from the top edge, so only its
 * lower corners are round. On `surface`, so the secondary buttons, which are `raised`, read as buttons: nothing raised
 * on raised.
 */
@Composable
private fun SheetFrame(onEscape: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = CloudStreamTheme.colors
    val large = CloudStreamTheme.shapes.large
    val shape = (large as? CornerBasedShape)?.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)) ?: large
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = ProfileSheetsDesktopDefaults.ScrimAlpha))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .onPreviewKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) onEscape()
                escape
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = modifier
                .width(ProfileSheetsDesktopDefaults.SheetWidth)
                .background(colors.surface, shape)
                .border(CloudStreamDimens.Hairline, colors.line, shape)
                .padding(DesktopDimens.WindowMargin),
            content = content,
        )
    }
}

/** Literals the `DS-25` sheets own, on the 4 dp step. */
object ProfileSheetsDesktopDefaults {
    /** `.sheet`: 440, agreed with `DS-24`'s sheets. */
    val SheetWidth: Dp = 440.dp

    /** `.av48`, A8's disc. */
    val DiscSize: Dp = 48.dp

    /** `.scrim`, rgba(0,0,0,.48). */
    const val ScrimAlpha = 0.48f

    /** As the profiles file keeps it (`DesktopProfileFile.MAX_NAME_CHARS`), here in chars, the stricter of the two. */
    const val MaxNameChars = 64
}
