package com.lagradost.cloudstream4.extensions.repositories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.anime
import com.lagradost.cloudstream4.core.strings.generated.resources.asian_drama
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.cartoons
import com.lagradost.cloudstream4.core.strings.generated.resources.documentaries
import com.lagradost.cloudstream4.core.strings.generated.resources.duplicate_add
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_install
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_installed
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_remove_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_add_group
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_cant_reach_body
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_cant_reach_title
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_empty_title
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_already
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_local
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_newer
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_not_https
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_not_repository
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_not_saved
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_shortcode
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_too_large
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_error_unreachable
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_header
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_link_help
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_link_hint
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_reading
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_remove_body
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_remove_body_none
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_remove_title
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_repo_unreachable_line
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_failed_download
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_failed_local
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_failed_not_saved
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_failed_size
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_failed_too_large
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_hash_mismatch
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_installing
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_installing_unknown
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_turned_off
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_unverified
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_update
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_row_version
import com.lagradost.cloudstream4.core.strings.generated.resources.ext_size_megabytes
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_blocked
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_hash_mismatch
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_held
import com.lagradost.cloudstream4.core.strings.generated.resources.extension_status_kept_off
import com.lagradost.cloudstream4.core.strings.generated.resources.extensions
import com.lagradost.cloudstream4.core.strings.generated.resources.extensions_waiting_count
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back_img_des
import com.lagradost.cloudstream4.core.strings.generated.resources.livestreams
import com.lagradost.cloudstream4.core.strings.generated.resources.movies
import com.lagradost.cloudstream4.core.strings.generated.resources.nsfw
import com.lagradost.cloudstream4.core.strings.generated.resources.others
import com.lagradost.cloudstream4.core.strings.generated.resources.ova
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_extension_review
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_offline_repos
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_remove
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_repo_summary
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_repositories
import com.lagradost.cloudstream4.core.strings.generated.resources.torrent
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series
import com.lagradost.cloudstream4.core.strings.generated.resources.update
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.extensions.untrustedText
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What the Extensions pane asks its host to do (`KMP-110c`). [ExtensionsPaneModel] does each one. */
sealed interface ExtensionsPaneAction {
    data class Add(val text: String) : ExtensionsPaneAction

    data object CancelAdd : ExtensionsPaneAction

    /** The field's text changed; the model holds it. */
    data class Editing(val text: String) : ExtensionsPaneAction

    data class Open(val key: String) : ExtensionsPaneAction

    data object Close : ExtensionsPaneAction

    data object Retry : ExtensionsPaneAction

    data class Install(val key: String, val internalName: String) : ExtensionsPaneAction

    data class CancelInstall(val key: String, val internalName: String) : ExtensionsPaneAction

    data class RemoveExtension(val key: String, val internalName: String) : ExtensionsPaneAction

    data class RemoveRepository(val key: String) : ExtensionsPaneAction

    /** Opens the review of every held extension: `ExtensionReviewScreen`, in the main window. */
    data object Review : ExtensionsPaneAction
}

/**
 * The Settings → Extensions pane, from `DS-27`'s X1 to X8 (`KMP-110c`). Words only, as the other panes are, with the
 * pack's glyphs where the boards name one. Every name and description a repository sent goes through
 * [untrustedText] before it is drawn: it is a third party's text in the app's own voice.
 *
 * @param installedFrom how many extensions a repository's removal takes with it, for the Remove sheet (X3b).
 * @param focusLink the field takes focus when the pane opens with nothing added (X1), or when Home asked for it (X6).
 */
@Composable
fun ExtensionsPaneDesktop(
    state: ExtensionsPaneState,
    onAction: (ExtensionsPaneAction) -> Unit,
    installedFrom: (String) -> Int,
    modifier: Modifier = Modifier,
    focusLink: Boolean = state.repositories.isEmpty(),
) {
    var removing by remember { mutableStateOf<RepositoryRow?>(null) }
    Box(modifier.fillMaxSize()) {
        val open = state.open
        if (open == null) {
            PaneList(state, onAction, focusLink, onRemove = { removing = it })
        } else {
            RepositoryPane(open, state.offline, onAction)
        }
        removing?.let { row ->
            RemoveSheet(
                name = row.name.untrustedText(),
                installed = installedFrom(row.key),
                onRemove = {
                    removing = null
                    onAction(ExtensionsPaneAction.RemoveRepository(row.key))
                },
                onCancel = { removing = null },
            )
        }
    }
}

@Composable
private fun PaneTitle(title: String, onBack: (() -> Unit)? = null) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .padding(top = if (onBack == null) SettingsWindowDefaults.TitleTop else ExtensionsPaneDefaults.BackTop)
            .padding(horizontal = DesktopDimens.WindowMargin),
    ) {
        if (onBack != null) {
            DesktopIconButton(
                icon = CloudStreamIcons.Back,
                contentDescription = stringResource(Res.string.go_back_img_des),
                onClick = onBack,
                tone = DesktopButtonTone.Quiet,
            )
        }
        BasicText(
            title,
            style = CloudStreamTheme.typography.label.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PaneBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        content = content,
        modifier = Modifier
            .padding(top = SettingsWindowDefaults.TitleBarHeight)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = DesktopDimens.WindowMargin, end = DesktopDimens.WindowMargin, bottom = DesktopDimens.WindowMargin),
    )
}

@Composable
private fun PaneList(
    state: ExtensionsPaneState,
    onAction: (ExtensionsPaneAction) -> Unit,
    focusLink: Boolean,
    onRemove: (RepositoryRow) -> Unit,
) {
    PaneTitle(stringResource(Res.string.extensions))
    PaneBody {
        if (state.offline) {
            Notice(
                glyph = CloudStreamIcons.Offline,
                title = stringResource(Res.string.search_state_offline_headline),
                body = UiString.Resource(Res.string.settings_offline_repos, listOf(when_(state.listsFromMillis))).resolve(),
            )
        }
        if (state.waiting > 0) {
            Group {
                SingleRow(UiString.Resource(Res.string.extensions_waiting_count, listOf(state.waiting)).resolve()) {
                    DesktopButton(stringResource(Res.string.settings_extension_review), onClick = { onAction(ExtensionsPaneAction.Review) })
                }
            }
        }
        GroupHeader(stringResource(Res.string.ext_repo_add_group))
        Group { AddRepository(state.add, state.link, offline = state.offline, focus = focusLink, onAction = onAction) }
        GroupHeader(stringResource(Res.string.settings_repositories))
        if (state.removeFailed) WarningLine(stringResource(Res.string.ext_remove_failed))
        Group {
            if (state.repositories.isEmpty()) {
                TwoLines(stringResource(Res.string.ext_repo_empty_title), stringResource(Res.string.ext_repo_empty_body))
            } else {
                state.repositories.forEach { row ->
                    RepositoryListRow(row, onOpen = { onAction(ExtensionsPaneAction.Open(row.key)) }, onRemove = { onRemove(row) })
                }
            }
        }
    }
}

/** X1, X2a, X2b: the field, Add (or Cancel while reading), and the line under them. The link stays after a failure. */
@Composable
private fun AddRepository(add: AddState, link: String, offline: Boolean, focus: Boolean, onAction: (ExtensionsPaneAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val reading = add == AddState.Reading
    val fieldFocus = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val submit = { if (link.isNotBlank() && !reading && !offline) onAction(ExtensionsPaneAction.Add(link)) }
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.fillMaxWidth().padding(DesktopDimens.RowPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
            val hint = stringResource(Res.string.ext_repo_link_hint)
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DesktopDimens.ToolbarFieldHeight)
                    .background(colors.bg, shape)
                    .border(CloudStreamDimens.Hairline, if (focused) colors.ink2 else colors.line, shape)
                    .padding(horizontal = DesktopDimens.RowPadding),
            ) {
                if (link.isEmpty()) BasicText(hint, style = typography.body.copy(color = colors.muted), maxLines = 1)
                BasicTextField(
                    value = link,
                    onValueChange = { onAction(ExtensionsPaneAction.Editing(it)) },
                    enabled = !reading && !offline,
                    textStyle = typography.body.copy(color = if (reading) colors.muted else colors.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.ink),
                    interactionSource = source,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .semantics { contentDescription = hint }
                        .onPreviewKeyEvent { event ->
                            val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            if (enter) submit()
                            enter
                        },
                )
            }
            when {
                reading -> DesktopButton(stringResource(Res.string.cancel), onClick = { onAction(ExtensionsPaneAction.CancelAdd) })
                else -> DesktopButton(
                    stringResource(Res.string.duplicate_add),
                    onClick = submit,
                    icon = CloudStreamIcons.Add,
                    // The one primary in the pane, and only while the field holds a link (X1, X3).
                    primary = link.isNotBlank() && !offline,
                )
            }
        }
        when (add) {
            AddState.Reading -> BasicText(stringResource(Res.string.ext_repo_reading), style = typography.caption.copy(color = colors.muted))
            is AddState.Failed -> WarningLine(addFailure(add.failure))
            AddState.Idle -> BasicText(stringResource(Res.string.ext_repo_link_help), style = typography.caption.copy(color = colors.muted))
        }
    }
    LaunchedEffect(focus) { if (focus) runCatching { fieldFocus.requestFocus() } }
}

@Composable
private fun addFailure(failure: AddFailure): String = when (failure) {
    AddFailure.NotHttps -> stringResource(Res.string.ext_repo_error_not_https)
    AddFailure.PrivateAddress -> stringResource(Res.string.ext_repo_error_local)
    AddFailure.TooLarge -> stringResource(Res.string.ext_repo_error_too_large)
    AddFailure.NewerFormat -> stringResource(Res.string.ext_repo_error_newer)
    AddFailure.NotARepository, AddFailure.Invalid -> stringResource(Res.string.ext_repo_error_not_repository)
    AddFailure.Unreachable -> stringResource(Res.string.ext_repo_error_unreachable)
    AddFailure.Shortcode -> stringResource(Res.string.ext_repo_error_shortcode)
    AddFailure.NotSaved -> stringResource(Res.string.ext_repo_error_not_saved)
    is AddFailure.AlreadyAdded -> UiString.Resource(Res.string.ext_repo_error_already, listOf(failure.name.untrustedText())).resolve()
}

/** X3: the repository's own name, and "N extensions · updated X", or that it cannot be reached (X5a). No link. */
@Composable
private fun RepositoryListRow(row: RepositoryRow, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val line = if (row.reachable) {
        UiString.Plural(Res.plurals.settings_repo_summary, row.extensions, listOf(row.extensions, when_(row.readAtMillis))).resolve()
    } else {
        UiString.Resource(Res.string.ext_repo_unreachable_line, listOf(when_(row.readAtMillis))).resolve()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(source)
            .background(if (hovered) colors.raised else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .heightIn(min = ExtensionsPaneDefaults.TwoLineRowHeight)
            .padding(DesktopDimens.RowPadding),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ExtensionsPaneDefaults.LeadSize).background(colors.raised, CloudStreamTheme.shapes.medium),
        ) { CloudStreamIcon(CloudStreamIcons.Repository, null, size = CloudStreamIconSize.Small, tint = colors.ink2) }
        Column(Modifier.weight(1f)) {
            BasicText(row.name.untrustedText(), style = typography.label.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
                if (!row.reachable) CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink2)
                BasicText(line, style = typography.caption.copy(color = if (row.reachable) colors.muted else colors.ink2))
            }
        }
        if (hovered) {
            DesktopIconButton(
                icon = CloudStreamIcons.Delete,
                contentDescription = stringResource(Res.string.settings_remove),
                onClick = onRemove,
                tone = DesktopButtonTone.Quiet,
                size = ExtensionsPaneDefaults.RowVerbSize,
                iconSize = CloudStreamIconSize.Small,
            )
        }
        CloudStreamIcon(CloudStreamIcons.Disclosure, null, size = CloudStreamIconSize.Small, tint = colors.muted)
    }
}

/** X4a, X4b, X5a: one repository opened in the pane, Back in its bar. Back, ⌘[ and Escape return to the list. */
@Composable
private fun RepositoryPane(open: OpenRepository, offline: Boolean, onAction: (ExtensionsPaneAction) -> Unit) {
    val name = open.name.untrustedText()
    val close = { onAction(ExtensionsPaneAction.Close) }
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val back = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Escape || (event.isMetaPressed && event.key == Key.LeftBracket))
                if (back) close()
                back
            }
            .focusable(),
    ) {
        PaneTitle(name, onBack = close)
        PaneBody {
            if (!open.reachable) {
                Notice(
                    glyph = CloudStreamIcons.Warning,
                    title = UiString.Resource(Res.string.ext_repo_cant_reach_title, listOf(name)).resolve(),
                    body = UiString.Resource(Res.string.ext_repo_cant_reach_body, listOf(when_(open.readAtMillis))).resolve(),
                    action = if (offline) null else ({ onAction(ExtensionsPaneAction.Retry) }),
                )
            }
            val installed = open.rows.count { it.state is RowState.Installed || it.state is RowState.UpdateAvailable || it.isHeldLike() }
            val header = UiString.Plural(
                Res.plurals.ext_repo_header,
                open.rows.size,
                listOf(open.rows.size, installed, when_(open.readAtMillis)),
            ).resolve()
            BasicText(
                header,
                style = CloudStreamTheme.typography.caption.copy(color = CloudStreamTheme.colors.muted),
                modifier = Modifier.padding(bottom = DesktopDimens.RelatedGap),
            )
            Group {
                open.rows.forEach { row ->
                    ExtensionListRow(
                        row = row,
                        reachable = open.reachable,
                        onAction = { action -> onAction(action(open.key, row.internalName)) },
                    )
                }
            }
        }
    }
    LaunchedEffect(open.key) { runCatching { focus.requestFocus() } }
}

private fun ExtensionRow.isHeldLike() =
    state == RowState.Held || state == RowState.KeptOff || state == RowState.Blocked || state == RowState.HashMismatch

/** One extension: its name, its state in the second line, and its one verb (X4a, X4b). */
@Composable
private fun ExtensionListRow(
    row: ExtensionRow,
    reachable: Boolean,
    onAction: ((String, String) -> ExtensionsPaneAction) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val details = details(row)
    val (line, warning) = when (val state = row.state) {
        RowState.Available -> details to false
        is RowState.Installing -> installingLine(state) to false
        is RowState.Installed -> (if (state.verified) details else stringResource(Res.string.ext_row_unverified)) to false
        is RowState.UpdateAvailable ->
            (UiString.Resource(Res.string.ext_row_update, listOf(state.from, state.to)).resolve() + sizePart(row.sizeBytes)) to false
        RowState.Held -> stringResource(Res.string.extension_status_held) to false
        RowState.KeptOff -> stringResource(Res.string.extension_status_kept_off) to false
        RowState.Blocked -> stringResource(Res.string.extension_status_blocked) to true
        RowState.HashMismatch -> stringResource(Res.string.extension_status_hash_mismatch) to true
        RowState.NotOffered -> stringResource(Res.string.ext_row_turned_off) to false
        is RowState.Failed -> installFailure(state.failure) to true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(source)
            .heightIn(min = ExtensionsPaneDefaults.TwoLineRowHeight)
            .padding(DesktopDimens.RowPadding),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                row.name.untrustedText(),
                style = typography.label.copy(color = if (row.offered) colors.ink else colors.ink2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
                if (warning) CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink2)
                BasicText(line, style = typography.caption.copy(color = if (warning) colors.ink2 else colors.muted))
            }
        }
        when (val state = row.state) {
            RowState.Available -> if (row.offered) {
                Verb(stringResource(Res.string.ext_install), enabled = reachable) { onAction(ExtensionsPaneAction::Install) }
            }
            is RowState.Installing -> {
                ProgressRule(state)
                DesktopButton(stringResource(Res.string.cancel), onClick = { onAction(ExtensionsPaneAction::CancelInstall) }, tone = DesktopButtonTone.Quiet)
            }
            is RowState.Installed -> {
                if (hovered) {
                    DesktopIconButton(
                        icon = CloudStreamIcons.Delete,
                        contentDescription = stringResource(Res.string.settings_remove),
                        onClick = { onAction(ExtensionsPaneAction::RemoveExtension) },
                        tone = DesktopButtonTone.Quiet,
                        size = ExtensionsPaneDefaults.RowVerbSize,
                        iconSize = CloudStreamIconSize.Small,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
                    CloudStreamIcon(CloudStreamIcons.Done, null, size = CloudStreamIconSize.Small, tint = colors.ink2)
                    BasicText(stringResource(Res.string.ext_installed), style = typography.label.copy(color = colors.ink2), maxLines = 1)
                }
            }
            is RowState.UpdateAvailable -> Verb(stringResource(Res.string.update), enabled = reachable) { onAction(ExtensionsPaneAction::Install) }
            // `DS-27`: off until you choose is the one with a choice to make, so its Review is filled. Kept off, and the
            // two that cannot be allowed (blocked by the scan, or not the file its hash names), are quiet.
            RowState.Held ->
                DesktopButton(stringResource(Res.string.settings_extension_review), onClick = { onAction { _, _ -> ExtensionsPaneAction.Review } })
            RowState.KeptOff, RowState.Blocked, RowState.HashMismatch -> DesktopButton(
                stringResource(Res.string.settings_extension_review),
                onClick = { onAction { _, _ -> ExtensionsPaneAction.Review } },
                tone = DesktopButtonTone.Quiet,
            )
            RowState.NotOffered -> Unit
            is RowState.Failed -> if (row.offered) {
                Verb(stringResource(Res.string.player_source_retry), enabled = reachable, icon = true) { onAction(ExtensionsPaneAction::Install) }
            }
        }
    }
}

/**
 * A row's verb. Off while its repository cannot be reached (X5a): a cached list can name an old hash.
 *
 * Off is the words in `muted` on the secondary fill, with no click, no hover and no focus, and said to be off to a
 * screen reader (DECIDED, PM 2026-11-10): the board's grey, from a token the theme already has, rather than a disabled
 * tone the design system does not.
 */
@Composable
private fun Verb(label: String, enabled: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    if (enabled) {
        DesktopButton(label = label, onClick = onClick, icon = if (icon) CloudStreamIcons.Retry else null)
        return
    }
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .background(colors.raised, CloudStreamTheme.shapes.medium)
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.ButtonPadding)
            .semantics(mergeDescendants = true) { disabled() },
    ) {
        if (icon) CloudStreamIcon(CloudStreamIcons.Retry, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        BasicText(label, style = CloudStreamTheme.typography.label.copy(color = colors.muted), maxLines = 1)
    }
}

/** X4a's 96 rule: what has arrived of what was stated. Its height is a stroke, exempt in its own axis. */
@Composable
private fun ProgressRule(state: RowState.Installing) {
    val colors = CloudStreamTheme.colors
    val fraction = state.stated?.takeIf { it > 0 }?.let { (state.received.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    Box(
        Modifier
            .width(ExtensionsPaneDefaults.ProgressWidth)
            .height(ExtensionsPaneDefaults.ProgressStroke)
            .clip(CloudStreamTheme.shapes.small)
            .background(colors.line),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.ink))
    }
}

@Composable
private fun installingLine(state: RowState.Installing): String = state.stated?.let {
    UiString.Resource(Res.string.ext_row_installing, listOf(bytes(state.received), bytes(it))).resolve()
} ?: UiString.Resource(Res.string.ext_row_installing_unknown, listOf(bytes(state.received))).resolve()

@Composable
private fun installFailure(failure: InstallFailure): String = when (failure) {
    InstallFailure.HashMismatch -> stringResource(Res.string.ext_row_hash_mismatch)
    InstallFailure.SizeMismatch -> stringResource(Res.string.ext_row_failed_size)
    InstallFailure.NotSaved -> stringResource(Res.string.ext_row_failed_not_saved)
    InstallFailure.NotOffered -> stringResource(Res.string.ext_row_turned_off)
    is InstallFailure.Fetch -> when (failure.failure) {
        RepositoryFailure.TooLarge -> stringResource(Res.string.ext_row_failed_too_large)
        RepositoryFailure.PrivateAddress -> stringResource(Res.string.ext_row_failed_local)
        else -> stringResource(Res.string.ext_row_failed_download)
    }
}

/** "v7 · English · Movies, TV series · 1.2 MB": what the extension gives, as its repository says. */
@Composable
private fun details(row: ExtensionRow): String {
    val types = row.tvTypes.mapNotNull { tvTypeName(it) }.distinct()
    return listOfNotNull(
        UiString.Resource(Res.string.ext_row_version, listOf(row.version)).resolve().takeIf { row.version > 0 },
        row.language?.let(::languageName),
        types.joinToString(", ").takeIf { it.isNotEmpty() },
        row.sizeBytes?.let { bytes(it) },
    ).joinToString(" · ")
}

@Composable
private fun sizePart(size: Long?): String = size?.let { " · ${bytes(it)}" }.orEmpty()

/** The repository's `tvTypes` are raw wire names; the ones the app has words for are shown in them, the rest not at all. */
@Composable
private fun tvTypeName(type: String): String? {
    val resource: StringResource = when (type) {
        "Movie", "AnimeMovie" -> Res.string.movies
        "TvSeries" -> Res.string.tv_series
        "Anime" -> Res.string.anime
        "OVA" -> Res.string.ova
        "Cartoon" -> Res.string.cartoons
        "Documentary" -> Res.string.documentaries
        "AsianDrama" -> Res.string.asian_drama
        "Live" -> Res.string.livestreams
        "Torrent" -> Res.string.torrent
        "NSFW" -> Res.string.nsfw
        "Others" -> Res.string.others
        else -> return null
    }
    return stringResource(resource)
}

/** A BCP-47 tag as the language's name in the current language; the JDK's own data, as dates are. */
private fun languageName(tag: String): String? = runCatching {
    Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() }
}.getOrNull()

/** Megabytes with one decimal, in the current locale's digits, and the unit from the strings (`#349` review). */
@Composable
private fun bytes(count: Long): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return UiString.Resource(Res.string.ext_size_megabytes, listOf(format.format(count / BYTES_PER_MEGABYTE))).resolve()
}

/**
 * When a list was read, as a localised short date and time. Relative times ("4 min ago") would need a plural set per
 * unit, which is `I18N-01`'s; `DesktopSearchHistory` shows absolute dates for the same reason.
 */
private fun when_(millis: Long?): String = millis?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }.getOrNull()
}.orEmpty()

@Composable
private fun Notice(glyph: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: (() -> Unit)? = null) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Group {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
            modifier = Modifier.fillMaxWidth().padding(DesktopDimens.RowPadding),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(ExtensionsPaneDefaults.LeadSize).background(colors.raised, CloudStreamTheme.shapes.medium),
            ) { CloudStreamIcon(glyph, null, size = CloudStreamIconSize.Small, tint = colors.ink2) }
            Column(Modifier.weight(1f)) {
                BasicText(title, style = typography.label.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(body, style = typography.caption.copy(color = colors.muted))
            }
            if (action != null) {
                DesktopButton(stringResource(Res.string.player_source_retry), onClick = action, icon = CloudStreamIcons.Retry)
            }
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    val colors = CloudStreamTheme.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
        CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink2)
        BasicText(text, style = CloudStreamTheme.typography.caption.copy(color = colors.ink2))
    }
}

@Composable
private fun SingleRow(text: String, control: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier.fillMaxWidth().heightIn(min = DesktopDimens.RowMinHeight).padding(DesktopDimens.RowPadding),
    ) {
        BasicText(text, style = CloudStreamTheme.typography.label.copy(color = CloudStreamTheme.colors.ink), modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun TwoLines(title: String, line: String) {
    val colors = CloudStreamTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = ExtensionsPaneDefaults.TwoLineRowHeight).padding(DesktopDimens.RowPadding)) {
        BasicText(title, style = CloudStreamTheme.typography.label.copy(color = colors.ink))
        BasicText(line, style = CloudStreamTheme.typography.caption.copy(color = colors.muted))
    }
}

@Composable
private fun GroupHeader(text: String) {
    Box(contentAlignment = Alignment.BottomStart, modifier = Modifier.fillMaxWidth().height(ExtensionsPaneDefaults.HeaderHeight)) {
        BasicText(text, style = CloudStreamTheme.typography.caption.copy(color = CloudStreamTheme.colors.muted), maxLines = 1)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
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
 * X3b: asks before a repository goes, with what goes with it. **Cancel is the primary and comes first** (`DEC-19`);
 * Return and Escape land on it, and only a click removes.
 */
@Composable
private fun RemoveSheet(name: String, installed: Int, onRemove: () -> Unit, onCancel: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val focus = remember { FocusRequester() }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = ExtensionsPaneDefaults.ScrimAlpha))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .onPreviewKeyEvent { event ->
                val safe = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.NumPadEnter)
                if (safe) onCancel()
                safe
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .padding(top = SettingsWindowDefaults.TitleBarHeight)
                .width(ExtensionsPaneDefaults.SheetWidth)
                .background(colors.surface, CloudStreamTheme.shapes.large)
                .border(CloudStreamDimens.Hairline, colors.line, CloudStreamTheme.shapes.large)
                .padding(DesktopDimens.WindowMargin)
                .focusRequester(focus)
                .focusable(),
        ) {
            BasicText(UiString.Resource(Res.string.ext_repo_remove_title, listOf(name)).resolve(), style = typography.railTitle.copy(color = colors.ink))
            BasicText(
                if (installed > 0) {
                    UiString.Plural(Res.plurals.ext_repo_remove_body, installed, listOf(installed)).resolve()
                } else {
                    stringResource(Res.string.ext_repo_remove_body_none)
                },
                style = typography.body.copy(color = colors.ink2),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = DesktopDimens.RelatedGap),
            ) {
                DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel, primary = true)
                DesktopButton(label = stringResource(Res.string.settings_remove), onClick = onRemove, icon = CloudStreamIcons.Delete)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The pane's own sizes, on the 4 dp step. */
object ExtensionsPaneDefaults {
    /** A row with a second line. Mirrors `SettingsPaneDefaults.TwoLineRowHeight`. */
    val TwoLineRowHeight: Dp = 64.dp

    /** A group's name, in a band this tall. Mirrors `SettingsPaneDefaults.HeaderHeight`. */
    val HeaderHeight: Dp = 24.dp

    /** The leading square of a repository row and a notice. Mirrors `DesktopDimens.RowControlHeight`. */
    val LeadSize: Dp = 32.dp

    /** A row's Remove: a 24 cell under the pointer (`DEC-19`). Mirrors `DesktopDimens.FieldActionSize`. */
    val RowVerbSize: Dp = 24.dp

    /** X4a's progress rule. */
    val ProgressWidth: Dp = 96.dp

    /** The rule's height: a stroke, exempt in its own axis. */
    val ProgressStroke: Dp = 4.dp

    /** Where Back sits, so the 36 button centres on the title's line. */
    val BackTop: Dp = 8.dp

    /** X3b: `DS-24`'s sheet, 440 wide. */
    val SheetWidth: Dp = 440.dp

    /** Mirrors `ProfileSheetsDesktopDefaults.ScrimAlpha`. */
    const val ScrimAlpha = 0.48f
}

private const val BYTES_PER_MEGABYTE = 1_000_000.0
