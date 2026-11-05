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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeReturn
import com.lagradost.cloudstream4.feature.home.HomeState
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.rows
import com.lagradost.cloudstream4.feature.home.toUiState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The TV home page: `design/TV Home.dc.html` artboard **3d**, and the source control **3c** draws.
 *
 * Stateless, like `SearchScreenTv`: a [HomeState] in, [HomeAction]s out.
 *
 * ## Where 3c's hero and Continue watching land
 *
 * Everything on the page is one list with keyed items, so they go in between the chrome and the rails and nothing else
 * moves. 3d is this same page scrolled: its rails are at the top because the hero is above them.
 *
 * ## Focus
 *
 * Nothing is taken until the host says it has resumed ([HomeState.appeared]), because focus taken before that does not
 * last on a TV. After that:
 *
 * - **A title that was opened** gets focus back ([HomeReturn]).
 * - **The first rows to land** take it for their first card, from the chrome if it was on the chrome, unless [mayTakeFocus]
 *   says it has gone elsewhere, which on a TV means a person opened the nav rail while the page was loading.
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
    val content = state.status.toUiState(state.selectedOption?.name, onAction, install)
    val rows = state.status.rows
    val returnTo = state.returnTo
    val list = rememberLazyListState(returnTo?.firstVisibleRow ?: 0, returnTo?.firstVisibleRowOffset ?: 0)
    var focusTarget by remember { mutableStateOf<HomeFocusTarget?>(null) }
    var screenHasFocus by remember { mutableStateOf(false) }
    val hasRows = rows.isNotEmpty()
    val scope = rememberCoroutineScope()
    val stops = remember { HomeStops() }

    // A title that was opened. The rows are on screen by now, because they outlive the view.
    LaunchedEffect(returnTo?.ready, hasRows) {
        if (returnTo == null || !returnTo.ready || state.status is HomeStatus.Loading) return@LaunchedEffect
        val row = rows.firstOrNull { it.key == returnTo.rowKey }
        when {
            row != null && row.items.any { it.id == returnTo.id } -> focusTarget = HomeFocusTarget(row.key, returnTo.id)
            hasRows -> {
                list.scrollToItem(0)
                focusTarget = HomeFocusTarget(rows.first().key, rows.first().items.first().id)
            }
            else -> onAction(HomeAction.ReturnHandled)
        }
    }

    // Rows landing, on a page that opened with none or whose focus went with what they replaced.
    LaunchedEffect(hasRows, state.appeared, returnTo == null) {
        if (!hasRows || !state.appeared || returnTo != null) return@LaunchedEffect
        // After a frame, so that a button these rows replaced has given focus up.
        withFrameNanos { }
        if ((state.landingOwed || !screenHasFocus) && !state.pickerOpen && mayTakeFocus()) {
            focusTarget = HomeFocusTarget(rows.first().key, rows.first().items.first().id)
        }
        if (state.landingOwed) onAction(HomeAction.LandingHandled)
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
            val promptHeight = maxHeight - HomeScreenTvDefaults.PageAboveContent - CloudStreamDimens.FocusBandInset
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
                        HomeChromeRow(
                            state,
                            content.failureNotice,
                            stops,
                            onAction,
                            Modifier.contentInset().onFocusChanged { if (it.hasFocus) scrollTo(0) },
                        )
                    }
                    when (content) {
                        is UiState.Loading -> items(SKELETON_RAILS, key = { "skeleton/$it" }) { HomeRailSkeleton() }
                        is UiState.Empty -> item(key = STATE_KEY) {
                            HomeStatePrompt(content.prompt, content.detail, promptHeight, state, mayTakeFocus)
                        }
                        is UiState.Offline -> item(key = STATE_KEY) {
                            HomeStatePrompt(content.prompt, null, promptHeight, state, mayTakeFocus)
                        }
                        is UiState.Error -> item(key = STATE_KEY) {
                            HomeStatePrompt(content.prompt, content.detail, promptHeight, state, mayTakeFocus)
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
                                onFocusTaken = {
                                    focusTarget = null
                                    if (returnTo != null) onAction(HomeAction.ReturnHandled)
                                },
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
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) scrollTo(index + ITEMS_BEFORE_RAILS)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A full-surface state, taking focus when it appears if that is this screen's to take. */
@Composable
private fun HomeStatePrompt(
    prompt: StatePrompt,
    detail: String?,
    height: Dp,
    state: HomeState,
    mayTakeFocus: () -> Boolean,
) {
    val takeFocus = remember(state.appeared) { state.appeared && !state.pickerOpen && mayTakeFocus() }
    StateView(prompt, Modifier.fillMaxWidth().height(height).contentInset(), detail, requestInitialFocus = takeFocus)
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
        if (notice != null) HomeFailureNotice(notice, stops, Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
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
 * `FailureBanner`'s content with a hollow neutral marker, which is what `DEC-17` rules a failure looks like. That component
 * still paints its dot in `live`, so it is not used here: reported in `KMP-36`, and left for the owner.
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
        StateActionButton(notice.retry, Modifier.homeStop(stops), StateActionEmphasis.Secondary)
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
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = name,
            style = remember(style, colors) { style.copy(color = colors.ink) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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

    /** Mirrors `FailureBannerDefaults.DetailMaxWidth`. */
    val FailureDetailMaxWidth: Dp = 240.dp
}

/** The chrome. The hero and Continue watching (3c) add to this. */
private const val ITEMS_BEFORE_RAILS = 1
private const val CHROME_KEY = "chrome"
private const val STATE_KEY = "state"
private const val SKELETON_RAILS = 3
