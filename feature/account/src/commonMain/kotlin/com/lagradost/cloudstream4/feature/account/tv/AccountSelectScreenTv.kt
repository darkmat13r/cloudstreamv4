package com.lagradost.cloudstream4.feature.account.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusLift
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.primitive.Avatar
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_add_profile
import com.lagradost.cloudstream4.core.strings.generated.resources.account_manage_profiles
import com.lagradost.cloudstream4.core.strings.generated.resources.account_select_note
import com.lagradost.cloudstream4.core.strings.generated.resources.account_select_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_connected
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_needs_browser
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_note
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_pair
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_remove
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_title
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_unavailable
import com.lagradost.cloudstream4.core.strings.generated.resources.library_not_connected
import com.lagradost.cloudstream4.core.strings.generated.resources.login
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.account.AccountAction
import com.lagradost.cloudstream4.feature.account.AccountSelectState
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.account.profileInitials
import com.lagradost.cloudstream4.feature.account.profileLineText
import com.lagradost.cloudstream4.feature.account.SignIn
import com.lagradost.cloudstream4.feature.account.SyncService
import org.jetbrains.compose.resources.stringResource

/**
 * Artboard **8d** of `TV Onboarding Extensions.dc.html`: who is watching, and which sync services that profile uses.
 *
 * - **Profiles are a card row** (`.card`, 240x280 on a 272 pitch: 120x140 dp on 136). A focused card lifts and takes a
 *   ring with no offset; the current profile rests filled, which is where focus starts.
 * - **Sync services are a list with their true state** (`.lrow`, 96 high on a 120 pitch: `ListRow` on a 60 dp pitch).
 *   A service that can only sign in through a browser says so on a device without one, rather than drawing a sign-in
 *   that cannot finish.
 * - **Pairing is a side panel** over the screen ([PairingPanelTv]); focus is trapped in it and comes back to the row
 *   that opened it. As `SidePanelHost` asks, an Android host also wires its platform back to
 *   [AccountAction.CancelPairing] while [AccountSelectState.pairing] is set: on API 36 BACK reaches the back dispatcher,
 *   not the panel's key handler.
 *
 * Focus order with the panel closed (8d's note): Manage profiles, then the cards (LEFT/RIGHT), then the rows (DOWN).
 *
 * @param avatar draws a profile's picture inside its disc; the default shows initials.
 */
@Composable
fun AccountSelectScreenTv(
    state: AccountSelectState,
    onAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
    avatar: (@Composable (ProfileCard) -> Unit)? = null,
) {
    val entry = remember { FocusRequester() }
    val rowFocus = remember(state.services.map { it.id }) { state.services.associate { it.id to FocusRequester() } }
    var opener by remember { mutableStateOf<String?>(null) }
    val pairing = state.pairing
    LaunchedEffect(Unit) { runCatching { entry.requestFocus() } }
    SidePanelHost(
        expanded = pairing != null,
        onDismissRequest = { onAction(AccountAction.CancelPairing) },
        // `BUG-36`: focus goes back to the row that opened the panel. [opener] is set on the press and never cleared, so
        // it still names that row while the panel slides out; read from live state it would not.
        opener = opener?.let { rowFocus[it] } ?: entry,
        modifier = modifier.fillMaxSize().background(CloudStreamTheme.colors.bg),
        panel = { pairing?.let { PairingPanelTv(it, onAction) } },
    ) {
        Column(Modifier.fillMaxSize().padding(start = AccountSelectDefaults.Edge, top = AccountSelectDefaults.Edge)) {
            Header(managing = state.managing, onAction = onAction)
            Spacer(Modifier.height(AccountSelectDefaults.HeaderToCards))
            Row(horizontalArrangement = Arrangement.spacedBy(AccountSelectDefaults.CardGap)) {
                val entryId = (state.profiles.firstOrNull { it.current } ?: state.profiles.firstOrNull())?.id
                for (card in state.profiles) {
                    ProfileCardTv(
                        card = card,
                        onPress = { onAction(AccountAction.ProfilePressed(card.id)) },
                        modifier = if (card.id == entryId) Modifier.focusRequester(entry) else Modifier,
                        avatar = avatar,
                    )
                }
                AddProfileCardTv(onPress = { onAction(AccountAction.AddProfile) })
            }
            Spacer(Modifier.height(AccountSelectDefaults.CardsToSync))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(Res.string.account_sync_title),
                    style = CloudStreamTheme.typography.railTitle,
                    color = CloudStreamTheme.colors.ink,
                    modifier = Modifier.width(AccountSelectDefaults.SyncTitleWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(AccountSelectDefaults.TitleToNote))
                Text(
                    stringResource(Res.string.account_sync_note),
                    style = CloudStreamTheme.typography.captionMono,
                    color = CloudStreamTheme.colors.muted,
                    modifier = Modifier.padding(bottom = AccountSelectDefaults.NoteDrop),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(AccountSelectDefaults.SyncTitleToRows))
            Column(verticalArrangement = Arrangement.spacedBy(AccountSelectDefaults.RowGap)) {
                for (service in state.services) {
                    val requester = rowFocus.getValue(service.id)
                    SyncRowTv(
                        service = service,
                        onPress = {
                            if (service.signIn == SignIn.PairWithCode && !service.connected) opener = service.id
                            onAction(AccountAction.ServicePressed(service.id))
                        },
                        modifier = Modifier.focusRequester(requester),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(managing: Boolean, onAction: (AccountAction) -> Unit) {
    Row(Modifier.height(AccountSelectDefaults.HeaderCell)) {
        Text(
            stringResource(Res.string.account_select_title),
            style = CloudStreamTheme.typography.h1,
            color = CloudStreamTheme.colors.ink,
            modifier = Modifier.width(AccountSelectDefaults.TitleWidth),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(AccountSelectDefaults.TitleToNote))
        Text(
            stringResource(Res.string.account_select_note),
            style = CloudStreamTheme.typography.captionMono,
            color = CloudStreamTheme.colors.muted,
            modifier = Modifier.width(AccountSelectDefaults.NoteWidth).padding(top = AccountSelectDefaults.NoteTop),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(AccountSelectDefaults.TitleToNote))
        Box(Modifier.height(AccountSelectDefaults.HeaderCell), contentAlignment = Alignment.Center) {
            GhostTextButton(
                label = stringResource(if (managing) Res.string.setup_done else Res.string.account_manage_profiles),
                onClick = { onAction(AccountAction.ToggleManage) },
                modifier = Modifier.width(AccountSelectDefaults.ManageWidth),
            )
        }
    }
}

/** `.card`: the disc, the name, and one muted line. A locked profile wears the lock glyph on its disc. */
@Composable
private fun ProfileCardTv(
    card: ProfileCard,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: (@Composable (ProfileCard) -> Unit)?,
) {
    CardFrame(resting = card.current, onPress = onPress, modifier = modifier) {
        // The words first: they are the card's label for a screen reader and for focus, not the disc's initials.
        CardText(card.name, AccountSelectDefaults.NameY, primary = true)
        CardText(profileLineText(card), AccountSelectDefaults.LineY, primary = false)
        Avatar(
            initials = profileInitials(card.name),
            modifier = Modifier.offset(x = AccountSelectDefaults.DiscX, y = AccountSelectDefaults.DiscY),
            image = avatar?.let { draw -> { draw(card) } },
        )
        if (card.locked) {
            CloudStreamIcon(
                icon = CloudStreamIcons.Lock,
                contentDescription = null,
                size = CloudStreamIconSize.Small,
                tint = CloudStreamTheme.colors.muted,
                modifier = Modifier.offset(x = AccountSelectDefaults.LockX, y = AccountSelectDefaults.LockY),
            )
        }
    }
}

@Composable
private fun AddProfileCardTv(onPress: () -> Unit) {
    CardFrame(resting = false, onPress = onPress) {
        Box(
            Modifier
                .offset(x = AccountSelectDefaults.AddX, y = AccountSelectDefaults.AddY)
                .size(AccountSelectDefaults.AddBox),
            contentAlignment = Alignment.Center,
        ) {
            CloudStreamIcon(icon = CloudStreamIcons.Add, contentDescription = null, size = CloudStreamIconSize.Large)
        }
        CardText(stringResource(Res.string.account_add_profile), AccountSelectDefaults.NameY, primary = true)
    }
}

@Composable
private fun CardFrame(
    resting: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // One focus target, the clickable; its focus is read in front of it. (An interaction source shared with a second
    // `focusable` drew no ring: the measured first screenshot.)
    var focused by remember { mutableStateOf(false) }
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    Box(
        modifier
            .size(AccountSelectDefaults.CardWidth, AccountSelectDefaults.CardHeight)
            .tvFocusLift(lifted = focused)
            // `.card.foc`: the ring sits on the card's edge, `outline-offset:0`.
            .tvFocusRing(focused = focused, color = colors.ink, shape = shape, offset = AccountSelectDefaults.RingOffset)
            .background(if (focused || resting) colors.raised else colors.surface, shape)
            .then(if (focused) Modifier else Modifier.border(CloudStreamDimens.Hairline, colors.line, shape))
            .onFocusChanged { focused = it.hasFocus }
            .clickable(interactionSource = null, indication = null, onClick = onPress),
    ) { content() }
}

@Composable
private fun CardText(text: String, y: Dp, primary: Boolean) {
    Text(
        text,
        style = if (primary) CloudStreamTheme.typography.label else CloudStreamTheme.typography.captionMono,
        color = if (primary) CloudStreamTheme.colors.ink else CloudStreamTheme.colors.muted,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.offset(x = AccountSelectDefaults.TextX, y = y).width(AccountSelectDefaults.TextWidth),
    )
}

/** A service row: its true state underneath, and what can be done here on the right (`.actw400`). */
@Composable
private fun SyncRowTv(service: SyncService, onPress: () -> Unit, modifier: Modifier = Modifier) {
    val meta = when {
        service.connected -> stringResource(Res.string.account_sync_connected)
        service.signIn == SignIn.Unavailable -> stringResource(Res.string.account_sync_needs_browser)
        else -> stringResource(Res.string.library_not_connected)
    }
    val action = when {
        service.connected -> stringResource(Res.string.account_sync_remove)
        service.signIn == SignIn.PairWithCode -> stringResource(Res.string.account_sync_pair)
        service.signIn == SignIn.Unavailable -> stringResource(Res.string.account_sync_unavailable)
        else -> stringResource(Res.string.login)
    }
    ListRow(
        state = ListRowState(title = service.name, subtitle = meta),
        onClick = onPress,
        modifier = modifier.width(AccountSelectDefaults.RowWidth),
        trailing = {
            Box(Modifier.width(AccountSelectDefaults.ActionWidth), contentAlignment = Alignment.CenterEnd) {
                Text(
                    action,
                    style = CloudStreamTheme.typography.label,
                    color = if (service.connected || service.signIn == SignIn.Unavailable) CloudStreamTheme.colors.muted
                    else CloudStreamTheme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/** Literals 8d owns. Artboard px / 2 = dp. */
internal object AccountSelectDefaults {
    /** The 56 px margin on the left and above the header. */
    val Edge: Dp = 28.dp

    /** `.btncell`, 96 px: the header row's height, the Manage button centred in it. */
    val HeaderCell: Dp = 48.dp

    /** `.h2` box, 520 px. */
    val TitleWidth: Dp = 260.dp

    /** The 24 px gap between the title, its note and the button. */
    val TitleToNote: Dp = 12.dp

    /** The header note, x600 to x1024. */
    val NoteWidth: Dp = 212.dp

    /** The header note sits at y88 against the title's y56. */
    val NoteTop: Dp = 16.dp

    /** The Manage button, 352 px. */
    val ManageWidth: Dp = 176.dp

    /** The header cell ends at y152; the cards start at y184. */
    val HeaderToCards: Dp = 16.dp

    /** `.card`, 240x280. */
    val CardWidth: Dp = 120.dp
    val CardHeight: Dp = 140.dp

    /** The 272 px pitch less the card. */
    val CardGap: Dp = 16.dp

    /** `.card.foc` has `outline-offset:0`. */
    val RingOffset: Dp = 0.dp

    /** `.cav` at (40, 16). Its size is `AvatarDefaults.Size`. */
    val DiscX: Dp = 20.dp
    val DiscY: Dp = 8.dp

    /** `.clock` at (176, 136): the lock glyph on the disc's lower right. */
    val LockX: Dp = 88.dp
    val LockY: Dp = 68.dp

    /** `.cadd`, 64 px at (88, 64): the add glyph's cell, the glyph centred in it. */
    val AddX: Dp = 44.dp
    val AddY: Dp = 32.dp
    val AddBox: Dp = 32.dp

    /** `.cnm` and `.csb`: 208 px wide at x16, at y192 and y232. */
    val TextX: Dp = 8.dp
    val TextWidth: Dp = 104.dp
    val NameY: Dp = 96.dp
    val LineY: Dp = 116.dp

    /** The cards end at y464 (lifted cards at y456); the sync title starts at y488. */
    val CardsToSync: Dp = 12.dp

    /** `.h3` box, 400 px; its note starts at x480. */
    val SyncTitleWidth: Dp = 200.dp

    /** The sync note's line box ends 8 px above the title's. */
    val NoteDrop: Dp = 4.dp

    /** The sync title ends at y544; the first row starts at y568. */
    val SyncTitleToRows: Dp = 12.dp

    /** `.lrow`, 1280 px wide. */
    val RowWidth: Dp = 640.dp

    /** The 120 px pitch less the 96 px row. */
    val RowGap: Dp = 12.dp

    /** `.actw400`. */
    val ActionWidth: Dp = 200.dp
}
