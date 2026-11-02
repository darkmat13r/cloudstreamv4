package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail

/**
 * Artboards 7a and 7b of `design/TV Detail Player.dc.html`, at px/2, and a reduced 7c.
 *
 * ## Where this departs from the artboards
 *
 * - **Back is a text button.** The artboard draws an icon, the icon pack is undecided, and
 *   `.claude/rules/icons.md` forbids drawing one.
 * - **No nav rail, bookmark or favourite.** Desktop has none of those features yet.
 * - **No cast rail or "More like this".** `RemoteDetails` does not carry them yet, so **the episode
 *   list takes the cast slot** at y320. The artboards put episodes on their own screen; that is
 *   `TV Episodes Downloads`, unbuilt.
 * - **7c is a plain list, not rows filling in live.** Links arrive all at once from
 *   `loadLinks`, and there is no per-extractor progress to draw yet.
 *
 * @param onBack leaves this screen. Going back from the source list is handled here.
 * @param artwork draws the hero image; the host owns image loading.
 */
@Composable
fun DetailScreenTv(
    state: DetailState,
    copy: DetailTvCopy,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = state.links
    Box(modifier.fillMaxSize()) {
        if (links == null) {
            UiStateHost(state = state.content, modifier = Modifier.fillMaxSize()) { detail ->
                TitlePage(detail, copy, onAction, onBack, artwork)
            }
            if (state.content !is UiState.Content) {
                BackCell(copy.back, onBack)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                BackCell(copy.back, onClick = { onAction(DetailAction.LinksDismissed) })
                UiStateHost(state = links, modifier = Modifier.fillMaxSize()) { list ->
                    LinkList(list, copy, onAction)
                }
            }
        }
    }
}

@Composable
private fun BackCell(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(start = DetailScreenTvDefaults.ContentStart, top = DetailScreenTvDefaults.ChromeTop)
            .height(CloudStreamDimens.ChromeRowHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        GhostTextButton(label = label, onClick = onClick)
    }
}

@Composable
private fun TitlePage(
    detail: TitleDetail,
    copy: DetailTvCopy,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    artwork: @Composable (String?) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Hero(detail.posterUrl, artwork)
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackCell(copy.back, onBack)
                Spacer(Modifier.padding(start = DetailScreenTvDefaults.ChromeGap))
                TextLine(
                    text = listOfNotNull(detail.providerName, detail.typeName?.let(copy.typeLabel)).joinToString(SEPARATOR),
                    style = CloudStreamTheme.typography.label,
                    color = CloudStreamTheme.colors.muted,
                    modifier = Modifier.padding(top = DetailScreenTvDefaults.ChromeTop),
                )
            }
            Column(Modifier.padding(start = DetailScreenTvDefaults.ContentStart)) {
                Spacer(Modifier.height(DetailScreenTvDefaults.ChromeToTitle))
                TextLine(
                    text = detail.title,
                    style = CloudStreamTheme.typography.h1,
                    color = CloudStreamTheme.colors.ink,
                    modifier = Modifier.widthIn(max = DetailScreenTvDefaults.TitleMaxWidth),
                )
                Spacer(Modifier.height(DetailScreenTvDefaults.TitleToMeta))
                TextLine(
                    // The type is already in the chrome row, as on the artboard.
                    text = detail.year?.toString().orEmpty(),
                    style = CloudStreamTheme.typography.label,
                    color = CloudStreamTheme.colors.ink2,
                )
                Spacer(Modifier.height(DetailScreenTvDefaults.MetaToPlot))
                BasicText(
                    text = detail.plot.orEmpty(),
                    style = CloudStreamTheme.typography.body.copy(color = CloudStreamTheme.colors.ink2),
                    maxLines = DetailScreenTvDefaults.PlotLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .height(DetailScreenTvDefaults.PlotHeight)
                        .widthIn(max = DetailScreenTvDefaults.PlotMaxWidth),
                )
                Spacer(Modifier.height(DetailScreenTvDefaults.PlotToActions))
                ActionRow(detail, copy, onAction)
                Spacer(Modifier.height(DetailScreenTvDefaults.ActionsToList))
            }
            if (detail.episodes.isNotEmpty()) {
                EpisodeList(detail.episodes, copy, onAction, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Hero(posterUrl: String?, artwork: @Composable (String?) -> Unit) {
    val black = CloudStreamTheme.colors.bg
    val sideScrim = remember(black) {
        Brush.horizontalGradient(
            0f to black.copy(alpha = DetailScreenTvDefaults.ScrimStartAlpha),
            DetailScreenTvDefaults.ScrimMidStop to black.copy(alpha = DetailScreenTvDefaults.ScrimMidAlpha),
            DetailScreenTvDefaults.ScrimEndStop to Color.Transparent,
        )
    }
    val floorScrim = remember(black) {
        Brush.verticalGradient(
            DetailScreenTvDefaults.FloorScrimStop to Color.Transparent,
            1f to black,
        )
    }
    Box(Modifier.fillMaxWidth().height(DetailScreenTvDefaults.HeroHeight)) {
        artwork(posterUrl)
        Box(Modifier.fillMaxSize().background(sideScrim))
        Box(Modifier.fillMaxSize().background(floorScrim))
    }
}

@Composable
private fun ActionRow(detail: TitleDetail, copy: DetailTvCopy, onAction: (DetailAction) -> Unit) {
    val primary = detail.primaryData
    if (primary == null) {
        TextLine(copy.noEpisodes, CloudStreamTheme.typography.label, CloudStreamTheme.colors.muted)
        return
    }
    // 7a and 7b: "Entry focus: Resume". The button that plays is where focus starts.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    PrimaryTextButton(
        label = if (detail.playData != null) copy.playMovie else copy.playEpisode,
        onClick = { onAction(DetailAction.PlayRequested(primary)) },
        modifier = Modifier.focusRequester(focus),
    )
}

@Composable
private fun EpisodeList(
    episodes: List<DetailEpisode>,
    copy: DetailTvCopy,
    onAction: (DetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val insets = Modifier.padding(start = DetailScreenTvDefaults.ContentStart, end = DetailScreenTvDefaults.ContentEnd)
    Column(modifier.then(insets)) {
        RailHeader(title = copy.episodes, count = episodes.size.toString())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            // The index is in the key because provider data can repeat, and a duplicate key crashes.
            items(episodes.size, key = { index -> "$index:${episodes[index].data}" }) { index ->
                val episode = episodes[index]
                val code = episode.code(copy, index)
                ListRow(
                    state = ListRowState(
                        title = episode.name ?: code,
                        subtitle = code.takeIf { episode.name != null },
                    ),
                    onClick = { onAction(DetailAction.PlayRequested(episode.data)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LinkList(links: List<StreamLink>, copy: DetailTvCopy, onAction: (DetailAction) -> Unit) {
    val first = remember { FocusRequester() }
    // Above the list, because a lazy row is disposed when it scrolls away: an effect inside row 0 would
    // run again on scrolling back and pull focus away from wherever the user had moved it.
    var focusPlaced by remember { mutableStateOf(false) }
    val insets = Modifier.padding(start = DetailScreenTvDefaults.ContentStart, end = DetailScreenTvDefaults.ContentEnd)
    Column(Modifier.fillMaxSize().then(insets)) {
        Spacer(Modifier.height(DetailScreenTvDefaults.ChromeToTitle))
        RailHeader(title = copy.sources, count = links.size.toString())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            items(links.size, key = { index -> "$index:${links[index].url}" }) { index ->
                val link = links[index]
                if (index == 0 && !focusPlaced) {
                    LaunchedEffect(first) {
                        first.requestFocus()
                        focusPlaced = true
                    }
                }
                ListRow(
                    state = ListRowState(title = link.label, subtitle = link.qualityLabel),
                    onClick = { onAction(DetailAction.LinkActivated(link)) },
                    modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                )
            }
        }
    }
}

@Composable
private fun TextLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** `S1:E2`, from the translated letters. An episode with neither number takes its place in the list. */
internal fun DetailEpisode.code(copy: DetailTvCopy, index: Int): String {
    val number = episode ?: (index + 1).takeIf { season == null }
    return listOfNotNull(
        season?.let { "${copy.seasonShort}$it" },
        number?.let { "${copy.episodeShort}$it" },
    ).joinToString(":")
}

private const val SEPARATOR = " · "

/** Artboard 7a/7b's y-coordinates at px/2. Every value is on the 4dp step. */
object DetailScreenTvDefaults {
    /** x200 on the artboard. Mirrors `CloudStreamDimens.ContentOriginX`. */
    val ContentStart: Dp = CloudStreamDimens.ContentOriginX
    val ContentEnd: Dp = 48.dp
    /** y56 on the artboard. Mirrors `CloudStreamDimens.ChromeRowTop`. */
    val ChromeTop: Dp = CloudStreamDimens.ChromeRowTop
    val ChromeGap: Dp = 8.dp
    /** Chrome ends at y152, the title starts at y176. */
    val ChromeToTitle: Dp = 12.dp
    val TitleToMeta: Dp = 4.dp
    val MetaToPlot: Dp = 12.dp
    val PlotHeight: Dp = 40.dp
    const val PlotLines = 2
    /** Plot ends at y408, the action row starts at y512. */
    val PlotToActions: Dp = 52.dp
    /** Action row ends at y576, the cast slot starts at y640. */
    val ActionsToList: Dp = 32.dp
    /** 600 on the artboard. */
    val HeroHeight: Dp = 300.dp
    val TitleMaxWidth: Dp = 600.dp
    val PlotMaxWidth: Dp = 532.dp
    const val ScrimStartAlpha = 0.92f
    const val ScrimMidAlpha = 0.72f
    const val ScrimMidStop = 0.66f
    const val ScrimEndStop = 0.96f
    const val FloorScrimStop = 0.66f
}
