package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StateActionButton
import com.lagradost.cloudstream4.core.designsystem.state.StateActionEmphasis
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.failureNotice
import com.lagradost.cloudstream4.core.designsystem.target.IconCell
import com.lagradost.cloudstream4.core.designsystem.target.IconOnlyButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.extensions
import com.lagradost.cloudstream4.core.strings.generated.resources.home_bookmarks_count
import com.lagradost.cloudstream4.core.strings.generated.resources.home_offline_bookmarks_body
import com.lagradost.cloudstream4.core.strings.generated.resources.home_offline_source_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.home_random
import com.lagradost.cloudstream4.core.strings.generated.resources.none
import com.lagradost.cloudstream4.core.strings.generated.resources.reload_provider
import com.lagradost.cloudstream4.core.strings.generated.resources.search
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_offline_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.switch_account
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeHero
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeReturn
import com.lagradost.cloudstream4.feature.home.HomeRow
import com.lagradost.cloudstream4.feature.home.HomeRowKey
import com.lagradost.cloudstream4.feature.home.HomeState
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.rows
import com.lagradost.cloudstream4.feature.home.shown
import com.lagradost.cloudstream4.feature.home.toUiState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The TV home page: `design/TV Home.dc.html` artboards **3c** and **3d**.
 *
 * Stateless, like `SearchScreenTv`: a [HomeState] in, [HomeAction]s out.
 *
 * ## The page, top to bottom
 *
 * One list with keyed items: the chrome with the hero under it (3c), then Continue watching, then the bookmarks rail
 * (`TV Home Extras` 3e), then the source's rails (3d, which is this page scrolled). The hero is part of the chrome's item
 * because its art runs behind the chrome. Continue watching and the bookmarks are this device's and not the source's, so
 * they stay above a failure, being offline (3g), or a page still loading (3f).
 *
 * ## Focus
 *
 * Nothing is taken until the host says it has resumed ([HomeState.appeared]), because focus taken before that does not
 * last on a TV. After that:
 *
 * - **A title that was opened** gets focus back ([HomeReturn]), on the hero, Continue watching or a rail.
 * - **The first rows to land** take it for the first thing on the page, from the chrome if it was on the chrome: the hero's
 *   button, which is where 3c draws it at rest, else the first Continue watching card, else the first card. They wait for
 *   the hero to be found or given up, so focus lands once. Not if [mayTakeFocus] says it has gone elsewhere, which on a
 *   TV means a person opened the nav rail while the page was loading, nor if it is already on Continue watching.
 * - **A state's own button** takes it when that state appears, under the same condition.
 * - **The source control keeps it** while another source loads, so the person can choose again.
 *
 * @param artwork draws a title's poster. Image loading is the host's.
 * @param mayTakeFocus whether focus is this screen's to move. Read at the moment it would move.
 * @param hostFocus the host's own request for focus as the page first opens, so that focus it gives is not taken for a
 *   person's choice. Null on a host that makes none.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreenTv(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (HomeItem) -> Unit = {},
    mayTakeFocus: () -> Boolean = { true },
    hostFocus: HomeHostFocus? = null,
) {
    val installLabel = stringResource(Res.string.extensions)
    val install = remember(installLabel, onAction) {
        StateAction(installLabel, CloudStreamIcons.Repository) { onAction(HomeAction.ExtensionsRequested) }
    }
    val content = state.status.toUiState(state.selectedOption?.name, install, onAction)
    val rows = state.status.rows
    val returnTo = state.returnTo
    val list = rememberLazyListState(returnTo?.firstVisibleRow ?: 0, returnTo?.firstVisibleRowOffset ?: 0)
    var focusTarget by remember { mutableStateOf<HomeFocusTarget?>(null) }
    var screenHasFocus by remember { mutableStateOf(false) }
    var continueHasFocus by remember { mutableStateOf(false) }
    var bookmarksHaveFocus by remember { mutableStateOf(false) }
    // A view built again, coming back by the nav rail, lands once as the first open does. Measured in `:app`: the window
    // gives a rebuilt view's focus to its first stop, the source control, before the rows can, and it stayed there.
    var landingInView by remember { mutableStateOf(true) }
    // Whether the focus the page holds is the one the host's own request gave it, or the window gave it before the host
    // resumed the page ([hostFocus]), with no key pressed since. Those are the arrivals that are not a person's (`BUG-41`): the bookmarks are one local read, so at launch they can be
    // on screen before the rows, and the host's first request then lands on a chip. Every other arrival in Continue
    // watching or the bookmarks, from a key on this page or from the nav rail, which is a view of its own, is a choice.
    var focusFromHost by remember { mutableStateOf(false) }
    // Whether a key has been pressed on this page since it was built. A focus the host finds already held can be the
    // window's only while nothing has been pressed: after a key, wherever focus is, a person put it there.
    var pressedOnPage by remember { mutableStateOf(false) }
    val hasRows = rows.isNotEmpty()
    // Only beside the rows it came from: a page that failed or went offline has no hero, whatever was found before.
    val hero = (state.hero as? HomeHero.Shown)?.takeIf { hasRows }
    val heroSettled = state.hero !is HomeHero.Loading
    val resume = state.history.resume
    val hasContinue = resume.isNotEmpty()
    // Absent with no bookmarks at all, as the old rail is. A filter that shows none still draws it (3f).
    val bookmarks = state.bookmarks
    val hasBookmarks = bookmarks.all.isNotEmpty()
    val shownBookmarks = bookmarks.shown
    val hasLocalRails = hasContinue || hasBookmarks
    val itemsBeforeBookmarks = if (hasContinue) ITEMS_BEFORE_CONTINUE + 1 else ITEMS_BEFORE_CONTINUE
    val itemsBeforeRails = if (hasBookmarks) itemsBeforeBookmarks + 1 else itemsBeforeBookmarks
    val offline = state.status.shown is HomeStatus.Offline
    val unsourced = state.status == HomeStatus.Unsourced
    val scope = rememberCoroutineScope()
    val stops = remember { HomeStops() }

    // A title that was opened. The rows are on screen by now, because they outlive the view.
    LaunchedEffect(returnTo?.ready, hasRows, hero != null, hasContinue, hasBookmarks, state.status is HomeStatus.Loading) {
        if (returnTo == null || !returnTo.ready || state.status is HomeStatus.Loading) return@LaunchedEffect
        val row = rows.firstOrNull { it.key == returnTo.rowKey }
        val back = when (returnTo.rowKey) {
            HomeRowKey.Hero -> hero?.takeIf { it.featured.id == returnTo.id }?.let { heroTarget(it.featured) }
            HomeRowKey.Continue -> resume.firstOrNull { it.id == returnTo.id }?.let { continueTarget(it) }
            HomeRowKey.Bookmarks -> shownBookmarks.firstOrNull { it.id == returnTo.id }?.let { bookmarkTarget(it) }
            // Random's cell, which is there only beside rows.
            HomeRowKey.Chrome -> HomeFocusTarget(HomeRowKey.Chrome, returnTo.id).takeIf { hasRows }
            else -> row?.items?.firstOrNull { it.id == returnTo.id }?.let { HomeFocusTarget(row.key, it.id) }
        }
        val fallback = back ?: landingTarget(hero, resume, shownBookmarks, rows)
        when {
            back != null -> focusTarget = back
            fallback != null -> {
                list.scrollToItem(0)
                focusTarget = fallback
            }
            else -> onAction(HomeAction.ReturnHandled)
        }
    }

    // Rows landing, on a page that opened with none or whose focus went with what they replaced.
    // With `None` chosen nothing comes from a source, so what the device keeps is what lands (3i).
    val landable = hasRows || (state.unsourced && (hasContinue || shownBookmarks.isNotEmpty()))
    LaunchedEffect(landable, state.appeared, returnTo == null, heroSettled) {
        if (!landable || !state.appeared || returnTo != null || !heroSettled) return@LaunchedEffect
        // After a frame, so that a button these rows replaced has given focus up.
        withFrameNanos { }
        val inLocalRail = continueHasFocus || bookmarksHaveFocus
        val free = (state.landingOwed || landingInView || !screenHasFocus) && !(inLocalRail && !focusFromHost)
        if (free && !state.pickerOpen && mayTakeFocus()) focusTarget = landingTarget(hero, resume, shownBookmarks, rows)
        // A request that gave the page no focus must not mark a later arrival as the host's.
        hostFocus?.requested = false
        landingInView = false
        if (state.landingOwed) onAction(HomeAction.LandingHandled)
    }
    // The window's own grant, which the host found already made as it resumed the page (`HomeHostFocus.resumed`).
    LaunchedEffect(hostFocus?.adopted) {
        val host = hostFocus ?: return@LaunchedEffect
        if (!host.adopted) return@LaunchedEffect
        if (screenHasFocus && !pressedOnPage) focusFromHost = true
        host.adopted = false
    }
    val onFocusTaken: () -> Unit = {
        // Whatever took it, a return included, is this view's landing.
        landingInView = false
        focusTarget = null
        if (returnTo != null) onAction(HomeAction.ReturnHandled)
    }

    SidePanelHost(
        expanded = state.pickerOpen,
        onDismissRequest = { onAction(HomeAction.PickerDismissed) },
        modifier = modifier
            .onFocusChanged {
                if (it.hasFocus && !screenHasFocus) {
                    focusFromHost = hostFocus?.requested == true
                    hostFocus?.requested = false
                }
                if (!it.hasFocus) focusFromHost = false
                screenHasFocus = it.hasFocus
            }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    focusFromHost = false
                    pressedOnPage = true
                }
                false
            },
        panel = { HomeSourcePanelTv(state, onAction) },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().homeStopsGroup(stops)) {
            val density = LocalDensity.current
            val band = with(density) { CloudStreamDimens.FocusBandInset.toPx() }
            val above = with(density) { CloudStreamDimens.ChromeRowTop.toPx() }
            val scrollTo: (Int) -> Unit = { index ->
                val info = list.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                val fits = item != null &&
                    HomeFocusScroll.railFits(item.offset + above, item.size.toFloat(), info.viewportSize.height.toFloat(), band)
                if (!fits) scope.launch { list.animateScrollToItem(index) }
            }
            // Continue watching sits above a state, so the state has what is left: 216.8dp at 720p. Offline fits in it once
            // its body goes (below). Any state that still does not fit grows rather than squeezing its button, which a fixed
            // height did to an empty ring (measured on the recorded screenshot), and when its button takes focus the page
            // moves until the chrome is wholly off the screen: moved by less, the chrome was left 4px from the top edge,
            // where a TV's overscan cuts it. The bookmarks rail above a state is the same (3g, 3m).
            val continueRoom = if (hasContinue) ContinueRailHeight + CloudStreamDimens.MinFocusGap else 0.dp
            val bookmarksRoom = if (hasBookmarks) BookmarksRailHeight + CloudStreamDimens.MinFocusGap else 0.dp
            val promptHeight = maxHeight - HomeScreenTvDefaults.PageAboveContent - CloudStreamDimens.FocusBandInset -
                continueRoom - bookmarksRoom
            val revealState: () -> Unit = {
                val info = list.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == itemsBeforeRails }
                val chrome = info.visibleItemsInfo.firstOrNull { it.index == 0 }
                if (item != null) {
                    val overflow = item.offset + above + item.size - (info.viewportSize.height - band)
                    val chromeOnScreen = chrome?.let { it.offset + above + it.size } ?: 0f
                    // A state sized to exactly what is left can come out a pixel or two over, from the bands above each
                    // rounding to whole pixels (measured under the bookmarks, which then moved the chrome off for nothing).
                    if (overflow > ROUNDING_PX) {
                        scope.launch { list.animateScrollBy(maxOf(overflow, chromeOnScreen.toFloat())) }
                    }
                }
            }
            val promptModifier = if (hasLocalRails) {
                Modifier.onFocusChanged { if (it.hasFocus) revealState() }
            } else {
                Modifier
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides HomeNoScrollSpec) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
                    contentPadding = PaddingValues(
                        top = CloudStreamDimens.ChromeRowTop,
                        bottom = CloudStreamDimens.FocusBandInset,
                    ),
                ) {
                    item(key = CHROME_KEY) {
                        Box(Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) scrollTo(0) }) {
                            hero?.let { HomeHeroBackdrop(it.featured, artwork) }
                            Column {
                                HomeChromeRow(
                                    state = state,
                                    notice = content.failureNotice,
                                    offlineBookmarks = bookmarks.all.size.takeIf { offline && hasBookmarks },
                                    stops = stops,
                                    onAction = onAction,
                                    focusTarget = focusTarget,
                                    onFocusTaken = onFocusTaken,
                                    onRandom = {
                                        onAction(
                                            HomeAction.RandomRequested(
                                                from = HomeReturn(
                                                    id = RANDOM_CELL,
                                                    rowKey = HomeRowKey.Chrome,
                                                    firstVisibleRow = list.firstVisibleItemIndex,
                                                    firstVisibleRowOffset = list.firstVisibleItemScrollOffset,
                                                ),
                                            ),
                                        )
                                    },
                                    modifier = Modifier.contentInset(),
                                )
                                hero?.let {
                                    HomeHeroTv(
                                        hero = it,
                                        takeFocus = focusTarget == heroTarget(it.featured),
                                        onFocusTaken = onFocusTaken,
                                        onOpen = {
                                            onAction(
                                                HomeAction.TitleActivated(
                                                    id = it.featured.id,
                                                    from = HomeReturn(
                                                        id = it.featured.id,
                                                        rowKey = HomeRowKey.Hero,
                                                        firstVisibleRow = list.firstVisibleItemIndex,
                                                        firstVisibleRowOffset = list.firstVisibleItemScrollOffset,
                                                    ),
                                                ),
                                            )
                                        },
                                        stops = stops,
                                        modifier = Modifier.contentInset(),
                                    )
                                }
                            }
                        }
                    }
                    if (hasContinue) {
                        item(key = CONTINUE_KEY) {
                            val returning = returnTo?.takeIf { it.rowKey == HomeRowKey.Continue }
                            HomeContinueTv(
                                entries = resume,
                                focusTarget = focusTarget,
                                initialCard = returning?.firstVisibleCard ?: 0,
                                initialCardOffset = returning?.firstVisibleCardOffset ?: 0,
                                onFocusTaken = onFocusTaken,
                                onOpen = { entry, rail ->
                                    onAction(
                                        HomeAction.TitleActivated(
                                            id = entry.id,
                                            from = HomeReturn(
                                                id = entry.id,
                                                rowKey = HomeRowKey.Continue,
                                                firstVisibleRow = list.firstVisibleItemIndex,
                                                firstVisibleRowOffset = list.firstVisibleItemScrollOffset,
                                                firstVisibleCard = rail.firstVisibleItemIndex,
                                                firstVisibleCardOffset = rail.firstVisibleItemScrollOffset,
                                            ),
                                        ),
                                    )
                                },
                                stops = stops,
                                artwork = artwork,
                                modifier = Modifier.onFocusChanged {
                                    continueHasFocus = it.hasFocus
                                    if (it.hasFocus) scrollTo(ITEMS_BEFORE_CONTINUE)
                                },
                            )
                        }
                    }
                    if (hasBookmarks) {
                        item(key = BOOKMARKS_KEY) {
                            val returning = returnTo?.takeIf { it.rowKey == HomeRowKey.Bookmarks }
                            HomeBookmarksTv(
                                bookmarks = bookmarks,
                                focusTarget = focusTarget,
                                initialCard = returning?.firstVisibleCard ?: 0,
                                initialCardOffset = returning?.firstVisibleCardOffset ?: 0,
                                onFocusTaken = onFocusTaken,
                                onOpen = { item, rail ->
                                    onAction(
                                        HomeAction.TitleActivated(
                                            id = item.id,
                                            from = HomeReturn(
                                                id = item.id,
                                                rowKey = HomeRowKey.Bookmarks,
                                                firstVisibleRow = list.firstVisibleItemIndex,
                                                firstVisibleRowOffset = list.firstVisibleItemScrollOffset,
                                                firstVisibleCard = rail.firstVisibleItemIndex,
                                                firstVisibleCardOffset = rail.firstVisibleItemScrollOffset,
                                            ),
                                        ),
                                    )
                                },
                                onFilter = { onAction(HomeAction.BookmarkFilterPressed(it)) },
                                stops = stops,
                                artwork = artwork,
                                progress = state.history.progress,
                                dimmed = offline,
                                modifier = Modifier.onFocusChanged {
                                    bookmarksHaveFocus = it.hasFocus
                                    if (it.hasFocus) scrollTo(itemsBeforeBookmarks)
                                },
                            )
                        }
                    }
                    when (content) {
                        is UiState.Loading -> items(SKELETON_RAILS, key = { "skeleton/$it" }) { HomeRailSkeleton() }
                        // `None` with something kept on the device is 3i: the kept rows alone, and no prompt under them.
                        is UiState.Empty -> if (!(unsourced && hasLocalRails)) {
                            item(key = STATE_KEY) {
                                HomeStatePrompt(content.prompt, content.detail, promptHeight, hasLocalRails, state, mayTakeFocus, promptModifier)
                            }
                        }
                        is UiState.Offline -> item(key = STATE_KEY) {
                            // Under Continue watching the headline says it alone: the body is four lines, 80dp more than
                            // the 720p screen has left, and its first sentence repeats the headline. Under the bookmarks it
                            // says what needs the connection, as 3g does, and the chrome says that the page is offline.
                            val prompt = offlinePrompt(content.prompt, state.selectedOption?.name, hasBookmarks, hasContinue)
                            HomeStatePrompt(prompt, null, promptHeight, hasLocalRails, state, mayTakeFocus, promptModifier)
                        }
                        is UiState.Error -> item(key = STATE_KEY) {
                            // 3m: under what the device keeps, the failure is a 48dp band where the source's rails were,
                            // not a page. With neither local rail it is the built full-page error.
                            if (hasLocalRails) {
                                HomeFailureBand(content.prompt, content.detail, state, mayTakeFocus, stops, promptModifier)
                            } else {
                                HomeStatePrompt(content.prompt, content.detail, promptHeight, hasLocalRails, state, mayTakeFocus, promptModifier)
                            }
                        }
                        // A row's key is a string, because a lazy list's key must fit in a Bundle on Android.
                        is UiState.Partial, is UiState.Content -> Unit
                    }
                    // Room under a state below Continue watching for the page to move the chrome wholly off the screen. A
                    // list scrolls only as far as its content reaches: without this it stopped at the state's own overflow,
                    // 15px, and left the chrome in the overscan (measured).
                    val isState = content is UiState.Empty || content is UiState.Offline || content is UiState.Error
                    if (hasLocalRails && isState && !unsourced) {
                        item(key = STATE_ROOM_KEY) { Spacer(Modifier.height(CloudStreamDimens.ChromeRowBottom)) }
                    }
                    when (content) {
                        is UiState.Loading, is UiState.Empty, is UiState.Offline, is UiState.Error -> Unit
                        is UiState.Partial, is UiState.Content -> itemsIndexed(
                            items = rows,
                            key = { _, row -> "row/${row.key.section}/${row.key.name}" },
                        ) { index, row ->
                            val returning = returnTo?.takeIf { it.rowKey == row.key }
                            HomeRailTv(
                                row = row,
                                focusTarget = focusTarget,
                                initialCard = returning?.firstVisibleCard ?: 0,
                                initialCardOffset = returning?.firstVisibleCardOffset ?: 0,
                                onFocusTarget = { focusTarget = it },
                                onFocusTaken = onFocusTaken,
                                onOpen = { item, rail ->
                                    onAction(
                                        HomeAction.TitleActivated(
                                            id = item.id,
                                            from = HomeReturn(
                                                id = item.id,
                                                rowKey = row.key,
                                                firstVisibleRow = list.firstVisibleItemIndex,
                                                firstVisibleRowOffset = list.firstVisibleItemScrollOffset,
                                                firstVisibleCard = rail.firstVisibleItemIndex,
                                                firstVisibleCardOffset = rail.firstVisibleItemScrollOffset,
                                            ),
                                        ),
                                    )
                                },
                                stops = stops,
                                onAction = onAction,
                                artwork = artwork,
                                progress = state.history.progress,
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) scrollTo(index + itemsBeforeRails)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The host's own focus request as the page first opens (`HomeTvFragment.onResume`, `BUG-41`). The window gives that focus
 * to the first stop it finds, which can be a bookmark chip; marked, the page still lands on the hero.
 */
class HomeHostFocus {
    internal var requested = false

    /**
     * Set where the page already held focus when the host resumed it with a landing owed: the window gave it on its own,
     * and nothing has been pressed, so that focus is not a person's either. The screen takes it as the host's and clears
     * this. Observed, because the arrival it describes has already happened.
     */
    internal var adopted by mutableStateOf(false)

    /** Runs the host's [requestFocus] for a page that does not hold focus, marking the arrival it causes as the host's. */
    fun request(requestFocus: () -> Boolean) {
        requested = true
        if (!requestFocus()) requested = false
    }

    /**
     * What a host does as the page resumes, with a landing still owed: asks for focus through [request] where the page
     * holds none, and **marks the focus it already holds as the host's** where it does (`BUG-41`, reopened). The window
     * can give the page focus on its own before the host resumes it, onto the first stop it finds, a chip when the
     * bookmarks are on screen before the rows; #183 marked only the arrival its own request caused, so that one counted as
     * a person's choice and the hero never took focus. One place, so a host and a test make the same decision.
     *
     * @param landingOwed `HomeState.landingOwed`: nothing has been pressed on this page since it opened.
     */
    fun resumed(landingOwed: Boolean, hasFocus: () -> Boolean, requestFocus: () -> Boolean) {
        if (!landingOwed) return
        if (hasFocus()) adopted = true else request(requestFocus)
    }
}

/**
 * The first thing on the page: the hero's button, else the first Continue watching card, else the first bookmark shown,
 * else the first card.
 */
private fun landingTarget(
    hero: HomeHero.Shown?,
    resume: List<HomeResume>,
    bookmarks: List<HomeItem>,
    rows: List<HomeRow>,
): HomeFocusTarget? {
    if (hero != null) return heroTarget(hero.featured)
    resume.firstOrNull()?.let { return continueTarget(it) }
    bookmarks.firstOrNull()?.let { return bookmarkTarget(it) }
    val row = rows.firstOrNull() ?: return null
    return row.items.firstOrNull()?.let { HomeFocusTarget(row.key, it.id) }
}

private fun continueTarget(entry: HomeResume) = HomeFocusTarget(HomeRowKey.Continue, entry.id)

private fun bookmarkTarget(item: HomeItem) = HomeFocusTarget(HomeRowKey.Bookmarks, item.id)

/**
 * Offline's words where something local stays above them. Under the bookmarks, 3g's: what needs the connection, and that
 * the bookmarks are kept. Under Continue watching, the headline alone, which is all there is room for at 720p.
 */
@Composable
private fun offlinePrompt(prompt: StatePrompt, sourceName: String?, underBookmarks: Boolean, underContinue: Boolean): StatePrompt {
    if (!underBookmarks) return if (underContinue) prompt.copy(body = "") else prompt
    val headline = sourceName?.let { UiString.Resource(Res.string.home_offline_source_headline, listOf(it)).resolve() }
        ?: prompt.headline
    val body = if (underContinue) "" else stringResource(Res.string.home_offline_bookmarks_body)
    return StatePrompt(headline = headline, body = body, action = prompt.action)
}

/**
 * A full-surface state, taking focus when it appears if that is this screen's to take.
 *
 * @param grows whether [height] is a floor rather than the height: under Continue watching what is left can be less than
 *   the state's words need.
 */
@Composable
private fun HomeStatePrompt(
    prompt: StatePrompt,
    detail: String?,
    height: Dp,
    grows: Boolean,
    state: HomeState,
    mayTakeFocus: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val takeFocus = remember(state.appeared) { state.appeared && !state.pickerOpen && mayTakeFocus() }
    val sized = if (grows) Modifier.heightIn(min = height) else Modifier.height(height)
    StateView(prompt, modifier.fillMaxWidth().then(sized).contentInset(), detail, requestInitialFocus = takeFocus)
}

/**
 * 3m's `.slot.notice`: a failure under the rails the device keeps, as one 48dp band where the source's rails were. It is
 * [HomeFailureNotice], the chrome's partial-failure notice: the hollow marker, what failed ("Could not load <source>"), the
 * source's mono detail, and Try again, here at the band's end. Try again takes focus when the band appears, as the
 * full-page state's button does.
 */
@Composable
private fun HomeFailureBand(
    prompt: StatePrompt,
    detail: String?,
    state: HomeState,
    mayTakeFocus: () -> Boolean,
    stops: HomeStops,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.small
    val takeFocus = remember(state.appeared) { state.appeared && !state.pickerOpen && mayTakeFocus() }
    val retry = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        retry.requestFocusIfAttached()
    }
    HomeFailureNotice(
        notice = FailureNotice(headline = prompt.body, detail = detail.orEmpty(), retry = prompt.action),
        stops = stops,
        retryAtEnd = true,
        retryModifier = Modifier.focusRequester(retry),
        modifier = modifier
            .fillMaxWidth()
            .contentInset()
            .height(CloudStreamDimens.ChipFocusCell)
            .background(colors.surface, shape)
            .border(CloudStreamDimens.Hairline, colors.line, shape)
            .padding(start = HomeScreenTvDefaults.FailureBandPaddingStart, end = HomeScreenTvDefaults.FailureBandPaddingEnd),
    )
}

/**
 * The top of the page: what failed on the left, as `TV Search` 4b draws a shortfall inside a row rather than in a band of
 * its own, and 3c's source control and account cell on the right.
 *
 * 3c's clock and its two other cells are the next board's.
 */
@Composable
private fun HomeChromeRow(
    state: HomeState,
    notice: FailureNotice?,
    offlineBookmarks: Int?,
    stops: HomeStops,
    onAction: (HomeAction) -> Unit,
    focusTarget: HomeFocusTarget?,
    onFocusTaken: () -> Unit,
    onRandom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRows = state.status.rows.isNotEmpty() && !state.unsourced
    val searchable = state.selectedOption != null && state.status.shown !is HomeStatus.Offline
    Row(
        modifier = modifier.fillMaxWidth().height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The notice takes the free width rather than sharing it with a spacer: shared, its headline was squeezed to
        // nothing (measured on the recorded screenshot).
        when {
            notice != null -> HomeFailureNotice(notice, stops, Modifier.weight(1f))
            offlineBookmarks != null -> HomeOfflineNotice(offlineBookmarks, Modifier.weight(1f))
            else -> Spacer(Modifier.weight(1f))
        }
        val sourceName = if (state.unsourced) stringResource(Res.string.none) else state.selectedOption?.name
        sourceName?.let { name ->
            HomeSourceControl(name = name, stops, onClick = { onAction(HomeAction.PickerOpened) })
        }
        // 3j's cells, in the old TV order. Reload and Random need loaded rows, and Search a source and a connection: where
        // they cannot work they are absent, and the state's own button does the job.
        val cell: @Composable (ImageVector, String, String, () -> Unit) -> Unit = { icon, label, name, onClick ->
            HomeChromeCell(icon, label, name, stops, focusTarget, onFocusTaken, onClick)
        }
        if (hasRows) {
            cell(CloudStreamIcons.Retry, stringResource(Res.string.reload_provider), RELOAD_CELL) {
                onAction(HomeAction.ReloadRequested)
            }
            cell(CloudStreamIcons.Random, stringResource(Res.string.home_random), RANDOM_CELL, onRandom)
        }
        if (searchable) {
            cell(CloudStreamIcons.Search, stringResource(Res.string.search), SEARCH_CELL) {
                onAction(HomeAction.SourceSearchRequested)
            }
        }
        val accounts = stringResource(Res.string.switch_account)
        val colors = CloudStreamTheme.colors
        // 3c's `.gl2` is `muted` at rest; the glyph turns `ink` only when its cell is focused.
        IconCell(onClick = { onAction(HomeAction.AccountsRequested) }, modifier = Modifier.homeStop(stops)) { focused ->
            CloudStreamIcon(
                CloudStreamIcons.Profile,
                contentDescription = accounts,
                tint = if (focused) colors.ink else colors.muted,
            )
        }
    }
}

/**
 * One of 3j's icon-only cells. `IconOnlyButton` names it under the cell while focused, and carries it as a description.
 * Takes focus when [focusTarget] names it, which is how Random's cell gets focus back when its title closes.
 */
@Composable
private fun HomeChromeCell(
    icon: ImageVector,
    label: String,
    cell: String,
    stops: HomeStops,
    focusTarget: HomeFocusTarget?,
    onFocusTaken: () -> Unit,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val takeFocus = focusTarget == HomeFocusTarget(HomeRowKey.Chrome, cell)
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        requester.requestFocusIfAttached()
        onFocusTaken()
    }
    IconOnlyButton(
        icon = icon,
        label = label,
        onClick = onClick,
        modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) stops.last = requester },
    )
}

/**
 * `FailureBanner`'s content: a hollow neutral marker, which is what `DEC-17` rules a failure looks like, and the retry as the
 * `ink` primary, as `FailureBanner` draws it since `DS-14` (`TV Search` 4b's `.bbtn`).
 *
 * @param retryAtEnd puts the retry at the row's end rather than beside the detail, as 3m's band draws it.
 * @param retryModifier applied to the retry, after its focus stop.
 */
@Composable
private fun HomeFailureNotice(
    notice: FailureNotice,
    stops: HomeStops,
    modifier: Modifier = Modifier,
    retryAtEnd: Boolean = false,
    retryModifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val labels: @Composable RowScope.() -> Unit = {
        StatusDot(StatusDotState.Failed)
        BasicText(
            text = notice.headline,
            style = typography.label.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        BasicText(
            text = notice.detail,
            style = typography.labelMono.copy(color = colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = HomeScreenTvDefaults.FailureDetailMaxWidth),
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (retryAtEnd) {
            // The labels take the free width as one group, so the retry sits at the row's end and the headline still
            // ellipsizes rather than pushing it off.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = labels,
            )
        } else {
            labels()
            Spacer(Modifier.width(CloudStreamDimens.ListRowPadding))
        }
        StateActionButton(notice.retry, Modifier.homeStop(stops).then(retryModifier), StateActionEmphasis.Primary)
    }
}

/**
 * 3g's `.notice`: being offline said in the chrome, with how many bookmarks are kept, because the prompt below names what
 * needs the connection. No button: the prompt's Try again is the way out, and one button for one thing is enough.
 */
@Composable
private fun HomeOfflineNotice(bookmarks: Int, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(StatusDotState.Failed)
        BasicText(
            text = stringResource(Res.string.search_state_offline_headline),
            style = typography.label.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        BasicText(
            text = UiString.Plural(Res.plurals.home_bookmarks_count, bookmarks).resolve(),
            style = typography.labelMono.copy(color = colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = HomeScreenTvDefaults.FailureDetailMaxWidth),
        )
    }
}

/** 3c's `.srcpill`, 320x64px on the artboard: which source this is, and the way to another. */
@Composable
private fun HomeSourceControl(name: String, stops: HomeStops, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val role = stringResource(Res.string.home_source)
    val style = CloudStreamTheme.typography.label
    Row(
        modifier = Modifier
            .homeStop(stops)
            .height(CloudStreamDimens.TextButtonHeight)
            .widthIn(min = HomeScreenTvDefaults.SourceControlMinWidth, max = HomeScreenTvDefaults.SourceControlMaxWidth)
            .background(colors.raised, shape)
            .tvFocusRing(focused = focused, shape = shape)
            .semantics { contentDescription = "$role · $name" }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = HomeScreenTvDefaults.SourceControlPadding),
        // The glyph at the far end, as 3c's `margin-left: auto` puts it, while the name decides the width.
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = name,
            style = remember(style, colors) { style.copy(color = colors.ink) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // No weight: a Row with a weighted child takes its whole maximum width, filling or not (measured: the
            // screenshot did not move with `fill = false`). A cap on the name lets the control fit its content.
            modifier = Modifier
                .widthIn(max = HomeScreenTvDefaults.SourceNameMaxWidth)
                .padding(end = CloudStreamDimens.ListRowPadding),
        )
        CloudStreamIcon(
            CloudStreamIcons.Disclosure,
            contentDescription = null,
            size = CloudStreamIconSize.Small,
            tint = colors.muted,
        )
    }
}

/** The inset of everything that is not a rail. A rail carries its own, so its cards are not clipped at the edge. */
private fun Modifier.contentInset(): Modifier = padding(
    start = HomeScreenTvDefaults.ContentInsetStart,
    end = HomeScreenTvDefaults.ContentInsetEnd,
)

/** 3d's geometry, halved from the artboard. Each number mirrors the token or the board it names. */
object HomeScreenTvDefaults {

    /** `ContentOriginX` less `NavRailWidth`: the rail is the shell's, so x100 is 24dp into this area. */
    val ContentInsetStart: Dp = CloudStreamDimens.ContentOriginX - CloudStreamDimens.NavRailWidth

    /** Mirrors `FocusBandInset`. */
    val ContentInsetEnd: Dp = CloudStreamDimens.FocusBandInset

    /** 3d's `.row { gap: 24px }`, so nine cards fit where it draws them. Mirrors `MinFocusGap`. */
    val CardGap: Dp = CloudStreamDimens.MinFocusGap

    /** The page above the first thing under the chrome. */
    val PageAboveContent: Dp = CloudStreamDimens.ChromeRowBottom + CloudStreamDimens.MinFocusGap

    /** 3c's `.srcpill { min-width: 320px }`. */
    val SourceControlMinWidth: Dp = 160.dp

    /** So a source's name cannot take the row from what failed. */
    val SourceControlMaxWidth: Dp = 240.dp

    /** 3c's `.srcpill { padding: 0 24px }`. */
    val SourceControlPadding: Dp = 12.dp

    /** What is left of [SourceControlMaxWidth] for the name: its padding, the gap and the 16dp glyph (`ChipGlyphSize`). */
    val SourceNameMaxWidth: Dp =
        SourceControlMaxWidth - SourceControlPadding * 2 - CloudStreamDimens.ListRowPadding - CloudStreamDimens.ChipGlyphSize

    /** Mirrors `FailureBannerDefaults.DetailMaxWidth`. */
    val FailureDetailMaxWidth: Dp = 240.dp

    /** 3m's `.slot.notice` left padding, 32px: from the band's edge to its hollow marker. */
    val FailureBandPaddingStart: Dp = 16.dp

    /** 3m's `.slot.notice` right padding, 24px: from Try again to the band's edge. Mirrors `dimens.MinFocusGap`. */
    val FailureBandPaddingEnd: Dp = CloudStreamDimens.MinFocusGap

    /** 3c's `.art`, 608px from the top of the screen. */
    val HeroArtHeight: Dp = 304.dp

    /** 3c's eyebrow at y176, 24px under the chrome's foot at y152 (`ChromeRowBottom`). Mirrors `MinFocusGap`. */
    val HeroAboveEyebrow: Dp = CloudStreamDimens.MinFocusGap

    /** 3c's 16px between the eyebrow, title, meta, synopsis and buttons. Mirrors `ListRowPadding`. */
    val HeroBandGap: Dp = CloudStreamDimens.ListRowPadding

    /** 3c's `.h1` line box, 120px, which is the `display` style's line height. */
    val HeroTitleLineBox: Dp = 60.dp

    /** 3c's `.h1 { width: 1000px }`. */
    val HeroTitleWidth: Dp = 500.dp

    /** 3c's eyebrow, `width: 800px`. */
    val HeroEyebrowWidth: Dp = 400.dp

    /** 3c's `.meta { width: 1200px }`. */
    val HeroMetaWidth: Dp = 600.dp

    /** 3c's `.syn`, 800x80px: two lines of `body`. */
    val HeroSynopsisWidth: Dp = 400.dp
    val HeroSynopsisHeight: Dp = 40.dp
    const val HERO_SYNOPSIS_LINES = 2

    /** From the button's foot at y568 to the art's at y608, 40px. Continue watching's header is `MinFocusGap` below that. */
    val HeroBelowButton: Dp = 20.dp

    /** Between facts on one mono line, as `KMP-37`'s failure line writes it. */
    const val META_SEPARATOR = " · "

    /** A bookmark's card: 3e's `.psa`, 240px, which is a `PosterRung.Medium` poster's height. */
    val BookmarksCardHeight: Dp = PosterRung.Medium.size.height

    /** 3g's `.ps.off { opacity: 0.4 }`: a card that needs a connection to open, offline. */
    const val OFFLINE_CARD_ALPHA = 0.4f
}

/** The chrome, which holds the hero when there is one. Continue watching, when there is any, follows it. */
private const val ITEMS_BEFORE_CONTINUE = 1
private const val CHROME_KEY = "chrome"
private const val CONTINUE_KEY = "continue"
private const val BOOKMARKS_KEY = "bookmarks"
private const val RELOAD_CELL = "reload"
private const val RANDOM_CELL = "random"
private const val SEARCH_CELL = "search"

/** What the bands above a state can add up to in pixel rounding: under this a state fits. */
private const val ROUNDING_PX = 2f
private const val STATE_KEY = "state"
private const val STATE_ROOM_KEY = "state/room"
private const val SKELETON_RAILS = 3
