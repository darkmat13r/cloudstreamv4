package com.lagradost.cloudstream4.feature.detail.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopMotion
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRow
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.designsystem.state.UiStateHost
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.no_episodes_found
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.strings.generated.resources.play_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.play_movie_button
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_close
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.code
import com.lagradost.cloudstream4.feature.detail.toLinksUiState
import com.lagradost.cloudstream4.feature.detail.toPageUiState
import com.lagradost.cloudstream4.feature.detail.typeLabel
import org.jetbrains.compose.resources.stringResource

/**
 * A title, for a resizable window. `docs/plans/desktop-design.md`.
 *
 * Unlike TV, the source list does not replace the page. It slides in from the right over a dimmed page
 * and leaves the same way, so where it came from stays visible and a click outside dismisses it. The
 * episode list is on the page: a window has room, and a separate screen would be one more step.
 *
 * @param artwork draws the poster; the host owns image loading.
 */
@Composable
fun DetailScreenDesktop(
    state: DetailState,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(DesktopDimens.WindowMargin)) {
                DesktopIconButton(CloudStreamIcons.Back, stringResource(Res.string.go_back), onBack)
            }
            UiStateHost(state = state.content.toPageUiState(onAction), modifier = Modifier.fillMaxSize()) { detail ->
                TitlePage(detail, onAction, artwork)
            }
        }
        SourcesPanel(state.links?.toLinksUiState(onAction), onAction)
    }
}

@Composable
private fun TitlePage(
    detail: TitleDetail,
    onAction: (DetailAction) -> Unit,
    artwork: @Composable (String?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val posterWidth = if (maxWidth < DetailScreenDesktopDefaults.CompactBelow) {
            DetailScreenDesktopDefaults.PosterWidthCompact
        } else {
            DetailScreenDesktopDefaults.PosterWidth
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                end = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                bottom = DesktopDimens.SectionGap,
            ),
        ) {
            item {
                Box(Modifier.padding(horizontal = DesktopDimens.RowPadding)) {
                    Header(detail, onAction, artwork, posterWidth)
                }
            }
            if (detail.episodes.isNotEmpty()) {
                item { EpisodesHeader(detail.episodes.size) }
                items(detail.episodes.size, key = { "$it:${detail.episodes[it].data}" }) { index ->
                    EpisodeRow(detail.episodes, index, onAction)
                }
            }
        }
    }
}

@Composable
private fun Header(
    detail: TitleDetail,
    onAction: (DetailAction) -> Unit,
    artwork: @Composable (String?) -> Unit,
    posterWidth: Dp,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.large
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.SectionGap)) {
        Box(
            Modifier
                .size(posterWidth, posterWidth * DetailScreenDesktopDefaults.PosterHeightOverWidth)
                .clip(shape)
                .background(colors.surface),
        ) {
            artwork(detail.posterUrl)
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                text = detail.title,
                style = typography.h1.copy(color = colors.ink),
                maxLines = DetailScreenDesktopDefaults.TitleLines,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DesktopDimens.RelatedGap))
            BasicText(
                text = listOfNotNull(
                    detail.providerName,
                    detail.typeName?.let { typeLabel(it) },
                    detail.year?.toString(),
                ).joinToString(" · "),
                style = typography.caption.copy(color = colors.muted),
                maxLines = 1,
            )
            Spacer(Modifier.height(DesktopDimens.GroupGap))
            PlayAction(detail, onAction)
            detail.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                Spacer(Modifier.height(DesktopDimens.WindowMargin))
                BasicText(
                    text = plot,
                    style = typography.body.copy(color = colors.ink2),
                    maxLines = DetailScreenDesktopDefaults.PlotLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = DesktopDimens.ReadingWidth),
                )
            }
        }
    }
}

@Composable
private fun PlayAction(detail: TitleDetail, onAction: (DetailAction) -> Unit) {
    val primary = detail.primaryData
    if (primary == null) {
        BasicText(
            text = stringResource(Res.string.no_episodes_found),
            style = CloudStreamTheme.typography.label.copy(color = CloudStreamTheme.colors.muted),
        )
        return
    }
    DesktopButton(
        label = if (detail.playData != null) stringResource(Res.string.play_movie_button) else stringResource(Res.string.play_episode),
        onClick = { onAction(DetailAction.PlayRequested(primary)) },
        primary = true,
        icon = CloudStreamIcons.Play,
    )
}

@Composable
private fun EpisodesHeader(count: Int) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier.padding(
            start = DesktopDimens.RowPadding,
            top = DesktopDimens.SectionGap,
            bottom = DesktopDimens.RelatedGap,
        ),
    ) {
        BasicText(text = stringResource(Res.string.episodes), style = typography.railTitle.copy(color = colors.ink))
        BasicText(text = count.toString(), style = typography.captionMono.copy(color = colors.muted))
    }
}

@Composable
private fun EpisodeRow(
    episodes: List<DetailEpisode>,
    index: Int,
    onAction: (DetailAction) -> Unit,
) {
    val episode = episodes[index]
    val code = episode.code(index)
    DesktopRow(
        title = episode.name ?: code,
        subtitle = code.takeIf { episode.name != null },
        onClick = { onAction(DetailAction.PlayRequested(episode.data)) },
    )
}

/** Kept composed while it leaves, showing what it last showed, so it does not go blank on the way out. */
@Composable
private fun SourcesPanel(
    links: UiState<List<StreamLink>>?,
    onAction: (DetailAction) -> Unit,
) {
    var shown by remember { mutableStateOf(links) }
    if (links != null) shown = links
    val colors = CloudStreamTheme.colors
    val dismiss = { onAction(DetailAction.LinksDismissed) }

    AnimatedVisibility(visible = links != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = DetailScreenDesktopDefaults.ScrimAlpha))
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
            visible = links != null,
            enter = slideInHorizontally(spec) { it },
            exit = slideOutHorizontally(spec) { it },
        ) {
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
                    BasicText(
                        text = stringResource(Res.string.pick_source),
                        style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
                        modifier = Modifier.weight(1f),
                    )
                    DesktopIconButton(
                        icon = CloudStreamIcons.Close,
                        contentDescription = stringResource(Res.string.sort_close),
                        onClick = dismiss,
                        tone = DesktopButtonTone.Quiet,
                    )
                }
                shown?.let { current ->
                    UiStateHost(state = current, modifier = Modifier.fillMaxSize()) { list ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list.size, key = { "$it:${list[it].url}" }) { index ->
                                val link = list[index]
                                DesktopRow(
                                    title = link.label,
                                    subtitle = link.qualityLabel,
                                    onClick = { onAction(DetailAction.LinkActivated(link)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

object DetailScreenDesktopDefaults {
    val PosterWidth: Dp = 240.dp
    val PosterWidthCompact: Dp = 160.dp
    const val PosterHeightOverWidth = 3f / 2f
    /** Includes the 800dp minimum window, where a large poster would push the episodes off screen. */
    val CompactBelow: Dp = 960.dp
    const val TitleLines = 3
    const val PlotLines = 6
    const val ScrimAlpha = 0.6f
}
