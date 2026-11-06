package com.lagradost.cloudstream4.feature.downloads.series.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPill
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPillState
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_active
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_delete_all
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_failed_count
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_offline_pill
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_on_disk
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_play_next
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_queued
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_retry_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_series_subtitle
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.title_downloads
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.SeriesAction
import com.lagradost.cloudstream4.feature.downloads.series.SeriesShelf
import com.lagradost.cloudstream4.feature.downloads.series.SeriesState
import com.lagradost.cloudstream4.feature.downloads.series.SeriesStatus
import com.lagradost.cloudstream4.feature.downloads.series.SeriesStop
import com.lagradost.cloudstream4.feature.downloads.series.SeriesVerb
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsFocusMemory
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsScreenTvDefaults
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsSkeleton
import com.lagradost.cloudstream4.feature.downloads.tv.contentInset
import com.lagradost.cloudstream4.feature.downloads.tv.downloadsStop
import com.lagradost.cloudstream4.feature.downloads.tv.requestFocusIfAttached
import org.jetbrains.compose.resources.stringResource

/**
 * One series' downloads on TV: `design/TV Episodes Downloads.dc.html` board **6d**, with **6b**'s row states.
 *
 * Stateless: a [SeriesState] in, [SeriesAction]s out. The name, two lines of figures and `Play next`, `Delete all` and
 * `Retry failed`, then the episodes under their seasons, each with three verbs beside it: play, the state's own verb
 * (pause, resume, retry or cancel, none when done) and delete.
 *
 * ## Focus
 *
 * On arrival `Play next` takes focus, the page's one primary, or the first row's Play if nothing can be played. After
 * the player or a dialog, focus goes back to the stop that left.
 *
 * @param formatBytes formats a size for this locale. That is the platform's work.
 * @param artwork the series' poster, drawn into the band's 64 by 96 cell; nothing when there is none.
 */
@Composable
fun DownloadSeriesScreenTv(
    state: SeriesState,
    onAction: (SeriesAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit = {},
) {
    val focus = remember { SeriesFocus() }
    val memory = remember { DownloadsFocusMemory() }
    SeriesFocusEffects(state, focus, memory, onAction)

    Column(modifier.fillMaxSize().onFocusChanged { focus.screenHasFocus = it.hasFocus }) {
        Spacer(Modifier.height(CloudStreamDimens.ChromeRowTop))
        SeriesChromeRow(state, Modifier.contentInset())
        when (val status = state.status) {
            SeriesStatus.Loading -> {
                Spacer(Modifier.height(SeriesScreenTvDefaults.ChromeToBandGap))
                DownloadsSkeleton(Modifier.fillMaxWidth().weight(1f))
            }

            is SeriesStatus.Showing -> {
                Spacer(Modifier.height(SeriesScreenTvDefaults.ChromeToBandGap))
                SeriesBand(state, formatBytes, artwork, Modifier.contentInset())
                Spacer(Modifier.height(SeriesScreenTvDefaults.BandToButtonsGap))
                SeriesButtons(state, focus, memory, onAction, Modifier.contentInset())
                SeriesRows(status.episodes, state, focus, memory, onAction, formatBytes, Modifier.fillMaxWidth().weight(1f))
            }

            SeriesStatus.Empty -> StateView(
                prompt = StatePrompt(
                    headline = stringResource(Res.string.downloads_empty_headline),
                    body = stringResource(Res.string.downloads_empty_body),
                    action = StateAction(
                        label = stringResource(Res.string.go_back),
                        icon = CloudStreamIcons.Back,
                        onActivate = { onAction(SeriesAction.BackRequested) },
                    ),
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            is SeriesStatus.Failed -> StateView(
                prompt = StatePrompt(
                    headline = stringResource(Res.string.downloads_load_failed_headline),
                    body = stringResource(Res.string.downloads_load_failed_body),
                    action = StateAction(
                        label = stringResource(Res.string.player_source_retry),
                        icon = CloudStreamIcons.Retry,
                        onActivate = { onAction(SeriesAction.Retry) },
                    ),
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
                detail = status.detail,
            )
        }
    }
}

/** Requesters that outlive recomposition: the three buttons, and every row's four stops. */
@Stable
internal class SeriesFocus {
    val playNext = FocusRequester()
    val deleteAll = FocusRequester()
    val retryFailed = FocusRequester()
    private val stops = mutableMapOf<Pair<DownloadEpisodeId, SeriesVerb>, FocusRequester>()

    var screenHasFocus by mutableStateOf(false)

    fun episode(id: DownloadEpisodeId, verb: SeriesVerb): FocusRequester = stops.getOrPut(id to verb) { FocusRequester() }

    fun forgetOtherThan(kept: Set<DownloadEpisodeId>): List<FocusRequester> {
        val gone = stops.keys.filter { it.first !in kept }
        return gone.map { stops.remove(it)!! }
    }

    fun requesterOf(stop: SeriesStop): FocusRequester = when (stop) {
        SeriesStop.PlayNext -> playNext
        SeriesStop.DeleteAll -> deleteAll
        SeriesStop.RetryFailed -> retryFailed
        is SeriesStop.Episode -> episode(stop.id, stop.verb)
    }
}

@Composable
private fun SeriesFocusEffects(
    state: SeriesState,
    focus: SeriesFocus,
    memory: DownloadsFocusMemory,
    onAction: (SeriesAction) -> Unit,
) {
    // Read while composing: by the time an effect runs, the view has already handed focus on (`KMP-40`).
    val heldFocus = memory.last.takeIf { memory.inside }
    val ids = state.episodes.map { it.id }
    val showing = state.status is SeriesStatus.Showing

    // Arrival: the page's primary, once there is something on it.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(showing) {
        if (!showing || arrived || state.returnTo != null) return@LaunchedEffect
        withFrameNanos { }
        arrived = focus.landOnArrival(state)
    }

    // A row went while one of its stops held focus: the next row's same verb, or the buttons.
    LaunchedEffect(ids) {
        if (!showing) return@LaunchedEffect
        val gone = focus.forgetOtherThan(ids.toSet())
        if (heldFocus in gone) {
            withFrameNanos { }
            focus.landOnArrival(state)
        }
    }

    // `Retry failed` goes when the last failure is retried, so focus moves to `Play next` first.
    val failures = state.failedCount
    LaunchedEffect(failures) {
        if (failures == 0 && heldFocus === focus.retryFailed) {
            withFrameNanos { }
            focus.playNext.requestFocusIfAttached()
        }
    }

    LaunchedEffect(memory.pending) {
        val target = memory.pending ?: return@LaunchedEffect
        if (!target.requestFocusIfAttached()) memory.settle()
        memory.pending = null
    }

    LaunchedEffect(focus.screenHasFocus) {
        if (!focus.screenHasFocus) memory.left()
    }

    val returnTo = state.returnTo
    LaunchedEffect(returnTo, ids) {
        if (returnTo == null || !returnTo.ready || !showing) return@LaunchedEffect
        withFrameNanos { }
        if (!focus.requesterOf(returnTo.stop).requestFocusIfAttached()) focus.landOnArrival(state)
        arrived = true
        onAction(SeriesAction.ReturnHandled)
    }
}

/** `Play next` if anything can be played, otherwise the first row. */
private fun SeriesFocus.landOnArrival(state: SeriesState): Boolean {
    if (SeriesShelf.next(state.episodes) != null && playNext.requestFocusIfAttached()) return true
    val first = state.episodes.firstOrNull() ?: return false
    return episode(first.id, SeriesVerb.Row).requestFocusIfAttached() || deleteAll.requestFocusIfAttached()
}

/** 6d's top row: "Downloads · the series", and the offline pill on the right. */
@Composable
private fun SeriesChromeRow(state: SeriesState, modifier: Modifier = Modifier) {
    val colors = CloudStreamTheme.colors
    val title = CloudStreamTheme.typography.railTitle
    val words = stringResource(Res.string.title_downloads)
    Row(
        modifier = modifier.fillMaxWidth().height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.ChromeGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesLabel(
            text = if (state.name.isEmpty()) words else UiString.Resource(Res.string.downloads_joined, listOf(words, state.name)).resolve(),
            style = title,
            color = colors.ink2,
            modifier = Modifier.weight(1f),
        )
        if (state.offline) StatusPill(StatusPillState.Failed, stringResource(Res.string.downloads_offline_pill))
    }
}

/** The poster, the name, and two lines: what is on disk, and what is moving or stuck. */
@Composable
private fun SeriesBand(
    state: SeriesState,
    formatBytes: (Long) -> String,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val type = CloudStreamTheme.typography
    val episodes = state.episodes
    val shape = CloudStreamTheme.shapes.medium
    Row(modifier.fillMaxWidth().height(SeriesScreenTvDefaults.BandHeight), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(SeriesScreenTvDefaults.PosterWidth, SeriesScreenTvDefaults.BandHeight)
                .clip(shape)
                .background(colors.raised, shape),
        ) { artwork() }
        Spacer(Modifier.size(SeriesScreenTvDefaults.PosterToTextGap))
        Column(verticalArrangement = Arrangement.spacedBy(SeriesScreenTvDefaults.BandLineGap)) {
            SeriesLabel(state.name, type.display, colors.ink)
            val onDisk = episodes.sumOf { it.bytesDone }
            // `episodes` is the word alone, so the number comes from the format, as on the Downloads page's rows.
            SeriesLabel(
                text = UiString.Resource(
                    Res.string.downloads_series_subtitle,
                    listOf(
                        episodes.size,
                        UiString.Plural(Res.plurals.episodes, episodes.size).resolve(),
                        UiString.Resource(Res.string.downloads_on_disk, listOf(formatBytes(onDisk))).resolve(),
                    ),
                ).resolve(),
                style = type.labelMono,
                color = colors.ink2,
            )
            val counts = listOfNotNull(
                countOf(episodes, DownloadEpisodeState.Running, Res.plurals.downloads_active),
                countOf(episodes, DownloadEpisodeState.Queued, Res.plurals.downloads_queued),
                countOf(episodes, DownloadEpisodeState.Failed, Res.plurals.downloads_failed_count),
            )
            if (counts.isNotEmpty()) SeriesLabel(counts.joinToString(SEPARATOR), type.body, colors.muted)
        }
    }
}

@Composable
private fun countOf(
    episodes: List<com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode>,
    state: DownloadEpisodeState,
    plural: org.jetbrains.compose.resources.PluralStringResource,
): String? {
    val count = episodes.count { it.state == state }
    if (count == 0) return null
    return UiString.Plural(plural, count, listOf(count)).resolve()
}

/** 6d's three: the page's one primary, then the two that act on many episodes. `Retry failed` only when there is one. */
@Composable
private fun SeriesButtons(
    state: SeriesState,
    focus: SeriesFocus,
    memory: DownloadsFocusMemory,
    onAction: (SeriesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(CloudStreamDimens.ChromeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.ChromeGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryTextButton(
            label = stringResource(Res.string.downloads_play_next),
            icon = CloudStreamIcons.Play,
            enabled = SeriesShelf.next(state.episodes) != null,
            onClick = { onAction(SeriesAction.PlayNextRequested) },
            modifier = Modifier.downloadsStop(focus.playNext, memory),
        )
        GhostTextButton(
            label = stringResource(Res.string.downloads_delete_all),
            icon = CloudStreamIcons.Delete,
            onClick = { onAction(SeriesAction.DeleteAllRequested) },
            modifier = Modifier.downloadsStop(focus.deleteAll, memory),
        )
        val failed = state.failedCount
        if (failed > 0) {
            GhostTextButton(
                label = UiString.Resource(Res.string.downloads_retry_failed, listOf(failed)).resolve(),
                icon = CloudStreamIcons.Retry,
                onClick = { onAction(SeriesAction.RetryFailedRequested) },
                modifier = Modifier.downloadsStop(focus.retryFailed, memory),
            )
        }
    }
}

/** One line of words that is not a control. */
@Composable
internal fun SeriesLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = remember(style, color) { style.copy(color = color) },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 6d's geometry, halved from the artboard. Each number mirrors the token or the board line it names. */
object SeriesScreenTvDefaults {
    /** The band's top, y176 on the artboard, less the top row's foot at y152. Mirrors `MinFocusGap`'s half. */
    val ChromeToBandGap: Dp = 12.dp

    /** 6d's poster, 128 by 192 on the artboard. */
    val PosterWidth: Dp = 64.dp

    /** The band, as tall as the poster. */
    val BandHeight: Dp = 96.dp

    /** From the poster to the words, x328 to x360 on the artboard. */
    val PosterToTextGap: Dp = 16.dp

    /** Between the band's lines. */
    val BandLineGap: Dp = 4.dp

    /** From the band to the buttons, y368 to y392 on the artboard. */
    val BandToButtonsGap: Dp = 12.dp
}

private const val SEPARATOR = " · "
