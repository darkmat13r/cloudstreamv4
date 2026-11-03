package com.lagradost.cloudstream4.feature.library.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.action_remove_from_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.select_library
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryCardWords
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import com.lagradost.cloudstream4.feature.library.libraryCardWords
import com.lagradost.cloudstream4.feature.library.toUiState
import org.jetbrains.compose.resources.stringResource

/**
 * What the user kept, for a resizable window driven by a pointer. `docs/plans/desktop-design.md`.
 *
 * One list at a time, chosen from a row of names that stays through every state — the same reason home's
 * source button does: changing list is the way out of a list that failed. Cards are a grid rather than
 * sideways rails, because a library is browsed by scanning and a mouse wheel scrolls one way.
 *
 * **What a card's click does depends on the list, deliberately.** On Continue watching it resumes, which
 * is the only thing anybody wants from that list. On a kept list it opens the title's page, because
 * choosing an episode, a source or a subtitle track belongs there and not here. So there is exactly one
 * button on a card — Remove — and what it removes is the list you are looking at.
 *
 * @param artwork draws a title's poster. Image loading is the host's.
 */
@Composable
fun LibraryScreenDesktop(
    state: LibraryState,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (LibraryCard) -> Unit = {},
) {
    val status = state.status.toUiState(onAction)
    val words = libraryCardWords()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabStrip(state.tab, onAction)
            val prompts = Modifier.fillMaxSize()
            when (val content = status) {
                // Never takes focus, as in home and search: a window opening on a failure has not asked for it.
                is UiState.Empty -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                is UiState.Error -> StateView(content.prompt, prompts, content.detail, requestInitialFocus = false)
                else -> UiStateHost(
                    state = content,
                    modifier = Modifier.fillMaxSize(),
                    skeleton = { Skeleton() },
                ) { cards -> Cards(cards, state.tab, words, onAction, artwork) }
            }
        }
        ListPanel(state, onAction)
    }
}

/**
 * The list picker: every list, the one it is in now marked, and the way out of all of them.
 *
 * A side panel rather than a menu under the button, because that is what this app's desktop screens
 * already do for a choice from a list (home's source picker) and because Compose has no menu primitive
 * here that is not Material's.
 */
@Composable
private fun ListPanel(state: LibraryState, onAction: (LibraryAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val dismiss = { onAction(LibraryAction.PickerDismissed) }
    val card = state.pickingCard
    val open = card != null
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = LibraryScreenDesktopDefaults.ScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                ),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        val spec: FiniteAnimationSpec<IntOffset> = DesktopMotion.spec()
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(spec) { it },
            exit = slideOutHorizontally(spec) { it },
        ) {
            // Held after the card goes, so the panel has something to draw while it slides out.
            val showing = remember(card?.id) { card } ?: return@AnimatedVisibility
            Column(
                Modifier
                    .width(DesktopDimens.SidePanelWidth)
                    .fillMaxHeight()
                    .background(colors.surface)
                    .border(CloudStreamDimens.Hairline, colors.line)
                    .padding(DesktopDimens.RowPadding),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = DesktopDimens.RowPadding, bottom = DesktopDimens.GroupGap),
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            text = stringResource(Res.string.select_library),
                            style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            text = showing.title,
                            style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DesktopButton(label = stringResource(Res.string.go_back), onClick = dismiss)
                }
                val shape = CloudStreamTheme.shapes.medium
                LibraryBucket.entries.forEach { bucket ->
                    DesktopRow(
                        title = LibraryTab.Bucket(bucket).label(),
                        onClick = { onAction(LibraryAction.BucketChosen(showing.id, bucket)) },
                        modifier = if (bucket == showing.bucket) {
                            Modifier.background(colors.raised, shape)
                        } else {
                            Modifier
                        },
                    )
                }
                DesktopRow(
                    title = stringResource(Res.string.action_remove_from_bookmarks),
                    onClick = { onAction(LibraryAction.RemoveRequested(showing.id)) },
                )
            }
        }
    }
}

/**
 * The list names, in one scrollable row.
 *
 * Seven names do not fit a minimum window, and a row that wraps moves the content under it every time a
 * name's translation changes length. It scrolls instead.
 */
@Composable
private fun TabStrip(selected: LibraryTab, onAction: (LibraryAction) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DesktopDimens.WindowMargin, vertical = DesktopDimens.GroupGap),
    ) {
        LibraryTab.all.forEach { tab ->
            DesktopButton(
                label = tab.label(),
                onClick = { onAction(LibraryAction.TabSelected(tab)) },
                primary = tab == selected,
            )
        }
    }
}

/** Lines of cards, as many to a line as the window is wide enough for. */
@Composable
private fun Cards(
    cards: List<LibraryCard>,
    tab: LibraryTab,
    words: LibraryCardWords,
    onAction: (LibraryAction) -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - DesktopDimens.WindowMargin * 2
        val columns = LibraryScreenDesktopDefaults.columns(width)
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
                CardLine(
                    cards = lines[line],
                    tab = tab,
                    words = words,
                    cardWidth = cardWidth,
                    onAction = onAction,
                    artwork = artwork,
                    modifier = Modifier.animateItem(
                        fadeInSpec = DesktopMotion.spec(),
                        placementSpec = DesktopMotion.spec(),
                        fadeOutSpec = null,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CardLine(
    cards: List<LibraryCard>,
    tab: LibraryTab,
    words: LibraryCardWords,
    cardWidth: Dp,
    onAction: (LibraryAction) -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap), modifier = modifier.fillMaxWidth()) {
        cards.forEach { card ->
            TitleCard(
                card = card,
                tab = tab,
                words = words,
                onAction = onAction,
                artwork = artwork,
                modifier = Modifier.width(cardWidth),
            )
        }
    }
}

@Composable
private fun TitleCard(
    card: LibraryCard,
    tab: LibraryTab,
    words: LibraryCardWords,
    onAction: (LibraryAction) -> Unit,
    artwork: @Composable (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.large
    val resuming = tab == LibraryTab.ContinueWatching
    val activate = {
        if (resuming) onAction(LibraryAction.ResumeActivated(card.id)) else onAction(LibraryAction.TitleActivated(card.id))
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier,
    ) {
        // The art and the title are the one target; Remove sits outside it, so a press on the button
        // cannot also open the title. Nesting one clickable inside another would leave that to whether
        // the button consumes the press, which is not a thing to leave to.
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.clickable(interactionSource = source, indication = null, onClick = activate),
        ) {
            // Only the art moves. Scaling the words too would make them shimmer on every hover.
            Box(
                modifier = Modifier
                    .desktopFeedback(source)
                    .fillMaxWidth()
                    .aspectRatio(LibraryScreenDesktopDefaults.CardRatio)
                    .tvFocusRing(focused = focused, shape = shape)
                    .clip(shape)
                    .background(colors.surface),
            ) {
                artwork(card)
                card.progress?.let { progress ->
                    ProgressRule(progress = progress, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
            BasicText(
                text = card.title,
                style = CloudStreamTheme.typography.label.copy(color = colors.ink),
                // Always two lines, so the buttons of a line of cards sit level. A one-line title
                // beside a two-line one put them 24 dp apart, which a render caught.
                minLines = LibraryScreenDesktopDefaults.TitleLines,
                maxLines = LibraryScreenDesktopDefaults.TitleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Held at the button's height so a card with no provider name is the same height as one with it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.fillMaxWidth().heightIn(min = DesktopDimens.ButtonHeight),
        ) {
            BasicText(
                text = card.providerName.orEmpty(),
                style = CloudStreamTheme.typography.caption.copy(color = colors.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DesktopButton(
                label = card.bucket?.let { LibraryTab.Bucket(it).label() } ?: words.setList,
                onClick = { onAction(LibraryAction.PickerOpened(card.id)) },
            )
        }
    }
}

/** The shape of what lands, so the cards replacing it do not move anything. */
@Composable
private fun Skeleton() {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - DesktopDimens.WindowMargin * 2
        val columns = LibraryScreenDesktopDefaults.columns(width)
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
     * Wider than home's portrait card (160) because this one carries a button, and two words of a
     * translated "Remove" beside a provider's name need the room. Mirrors nothing in `dimens`.
     */
    val CardMinWidth: Dp = 200.dp

    /** Posters, as everywhere else that draws one. */
    const val CardRatio = 2f / 3f

    const val TitleLines = 2

    const val SkeletonLines = 2

    /** As home's picker dims the page behind it. */
    const val ScrimAlpha = 0.6f

    fun cardWidth(width: Dp, columns: Int): Dp =
        ((width - DesktopDimens.GroupGap * (columns - 1)) / columns).coerceAtLeast(0.dp)

    fun columns(width: Dp): Int =
        ((width + DesktopDimens.GroupGap) / (CardMinWidth + DesktopDimens.GroupGap)).toInt().coerceAtLeast(1)
}
