package com.lagradost.cloudstream4.feature.settings.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.poster_size_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.random_button_settings
import com.lagradost.cloudstream4.core.strings.generated.resources.search
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_download_folder
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_downloads_at_once
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_downloads_at_once_body
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_pane_appearance
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_pane_playback
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_search_no_results
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_search_no_results_body
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_seek_amount
import com.lagradost.cloudstream4.core.strings.generated.resources.title_downloads
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.settings.SettingsPane
import com.lagradost.cloudstream4.feature.settings.SettingsRow
import com.lagradost.cloudstream4.feature.settings.SettingsSearch
import com.lagradost.cloudstream4.feature.settings.SettingsValues
import org.jetbrains.compose.resources.stringResource

/**
 * The Mac's Settings window (`DS-24` ST1 to ST9), part 1 (`KMP-90`): the sidebar of panes with search above it, and
 * the chosen pane beside it. The window is [SettingsWindowDefaults.Width] wide at every height; the host fixes that.
 *
 * The sidebar runs the window's full height, and the window's own title bar is transparent over it, so the first
 * [SettingsWindowDefaults.TitleBarHeight] of each column is left for the traffic lights and the pane's name.
 */
@Composable
fun SettingsWindowDesktop(
    values: SettingsValues,
    folder: DownloadFolder,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    state: SettingsWindowState = remember { SettingsWindowState() },
) {
    val colors = CloudStreamTheme.colors
    val words = rowWords(folder)
    val titles = SettingsPane.entries.associateWith { paneName(it) }
    val panes = SettingsSearch.panes(state.query, { words.getValue(it) }, { titles.getValue(it) })
    Row(modifier.fillMaxSize().background(colors.bg)) {
        Sidebar(
            query = state.query,
            onQuery = { state.query = it },
            panes = panes,
            selected = state.pane,
            onSelect = { state.pane = it },
        )
        PaneColumn(
            pane = state.pane,
            marked = SettingsSearch.rows(state.pane, state.query) { words.getValue(it) },
            values = values,
            folder = folder,
            onAction = onAction,
        )
    }
}

/**
 * Which pane is shown and what the search field holds. Only the window's own state: no setting lives here. Hoisted so
 * a host or a test can set it; the pane stays where it was while a search runs, as `DS-24` ST7 draws it.
 */
@Stable
class SettingsWindowState(pane: SettingsPane = SettingsPane.entries.first(), query: String = "") {
    var pane: SettingsPane by mutableStateOf(pane)
    var query: String by mutableStateOf(query)
}

/** What the window asks its host to do. Each setting change is written at once; there is no Apply. */
sealed interface SettingsAction {
    data class SetShowRandom(val on: Boolean) : SettingsAction
    data class SetPosterSize(val size: PosterSize) : SettingsAction

    /** Always one of `SettingsValues.SEEK_SECONDS`: the pop-up offers nothing else. */
    data class SetSeekSeconds(val seconds: Int) : SettingsAction

    data class SetDownloadsAtOnce(val count: Int) : SettingsAction

    /** Opens the download folder in Finder. Offered only while the folder exists. */
    data object RevealDownloadFolder : SettingsAction
}

/**
 * The download folder as the window may show it: its name only, never a path (`DS-24` ST6).
 *
 * @param exists false while the folder is missing, as on a disk that is not connected. Show in Finder is then not offered.
 */
data class DownloadFolder(val name: String, val exists: Boolean)

/** The layout's own sizes: the boards' at 1 px = 1 dp, on the 4 dp step. */
object SettingsWindowDefaults {
    /** The whole window, fixed at every height. Sidebar plus pane. */
    val Width: Dp = 760.dp
    val Height: Dp = 600.dp

    /** The shortest the window goes; the pane scrolls below that (`DS-24` ST9). */
    val MinHeight: Dp = 480.dp
    val SidebarWidth: Dp = 216.dp

    /** The band the traffic lights and the pane's name sit in. */
    val TitleBarHeight: Dp = 52.dp

    /** The pane's name, from the top of the window. Centres its line box in [TitleBarHeight]. */
    val TitleTop: Dp = 12.dp

    /** Where the sidebar's list starts, below the search field. */
    val NavTop: Dp = 100.dp

    /** A pane's name in the sidebar: the compact control height. Mirrors `DesktopDimens.RowControlHeight`. */
    val NavItemHeight: Dp = 32.dp

    /** Between a title and the line under it, where the two are not one row. Mirrors `DesktopDimens.RelatedGap / 2`. */
    val LineGap: Dp = 4.dp
}

@Composable
private fun Sidebar(
    query: String,
    onQuery: (String) -> Unit,
    panes: List<SettingsPane>,
    selected: SettingsPane,
    onSelect: (SettingsPane) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    Box(
        Modifier
            .width(SettingsWindowDefaults.SidebarWidth)
            .fillMaxHeight()
            .background(colors.surface),
    ) {
        SearchField(
            query = query,
            onQuery = onQuery,
            modifier = Modifier
                .padding(top = SettingsWindowDefaults.TitleBarHeight)
                .padding(horizontal = DesktopDimens.RowPadding),
        )
        Column(
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .padding(top = SettingsWindowDefaults.NavTop)
                .padding(horizontal = DesktopDimens.RelatedGap)
                .fillMaxWidth(),
        ) {
            if (panes.isEmpty()) {
                NoResults(query)
            } else {
                panes.forEach { pane -> NavItem(paneName(pane), pane == selected) { onSelect(pane) } }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val placeholder = stringResource(Res.string.search)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier
            .fillMaxWidth()
            .height(DesktopDimens.RowControlHeight)
            .background(colors.raised, CloudStreamTheme.shapes.medium)
            .padding(horizontal = DesktopDimens.RelatedGap),
    ) {
        CloudStreamIcon(CloudStreamIcons.Search, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = typography.body.copy(color = colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(colors.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .onPreviewKeyEvent { event ->
                        // Escape empties a field with something in it, as in every Mac search field.
                        val clears = event.key == Key.Escape && event.type == KeyEventType.KeyDown && query.isNotEmpty()
                        if (clears) onQuery("")
                        clears
                    },
            )
            if (query.isEmpty()) {
                BasicText(placeholder, style = typography.body.copy(color = colors.muted), maxLines = 1)
            }
        }
    }
}

@Composable
private fun NoResults(query: String) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Column(Modifier.padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap)) {
        BasicText(
            UiString.Resource(Res.string.settings_search_no_results, listOf(query.trim())).resolve(),
            style = typography.label.copy(color = colors.ink),
        )
        BasicText(
            stringResource(Res.string.settings_search_no_results_body),
            style = typography.caption.copy(color = colors.muted),
            modifier = Modifier.padding(top = SettingsWindowDefaults.LineGap),
        )
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (selected) colors.raised else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .heightIn(min = SettingsWindowDefaults.NavItemHeight)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        val style = CloudStreamTheme.typography.label
        BasicText(
            label,
            style = if (selected) style.copy(color = colors.ink) else style.copy(color = colors.ink2),
            maxLines = 1,
        )
    }
}

@Composable
private fun PaneColumn(
    pane: SettingsPane,
    marked: Set<SettingsRow>,
    values: SettingsValues,
    folder: DownloadFolder,
    onAction: (SettingsAction) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    Box(Modifier.fillMaxSize()) {
        BasicText(
            paneName(pane),
            style = CloudStreamTheme.typography.label.copy(color = colors.ink),
            maxLines = 1,
            modifier = Modifier
                .padding(top = SettingsWindowDefaults.TitleTop)
                .padding(horizontal = DesktopDimens.WindowMargin),
        )
        Column(
            modifier = Modifier
                .padding(top = SettingsWindowDefaults.TitleBarHeight)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = DesktopDimens.WindowMargin, end = DesktopDimens.WindowMargin, bottom = DesktopDimens.WindowMargin),
        ) {
            when (pane) {
                SettingsPane.Appearance -> AppearancePane(values, marked, onAction)
                SettingsPane.Playback -> PlaybackPane(values, marked, onAction)
                SettingsPane.Downloads -> DownloadsPane(values, folder, marked, onAction)
            }
        }
    }
}

@Composable
private fun paneName(pane: SettingsPane): String = when (pane) {
    SettingsPane.Appearance -> stringResource(Res.string.settings_pane_appearance)
    SettingsPane.Playback -> stringResource(Res.string.settings_pane_playback)
    SettingsPane.Downloads -> stringResource(Res.string.title_downloads)
}

/** Each row's words as search reads them: what the row shows, in the current language. */
@Composable
private fun rowWords(folder: DownloadFolder): Map<SettingsRow, List<String>> = mapOf(
    // No line under it: the board's "On Home and Library." is untrue on the Mac, whose Library has no Random.
    SettingsRow.PosterSize to listOf(stringResource(Res.string.poster_size_settings)),
    SettingsRow.RandomButton to listOf(stringResource(Res.string.random_button_settings)),
    SettingsRow.SeekAmount to listOf(stringResource(Res.string.settings_seek_amount)),
    SettingsRow.DownloadFolder to listOf(stringResource(Res.string.settings_download_folder), folder.name),
    SettingsRow.DownloadsAtOnce to listOf(
        stringResource(Res.string.settings_downloads_at_once),
        stringResource(Res.string.settings_downloads_at_once_body),
    ),
)
