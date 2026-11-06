package com.lagradost.cloudstream4.feature.downloads.series.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopRowsSkeleton
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPill
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPillState
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.cancel
import com.lagradost.cloudstream4.core.strings.generated.resources.delete
import com.lagradost.cloudstream4.core.strings.generated.resources.download_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.download_paused
import com.lagradost.cloudstream4.core.strings.generated.resources.download_size_format
import com.lagradost.cloudstream4.core.strings.generated.resources.downloaded
import com.lagradost.cloudstream4.core.strings.generated.resources.downloading
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_active
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_delete_all
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_empty_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_episode_code
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_episode_count
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_failed_count
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_joined
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_load_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_offline_pill
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_on_disk
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_play_next
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_queued
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_rate
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_network
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_no_link
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_source
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_storage_full
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_reason_storage_unavailable
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_retry_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_show_in_finder
import com.lagradost.cloudstream4.core.strings.generated.resources.downloads_state_queued
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_short
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.home_play
import com.lagradost.cloudstream4.core.strings.generated.resources.pause
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.season
import com.lagradost.cloudstream4.core.strings.generated.resources.season_format
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.strings.generated.resources.title_downloads
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.desktop.DesktopDeleteSheet
import com.lagradost.cloudstream4.feature.downloads.desktop.DesktopVerb
import com.lagradost.cloudstream4.feature.downloads.desktop.DownloadsScreenDesktopDefaults
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.SeriesAction
import com.lagradost.cloudstream4.feature.downloads.series.SeriesState
import com.lagradost.cloudstream4.feature.downloads.series.SeriesStatus
import com.lagradost.cloudstream4.feature.downloads.series.SeriesVerb
import com.lagradost.cloudstream4.media.model.DownloadFailure
import org.jetbrains.compose.resources.stringResource

/**
 * One series' downloads on the Mac, board D4 of `design/Desktop Downloads.dc.html` (`KMP-54`), on the shared
 * `DownloadSeriesViewModel`, as `DownloadSeriesScreenTv` is.
 *
 * The toolbar carries Back (⌘[) and the path, Downloads › the series. Under the art and the counts are Play next, Retry
 * failed when something has, and Delete all. Episodes sit under their season's header in 48 rows: number, name, state with
 * its rule, and size. Hovering a row shows its verbs as 24 cells with tooltips (rule 4, `DEC-19`): Play when there is
 * something to play, the state's own verb, Show in Finder when the file is on the disk, and Delete. A double-click plays.
 *
 * A failure names its reason. A resume gets a fresh link from the extension, since none is stored (the owner on `KMP-52`,
 * 2026-11-06), so one that finds no source reads "Failed · No working link" and offers Retry.
 *
 * @param confirming the episodes the host is asking about, from `SeriesEffect.ConfirmDelete` or `ConfirmDeleteAll`, until
 *   it is answered: the same sheet as the titles page, Cancel the default.
 * @param canReveal whether an episode's file is on the disk yet.
 * @param artwork draws the series' poster. Image loading is the host's.
 */
@Composable
fun DownloadSeriesScreenDesktop(
    state: SeriesState,
    onAction: (SeriesAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
    confirming: List<DownloadEpisodeId>? = null,
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    canReveal: (DownloadEpisode) -> Boolean = { false },
    onReveal: (DownloadEpisodeId) -> Unit = {},
    artwork: @Composable () -> Unit = {},
) {
    val back = { onAction(SeriesAction.BackRequested) }
    // The page holds focus so ⌘[ reaches it; nothing on it is a focus stop of its own until the shell's menu bar (KMP-53).
    val pageFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { pageFocus.requestFocus() }
    Box(
        modifier
            .fillMaxSize()
            .background(CloudStreamTheme.colors.bg)
            .focusRequester(pageFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val goesBack = event.type == KeyEventType.KeyDown && confirming == null &&
                    event.isMetaPressed && event.key == Key.LeftBracket
                if (goesBack) back()
                goesBack
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state.name, back)
            when (val status = state.status) {
                SeriesStatus.Loading -> DesktopRowsSkeleton(
                    Modifier.padding(horizontal = DesktopDimens.WindowMargin - DesktopDimens.RowPadding),
                )

                is SeriesStatus.Failed -> StateView(
                    prompt = StatePrompt(
                        headline = stringResource(Res.string.downloads_load_failed_headline),
                        body = stringResource(Res.string.downloads_load_failed_body),
                        action = StateAction(stringResource(Res.string.player_source_retry), CloudStreamIcons.Retry) {
                            onAction(SeriesAction.Retry)
                        },
                    ),
                    modifier = Modifier.fillMaxSize().padding(DesktopDimens.WindowMargin),
                    detail = status.detail,
                    requestInitialFocus = false,
                )

                SeriesStatus.Empty -> StateView(
                    prompt = StatePrompt(
                        headline = stringResource(Res.string.downloads_empty_headline),
                        body = stringResource(Res.string.downloads_empty_body),
                        action = StateAction(stringResource(Res.string.go_back), CloudStreamIcons.Back, back),
                    ),
                    modifier = Modifier.fillMaxSize().padding(DesktopDimens.WindowMargin),
                    requestInitialFocus = false,
                )

                is SeriesStatus.Showing -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                        end = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                        bottom = DesktopDimens.SectionGap,
                    ),
                ) {
                    item(key = "header") { Header(state, formatBytes, onAction, artwork) }
                    status.episodes.groupBy { it.season }.forEach { (season, episodes) ->
                        if (season != null) item(key = "season-$season") { SeasonHeader(season, episodes.size) }
                        items(episodes, key = { it.id.value }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                formatBytes = formatBytes,
                                canReveal = canReveal(episode),
                                onPlay = { onAction(SeriesAction.PlayRequested(episode.id, SeriesVerb.Play, 0)) },
                                onVerb = { onAction(SeriesAction.StateVerbRequested(episode.id)) },
                                onReveal = { onReveal(episode.id) },
                                onDelete = { onAction(SeriesAction.DeleteRequested(episode.id, 0)) },
                            )
                        }
                    }
                }
            }
        }
        confirming?.let { ids ->
            val bytes = state.episodes.filter { it.id in ids }.sumOf { it.bytesDone }
            DesktopDeleteSheet(ids.size, bytes, formatBytes, onConfirmDelete, onCancelDelete)
        }
    }
}

/** Back, then the path: Downloads, which also goes back, then the series. */
@Composable
private fun Toolbar(name: String, back: () -> Unit) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .height(DownloadsScreenDesktopDefaults.ToolbarHeight)
            .border(CloudStreamDimens.Hairline, colors.line)
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        DesktopIconButton(
            icon = CloudStreamIcons.Back,
            contentDescription = stringResource(Res.string.go_back),
            onClick = back,
            tone = DesktopButtonTone.Quiet,
        )
        DesktopButton(label = stringResource(Res.string.title_downloads), onClick = back)
        // Decoration: the path's separator.
        CloudStreamIcon(CloudStreamIcons.Disclosure, null, size = CloudStreamIconSize.Medium, tint = colors.muted)
        BasicText(
            text = name,
            style = CloudStreamTheme.typography.label.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The art, the series, what is on the disk, and the three page-wide actions. */
@Composable
private fun Header(
    state: SeriesState,
    formatBytes: (Long) -> String,
    onAction: (SeriesAction) -> Unit,
    artwork: @Composable () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val episodes = state.episodes
    Row(
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.WindowMargin),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesktopDimens.RowPadding)
            .padding(top = DesktopDimens.WindowMargin, bottom = DesktopDimens.SectionGap),
    ) {
        Box(
            Modifier
                .size(SeriesScreenDesktopDefaults.ArtWidth, SeriesScreenDesktopDefaults.ArtHeight)
                .clip(CloudStreamTheme.shapes.medium)
                .background(colors.surface),
        ) { artwork() }
        Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap)) {
                BasicText(state.name, style = typography.h1.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.offline) StatusPill(StatusPillState.Failed, stringResource(Res.string.downloads_offline_pill))
            }
            val count = UiString.Plural(Res.plurals.downloads_episode_count, episodes.size).resolve()
            val onDisk = UiString.Resource(Res.string.downloads_on_disk, listOf(formatBytes(episodes.sumOf { it.bytesDone }))).resolve()
            BasicText(
                text = UiString.Resource(Res.string.downloads_joined, listOf(count, onDisk)).resolve().uppercase(),
                style = typography.captionMono.copy(color = colors.muted),
                maxLines = 1,
            )
            counts(episodes)?.let { BasicText(it, style = typography.body.copy(color = colors.ink2), maxLines = 1) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
                modifier = Modifier.padding(top = DesktopDimens.RelatedGap),
            ) {
                DesktopButton(
                    label = stringResource(Res.string.downloads_play_next),
                    onClick = { onAction(SeriesAction.PlayNextRequested) },
                    primary = true,
                    icon = CloudStreamIcons.NextEpisode,
                )
                if (state.failedCount > 0) {
                    DesktopButton(
                        label = UiString.Resource(Res.string.downloads_retry_failed, listOf(state.failedCount)).resolve(),
                        onClick = { onAction(SeriesAction.RetryFailedRequested) },
                        icon = CloudStreamIcons.Retry,
                    )
                }
                DesktopButton(
                    label = stringResource(Res.string.downloads_delete_all),
                    onClick = { onAction(SeriesAction.DeleteAllRequested) },
                    icon = CloudStreamIcons.Delete,
                )
            }
        }
    }
}

/** "1 active download · 1 download queued · 1 failed", leaving out what is none, or null when all of it is none. */
@Composable
private fun counts(episodes: List<DownloadEpisode>): String? {
    val running = episodes.count { it.state == DownloadEpisodeState.Running }
    val queued = episodes.count { it.state == DownloadEpisodeState.Queued }
    val failed = episodes.count { it.state == DownloadEpisodeState.Failed }
    val parts = listOfNotNull(
        running.takeIf { it > 0 }?.let { UiString.Plural(Res.plurals.downloads_active, it).resolve() },
        queued.takeIf { it > 0 }?.let { UiString.Plural(Res.plurals.downloads_queued, it).resolve() },
        failed.takeIf { it > 0 }?.let { UiString.Plural(Res.plurals.downloads_failed_count, it).resolve() },
    )
    return parts.takeIf { it.isNotEmpty() }?.reduce { a, b -> UiString.Resource(Res.string.downloads_joined, listOf(a, b)).resolve() }
}

/** "Season 1", and how many of its episodes are here, which the new plural lets this page say in every language. */
@Composable
private fun SeasonHeader(season: Int, count: Int) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
        modifier = Modifier.fillMaxWidth().padding(horizontal = DesktopDimens.RowPadding).padding(bottom = DesktopDimens.RelatedGap),
    ) {
        BasicText(
            text = UiString.Resource(Res.string.season_format, listOf(stringResource(Res.string.season), season, "")).resolve(),
            style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
            maxLines = 1,
        )
        BasicText(
            text = UiString.Plural(Res.plurals.downloads_episode_count, count).resolve().uppercase(),
            style = CloudStreamTheme.typography.captionMono.copy(color = colors.muted),
            maxLines = 1,
            modifier = Modifier.padding(bottom = SeriesScreenDesktopDefaults.CountBaselineNudge),
        )
    }
}

/** One episode, 48 tall. The verbs show under the pointer, in a column kept for them, so the words never move. */
@Composable
private fun EpisodeRow(
    episode: DownloadEpisode,
    formatBytes: (Long) -> String,
    canReveal: Boolean,
    onPlay: () -> Unit,
    onVerb: () -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = CloudStreamTheme.shapes.medium
    val play by rememberUpdatedState(onPlay)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(SeriesScreenDesktopDefaults.RowHeight)
            .hoverable(source)
            .background(if (hovered) colors.surface else Color.Transparent, shape)
            .then(if (hovered) Modifier.border(CloudStreamDimens.Hairline, colors.line, shape) else Modifier)
            .pointerInput(episode.id, episode.isPlayable) {
                // A double-click plays, as a double-click opens on the titles page.
                awaitPointerEventScope {
                    var last = Long.MIN_VALUE / 2
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type != PointerEventType.Press || !event.buttons.isPrimaryPressed) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val double = change.uptimeMillis - last <= viewConfiguration.doubleTapTimeoutMillis
                        last = if (double) Long.MIN_VALUE / 2 else change.uptimeMillis
                        if (double && episode.isPlayable) play()
                    }
                }
            }
            .padding(horizontal = DesktopDimens.RowPadding),
    ) {
        BasicText(
            text = episode.episode?.toString().orEmpty(),
            style = typography.captionMono.copy(color = colors.muted),
            maxLines = 1,
            modifier = Modifier.width(SeriesScreenDesktopDefaults.NumberWidth),
        )
        Spacer(Modifier.width(DesktopDimens.RelatedGap))
        BasicText(
            text = titleOf(episode),
            style = typography.label.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DesktopDimens.GroupGap))
        EpisodeStatus(episode, formatBytes)
        Spacer(Modifier.width(DesktopDimens.GroupGap))
        BasicText(
            text = size(episode, formatBytes),
            style = typography.captionMono.copy(color = colors.ink2, textAlign = TextAlign.End),
            maxLines = 1,
            modifier = Modifier.width(DownloadsScreenDesktopDefaults.SizeWidth),
        )
        Spacer(Modifier.width(DesktopDimens.RelatedGap))
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(SeriesScreenDesktopDefaults.VerbsWidth),
        ) {
            if (hovered) {
                if (episode.isPlayable) DesktopVerb(CloudStreamIcons.Play, stringResource(Res.string.home_play), onPlay)
                when (episode.state) {
                    DownloadEpisodeState.Running -> DesktopVerb(CloudStreamIcons.Pause, stringResource(Res.string.pause), onVerb)
                    DownloadEpisodeState.Paused -> DesktopVerb(CloudStreamIcons.Download, stringResource(Res.string.resume), onVerb)
                    DownloadEpisodeState.Failed ->
                        DesktopVerb(CloudStreamIcons.Retry, stringResource(Res.string.player_source_retry), onVerb)

                    DownloadEpisodeState.Queued -> DesktopVerb(CloudStreamIcons.Close, stringResource(Res.string.cancel), onVerb)
                    DownloadEpisodeState.Done -> Unit
                }
                if (canReveal) DesktopVerb(CloudStreamIcons.Folder, stringResource(Res.string.downloads_show_in_finder), onReveal)
                DesktopVerb(CloudStreamIcons.Delete, stringResource(Res.string.delete), onDelete)
            }
        }
    }
}

/** The state in words: what is moving and how fast, or why it failed. The rule sits under it while it is not done. */
@Composable
private fun EpisodeStatus(episode: DownloadEpisode, formatBytes: (Long) -> String) {
    val colors = CloudStreamTheme.colors
    val percent = episode.progress?.let { "${(it * PERCENT).toInt()}%" }
    val parts: List<String> = when (episode.state) {
        DownloadEpisodeState.Done -> listOf(stringResource(Res.string.downloaded))
        DownloadEpisodeState.Running -> listOfNotNull(
            stringResource(Res.string.downloading),
            percent,
            episode.bytesPerSecond.takeIf { it > 0L }?.let { UiString.Resource(Res.string.downloads_rate, listOf(formatBytes(it))).resolve() },
        )

        DownloadEpisodeState.Queued -> listOf(stringResource(Res.string.downloads_state_queued))
        DownloadEpisodeState.Paused -> listOfNotNull(stringResource(Res.string.download_paused), percent)
        DownloadEpisodeState.Failed -> listOfNotNull(stringResource(Res.string.download_failed), episode.failure?.let { reason(it) })
    }
    val text = parts.reduce { a, b -> UiString.Resource(Res.string.downloads_joined, listOf(a, b)).resolve() }
    val color = when (episode.state) {
        DownloadEpisodeState.Done -> colors.muted
        DownloadEpisodeState.Queued, DownloadEpisodeState.Paused -> colors.ink2
        DownloadEpisodeState.Running, DownloadEpisodeState.Failed -> colors.ink
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap / 2),
        modifier = Modifier.width(SeriesScreenDesktopDefaults.StatusWidth),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
            // A failure is a glyph and words, never `live` (`DEC-17`). Decoration: the words say it.
            if (episode.state == DownloadEpisodeState.Failed) {
                CloudStreamIcon(CloudStreamIcons.Warning, null, size = CloudStreamIconSize.Small, tint = color)
            }
            BasicText(text, style = CloudStreamTheme.typography.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val progress = episode.progress
        if (progress != null && episode.state == DownloadEpisodeState.Running) {
            ProgressRule(
                progress = progress,
                color = colors.ink,
                trackColor = colors.line,
                modifier = Modifier.width(DownloadsScreenDesktopDefaults.RuleWidth),
            )
        }
    }
}

/**
 * The episode's name, or "S1 · E3" when it has none. The number has its own column here, so the code is only the
 * fallback; TV's `SeriesRows` joins the two in one line. Its private rules and these should become one shared function
 * when the TV suites can be rerun for it (noted in `KMP-54`).
 */
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
        name != null -> name
        else -> code.orEmpty()
    }
}

/** Why it failed, in the words TV's `SeriesRows` uses. Null where nothing recorded why. */
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

@Composable
private fun size(episode: DownloadEpisode, formatBytes: (Long) -> String): String = when {
    episode.state == DownloadEpisodeState.Done -> formatBytes(episode.bytesTotal.takeIf { it > 0L } ?: episode.bytesDone)
    episode.bytesTotal <= 0L -> "—"
    else -> UiString.Resource(Res.string.download_size_format, listOf(formatBytes(episode.bytesDone), formatBytes(episode.bytesTotal))).resolve()
}

private const val PERCENT = 100

/** Values the series page needs that `DownloadsScreenDesktopDefaults` and `DesktopDimens` do not name. */
object SeriesScreenDesktopDefaults {
    /** Board D4's poster, 2:3. */
    val ArtWidth: Dp = 96.dp
    val ArtHeight: Dp = 144.dp

    /** An episode's row: `DesktopDimens.RowMinHeight`. */
    val RowHeight: Dp = 48.dp

    /** The episode number's column, two mono digits. Mirrors `DesktopDimens.FieldActionSize`. */
    val NumberWidth: Dp = 24.dp

    /** Wide enough for "Download Failed · No working link", the longest English state, with its glyph, in the desktop body. */
    val StatusWidth: Dp = 296.dp

    /** Four 24 verbs and three 8 gaps: Play, the state's verb, Show in Finder and Delete. */
    val VerbsWidth: Dp = 120.dp

    /** Sets the mono count on the season title's baseline: the 32 line less the 16 one, halved. */
    val CountBaselineNudge: Dp = 4.dp
}
