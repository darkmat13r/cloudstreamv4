package com.lagradost.cloudstream4.feature.downloads.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.delete
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_cancel_confirm_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_cancel_confirm_title
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_cancel_download
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_keep_downloading
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_delete_confirm_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_delete_confirm_title
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Board D3: asks before a delete. **Cancel is the filled default** (`DEC-19`, rule 1 of `ui-components.md`): the delete
 * cannot be undone. Both pages use it: titles (D3) and one series' episodes (D4).
 */
@Composable
internal fun DesktopDeleteSheet(
    count: Int,
    bytes: Long,
    formatBytes: (Long) -> String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) = DesktopConfirmSheet(
    title = UiString.Plural(Res.plurals.downloads_delete_confirm_title, count).resolve(),
    body = UiString.Resource(Res.string.downloads_delete_confirm_body, listOf(formatBytes(bytes))).resolve(),
    destructive = stringResource(Res.string.delete),
    safe = stringResource(Res.string.cancel),
    onDestructive = onConfirm,
    onSafe = onCancel,
)

/**
 * Asks before cancelling a download that has fetched something, which would discard it (the PM's call, 2026-11-07): a
 * 90% download should not be lost to a stray click while Delete asks first. **Keep downloading is the filled default.**
 */
@Composable
internal fun DesktopCancelDownloadSheet(
    bytes: Long,
    formatBytes: (Long) -> String,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) = DesktopConfirmSheet(
    title = stringResource(Res.string.downloads_cancel_confirm_title),
    body = UiString.Resource(Res.string.downloads_cancel_confirm_body, listOf(formatBytes(bytes))).resolve(),
    destructive = stringResource(Res.string.downloads_cancel_download),
    safe = stringResource(Res.string.downloads_keep_downloading),
    onDestructive = onConfirm,
    onSafe = onKeep,
)

/**
 * A sheet from under the toolbar, over a dimmed page, for an action that cannot be undone. The safe choice is the filled
 * default and the destructive one the secondary (rule 1); Return and Escape both land on the safe one, and only a click
 * does the destructive thing.
 */
@Composable
private fun DesktopConfirmSheet(
    title: String,
    body: String,
    destructive: String,
    safe: String,
    onDestructive: () -> Unit,
    onSafe: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    // The sheet takes focus, not Cancel: a Mac marks the default button by its fill, and a focus ring on it read as a
    // second, dark button in review. Return and Escape are the sheet's own keys below.
    val sheetFocus = remember { FocusRequester() }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = DownloadsScreenDesktopDefaults.ScrimAlpha))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            // Under the toolbar band, as board D3 hangs it: the sheet belongs to the page, not to the window's title bar.
            .padding(top = DownloadsScreenDesktopDefaults.ToolbarHeight)
            .focusRequester(sheetFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val answers = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.NumPadEnter)
                if (answers) onSafe()
                answers
            },
    ) {
        // Hangs from the window's top edge, so only its lower corners are round. On `surface`, not `raised`, so the
        // secondary Delete, which is `raised`, reads as a button.
        val large = CloudStreamTheme.shapes.large
        val shape = (large as? CornerBasedShape)?.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)) ?: large
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .width(DownloadsScreenDesktopDefaults.SheetWidth)
                .background(colors.surface, shape)
                .border(CloudStreamDimens.Hairline, colors.line, shape)
                .padding(DesktopDimens.WindowMargin),
        ) {
            BasicText(
                text = title,
                style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
            )
            BasicText(
                text = body,
                style = CloudStreamTheme.typography.body.copy(color = colors.ink2),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = DesktopDimens.RelatedGap),
            ) {
                DesktopButton(label = destructive, onClick = onDestructive)
                DesktopButton(label = safe, onClick = onSafe, primary = true)
            }
        }
    }
    LaunchedEffect(Unit) { sheetFocus.requestFocus() }
}
