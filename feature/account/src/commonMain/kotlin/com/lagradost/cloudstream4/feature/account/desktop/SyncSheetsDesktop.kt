package com.lagradost.cloudstream4.feature.account.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_browser_body
import com.lagradost.cloudstream4.core.strings.generated.resources.account_browser_open_again
import com.lagradost.cloudstream4.core.strings.generated.resources.account_browser_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_browser_waiting
import com.lagradost.cloudstream4.core.strings.generated.resources.account_code_expired
import com.lagradost.cloudstream4.core.strings.generated.resources.account_code_expires
import com.lagradost.cloudstream4.core.strings.generated.resources.account_code_instructions
import com.lagradost.cloudstream4.core.strings.generated.resources.account_code_open
import com.lagradost.cloudstream4.core.strings.generated.resources.account_email
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_code
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_new_code
import com.lagradost.cloudstream4.core.strings.generated.resources.account_pair_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_password
import com.lagradost.cloudstream4.core.strings.generated.resources.account_password_mismatch
import com.lagradost.cloudstream4.core.strings.generated.resources.account_password_note
import com.lagradost.cloudstream4.core.strings.generated.resources.account_signin_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.account_signin_failed_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_signin_not_kept
import com.lagradost.cloudstream4.core.strings.generated.resources.account_signin_not_removed
import com.lagradost.cloudstream4.core.strings.generated.resources.account_signin_offline_body
import com.lagradost.cloudstream4.core.strings.generated.resources.account_signins_until_quit
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_connected
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_note
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_remove
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_remove_body
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_remove_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_unavailable
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.library_not_connected
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_remove
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_sign_in
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_subtitle_services
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_sync_for
import com.lagradost.cloudstream4.core.strings.generated.resources.settings_sync_per_profile
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.strings.generated.resources.show
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.jetbrains.compose.resources.stringResource

/** A service's state as A9 reads it: by presence only, never an account name or an id. */
enum class SyncStatus { Connected, NotConnected, Unavailable }

/** One row of A9. [id] is the host's, for focus to come back to the row that opened a sign-in (A12). */
data class SyncRowState(
    val id: String,
    val name: String,
    val status: SyncStatus,
    val onSignIn: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * `DS-25` A9 and A12: **Sync for <profile>** (`KMP-95d`), the current profile's sign-ins, read by presence: Connected
 * or Not connected, never an account name or an id. Each row's one button signs in or removes (A14 asks first).
 *
 * - [syncRows] are the sync services and [subtitleRows] the subtitle services, under their own heading. The Mac's
 *   Settings has no subtitle services yet, so A16 is reached from here (a difference from the board, reported).
 * - **Sign-ins last until you quit** (D3): the line says so under the note, while sign-ins are kept in memory only.
 *   [untilQuit] is false on a signed build, whose sign-ins are in the Keychain (`KMP-95f`), and the line is not drawn.
 * - [focusOn] is the row a cancelled or finished sign-in came from: its button takes focus again (A12).
 */
@Composable
fun SyncServicesSheet(
    profileName: String,
    syncRows: List<SyncRowState>,
    subtitleRows: List<SyncRowState>,
    onDone: () -> Unit,
    focusOn: String? = null,
    untilQuit: Boolean = true,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val sheetFocus = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    SheetFrame(onEscape = onDone, modifier = Modifier.focusRequester(sheetFocus).focusable()) {
        Column(verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap)) {
            BasicText(UiString.Resource(Res.string.settings_sync_for, listOf(profileName)).resolve(), style = typography.railTitle.copy(color = colors.ink))
            BasicText(
                stringResource(Res.string.settings_sync_per_profile) + " " + stringResource(Res.string.account_sync_note),
                style = typography.label.copy(color = colors.muted),
            )
            if (untilQuit) UntilQuitLine()
        }
        Column {
            syncRows.forEachIndexed { index, row ->
                if (index > 0) SheetRule()
                FocusedRow(row, rowFocus)
            }
        }
        if (subtitleRows.isNotEmpty()) {
            Column {
                BasicText(stringResource(Res.string.settings_subtitle_services), style = typography.label.copy(color = colors.muted))
                subtitleRows.forEachIndexed { index, row ->
                    if (index > 0) SheetRule()
                    FocusedRow(row, rowFocus)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            DesktopButton(label = stringResource(Res.string.setup_done), onClick = onDone, primary = true)
        }
    }
    LaunchedEffect(focusOn) {
        val target = focusOn?.let { rowFocus[it] }
        runCatching { (target ?: sheetFocus).requestFocus() }
    }
}

/** A row whose button can take focus back (A12): its requester is remembered with it, and known to the sheet while shown. */
@Composable
private fun FocusedRow(row: SyncRowState, rowFocus: MutableMap<String, FocusRequester>) {
    key(row.id) {
        val focus = remember { FocusRequester() }
        DisposableEffect(focus) {
            rowFocus[row.id] = focus
            onDispose { rowFocus.remove(row.id) }
        }
        SyncRow(row, focus)
    }
}

@Composable
private fun SyncRow(row: SyncRowState, focus: FocusRequester) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val line = when (row.status) {
        SyncStatus.Connected -> stringResource(Res.string.account_sync_connected)
        SyncStatus.NotConnected -> stringResource(Res.string.library_not_connected)
        SyncStatus.Unavailable -> stringResource(Res.string.account_sync_unavailable)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.fillMaxWidth().heightIn(min = SyncSheetsDesktopDefaults.RowHeight),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap)) {
            BasicText(row.name, style = typography.body.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(line, style = typography.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (row.status) {
            SyncStatus.Connected -> DesktopButton(
                label = stringResource(Res.string.account_sync_remove),
                onClick = row.onRemove,
                modifier = Modifier.focusRequester(focus),
            )
            SyncStatus.NotConnected -> DesktopButton(
                label = stringResource(Res.string.settings_sign_in),
                onClick = row.onSignIn,
                modifier = Modifier.focusRequester(focus),
            )
            SyncStatus.Unavailable -> Unit
        }
    }
}

/**
 * `DS-25` A11 and A11b: **Pair Simkl** with a code (`KMP-95d`). The code is approved in any browser, so no redirect is
 * needed. The host polls while this shows; every way out stops the poll (`BUG-49`).
 *
 * [qr] is a QR of the page's address, never of the code, beside it as the board draws it; dimmed once [expired].
 *
 * @param code shown because it must be read; a short-lived credential, never logged.
 * @param address the page to approve it on, as shown: no scheme.
 * @param secondsLeft the countdown, or null once [expired].
 * @param expired A11b: the code is struck through and nothing polls; a new code is the one primary.
 * @param untilQuit draws "Sign-ins last until you quit"; false on a signed build (`KMP-95f`).
 */
@Composable
fun PairCodeSheet(
    serviceName: String,
    code: String,
    address: String,
    secondsLeft: Int?,
    expired: Boolean,
    onOpen: () -> Unit,
    onNewCode: () -> Unit,
    onCancel: () -> Unit,
    qr: ImageBitmap? = null,
    untilQuit: Boolean = true,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val sheetFocus = remember { FocusRequester() }
    SheetFrame(onEscape = onCancel, modifier = Modifier.focusRequester(sheetFocus).focusable()) {
        BasicText(UiString.Resource(Res.string.account_pair_title, listOf(serviceName)).resolve(), style = typography.railTitle.copy(color = colors.ink))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
            if (qr != null) {
                Box(
                    Modifier
                        .size(SyncSheetsDesktopDefaults.QrSize)
                        // White whatever the theme: a code has to scan.
                        .background(Color.White, CloudStreamTheme.shapes.medium)
                        .padding(SyncSheetsDesktopDefaults.QrQuietZone)
                        .testTag(PAIR_QR_TAG),
                ) {
                    Image(
                        bitmap = qr,
                        contentDescription = null,
                        filterQuality = FilterQuality.None,
                        alpha = if (expired) SyncSheetsDesktopDefaults.ExpiredQrAlpha else 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
                BasicText(stringResource(Res.string.account_pair_code), style = typography.label.copy(color = colors.muted))
                BasicText(
                    code,
                    style = typography.h1.copy(
                        color = if (expired) colors.muted else colors.ink,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = SyncSheetsDesktopDefaults.CodeTracking,
                        textDecoration = if (expired) TextDecoration.LineThrough else null,
                    ),
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = code },
                )
                if (expired) {
                    BasicText(stringResource(Res.string.account_code_expired), style = typography.body.copy(color = colors.ink2))
                } else {
                    BasicText(UiString.Resource(Res.string.account_code_instructions, listOf(address)).resolve(), style = typography.body.copy(color = colors.ink2))
                    if (secondsLeft != null) {
                        BasicText(
                            UiString.Resource(Res.string.account_code_expires, listOf(countdown(secondsLeft))).resolve(),
                            style = typography.caption.copy(color = colors.muted),
                        )
                    }
                }
            }
        }
        if (!expired) LinkAction(UiString.Resource(Res.string.account_code_open, listOf(address)).resolve(), onOpen)
        if (untilQuit) UntilQuitLine()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (expired) {
                Spacer(Modifier.weight(1f))
                DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel)
                DesktopButton(label = stringResource(Res.string.account_pair_new_code), onClick = onNewCode, primary = true, icon = CloudStreamIcons.Retry)
            } else {
                DesktopButton(label = stringResource(Res.string.account_pair_new_code), onClick = onNewCode, icon = CloudStreamIcons.Retry)
                Spacer(Modifier.weight(1f))
                DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { sheetFocus.requestFocus() } }
}

/**
 * `DS-25` A10: signing in through the browser (`KMP-95e`). The page opens on its own; the service sends the browser back
 * to `cloudstreamapp://`, and the host closes this sheet. There is no password field: the app never sees one.
 * Open again opens the same page. Cancel ends the wait at once (A12). [untilQuit] is false on a signed build (`KMP-95f`).
 */
@Composable
fun BrowserSignInSheet(serviceName: String, onOpenAgain: () -> Unit, onCancel: () -> Unit, untilQuit: Boolean = true) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val sheetFocus = remember { FocusRequester() }
    SheetFrame(onEscape = onCancel, modifier = Modifier.focusRequester(sheetFocus).focusable()) {
        BasicText(UiString.Resource(Res.string.account_browser_title, listOf(serviceName)).resolve(), style = typography.railTitle.copy(color = colors.ink))
        Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
            BasicText(UiString.Resource(Res.string.account_browser_body, listOf(serviceName)).resolve(), style = typography.body.copy(color = colors.ink2))
            BasicText(stringResource(Res.string.account_browser_waiting), style = typography.caption.copy(color = colors.muted))
            if (untilQuit) UntilQuitLine()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            DesktopButton(label = stringResource(Res.string.account_browser_open_again), onClick = onOpenAgain, icon = CloudStreamIcons.OpenExternal)
            Spacer(Modifier.weight(1f))
            DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel)
        }
    }
    LaunchedEffect(Unit) { runCatching { sheetFocus.requestFocus() } }
}

/**
 * `DS-25` A13 and A13b: a sign-in that failed, or could not start offline (`KMP-95d`). No detail from the service is
 * shown. Try again is the primary and starts the sign-in afresh. [notKept]: the service said yes, but the Keychain did
 * not keep it, so nothing is signed in (`KMP-95f`).
 */
@Composable
fun SignInFailedSheet(serviceName: String, offline: Boolean, onRetry: () -> Unit, onCancel: () -> Unit, notKept: Boolean = false) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val sheetFocus = remember { FocusRequester() }
    SheetFrame(onEscape = onCancel, modifier = Modifier.focusRequester(sheetFocus).focusable()) {
        BasicText(
            if (offline) stringResource(Res.string.search_state_offline_headline)
            else UiString.Resource(Res.string.account_signin_failed_title, listOf(serviceName)).resolve(),
            style = typography.railTitle.copy(color = colors.ink),
        )
        BasicText(
            UiString.Resource(
                when {
                    offline -> Res.string.account_signin_offline_body
                    notKept -> Res.string.account_signin_not_kept
                    else -> Res.string.account_signin_failed_body
                },
                listOf(serviceName),
            ).resolve(),
            style = typography.body.copy(color = colors.ink2),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel)
            DesktopButton(label = stringResource(Res.string.search_state_retry), onClick = onRetry, primary = true, icon = CloudStreamIcons.Retry)
        }
    }
    LaunchedEffect(Unit) { runCatching { sheetFocus.requestFocus() } }
}

/**
 * `DS-25` A14: removing a sign-in from this Mac. **Cancel is the primary** (`DEC-19`), and Return and Escape both land
 * on it; only a click removes. No service revokes a token, so the words say what happens: it leaves this device.
 * [notRemoved]: the last Remove was refused by the Keychain, so the sign-in is still there (`KMP-95f`).
 */
@Composable
fun RemoveSignInSheet(
    profileName: String,
    serviceName: String,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    notRemoved: Boolean = false,
) {
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
        BasicText(UiString.Resource(Res.string.account_sync_remove_title, listOf(serviceName)).resolve(), style = typography.railTitle.copy(color = colors.ink))
        BasicText(UiString.Resource(Res.string.account_sync_remove_body, listOf(profileName, serviceName)).resolve(), style = typography.body.copy(color = colors.ink2))
        if (notRemoved) {
            BasicText(
                UiString.Resource(Res.string.account_signin_not_removed, listOf(serviceName)).resolve(),
                style = typography.label.copy(color = colors.ink2),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            DesktopButton(label = stringResource(Res.string.settings_remove), onClick = onRemove)
            DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel, primary = true)
        }
    }
    LaunchedEffect(Unit) { runCatching { sheetFocus.requestFocus() } }
}

/**
 * `DS-25` A16 and A16b: an email and password typed here, for OpenSubtitles and SubDL only (the owner's exception,
 * 2026-11-08). The password is masked, with Show; **never prefilled**; and the screen never logs either field.
 *
 * - [mismatch] (A16b): the service refused them. The email stays, the password clears, and the line says which.
 * - [busy]: a sign-in is on its way; Sign in does nothing until it answers, and Cancel ends it.
 * - The note says the password stays on this device, and the line under it that sign-ins last until you quit (D3).
 *
 * @param attempt changed by the host after a refusal, to clear the password.
 * @param untilQuit draws "Sign-ins last until you quit"; false on a signed build (`KMP-95f`).
 */
@Composable
fun PasswordSignInSheet(
    serviceName: String,
    mismatch: Boolean,
    busy: Boolean,
    attempt: Int,
    onSignIn: (email: String, password: String) -> Unit,
    onCancel: () -> Unit,
    email: String = "",
    untilQuit: Boolean = true,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    var emailText by remember { mutableStateOf(email) }
    var password by remember(attempt) { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val submit = { if (!busy && emailText.isNotBlank() && password.isNotEmpty()) onSignIn(emailText.trim(), password) }
    SheetFrame(onEscape = onCancel) {
        BasicText(UiString.Resource(Res.string.account_browser_title, listOf(serviceName)).resolve(), style = typography.railTitle.copy(color = colors.ink))
        Column(verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap)) {
            BasicText(
                if (mismatch) stringResource(Res.string.account_password_mismatch)
                else UiString.Resource(Res.string.account_password_note, listOf(serviceName)).resolve(),
                style = typography.body.copy(color = colors.ink2),
            )
            if (untilQuit) UntilQuitLine()
        }
        SheetField(
            label = stringResource(Res.string.account_email),
            value = emailText,
            onValueChange = { emailText = it.take(SyncSheetsDesktopDefaults.MaxFieldChars) },
            onSubmit = { runCatching { passwordFocus.requestFocus() } },
            focus = emailFocus,
        )
        SheetField(
            label = stringResource(Res.string.account_password),
            value = password,
            onValueChange = { password = it.take(SyncSheetsDesktopDefaults.MaxFieldChars) },
            onSubmit = submit,
            focus = passwordFocus,
            secret = !shown,
            trailing = {
                ShowToggle(shown = shown, onToggle = { shown = !shown })
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            DesktopButton(label = stringResource(Res.string.cancel), onClick = onCancel)
            DesktopButton(label = stringResource(Res.string.settings_sign_in), onClick = submit, primary = true)
        }
    }
    LaunchedEffect(attempt) {
        runCatching { if (emailText.isBlank()) emailFocus.requestFocus() else passwordFocus.requestFocus() }
    }
}

@Composable
private fun SheetField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focus: FocusRequester,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
        BasicText(label, style = typography.label.copy(color = colors.muted))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DesktopDimens.ToolbarFieldHeight)
                .background(colors.bg, shape)
                .border(CloudStreamDimens.Hairline, if (focused) colors.ink2 else colors.line, shape)
                .padding(start = DesktopDimens.RowPadding, end = DesktopDimens.RelatedGap),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = typography.body.copy(color = colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(colors.ink),
                interactionSource = source,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics {
                        contentDescription = label
                        if (secret) password()
                    }
                    .onPreviewKeyEvent { event ->
                        val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        if (enter) onSubmit()
                        enter
                    },
            )
            trailing?.invoke()
        }
    }
}

/** A16's Show: words in the field's end, which unmask the password while on. */
@Composable
private fun ShowToggle(shown: Boolean, onToggle: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val label = stringResource(Res.string.show)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = DesktopDimens.FieldActionSize)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (hovered || shown) colors.line else Color.Transparent, shape)
            .toggleable(value = shown, interactionSource = source, indication = null, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = label }
            .padding(horizontal = DesktopDimens.RelatedGap),
    ) {
        BasicText(label, style = CloudStreamTheme.typography.label.copy(color = colors.ink2))
    }
}

/** A11's Open simkl.com/pin: words after the glyph, as a link reads. */
@Composable
private fun LinkAction(label: String, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .heightIn(min = DesktopDimens.RowControlHeight)
            .tvFocusRing(focused = focused, shape = shape)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
    ) {
        CloudStreamIcon(CloudStreamIcons.OpenExternal, null, size = CloudStreamIconSize.Small, tint = colors.ink2)
        BasicText(label, style = CloudStreamTheme.typography.label.copy(color = colors.ink2), maxLines = 1)
    }
}

/** D3: an unsigned build keeps sign-ins in memory only, and the window says so wherever one is made or shown. */
@Composable
private fun UntilQuitLine() {
    BasicText(
        stringResource(Res.string.account_signins_until_quit),
        style = CloudStreamTheme.typography.caption.copy(color = CloudStreamTheme.colors.muted),
    )
}

/** `.rule` between A9's rows: a hairline, drawn and not laid out. */
@Composable
private fun SheetRule() {
    Box(Modifier.fillMaxWidth().height(CloudStreamDimens.Hairline).background(CloudStreamTheme.colors.line))
}

/** The QR's test tag: it has no words, so a test finds it by this. */
const val PAIR_QR_TAG = "pair-qr"

/** `9:41`, as A11 counts down. */
internal fun countdown(seconds: Int): String {
    val left = seconds.coerceAtLeast(0)
    return "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
}

/** Literals the sync sheets own, on the 4 dp step. */
object SyncSheetsDesktopDefaults {
    /** `.svc`: a service row with two lines and a button, 64 as `DS-24`'s rows with a second line. */
    val RowHeight: Dp = 64.dp

    /** `.code`'s tracking, so a code reads as separate characters, as 8d's does. */
    val CodeTracking = 0.18.em

    /** `.qr`, 120 at 1 px = 1 dp, beside the code as A11 draws it. */
    val QrSize: Dp = 120.dp

    /** The white margin a scanner needs, inside [QrSize]. */
    val QrQuietZone: Dp = 8.dp

    /** A11b dims the QR with the struck-through code: it no longer leads anywhere useful. */
    const val ExpiredQrAlpha = 0.32f

    /** Long enough for any email or password; the services' own limits are lower. */
    const val MaxFieldChars = 256
}
