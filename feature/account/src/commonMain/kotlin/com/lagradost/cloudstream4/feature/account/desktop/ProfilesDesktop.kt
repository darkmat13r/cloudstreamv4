package com.lagradost.cloudstream4.feature.account.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_select_note
import com.lagradost.cloudstream4.core.strings.generated.resources.account_select_title
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.account.profileInitials
import com.lagradost.cloudstream4.feature.account.profileLineText
import org.jetbrains.compose.resources.stringResource

/**
 * `DS-25` A1 and A15: "Who's watching?" at launch, 8d's card row at a pointer's size (`KMP-95`). The host shows it
 * only with two profiles or more, the default counted.
 *
 * The current profile's card is filled `raised` and ringed when the picker opens, so Return opens it; the arrow keys
 * move the ring, and a click opens the card under the pointer. The row is the one focus stop and draws the ring itself,
 * so the picker answers the keyboard from the first frame. Below [ProfilesDesktopDefaults.CompactBelow] the
 * heading moves up (A15), so four cards still fit in one row at the 800×520 minimum; a fifth wraps.
 *
 * Manage profiles and Add profile come with editing (`KMP-95b`); a locked profile's PIN with `KMP-95c`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePickerDesktop(
    profiles: List<ProfileCard>,
    onChoose: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val rowFocus = remember { FocusRequester() }
    var rowFocused by remember { mutableStateOf(false) }
    // The card the ring is on. The row holds focus and moves the ring, so focus never depends on a card taking it.
    var ringed by remember(profiles.map { it.id }) { mutableIntStateOf(profiles.indexOfFirst { it.current }.coerceAtLeast(0)) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val top = if (maxHeight < ProfilesDesktopDefaults.CompactBelow) ProfilesDesktopDefaults.CompactTop
        else ProfilesDesktopDefaults.PickerTop
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.SectionGap),
            modifier = Modifier.fillMaxWidth().padding(top = top, start = DesktopDimens.WindowMargin, end = DesktopDimens.WindowMargin),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            ) {
                BasicText(stringResource(Res.string.account_select_title), style = typography.h1.copy(color = colors.ink))
                BasicText(stringResource(Res.string.account_select_note), style = typography.body.copy(color = colors.muted))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.CardGap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.CardGap),
                modifier = Modifier
                    .focusRequester(rowFocus)
                    .onFocusChanged { rowFocused = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || profiles.isEmpty()) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft, Key.DirectionUp -> ringed = (ringed - 1).coerceAtLeast(0)
                            Key.DirectionRight, Key.DirectionDown -> ringed = (ringed + 1).coerceAtMost(profiles.lastIndex)
                            Key.Enter, Key.NumPadEnter -> profiles.getOrNull(ringed)?.let { onChoose(it.id) }
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    }
                    .focusable(),
            ) {
                profiles.forEachIndexed { index, card ->
                    PickerCard(card = card, ringed = rowFocused && index == ringed, onClick = { onChoose(card.id) })
                }
            }
            // Here and not beside BoxWithConstraints: its content is composed during layout, after an effect outside it
            // would already have run against a row that did not exist yet.
            LaunchedEffect(Unit) { runCatching { rowFocus.requestFocus() } }
        }
    }
}

@Composable
private fun PickerCard(card: ProfileCard, ringed: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = CloudStreamTheme.shapes.large
    val line = profileLineText(card)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.DiscGap),
        modifier = Modifier
            .width(ProfilesDesktopDefaults.CardWidth)
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = ringed, shape = shape)
            .background(
                when {
                    card.current -> colors.raised
                    hovered -> colors.line
                    else -> Color.Transparent
                },
                shape,
            )
            .semantics(mergeDescendants = true) { contentDescription = "${card.name}, $line" }
            // The row is the one focus stop; a card is for the pointer.
            .focusProperties { canFocus = false }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = ProfilesDesktopDefaults.CardPadding),
    ) {
        ProfileDisc(
            name = card.name,
            size = ProfilesDesktopDefaults.DiscSize,
            // Nothing sits raised on raised: on the current card the disc steps up to `line`.
            fill = if (card.current) colors.line else colors.raised,
            style = typography.h1,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap),
            modifier = Modifier.padding(horizontal = DesktopDimens.RelatedGap),
        ) {
            BasicText(
                card.name,
                style = typography.body.copy(color = colors.ink, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap)) {
                if (card.locked) CloudStreamIcon(CloudStreamIcons.Lock, null, size = CloudStreamIconSize.Small, tint = colors.muted)
                BasicText(line, style = typography.label.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * `DS-25` A2: the profile button at the foot of the sidebar, and the list it opens above itself (`KMP-95`).
 *
 * The button says who is watching. Pressing it opens the profiles, the current one checked; choosing one calls
 * [onChoose] and closes the list, and choosing the current one only closes it. A click outside or Escape closes it
 * with nothing chosen. [open] is the host's, so the window's own Escape can close it too.
 *
 * Manage profiles and Sync services join the list with `KMP-95b` and `KMP-95d`.
 */
@Composable
fun ProfileButtonDesktop(
    profiles: List<ProfileCard>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onChoose: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val current = profiles.firstOrNull { it.current } ?: return
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DesktopDimens.RowControlHeight)
                .tvFocusRing(focused = focused, shape = shape)
                .background(if (hovered || open) colors.line else Color.Transparent, shape)
                .semantics(mergeDescendants = true) { contentDescription = current.name }
                .clickable(interactionSource = source, indication = null, role = Role.DropdownList) { onOpenChange(!open) }
                .padding(horizontal = DesktopDimens.RowPadding),
        ) {
            ProfileDisc(current.name, ProfilesDesktopDefaults.SmallDiscSize, fill = colors.raised, style = typography.caption)
            BasicText(
                current.name,
                style = typography.label.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CloudStreamIcon(CloudStreamIcons.Selector, null, size = CloudStreamIconSize.Small, tint = colors.ink2)
        }
        if (open) {
            ProfileMenu(
                profiles = profiles,
                onDismiss = { onOpenChange(false) },
                onChoose = { id ->
                    onOpenChange(false)
                    if (id != current.id) onChoose(id)
                },
            )
        }
    }
}

@Composable
private fun ProfileMenu(profiles: List<ProfileCard>, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    val gap = with(LocalDensity.current) { ProfilesDesktopDefaults.PopoverOffset.roundToPx() }
    val above = remember(gap) { AboveAnchor(gap) }
    val listFocus = remember { FocusRequester() }
    Popup(popupPositionProvider = above, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) { runCatching { listFocus.requestFocus() } }
        Column(
            verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap),
            modifier = Modifier
                .width(ProfilesDesktopDefaults.PopoverWidth)
                .background(colors.raised, shape)
                .border(CloudStreamDimens.Hairline, colors.line, shape)
                .padding(DesktopDimens.RelatedGap)
                .focusRequester(listFocus)
                .onPreviewKeyEvent { event ->
                    (event.key == Key.Escape && event.type == KeyEventType.KeyDown).also { if (it) onDismiss() }
                }
                .focusable(),
        ) {
            profiles.forEach { card -> MenuRow(card, onClick = { onChoose(card.id) }) }
        }
    }
}

@Composable
private fun MenuRow(card: ProfileCard, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val line = profileLineText(card)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DesktopDimens.RowMinHeight)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (hovered) colors.line else Color.Transparent, shape)
            .semantics(mergeDescendants = true) { contentDescription = "${card.name}, $line" }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        // On the raised list the disc steps up to `line`, and under the hover fill back down to `raised`.
        ProfileDisc(card.name, ProfilesDesktopDefaults.SmallDiscSize, fill = if (hovered) colors.raised else colors.line, style = typography.caption)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProfilesDesktopDefaults.LineGap)) {
            BasicText(card.name, style = typography.label.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(line, style = typography.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            card.current -> CloudStreamIcon(CloudStreamIcons.Done, null, size = CloudStreamIconSize.Small, tint = colors.ink)
            card.locked -> CloudStreamIcon(CloudStreamIcons.Lock, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        }
    }
}

/** A profile with no picture: its initials in a disc. A disc is a shape the design system draws, not a glyph. */
@Composable
private fun ProfileDisc(name: String, size: Dp, fill: Color, style: TextStyle) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).background(fill, CloudStreamTheme.shapes.pill),
    ) {
        BasicText(profileInitials(name), style = style.copy(color = CloudStreamTheme.colors.ink2, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** Opens the list above its button, its left edge on the button's, [gap] px clear of it (A2). */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0))
}

/** Literals the `DS-25` boards own (1 px = 1 dp there), on the 4 dp step. */
object ProfilesDesktopDefaults {
    /** `.card`: a picker card's width. */
    val CardWidth: Dp = 160.dp

    /** `.card` padding, above and below. Mirrors `DesktopDimens.GroupGap`. */
    val CardPadding: Dp = 16.dp

    /** `.cards` gap, between cards. Mirrors `DesktopDimens.WindowMargin`. */
    val CardGap: Dp = 24.dp

    /** `.card` gap, between the disc and the words. Mirrors `DesktopDimens.RowPadding`. */
    val DiscGap: Dp = 12.dp

    /** `.who` gap, between a name and its line; also the list's row gap. Mirrors `CloudStreamDimens.StackGap`. */
    val LineGap: Dp = 4.dp

    /** `.av96`, a picker card's disc. */
    val DiscSize: Dp = 96.dp

    /** `.av24`, the disc in the sidebar's button and in its list. */
    val SmallDiscSize: Dp = 24.dp

    /** `.pick` padding-top at 1280×800 (A1). */
    val PickerTop: Dp = 136.dp

    /** The same at 800×520 (A15), where the heading moves up. */
    val CompactTop: Dp = 64.dp

    /**
     * Below this content height the picker uses [CompactTop]. The boards draw only the two ends, 772 and 492 under the
     * title bar; this sits between them, where [PickerTop] would start pushing a second row of cards off the window.
     */
    val CompactBelow: Dp = 640.dp

    /** `.pop`: the profile list's width. */
    val PopoverWidth: Dp = 264.dp

    /** The list's clearance above its button: `bottom: 60px` less the button's 56. Mirrors `CloudStreamDimens.StackGap`. */
    val PopoverOffset: Dp = 4.dp
}
