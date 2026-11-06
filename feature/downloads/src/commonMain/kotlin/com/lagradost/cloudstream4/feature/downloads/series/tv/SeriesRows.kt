package com.lagradost.cloudstream4.feature.downloads.series.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRow
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRowState
import com.lagradost.cloudstream4.core.designsystem.target.IconOnlyButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.delete
import com.lagradost.cloudstream4.core.strings.generated.resources.download_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.download_paused
import com.lagradost.cloudstream4.core.strings.generated.resources.download_size_format
import com.lagradost.cloudstream4.core.strings.generated.resources.download_time_left_hour_min_sec_format
import com.lagradost.cloudstream4.core.strings.generated.resources.download_time_left_min_sec_format
import com.lagradost.cloudstream4.core.strings.generated.resources.download_time_left_sec_format
import com.lagradost.cloudstream4.core.strings.generated.resources.downloaded
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_episode_code
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_rate
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_network
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_no_link
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_source
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_storage_full
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_storage_unavailable
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_state_queued
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_watched
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_short
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.home_play
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.season
import com.lagradost.cloudstream4.core.strings.generated.resources.season_format
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.SeriesAction
import com.lagradost.cloudstream4.feature.downloads.series.SeriesState
import com.lagradost.cloudstream4.feature.downloads.series.SeriesStop
import com.lagradost.cloudstream4.feature.downloads.series.SeriesVerb
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsFocusMemory
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsRowScroll
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsRowScrollSpec
import com.lagradost.cloudstream4.feature.downloads.tv.DownloadsScreenTvDefaults
import com.lagradost.cloudstream4.feature.downloads.tv.downloadsStop
import com.lagradost.cloudstream4.media.model.DownloadFailure
import org.jetbrains.compose.resources.stringResource

/** A list item: a season's header, or an episode. Headers are a row tall, so every item keeps the same pitch. */
private sealed interface SeriesItem {
    val key: String

    data class Header(val season: Int?, val count: Int) : SeriesItem {
        override val key: String get() = "season-$season"
    }

    data class Episode(val episode: DownloadEpisode) : SeriesItem {
        override val key: String get() = "episode-${episode.id.value}"
    }
}

/** Episodes under their season's header. Episodes with no season have no header, and take no slot for one. */
private fun itemsOf(episodes: List<DownloadEpisode>): List<SeriesItem> =
    episodes.groupBy { it.season }.flatMap { (season, inSeason) ->
        val header = if (season == null) emptyList() else listOf(SeriesItem.Header(season, inSeason.size))
        header + inSeason.map { SeriesItem.Episode(it) }
    }

/**
 * 6d's list. Scrolling is `DownloadsRowScroll`'s: a row that fits does not move the list, and one that does not moves it
 * by whole rows, so nothing is left half drawn against the buttons. A season's header is a row tall for that reason:
 * 6d draws it 28 dp, which would put every row after it off the pitch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SeriesRows(
    episodes: List<DownloadEpisode>,
    state: SeriesState,
    focus: SeriesFocus,
    memory: DownloadsFocusMemory,
    onAction: (SeriesAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val items = remember(episodes) { itemsOf(episodes) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.returnTo?.firstVisibleIndex ?: 0)
    val density = LocalDensity.current
    val top = with(density) { DownloadsScreenTvDefaults.BandToRowGap.roundToPx() }
    val end = with(density) { DownloadsScreenTvDefaults.ListBottomInset.roundToPx() }
    val gap = with(density) { DownloadsScreenTvDefaults.RowGap.roundToPx() }
    val row = with(density) { CloudStreamDimens.ListRowHeight.roundToPx() }
    val pitch = row + gap
    val scrollSpec = remember(top, end, row, pitch) {
        DownloadsRowScrollSpec(top.toFloat(), end.toFloat(), row.toFloat(), pitch.toFloat())
    }
    BoxWithConstraints(modifier) {
        val endPadding = DownloadsRowScroll.listEndPadding(constraints.maxHeight, items.size, top, end, pitch, gap)
        CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent { clipRect(top = (top - gap).toFloat()) { this@drawWithContent.drawContent() } },
                state = listState,
                contentPadding = PaddingValues(
                    start = DownloadsScreenTvDefaults.ContentInsetStart,
                    end = DownloadsScreenTvDefaults.ContentInsetEnd,
                    top = DownloadsScreenTvDefaults.BandToRowGap,
                    bottom = with(density) { endPadding.toDp() },
                ),
                verticalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.RowGap),
            ) {
                items(items = items, key = { it.key }) { item ->
                    when (item) {
                        is SeriesItem.Header -> SeasonHeader(item)
                        is SeriesItem.Episode -> EpisodeRow(
                            episode = item.episode,
                            focus = focus,
                            memory = memory,
                            onPlay = { from -> onAction(SeriesAction.PlayRequested(item.episode.id, from, listState.firstVisibleItemIndex)) },
                            onStateVerb = { onAction(SeriesAction.StateVerbRequested(item.episode.id)) },
                            onDelete = { onAction(SeriesAction.DeleteRequested(item.episode.id, listState.firstVisibleItemIndex)) },
                            formatBytes = formatBytes,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonHeader(header: SeriesItem.Header) {
    val colors = CloudStreamTheme.colors
    val type = CloudStreamTheme.typography
    // 6d's header also counts the season's episodes. `episodes` is the word alone and no format joins a number to it in
    // every language, so the count is left out rather than built from pieces.
    val seasonWord = stringResource(Res.string.season)
    val season = header.season?.let { UiString.Resource(Res.string.season_format, listOf(seasonWord, it, "")).resolve() }
        ?: return
    Box(Modifier.fillMaxWidth().height(CloudStreamDimens.ListRowHeight), contentAlignment = Alignment.BottomStart) {
        SeriesLabel(text = season, style = type.label, color = colors.ink2)
    }
}

/**
 * One episode and 6d's three verbs: A plays it, B is its state's verb, C deletes it. They are icon-only, each naming
 * itself under its cell when focused: the owner's fourth exception to `ui-components.md` rule 4 (2026-11-06). B never
 * moves: a finished row keeps its place empty, so Down from a B always lands on the B below. Every row keeps the same
 * modifier chain (`KMP-34`).
 */
@Composable
private fun EpisodeRow(
    episode: DownloadEpisode,
    focus: SeriesFocus,
    memory: DownloadsFocusMemory,
    onPlay: (SeriesVerb) -> Unit,
    onStateVerb: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String,
) {
    val id = episode.id
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadProgressRow(
            state = rowState(episode, formatBytes),
            onClick = { if (episode.isPlayable) onPlay(SeriesVerb.Row) },
            modifier = Modifier.weight(1f).downloadsStop(focus.episode(id, SeriesVerb.Row), memory),
            leading = { StateGlyph(episode.state) },
        )
        IconOnlyButton(
            icon = CloudStreamIcons.Play,
            label = stringResource(Res.string.home_play),
            enabled = episode.isPlayable,
            onClick = { onPlay(SeriesVerb.Play) },
            modifier = Modifier.downloadsStop(focus.episode(id, SeriesVerb.Play), memory),
        )
        val verb = stateVerb(episode.state)
        if (verb == null) {
            Spacer(Modifier.width(CloudStreamDimens.IconTargetCell))
        } else {
            IconOnlyButton(
                icon = verb.second,
                label = stringResource(verb.first),
                onClick = onStateVerb,
                modifier = Modifier.downloadsStop(focus.episode(id, SeriesVerb.State), memory),
            )
        }
        IconOnlyButton(
            icon = CloudStreamIcons.Delete,
            label = stringResource(Res.string.delete),
            onClick = onDelete,
            modifier = Modifier.downloadsStop(focus.episode(id, SeriesVerb.Delete), memory),
        )
    }
}

/** Column B: pause, resume, retry or cancel, by `DS-16`'s table; a finished episode has nothing to do there. */
private fun stateVerb(state: DownloadEpisodeState) = when (state) {
    DownloadEpisodeState.Running -> Res.string.pause to CloudStreamIcons.Pause
    DownloadEpisodeState.Paused -> Res.string.resume to CloudStreamIcons.Play
    DownloadEpisodeState.Failed -> Res.string.player_source_retry to CloudStreamIcons.Retry
    DownloadEpisodeState.Queued -> Res.string.cancel to CloudStreamIcons.Close
    DownloadEpisodeState.Done -> null
}

/** 6d's leading disc: done, moving, paused or failed. Waiting has no glyph in the pack, so it has none. */
@Composable
private fun StateGlyph(state: DownloadEpisodeState) {
    val colors = CloudStreamTheme.colors
    val (icon, tint) = when (state) {
        DownloadEpisodeState.Done -> CloudStreamIcons.Done to colors.ink2
        DownloadEpisodeState.Running -> CloudStreamIcons.Download to colors.ink
        DownloadEpisodeState.Paused -> CloudStreamIcons.Pause to colors.muted
        DownloadEpisodeState.Failed -> CloudStreamIcons.Warning to colors.ink2
        DownloadEpisodeState.Queued -> return
    }
    CloudStreamIcon(icon = icon, contentDescription = null, size = CloudStreamIconSize.Small, tint = tint)
}

@Composable
private fun rowState(episode: DownloadEpisode, formatBytes: (Long) -> String): DownloadProgressRowState {
    val ofTotal = UiString.Resource(
        Res.string.download_size_format,
        listOf(formatBytes(episode.bytesDone), formatBytes(episode.bytesTotal)),
    ).resolve()
    val parts: List<String> = when (episode.state) {
        DownloadEpisodeState.Done -> listOfNotNull(
            stringResource(Res.string.downloaded),
            formatBytes(episode.bytesTotal.takeIf { it > 0L } ?: episode.bytesDone),
            episode.watched?.let { UiString.Resource(Res.string.downloads_watched, listOf((it * PERCENT).toInt())).resolve() },
        )

        DownloadEpisodeState.Running -> listOfNotNull(
            ofTotal,
            episode.bytesPerSecond.takeIf { it > 0L }?.let {
                UiString.Resource(Res.string.downloads_rate, listOf(formatBytes(it))).resolve()
            },
            episode.secondsLeft?.let { timeLeft(it) },
        )

        DownloadEpisodeState.Queued -> listOfNotNull(
            stringResource(Res.string.downloads_state_queued),
            episode.queue?.let { "${it.position}/${it.total}" },
            episode.bytesTotal.takeIf { it > 0L }?.let(formatBytes),
        )

        DownloadEpisodeState.Paused -> listOf(stringResource(Res.string.download_paused), ofTotal)
        DownloadEpisodeState.Failed -> listOfNotNull(
            stringResource(Res.string.download_failed),
            episode.failure?.let { reason(it) },
            ofTotal,
        )
    }
    return DownloadProgressRowState(
        title = titleOf(episode),
        subtitle = parts.joinToString(SEPARATOR),
        value = when (episode.state) {
            DownloadEpisodeState.Done -> null
            DownloadEpisodeState.Queued -> null
            else -> episode.progress?.let { "${(it * PERCENT).toInt()}%" }
        },
        progress = episode.progress,
        failed = episode.state == DownloadEpisodeState.Failed,
    )
}

/** "S1 · E3 · Name", or as much of it as is known. */
@Composable
private fun titleOf(episode: DownloadEpisode): String {
    val season = episode.season
    val number = episode.episode
    val code = when {
        season != null && number != null -> UiString.Resource(
            Res.string.downloads_episode_code,
            listOf(stringResource(Res.string.season_short), season, stringResource(Res.string.episode_short), number),
        ).resolve()

        number != null -> "${stringResource(Res.string.episode_short)}$number"
        else -> null
    }
    val name = episode.name
    return when {
        code != null && name != null -> UiString.Resource(Res.string.downloads_joined, listOf(code, name)).resolve()
        else -> code ?: name.orEmpty()
    }
}

@Composable
private fun reason(failure: DownloadFailure): String? = when (failure) {
    DownloadFailure.NetworkLost -> stringResource(Res.string.downloads_reason_network)
    is DownloadFailure.StorageFull -> stringResource(Res.string.downloads_reason_storage_full)
    DownloadFailure.StorageUnavailable -> stringResource(Res.string.downloads_reason_storage_unavailable)
    DownloadFailure.NoUsableLink -> stringResource(Res.string.downloads_reason_no_link)
    is DownloadFailure.InsufficientSpace -> stringResource(Res.string.downloads_reason_storage_full)
    DownloadFailure.SourceFailed,
    is DownloadFailure.ServerRefused,
    DownloadFailure.AddressRefused,
    DownloadFailure.SourceChanged,
    is DownloadFailure.SizeMismatch,
    DownloadFailure.HashMismatch,
    -> stringResource(Res.string.downloads_reason_source)
    DownloadFailure.Unknown, DownloadFailure.DestinationExists -> null
}

/** The old notification's own formats. */
@Composable
private fun timeLeft(seconds: Long): String {
    val hours = (seconds / SECONDS_PER_HOUR).toInt()
    val minutes = ((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
    val rest = (seconds % SECONDS_PER_MINUTE).toInt()
    return when {
        hours > 0 -> UiString.Resource(Res.string.download_time_left_hour_min_sec_format, listOf(hours, minutes, rest)).resolve()
        minutes > 0 -> UiString.Resource(Res.string.download_time_left_min_sec_format, listOf(minutes, rest)).resolve()
        else -> UiString.Resource(Res.string.download_time_left_sec_format, listOf(rest)).resolve()
    }
}

private const val PERCENT = 100
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
private const val SEPARATOR = " · "
