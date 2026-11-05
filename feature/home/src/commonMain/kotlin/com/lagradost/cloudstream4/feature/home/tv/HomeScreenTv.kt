package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.extensions
import com.lagradost.cloudstream4.core.strings.generated.resources.home_source
import com.lagradost.cloudstream4.core.strings.generated.resources.switch_account
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
 * One list with keyed items: the chrome with the hero under it (3c), then Continue watching, then the source's rails (3d,
 * which is this page scrolled). The hero is part of the chrome's item because its art runs behind the chrome. Continue
 * watching is this device's and not the source's, so it stays above a failure, being offline, or a page still loading.
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreenTv(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (HomeItem) -> Unit = {},
    mayTakeFocus: () -> Boolean = { true },
) {
    val installLabel = stringResource(Res.string.extensions)
    val install = remember(installLabel, onAction) {
        StateAction(installLabel) { onAction(HomeAction.ExtensionsRequested) }
    }
    val content = state.status.toUiState(state.selectedOption?.name, install, onAction)
    val rows = state.status.rows
    val returnTo = state.returnTo
    val list = rememberLazyListState(returnTo?.firstVisibleRow ?: 0, returnTo?.firstVisibleRowOffset ?: 0)
    var focusTarget by remember { mutableStateOf<HomeFocusTarget?>(null) }
    var screenHasFocus by remember { mutableStateOf(false) }
    var continueHasFocus by remember { mutableStateOf(false) }
    val hasRows = rows.isNotEmpty()
    // Only beside the rows it came from: a page that failed or went offline has no hero, whatever was found before.
    val hero = (state.hero as? HomeHero.Shown)?.takeIf { hasRows }
    val heroSettled = state.hero !is HomeHero.Loading
    val resume = state.history.resume
    val hasContinue = resume.isNotEmpty()
    val itemsBeforeRails = if (hasContinue) ITEMS_BEFORE_CONTINUE + 1 else ITEMS_BEFORE_CONTINUE
    val scope = rememberCoroutineScope()
    val stops = remember { HomeStops() }

    // A title that was opened. The rows are on screen by now, because they outlive the view.
    LaunchedEffect(returnTo?.ready, hasRows, hero != null, hasContinue, state.status is HomeStatus.Loading) {
        if (returnTo == null || !returnTo.ready || state.status is HomeStatus.Loading) return@LaunchedEffect
        val row = rows.firstOrNull { it.key == returnTo.rowKey }
        val back = when (returnTo.rowKey) {
            HomeRowKey.Hero -> hero?.takeIf { it.featured.id == returnTo.id }?.let { heroTarget(it.featured) }
            HomeRowKey.Continue -> resume.firstOrNull { it.id == returnTo.id }?.let { continueTarget(it) }
            else -> row?.items?.firstOrNull { it.id == returnTo.id }?.let { HomeFocusTarget(row.key, it.id) }
        }
        val fallback = back ?: landingTarget(hero, resume, rows)
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
    LaunchedEffect(hasRows, state.appeared, returnTo == null, heroSettled) {
        if (!hasRows || !state.appeared || returnTo != null || !heroSettled) return@LaunchedEffect
        // After a frame, so that a button these rows replaced has given focus up.
        withFrameNanos { }
        val free = (state.landingOwed || !screenHasFocus) && !continueHasFocus
        if (free && !state.pickerOpen && mayTakeFocus()) focusTarget = landingTarget(hero, resume, rows)
        if (state.landingOwed) onAction(HomeAction.LandingHandled)
    }
    val onFocusTaken: () -> Unit = {
        focusTarget = null
        if (returnTo != null) onAction(HomeAction.ReturnHandled)
    }

    SidePanelHost(
        expanded = state.pickerOpen,
        onDismissRequest = { onAction(HomeAction.PickerDismissed) },
        modifier = modifier.onFocusChanged { screenHasFocus = it.hasFocus },
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
            // Continue watching sits above a state, so the state has what is left, and grows past it when that is less
            // than its words need: measured on the recorded screenshot, a fixed height squeezed its button to an empty
            // ring. The page then moves by the least that shows the button, when the state takes focus.
            val continueRoom = if (hasContinue) ContinueRailHeight + CloudStreamDimens.MinFocusGap else 0.dp
            val promptHeight =
                maxHeight - HomeScreenTvDefaults.PageAboveContent - CloudStreamDimens.FocusBandInset - continueRoom
            val revealState: () -> Unit = {
                val info = list.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == itemsBeforeRails }
                if (item != null) {
                    val overflow = item.offset + above + item.size - (info.viewportSize.height - band)
                    if (overflow > 0f) scope.launch { list.animateScrollBy(overflow) }
                }
            }
            val promptModifier = if (hasContinue) {
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
                                HomeChromeRow(state, content.failureNotice, stops, onAction, Modifier.contentInset())
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
                    when (content) {
                        is UiState.Loading -> items(SKELETON_RAILS, key = { "skeleton/$it" }) { HomeRailSkeleton() }
                        is UiState.Empty -> item(key = STATE_KEY) {
                            HomeStatePrompt(content.prompt, content.detail, promptHeight, hasContinue, state, mayTakeFocus, promptModifier)
                        }
                        is UiState.Offline -> item(key = STATE_KEY) {
                            HomeStatePrompt(content.prompt, null, promptHeight, hasContinue, state, mayTakeFocus, promptModifier)
                        }
                        is UiState.Error -> item(key = STATE_KEY) {
                            HomeStatePrompt(content.prompt, content.detail, promptHeight, hasContinue, state, mayTakeFocus, promptModifier)
                        }
                        // A row's key is a string, because a lazy list's key must fit in a Bundle on Android.
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

/** The first thing on the page: the hero's button, else the first Continue watching card, else the first card. */
private fun landingTarget(hero: HomeHero.Shown?, resume: List<HomeResume>, rows: List<HomeRow>): HomeFocusTarget? {
    if (hero != null) return heroTarget(hero.featured)
    resume.firstOrNull()?.let { return continueTarget(it) }
    val row = rows.firstOrNull() ?: return null
    return row.items.firstOrNull()?.let { HomeFocusTarget(row.key, it.id) }
}

private fun continueTarget(entry: HomeResume) = HomeFocusTarget(HomeRowKey.Continue, entry.id)

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
 * The top of the page: what failed on the left, as `TV Search` 4b draws a shortfall inside a row rather than in a band of
 * its own, and 3c's source control and account cell on the right.
 *
 * 3c's clock and its two other cells are the next board's.
 */
@Composable
private fun HomeChromeRow(
    state: HomeState,
    notice: FailureNotice?,
    stops: HomeStops,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The notice takes the free width rather than sharing it with a spacer: shared, its headline was squeezed to
        // nothing (measured on the recorded screenshot).
        if (notice != null) HomeFailureNotice(notice, stops, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        state.selectedOption?.let { selected ->
            HomeSourceControl(name = selected.name, stops, onClick = { onAction(HomeAction.PickerOpened) })
        }
        val accounts = stringResource(Res.string.switch_account)
        IconCell(onClick = { onAction(HomeAction.AccountsRequested) }, modifier = Modifier.homeStop(stops)) {
            CloudStreamIcon(CloudStreamIcons.Profile, contentDescription = accounts)
        }
    }
}

/**
 * `FailureBanner`'s content: a hollow neutral marker, which is what `DEC-17` rules a failure looks like, and the retry as the
 * `ink` primary, as `FailureBanner` draws it since `DS-14` (`TV Search` 4b's `.bbtn`).
 */
@Composable
private fun HomeFailureNotice(notice: FailureNotice, stops: HomeStops, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.width(CloudStreamDimens.ListRowPadding))
        StateActionButton(notice.retry, Modifier.homeStop(stops), StateActionEmphasis.Primary)
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
}

/** The chrome, which holds the hero when there is one. Continue watching, when there is any, follows it. */
private const val ITEMS_BEFORE_CONTINUE = 1
private const val CHROME_KEY = "chrome"
private const val CONTINUE_KEY = "continue"
private const val STATE_KEY = "state"
private const val SKELETON_RAILS = 3
