package com.lagradost.cloudstream4.feature.settings.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPopupButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopSwitch
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_show_in_finder
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_download_folder
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_downloads_at_once
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_downloads_at_once_body
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_folder
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_folder_missing
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_play_next
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_play_next_body
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_playing
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_speed
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.settings.SettingsRow
import com.lagradost.cloudstream4.feature.settings.SettingsValues
import org.jetbrains.compose.resources.stringResource

/** `DS-24` ST9's Playback pane, part 1: the one row the player reads today. */
@Composable
internal fun ColumnScope.PlaybackPane(values: SettingsValues, marked: Set<SettingsRow>, onAction: (SettingsAction) -> Unit) {
    GroupHeader(stringResource(Res.string.settings_playing))
    Group {
        val title = stringResource(Res.string.settings_play_next)
        SettingRow(
            title = title,
            line = stringResource(Res.string.settings_play_next_body),
            marked = SettingsRow.PlayNextEpisode in marked,
        ) {
            DesktopSwitch(
                checked = values.autoplayNext,
                onCheckedChange = { onAction(SettingsAction.SetAutoplayNext(it)) },
                label = title,
            )
        }
    }
}

/** `DS-24` ST6's Downloads pane, part 1: the folder by its name, and how many download at once. */
@Composable
internal fun ColumnScope.DownloadsPane(
    values: SettingsValues,
    folder: DownloadFolder,
    marked: Set<SettingsRow>,
    onAction: (SettingsAction) -> Unit,
) {
    GroupHeader(stringResource(Res.string.settings_folder))
    Group {
        SettingRow(
            title = stringResource(Res.string.settings_download_folder),
            line = if (folder.exists) {
                folder.name
            } else {
                UiString.Resource(Res.string.settings_folder_missing, listOf(folder.name)).resolve()
            },
            warning = !folder.exists,
            marked = SettingsRow.DownloadFolder in marked,
            lead = { FolderLead(warning = !folder.exists) },
        ) {
            // Missing, there is nothing to show: the button is not offered rather than offered dead.
            if (folder.exists) {
                DesktopButton(
                    label = stringResource(Res.string.downloads_show_in_finder),
                    onClick = { onAction(SettingsAction.RevealDownloadFolder) },
                    icon = CloudStreamIcons.Folder,
                    modifier = Modifier.height(DesktopDimens.RowControlHeight),
                )
            }
        }
    }
    GroupHeader(stringResource(Res.string.settings_speed))
    Group {
        val title = stringResource(Res.string.settings_downloads_at_once)
        SettingRow(
            title = title,
            line = stringResource(Res.string.settings_downloads_at_once_body),
            marked = SettingsRow.DownloadsAtOnce in marked,
        ) {
            DesktopPopupButton(
                selected = values.downloadsAtOnce,
                options = SettingsValues.DOWNLOADS_AT_ONCE.toList(),
                onSelect = { onAction(SettingsAction.SetDownloadsAtOnce(it)) },
                label = title,
                optionLabel = { it.toString() },
            )
        }
    }
}

/** The sizes the panes share. The boards' at 1 px = 1 dp. */
object SettingsPaneDefaults {
    /** A group's name, in a band this tall above it. */
    val HeaderHeight: Dp = 24.dp

    /** A row with a second line. A single-line row is `DesktopDimens.RowMinHeight`. */
    val TwoLineRowHeight: Dp = 64.dp

    /** The leading square some rows carry. Mirrors `DesktopDimens.RowControlHeight`. */
    val LeadSize: Dp = 32.dp
}

@Composable
private fun GroupHeader(text: String) {
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier.fillMaxWidth().height(SettingsPaneDefaults.HeaderHeight),
    ) {
        BasicText(
            text,
            style = CloudStreamTheme.typography.caption.copy(color = CloudStreamTheme.colors.muted),
            maxLines = 1,
        )
    }
}

@Composable
private fun ColumnScope.Group(content: @Composable ColumnScope.() -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    Column(
        content = content,
        modifier = Modifier
            .padding(top = DesktopDimens.RelatedGap, bottom = DesktopDimens.GroupGap)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape),
    )
}

/**
 * A setting: its name, an optional second line, and its control at the trailing end.
 *
 * @param marked a search matched this row, so it carries the raised fill until the search changes.
 * @param warning the second line says something is wrong, so it reads in `ink2` rather than `muted`.
 */
@Composable
private fun SettingRow(
    title: String,
    line: String?,
    marked: Boolean,
    warning: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (marked) colors.raised else Color.Transparent)
            .heightIn(min = if (line == null) DesktopDimens.RowMinHeight else SettingsPaneDefaults.TwoLineRowHeight)
            .padding(DesktopDimens.RowPadding),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                style = typography.label.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line != null) {
                BasicText(line, style = typography.caption.copy(color = if (warning) colors.ink2 else colors.muted))
            }
        }
        control()
    }
}

@Composable
private fun FolderLead(warning: Boolean) {
    val colors = CloudStreamTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SettingsPaneDefaults.LeadSize)
            .background(colors.raised, CloudStreamTheme.shapes.medium),
    ) {
        CloudStreamIcon(
            if (warning) CloudStreamIcons.Warning else CloudStreamIcons.Folder,
            null,
            size = CloudStreamIconSize.Small,
            tint = colors.ink2,
        )
    }
}
