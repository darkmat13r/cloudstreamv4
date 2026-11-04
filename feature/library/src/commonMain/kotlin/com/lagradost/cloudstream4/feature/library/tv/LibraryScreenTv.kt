package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.ChipRow
import com.lagradost.cloudstream4.core.designsystem.target.TextField
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.filter_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.library
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import org.jetbrains.compose.resources.stringResource

/**
 * The lists a TV shows as tabs, in order: `WatchType`'s five as 5a draws them, then Favourites.
 *
 * 5a draws no Favourites tab, but Android's old library screen has one on a TV and people's favourites are
 * in it, so leaving it out would hide their lists. Continue watching is not here: no board draws it in this
 * screen, and on a TV it belongs to home.
 */
val LibraryTvTabs: List<LibraryTab> = LibraryBucket.entries.map { LibraryTab.Bucket(it) }

/**
 * The TV library: `design/TV Library.dc.html` artboards **5a** (grid) and **5c** (empty page).
 *
 * Stateless, like `SearchScreenTv`: a [LibraryState] in, [LibraryAction]s out.
 *
 * ## What is here for 5b, 5d and 5e to land in
 *
 * The chrome row keeps 5a's three slots even though only the field is built. The left slot holds a readout
 * that the provider control replaces, the right slot is empty until the sort control fills it, and the
 * field already has the middle width it has on the board. So those controls move nothing. A provider's
 * failure lands on the left slot and its `Retry` beside it, both inside this band.
 *
 * ## Focus
 *
 * Nothing takes focus on arrival: the shell's rail holds it until the user presses Right. The action of an
 * empty page does not take it either (`requestInitialFocus = false`), because choosing an empty tab would
 * otherwise throw focus off the tab row every time and stepping across tabs would cost two extra presses.
 * It is the next stop down instead, which is what 5c asks for.
 *
 * LEFT from the first column goes to the rail, and RIGHT from the rail comes back to the same stop.
 *
 * Pressing that action removes the button under focus, so focus moves to the selected tab rather than
 * being dropped. A card that was opened gets focus back through [LibraryState.returnTo].
 *
 * @param artwork draws a title's poster. Image loading is the host's.
 */
@Composable
fun LibraryScreenTv(
    state: LibraryState,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (LibraryCard) -> Unit = {},
) {
    val selectedTab = remember { FocusRequester() }
    var promptPresses by remember { mutableIntStateOf(0) }
    val content = state.toTvUiState { action ->
        onAction(action)
        promptPresses++
    }

    LaunchedEffect(promptPresses, state.tab) {
        if (promptPresses > 0) selectedTab.requestFocusIfAttached()
    }

    // The grid restores a card it still has. Anything else, a title removed on its own page say, comes here.
    val returnTo = state.returnTo
    val status = state.status
    LaunchedEffect(returnTo, status) {
        if (returnTo == null || !returnTo.ready || status is LibraryStatus.Loading) return@LaunchedEffect
        val stillThere = (status as? LibraryStatus.Showing)?.cards.orEmpty().any { it.id == returnTo.id }
        if (stillThere) return@LaunchedEffect
        selectedTab.requestFocusIfAttached()
        onAction(LibraryAction.ReturnHandled)
    }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(CloudStreamDimens.ChromeRowTop))
        LibraryChromeRow(state, onAction, Modifier.contentInset().fillMaxWidth())
        Spacer(Modifier.height(LibraryScreenTvDefaults.ChromeToTabGap))
        LibraryTabRow(state, selectedTab, onAction)

        val region = Modifier.contentInset().fillMaxSize()
        when (content) {
            is UiState.Empty -> StateView(
                prompt = content.prompt,
                modifier = region,
                detail = content.detail,
                requestInitialFocus = false,
                glyph = { LibraryEmptyGlyph() },
            )

            is UiState.Error -> StateView(content.prompt, region, content.detail, requestInitialFocus = false)
            else -> UiStateHost(
                state = content,
                modifier = region,
                skeleton = { LibraryGridSkeleton(it) },
            ) { cards ->
                // Each list keeps its own scroll: a list you have not opened starts at its top.
                key(state.tab) {
                    LibraryGridTv(
                        cards = cards,
                        returnTo = returnTo,
                        onAction = onAction,
                        artwork = artwork,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 5a's chrome row, y56 to y152 on the artboard. Only the field is a control so far.
 *
 * The readout is plain text, with no fill and no focus: 5a draws it as a button, and something that looks
 * focusable and does nothing is the defect `StateAction` exists to prevent. It becomes the button when the
 * provider panel (5e) arrives.
 */
@Composable
private fun LibraryChromeRow(
    state: LibraryState,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier.height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryReadout(state.totalTitles, Modifier.width(LibraryScreenTvDefaults.ChromeSlotWidth))
        TextField(
            value = state.filter,
            onValueChange = { onAction(LibraryAction.FilterChanged(it)) },
            placeholder = stringResource(Res.string.filter_bookmarks),
            modifier = Modifier.weight(FIELD_WEIGHT),
            // The list narrows as it is typed, so there is nothing to submit except leaving the keyboard.
            onSubmit = { keyboard?.hide() },
            // `BUG-30`: passing through the row must not open a keyboard that then takes the D-pad.
            keyboardOnOk = true,
            leadingIcon = CloudStreamIcons.Search,
        )
        Spacer(Modifier.width(LibraryScreenTvDefaults.ChromeSlotWidth))
    }
}

/** The library's name and, where it is known, how many titles it holds. A 32dp stack, centred at +8. */
@Composable
private fun LibraryReadout(totalTitles: Int?, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val title = typography.label
    val count = typography.labelMono
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap)) {
        BasicText(
            text = stringResource(Res.string.library),
            style = remember(title, colors) { title.copy(color = colors.ink) },
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(CloudStreamDimens.StackTitleLineBox),
        )
        if (totalTitles != null) {
            BasicText(
                text = totalTitles.toString(),
                style = remember(count, colors) { count.copy(color = colors.muted) },
                maxLines = SINGLE_LINE,
                modifier = Modifier.height(CloudStreamDimens.StackSubtitleLineBox),
            )
        }
    }
}

/**
 * 5a's tabs, y176 to y272 on the artboard: the design system's chips, each with its list's real count.
 *
 * A tab carries a number only once the store has been read. A list with nothing in it reads 0, which 5c
 * shows, and that is a fact rather than a missing one.
 *
 * `ChipRow` does not scroll and says to raise it when chips do not fit. Six fit in English, and a long
 * language can overflow, which would put a tab where no key reaches it. So this scrolls the row from
 * outside rather than changing the component, with the inset as padding so the ring has room at both ends.
 */
@Composable
private fun LibraryTabRow(
    state: LibraryState,
    selectedTab: FocusRequester,
    onAction: (LibraryAction) -> Unit,
) {
    val counted = state.totalTitles != null
    ChipRow(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .contentInset(),
    ) {
        LibraryTvTabs.forEach { tab ->
            val selected = tab == state.tab
            Chip(
                label = tab.label(),
                selected = selected,
                onClick = { onAction(LibraryAction.TabSelected(tab)) },
                modifier = if (selected) Modifier.focusRequester(selectedTab) else Modifier,
                count = if (counted && tab is LibraryTab.Bucket) (state.counts[tab.bucket] ?: 0).toString() else null,
            )
        }
    }
}

/** 5c's tile: the glyph box `StateView` reserves, filled raised with a hairline, and the library's icon in it. */
@Composable
private fun LibraryEmptyGlyph() {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.large
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.raised, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        CloudStreamIcon(
            icon = CloudStreamIcons.Library,
            contentDescription = null,
            size = CloudStreamIconSize.Large,
            tint = colors.muted,
        )
    }
}

/** A `FocusRequester` with no node attached throws, and "nothing to focus yet" is not an error here. */
internal fun FocusRequester.requestFocusIfAttached() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // See above.
    }
}

/** The screen's horizontal inset. The grid carries its own, because a lazy grid must not be clipped. */
private fun Modifier.contentInset(): Modifier = padding(
    start = LibraryScreenTvDefaults.ContentInsetStart,
    end = LibraryScreenTvDefaults.ContentInsetEnd,
)

/** 5a's geometry, halved from the artboard. Each number mirrors the token or the board it names. */
object LibraryScreenTvDefaults {

    /** `ContentOriginX` less `NavRailWidth`: the rail is the shell's, so x100 is 24dp into this area. */
    val ContentInsetStart: Dp = CloudStreamDimens.ContentOriginX - CloudStreamDimens.NavRailWidth

    /** Mirrors `FocusBandInset`. */
    val ContentInsetEnd: Dp = CloudStreamDimens.FocusBandInset

    /** `ChipRowTop` less `ChromeRowBottom`. Equals `MinFocusGap`. */
    val ChromeToTabGap: Dp = CloudStreamDimens.ChipRowTop - CloudStreamDimens.ChromeRowBottom

    /** 5a's provider and sort buttons, 424px wide on the artboard. */
    val ChromeSlotWidth: Dp = 212.dp

    /**
     * From the tab row to the first card: y272 to y304 on the artboard. Mirrors `LiftedRowClearance`, which
     * is also what leaves a focused card's lift and ring room inside the grid's clip.
     */
    val TabToGridGap: Dp = CloudStreamDimens.LiftedRowClearance

    /** The card pitch across, 240px on the artboard less the 192px card. Mirrors `ChipRowSpacing`. */
    val GridColumnGap: Dp = CloudStreamDimens.ChipRowSpacing

    /** The row pitch down, 320px on the artboard less the 288px card. Mirrors `LiftedRowClearance`. */
    val GridRowGap: Dp = CloudStreamDimens.LiftedRowClearance

    /** Below the last row, for its ring. Mirrors `FocusBandInset`, as in `SearchScreenTvDefaults`. */
    val GridBottomInset: Dp = CloudStreamDimens.FocusBandInset
}

private const val FIELD_WEIGHT = 1f
private const val SINGLE_LINE = 1
