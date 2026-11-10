package com.lagradost.cloudstream4.feature.detail.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.strings.generated.resources.actor_background
import com.lagradost.cloudstream4.core.strings.generated.resources.actor_main
import com.lagradost.cloudstream4.core.strings.generated.resources.actor_supporting
import com.lagradost.cloudstream4.core.strings.generated.resources.cast
import com.lagradost.cloudstream4.core.strings.generated.resources.cast_voiced_by
import com.lagradost.cloudstream4.core.strings.generated.resources.load_more
import com.lagradost.cloudstream4.core.strings.generated.resources.more_like_this
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailCastMember
import com.lagradost.cloudstream4.feature.detail.DetailRecommendation
import com.lagradost.cloudstream4.feature.detail.META_SEPARATOR
import com.lagradost.cloudstream4.feature.detail.typeLabel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * `DS-26`'s Cast under the episodes (C1, C3, C3b, C4): a header, then everyone, a line of people at a time.
 *
 * Each line is its own lazy item, so a line below the window is not composed and its photos are not asked for: the
 * hosts a plugin chose learn this Mac's address only for what is on screen (`KMP-103`'s condition 6). Nothing is drawn
 * when there is no cast: no header over nothing (C5a).
 *
 * @param columns people a line, from the page's width ([DetailCastDesktopDefaults.castColumns]).
 * @param picture draws a photo the host has loaded and bounded; the person's initials are under it until it arrives,
 *   and stay if it never does (C3).
 */
internal fun LazyListScope.castSection(
    cast: List<DetailCastMember>,
    columns: Int,
    picture: @Composable (String) -> Unit,
) {
    if (cast.isEmpty()) return
    item(key = CAST_HEADER_KEY) { SectionHeader(stringResource(Res.string.cast)) }
    val lines = cast.chunked(columns)
    items(lines.size, key = { "$CAST_LINE_KEY$it:$columns" }) { line ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesktopDimens.RowPadding)
                .padding(top = if (line == 0) 0.dp else DesktopDimens.GroupGap),
        ) {
            // A voice line makes a person three lines tall, and everyone on that line takes it, so its edges stay even.
            val height = if (lines[line].any { it.voiceActorName != null }) {
                DetailCastDesktopDefaults.PersonHeightWithVoice
            } else {
                DetailCastDesktopDefaults.PersonHeight
            }
            lines[line].forEach { person -> Person(person, picture, height, Modifier.weight(1f)) }
            // A short last line keeps the others' widths, so a person never moves when the window does not.
            repeat(columns - lines[line].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * `DS-26`'s More like this (C1, C2, C6): a header with Load more, as Home's rows have, and the cards a line at a time.
 * [shownLines] lines show; Load more opens one more. Every card is in hand, so a line opens at once, and the button
 * goes once all of them show.
 */
internal fun LazyListScope.recommendationsSection(
    recommendations: List<DetailRecommendation>,
    columns: Int,
    shownLines: Int,
    onMore: () -> Unit,
    onAction: (DetailAction) -> Unit,
    poster: @Composable (String) -> Unit,
) {
    if (recommendations.isEmpty()) return
    val lines = recommendations.chunked(columns)
    val shown = lines.take(shownLines.coerceAtLeast(1))
    item(key = MORE_HEADER_KEY) {
        SectionHeader(stringResource(Res.string.more_like_this), onMore = if (shown.size < lines.size) onMore else null)
    }
    items(shown.size, key = { "$MORE_LINE_KEY$it:$columns" }) { line ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesktopDimens.RowPadding)
                .padding(top = if (line == 0) 0.dp else DesktopDimens.SectionGap),
        ) {
            shown[line].forEach { title -> RecommendationCard(title, onAction, poster, Modifier.weight(1f)) }
            repeat(columns - shown[line].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** `EpisodesHeader`'s place and type, with Home's row button at its end when there is more to show. */
@Composable
private fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DesktopDimens.RowPadding, end = DesktopDimens.RowPadding, top = DesktopDimens.SectionGap, bottom = DesktopDimens.GroupGap)
            .height(DesktopDimens.ButtonHeight),
    ) {
        BasicText(
            text = title,
            style = CloudStreamTheme.typography.railTitle.copy(color = CloudStreamTheme.colors.ink),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) DesktopButton(label = stringResource(Res.string.load_more), onClick = onMore)
    }
}

/**
 * One person: a round photo over their initials, then the name, the role and who voices them, a line each and cut with
 * an ellipsis. A person takes no press: there is no person page. When the pointer rests on one whose words were cut,
 * all of them show in a tooltip (C4).
 */
@Composable
private fun Person(person: DetailCastMember, picture: @Composable (String) -> Unit, height: Dp, modifier: Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val role = person.role ?: person.roleKind?.let { roleKindLabel(it) }
    // Formatted by UiString: Compose's own argument overload leaves a plain %s in the text.
    val voiced = person.voiceActorName?.let { UiString.Resource(Res.string.cast_voiced_by, listOf(it)).resolve() }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var cut by remember(person) { mutableStateOf(false) }
    val noteCut: (TextLayoutResult) -> Unit = { if (it.hasVisualOverflow) cut = true }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = modifier.height(height).hoverable(source).testTag(DetailCastDesktopDefaults.PersonTestTag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DetailCastDesktopDefaults.PhotoSize)
                .clip(CircleShape)
                .background(colors.raised),
        ) {
            BasicText(text = initials(person.name), style = typography.body.copy(color = colors.ink2), maxLines = 1)
            person.imageUrl?.let { url -> Box(Modifier.fillMaxSize()) { picture(url) } }
        }
        Column(Modifier.weight(1f)) {
            BasicText(person.name, style = typography.label.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = noteCut)
            role?.let {
                BasicText(it, style = typography.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = noteCut)
            }
            voiced?.let {
                BasicText(it, style = typography.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = noteCut)
            }
        }
    }
    if (cut) WholeWords(hovered, listOfNotNull(person.name, role, voiced).joinToString(META_SEPARATOR))
}

/** After the pointer rests, the person's words whole, under them: what an ellipsis cut. */
@Composable
private fun WholeWords(hovered: Boolean, words: String) {
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        tip = false
        if (hovered) {
            delay(DetailCastDesktopDefaults.TooltipDelayMillis)
            tip = true
        }
    }
    if (!tip) return
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.small
    val below = with(LocalDensity.current) { (DetailCastDesktopDefaults.PersonHeight + DesktopDimens.RelatedGap).roundToPx() }
    Popup(offset = IntOffset(0, below), properties = PopupProperties(focusable = false)) {
        BasicText(
            text = words,
            style = CloudStreamTheme.typography.caption.copy(color = colors.ink),
            modifier = Modifier
                .background(colors.raised, shape)
                .border(CloudStreamDimens.Hairline, colors.line, shape)
                .padding(horizontal = DesktopDimens.RelatedGap, vertical = CloudStreamDimens.StackGap),
        )
    }
}

/**
 * Home's card (`TitleCard`): the poster, 2:3, then the title in two lines at most and its kind and year. Only the art
 * lifts under the pointer; the words never move. A missing poster leaves the card's plain surface (C6).
 */
@Composable
private fun RecommendationCard(
    title: DetailRecommendation,
    onAction: (DetailAction) -> Unit,
    poster: @Composable (String) -> Unit,
    modifier: Modifier,
) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.large
    val line = listOfNotNull(title.typeName?.let { typeLabel(it) }, title.year?.toString()).joinToString(META_SEPARATOR)
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier.clickable(interactionSource = source, indication = null) {
            onAction(DetailAction.RecommendationActivated(title.id))
        },
    ) {
        Box(
            Modifier
                .desktopFeedback(source)
                .fillMaxWidth()
                .aspectRatio(DetailCastDesktopDefaults.PosterRatio)
                .tvFocusRing(focused = focused, shape = shape)
                .clip(shape)
                .background(colors.surface),
        ) {
            title.posterUrl?.let { url -> Box(Modifier.fillMaxSize()) { poster(url) } }
        }
        Column {
            BasicText(
                title.title,
                style = CloudStreamTheme.typography.label.copy(color = colors.ink),
                maxLines = DetailCastDesktopDefaults.TitleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.isNotEmpty()) {
                BasicText(line, style = CloudStreamTheme.typography.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `ActorRole`'s three kinds in words. Anything else is shown as nothing rather than as an identifier. */
@Composable
private fun roleKindLabel(kind: String): String? = when (kind) {
    "Main" -> stringResource(Res.string.actor_main)
    "Supporting" -> stringResource(Res.string.actor_supporting)
    "Background" -> stringResource(Res.string.actor_background)
    else -> null
}

/** The first letter of the first two words, as a profile's picture stands in for an address (`KMP-73`). */
internal fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { firstLetter(it).uppercase() }

/** The first character of [word], kept whole when it is a surrogate pair (a letter outside the BMP). */
private fun firstLetter(word: String): String =
    if (word.length > 1 && word[0].isHighSurrogate() && word[1].isLowSurrogate()) word.substring(0, 2) else word.substring(0, 1)

object DetailCastDesktopDefaults {
    /**
     * A person with a name and a role: the photo, and two lines beside it on the desktop ramp (`forDesktop`): label's
     * 24 line box and caption's 16. The PM's 40 (2026-11-09) was set against the board's line boxes, label 16 and
     * caption 12, and it holds two lines on the ramp.
     */
    val PersonHeight: Dp = 40.dp

    /**
     * A person with a voice line too (C3b): label's 24 line box, then caption's 16 for the role and 16 for "Voiced by",
     * 24 + 16 + 16 on the desktop ramp. Measured with Manrope at 40, the voice line was given 2 of its 16 and cut
     * (`KMP-104`). Every person on a line with a voice takes this, so the line's edges stay even (the PM, 2026-11-10).
     */
    val PersonHeightWithVoice: Dp = 56.dp
    val PhotoSize: Dp = 40.dp

    /** The narrowest a person may be: four a line in a 1280 window, two in the 800 minimum (`DS-26` C1, C7). */
    val PersonMinWidth: Dp = 232.dp

    /** The narrowest a card may be: Home's Medium poster size, `HomeScreenDesktopDefaults.PortraitCardMinWidth`. */
    val CardMinWidth: Dp = 160.dp
    const val PosterRatio = 2f / 3f
    const val TitleLines = 2
    const val TooltipDelayMillis = 600L

    /** A person's box, so a test can hold their words against its edges. */
    const val PersonTestTag = "cast-person"

    /** How many fit in [width] at [min] each, with the group gap between, never fewer than one. */
    fun columns(width: Dp, min: Dp, gap: Dp = DesktopDimens.GroupGap): Int =
        ((width + gap) / (min + gap)).toInt().coerceAtLeast(1)
}

private const val CAST_HEADER_KEY = "cast"
private const val CAST_LINE_KEY = "cast-line-"
private const val MORE_HEADER_KEY = "more"
private const val MORE_LINE_KEY = "more-line-"
