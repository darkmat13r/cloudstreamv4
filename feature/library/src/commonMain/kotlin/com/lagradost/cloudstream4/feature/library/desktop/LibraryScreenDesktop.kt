package com.lagradost.cloudstream4.feature.library.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBar
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.elapsedAge
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.filter_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sort_current
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sync_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sync_retry
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryCardWords
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import com.lagradost.cloudstream4.feature.library.libraryCardWords
import com.lagradost.cloudstream4.feature.library.tv.countOf
import com.lagradost.cloudstream4.feature.library.tv.label
import com.lagradost.cloudstream4.feature.library.tv.line
import com.lagradost.cloudstream4.feature.library.tv.metaLine
import com.lagradost.cloudstream4.feature.library.tv.staleLine
import org.jetbrains.compose.resources.stringResource

/**
 * What the user kept, for a resizable window driven by a pointer: `DS-22`'s boards L1 to L7, and
 * `docs/plans/desktop-design.md`.
 *
 * A bar with the library's source and when it last synced, the filter and Sort (L1); the lists as a row of buttons
 * that stays through every state, because changing list is the way out of a list that failed or is empty (L3). Cards
 * are a grid rather than sideways rails, because a library is browsed by scanning and a mouse wheel scrolls one way.
 *
 * **What a card's click does depends on the list, deliberately.** On Continue watching it resumes, which is the only
 * thing anybody wants from that list. On a kept list it opens the title's page, because choosing an episode, a source
 * or a subtitle track belongs there. The card's one button, Remove, shows while the pointer or focus is on it, at the
 * poster's top-right (DS-22), and removes it from the list you are looking at. Moving it to another list is the list
 * picker (L6), which a secondary click or the context-menu key opens, as a Mac opens a thing's menu.
 *
 * Choosing a source (L5), a sort (L4) and a list (L6) are side panels from the right, as every desktop choice from a
 * list is.
 *
 * @param now the clock a sync's age is measured with, or null where there is none and no age is shown.
 * @param artwork draws a title's poster. Image loading is the host's.
 * @param posterSize Settings' Poster size (`KMP-90e`): how wide a card is at least.
 */
@Composable
fun LibraryScreenDesktop(
    state: LibraryState,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier,
    now: (() -> Long)? = null,
    artwork: @Composable (LibraryCard) -> Unit = {},
    posterSize: PosterSize = PosterSize.Medium,
) {
    val status = state.toDesktopUiState(onAction)
    val words = libraryCardWords()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // L7: in the smallest window the sync line and the filter give way, so the source and Sort keep their words.
        val compact = maxWidth < LibraryScreenDesktopDefaults.CompactBelow
        Column(Modifier.fillMaxSize()) {
            Bar(state, compact, now, onAction)
            TabStrip(state, onAction)
            if (state.stale != null) SyncBanner(state, now, onAction)
            val prompts = Modifier.fillMaxSize()
            when (val content = status) {
                // Never takes focus, as in home and search: a window opening on a failure has not asked for it.
                is UiState.Empty -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                is UiState.Offline -> StateView(content.prompt, prompts, requestInitialFocus = false)
                is UiState.Error -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                else -> UiStateHost(
                    state = content,
                    modifier = Modifier.fillMaxSize(),
                    skeleton = { Skeleton(posterSize) },
                ) { cards -> Cards(cards, state.tab, held = state.stale != null, words, onAction, artwork, posterSize) }
            }
        }
        LibraryPanelsDesktop(state, now, onAction)
    }
}

/** L1's bar: where the library comes from and how fresh it is, then the filter and Sort at the trailing end. */
@Composable
private fun Bar(state: LibraryState, compact: Boolean, now: (() -> Long)?, onAction: (LibraryAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    // KMP-98: the page bar every Mac page starts with, its hairline included.
    DesktopPageBar {
        // The source and its line take what the trailing controls leave, and the line gives way first.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.weight(1f),
        ) {
            val source = state.shownSource ?: return@Row
            BasicText(
                text = stringResource(Res.string.home_source),
                style = typography.label.copy(color = colors.muted),
                maxLines = 1,
            )
            DesktopButton(label = source.name, onClick = { onAction(LibraryAction.SourcesOpened) })
            if (!compact) {
                // L2: over a held read the line says why and how old, in place of when it last synced.
                val line = state.stale?.let { source.staleLine(it, now) } ?: source.line(state.totalTitles, now)
                if (line != null) {
                    BasicText(
                        text = line,
                        style = typography.captionMono.copy(color = colors.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // A filter with words in it stays at any size: hiding it would leave a list narrowed by something unseen.
        if (!compact || state.filter.isNotEmpty()) {
            FilterField(
                text = state.filter,
                onAction = onAction,
                modifier = Modifier.width(LibraryScreenDesktopDefaults.FilterWidth),
            )
        }
        DesktopButton(
            label = UiString.Resource(
                Res.string.library_sort_current,
                listOf(stringResource(state.appliedSort.label())),
            ).resolve(),
            onClick = { onAction(LibraryAction.SortOpened) },
        )
    }
}

/** Narrows every list, as TV's does. Escape empties it, as it does in every Mac search field. */
@Composable
private fun FilterField(text: String, onAction: (LibraryAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .height(DesktopDimens.ButtonHeight)
            .background(colors.surface, shape)
            // A brighter hairline rather than the ring, as the search field: the caret already says where typing goes.
            .border(CloudStreamDimens.Hairline, if (focused) colors.muted else colors.line, shape)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { onAction(LibraryAction.FilterChanged(it)) },
            textStyle = typography.label.copy(color = colors.ink),
            singleLine = true,
            cursorBrush = SolidColor(colors.ink),
            interactionSource = source,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val clears = event.key == Key.Escape && event.type == KeyEventType.KeyDown && text.isNotEmpty()
                    if (clears) onAction(LibraryAction.FilterChanged(""))
                    clears
                },
        )
        if (text.isEmpty()) {
            BasicText(
                text = stringResource(Res.string.filter_bookmarks),
                style = typography.label.copy(color = colors.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The list names, each with how many it holds, in one scrollable row.
 *
 * Seven names do not fit a minimum window, and a row that wraps moves the content under it every time a name's
 * translation changes length. It scrolls instead.
 */
@Composable
private fun TabStrip(state: LibraryState, onAction: (LibraryAction) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // L1: a margin's height from the bar's rule, and the same again to the cards.
            .padding(horizontal = DesktopDimens.WindowMargin, vertical = DesktopDimens.WindowMargin),
    ) {
        state.desktopTabs().forEach { tab ->
            TabButton(
                label = tab.label(),
                count = state.countOf(tab),
                selected = tab == state.tab,
                onClick = { onAction(LibraryAction.TabSelected(tab)) },
            )
        }
    }
}

/** A list's button: its name, and its size in the mono face where one was read. The list shown is the white one. */
@Composable
private fun TabButton(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val fill = when {
        selected -> if (hovered) colors.ink2 else colors.ink
        else -> if (hovered) colors.line else colors.raised
    }
    val content = if (selected) colors.bg else colors.ink
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .desktopFeedback(source)
            .tvFocusRing(focused = focused, shape = shape)
            .background(fill, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.ButtonPadding),
    ) {
        BasicText(text = label, style = typography.label.copy(color = content), maxLines = 1)
        if (count != null) {
            BasicText(
                text = count.toString(),
                style = typography.captionMono.copy(color = colors.muted),
                maxLines = 1,
            )
        }
    }
}

/**
 * L2: the shown service could not be read, so the cards are the last read held, and this says from when and offers
 * the read again. The cards stay, dimmed, so nothing on screen pretends to be live.
 */
@Composable
private fun SyncBanner(state: LibraryState, now: (() -> Long)?, onAction: (LibraryAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = state.shownSource ?: return
    val at = source.syncedAt
    val clock = now
    // Without an age the bar's line already says it is cached, and a sentence with a hole in it says less.
    val age = if (at != null && clock != null) elapsedAge(clock() - at).resolve() else null
    val shape = CloudStreamTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RowPadding),
        modifier = Modifier
            .padding(
                start = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                end = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                bottom = DesktopDimens.GroupGap,
            )
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
    ) {
        CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = colors.ink)
        BasicText(
            text = if (age != null) {
                UiString.Resource(Res.string.library_sync_failed, listOf(source.name, age)).resolve()
            } else {
                source.staleLine(state.stale ?: return@Row, now) ?: source.name
            },
            style = CloudStreamTheme.typography.label.copy(color = colors.ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DesktopButton(
            label = stringResource(Res.string.library_sync_retry),
            icon = CloudStreamIcons.Retry,
            onClick = { onAction(LibraryAction.Retry) },
        )
    }
}

/** Lines of cards, as many to a line as the window is wide enough for. */
@Composable
private fun Cards(
    cards: List<LibraryCard>,
    tab: LibraryTab,
    held: Boolean,
    words: LibraryCardWords,
    onAction: (LibraryAction) -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
    posterSize: PosterSize,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - DesktopDimens.WindowMargin * 2
        val columns = LibraryScreenDesktopDefaults.columns(width, posterSize)
        val cardWidth = LibraryScreenDesktopDefaults.cardWidth(width, columns)
        val lines = cards.chunked(columns)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesktopDimens.WindowMargin,
                end = DesktopDimens.WindowMargin,
                bottom = DesktopDimens.SectionGap,
            ),
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.SectionGap),
        ) {
            items(lines.size, key = { lines[it].first().id.value }) { line ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(
                            fadeInSpec = DesktopMotion.spec(),
                            placementSpec = DesktopMotion.spec(),
                            fadeOutSpec = null,
                        ),
                ) {
                    lines[line].forEach { card ->
                        TitleCard(
                            card = card,
                            tab = tab,
                            held = held,
                            words = words,
                            onAction = onAction,
                            artwork = artwork,
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleCard(
    card: LibraryCard,
    tab: LibraryTab,
    held: Boolean,
    words: LibraryCardWords,
    onAction: (LibraryAction) -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val latest by rememberUpdatedState(onAction)
    // The card's own hover, over Remove as well: the button sits on the art, and hovering it must not hide it.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    var removeFocused by remember { mutableStateOf(false) }
    val shape = CloudStreamTheme.shapes.large
    val resuming = tab == LibraryTab.ContinueWatching
    val activate = {
        if (resuming) onAction(LibraryAction.ResumeActivated(card.id)) else onAction(LibraryAction.TitleActivated(card.id))
    }
    val pick = { latest(LibraryAction.PickerOpened(card.id)) }
    // Only the art moves, on the card's hover and the target's press. Scaling the words would make them shimmer.
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> DesktopMotion.PressScale
            hovered -> DesktopMotion.HoverScale
            else -> 1f
        },
        animationSpec = DesktopMotion.spec(),
    )
    Box(
        modifier = modifier
            .hoverable(hover)
            // A secondary click is a thing's menu on a Mac. Taken before the target sees it, so it never also opens.
            .pointerInput(card.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            pick()
                        }
                    }
                }
            },
    ) {
        // The art and the words are the one target; Remove sits outside it, so a press on the button cannot also
        // open the title. Nesting one clickable inside another would leave that to which consumes the press.
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier
                .clickable(interactionSource = source, indication = null, onClick = activate)
                .onKeyEvent { event ->
                    val menu = event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed))
                    if (menu) pick()
                    menu
                },
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .fillMaxWidth()
                    .aspectRatio(LibraryScreenDesktopDefaults.CardRatio)
                    .tvFocusRing(focused = focused, shape = shape)
                    .clip(shape)
                    .background(colors.surface),
            ) {
                // L2: a held read is dimmed, so nothing pretends to be live. The words stay legible.
                Box(Modifier.fillMaxSize().alpha(if (held) LibraryScreenDesktopDefaults.HeldAlpha else 1f)) {
                    artwork(card)
                }
                card.progress?.let { progress ->
                    ProgressRule(progress = progress, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
            Column {
                BasicText(
                    text = card.title,
                    style = CloudStreamTheme.typography.label.copy(color = colors.ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Held at a line's height so a card with nothing to say lines up with one that has.
                CardMeta(card, Modifier.heightIn(min = LibraryScreenDesktopDefaults.MetaLineHeight))
            }
        }
        if (hovered || focused || removeFocused) {
            DesktopButton(
                label = words.remove,
                icon = CloudStreamIcons.Delete,
                onClick = { onAction(LibraryAction.RemoveRequested(card.id)) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DesktopDimens.RelatedGap)
                    .onFocusChanged { removeFocused = it.hasFocus },
            )
        }
    }
}

/**
 * L1's line under a title: its kind, the user's rating with the pack's star, and how far through it is, each where it
 * is known. A card with none of them names its provider, as before.
 */
@Composable
private fun CardMeta(card: LibraryCard, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val style = CloudStreamTheme.typography.caption.copy(color = colors.muted)
    val progress = card.metaLine()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LibraryScreenDesktopDefaults.MetaGap),
        modifier = modifier,
    ) {
        var first = true
        val separator = @Composable {
            if (!first) BasicText(text = LibraryScreenDesktopDefaults.META_SEPARATOR, style = style, maxLines = 1)
            first = false
        }
        card.typeLabel?.let {
            separator()
            BasicText(text = it, style = style, maxLines = 1)
        }
        card.rating?.let {
            separator()
            CloudStreamIcon(CloudStreamIcons.Star, null, size = CloudStreamIconSize.Small, tint = colors.muted)
            BasicText(text = it, style = style, maxLines = 1)
        }
        progress?.let {
            separator()
            BasicText(text = it, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The shape of what lands, so the cards replacing it do not move anything. */
@Composable
private fun Skeleton(posterSize: PosterSize) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - DesktopDimens.WindowMargin * 2
        val columns = LibraryScreenDesktopDefaults.columns(width, posterSize)
        val cardWidth = LibraryScreenDesktopDefaults.cardWidth(width, columns)
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.SectionGap),
            modifier = Modifier.fillMaxSize().padding(horizontal = DesktopDimens.WindowMargin),
        ) {
            repeat(LibraryScreenDesktopDefaults.SkeletonLines) {
                Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
                    repeat(columns) {
                        Box(
                            Modifier
                                .width(cardWidth)
                                .aspectRatio(LibraryScreenDesktopDefaults.CardRatio)
                                .clip(shape)
                                .background(colors.surface),
                        )
                    }
                }
            }
        }
    }
}

/** Values this screen needs that `:core:theme` does not name. */
object LibraryScreenDesktopDefaults {

    /**
     * The built minimum, which DS-22's notes keep: four to a line at L1's window, and two in the minimum one. L7 draws
     * three, which at 164 wide is under this, so L7 is the departure. Mirrors nothing in `dimens`.
     */
    val CardMinWidth: Dp = 200.dp

    /** Poster size's Small and Large here (`KMP-90e`); Medium is [CardMinWidth]. A step wider than Search's and Home's. */
    val SmallCardMinWidth: Dp = 160.dp
    val LargeCardMinWidth: Dp = 240.dp

    /** Posters, as everywhere else that draws one. */
    const val CardRatio = 2f / 3f

    /** The caption line box, so a card with no line is as tall as one with. Mirrors the desktop caption's 16. */
    val MetaLineHeight: Dp = 16.dp

    /** Between the parts of a card's line. Mirrors nothing: the separator carries its own spaces. */
    val MetaGap: Dp = 0.dp

    const val META_SEPARATOR = " · "

    /** L1's filter field. */
    val FilterWidth: Dp = 200.dp

    /** Below this the sync line and the filter give way (L7). Home's compact hero turns at the same width. */
    val CompactBelow: Dp = 720.dp

    /** L2's held cards. */
    const val HeldAlpha = 0.5f

    const val SkeletonLines = 2

    /** As home's picker dims the page behind it. */
    const val ScrimAlpha = 0.6f

    fun cardWidth(width: Dp, columns: Int): Dp =
        ((width - DesktopDimens.GroupGap * (columns - 1)) / columns).coerceAtLeast(0.dp)

    fun cardMinWidth(size: PosterSize): Dp = when (size) {
        PosterSize.Small -> SmallCardMinWidth
        PosterSize.Medium -> CardMinWidth
        PosterSize.Large -> LargeCardMinWidth
    }

    fun columns(width: Dp, posterSize: PosterSize = PosterSize.Medium): Int {
        val minimum = cardMinWidth(posterSize)
        return ((width + DesktopDimens.GroupGap) / (minimum + DesktopDimens.GroupGap)).toInt().coerceAtLeast(1)
    }
}
