package com.lagradost.cloudstream4.feature.downloads.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRow
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRowState
import com.lagradost.cloudstream4.core.designsystem.state.SkeletonListRow
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.download_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.download_paused
import com.lagradost.cloudstream4.core.strings.generated.resources.download_size_format
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.extra_info_format
import com.lagradost.cloudstream4.core.strings.generated.resources.movies_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.popup_resume_download
import com.lagradost.cloudstream4.core.strings.generated.resources.storage_size_format
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.DownloadTitle
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleState
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsState
import com.lagradost.cloudstream4.feature.downloads.DownloadsStop
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 6c's rows: 96px tall at a 120px pitch, six on the artboard with the seventh peeking below, and Down from the
 * last one scrolls the next up. A title in flight stays where its name puts it rather than jumping to the top.
 *
 * The list carries its inset as content padding, because an outer `padding` would clip a ring.
 */
@Composable
internal fun DownloadRows(
    titles: List<DownloadTitle>,
    state: DownloadsState,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    onAction: (DownloadsAction) -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val returnTo = state.returnTo
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = returnTo?.firstVisibleIndex ?: 0)
    val returning = (returnTo?.stop as? DownloadsStop.Title)?.id

    // Puts the row on screen. The row takes focus itself, once it is composed.
    LaunchedEffect(returning) {
        val index = titles.indexOfFirst { it.id == returning }
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(
            start = DownloadsScreenTvDefaults.ContentInsetStart,
            end = DownloadsScreenTvDefaults.ContentInsetEnd,
            top = DownloadsScreenTvDefaults.BandToRowGap,
            bottom = DownloadsScreenTvDefaults.ListBottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.RowGap),
    ) {
        items(items = titles, key = { it.id.value }) { title ->
            DownloadTitleRow(
                title = title,
                choosing = state.selection != null,
                chosen = state.selection.orEmpty().contains(title.id),
                focus = focus,
                memory = memory,
                takeFocus = returnTo?.ready == true && returning == title.id,
                onFocusTaken = { onAction(DownloadsAction.ReturnHandled) },
                onOpen = { onAction(DownloadsAction.TitleActivated(title.id, listState.firstVisibleItemIndex)) },
                onResume = { onAction(DownloadsAction.ResumeTitle(title.id)) },
                formatBytes = formatBytes,
            )
        }
    }
}

/**
 * One title, and beside it the one verb a failed or paused transfer needs. The verb is a sibling of the row, as on
 * 6d, so the row stays one stop and Right from it is the verb.
 *
 * The board draws no verb on 6c. It is here because a failure has no other way back in: the downloader never retries
 * one by itself, and a remote has no long-press to reach the old popup.
 *
 * Every row keeps the same modifier chain, requester included: adding one only to the row being returned to rebuilt
 * its focus node and dropped the focus it had just taken (`KMP-34`).
 */
@Composable
private fun DownloadTitleRow(
    title: DownloadTitle,
    choosing: Boolean,
    chosen: Boolean,
    focus: DownloadsFocus,
    memory: DownloadsFocusMemory,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    formatBytes: (Long) -> String,
) {
    val rowFocus = focus.row(title.id)
    val verbFocus = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // After a frame: a row composed in this one has not been placed.
        withFrameNanos { }
        rowFocus.requestFocusIfAttached()
        onFocusTaken()
    }
    val verb = when {
        choosing -> null
        title.state == DownloadTitleState.Failed -> stringResource(Res.string.player_source_retry)
        title.state == DownloadTitleState.Paused -> stringResource(Res.string.popup_resume_download)
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadProgressRow(
            state = rowState(title, chosen, formatBytes),
            onClick = onOpen,
            // Without a verb of its own, Right would search sideways and land on another row's.
            modifier = Modifier
                .weight(FILL)
                .focusProperties { if (verb == null) right = FocusRequester.Cancel }
                .downloadsStop(rowFocus, memory),
            leading = { DownloadKindGlyph(title.kind) },
        )
        if (verb != null) {
            GhostTextButton(
                label = verb,
                onClick = {
                    // Before the button goes with the state it answers.
                    rowFocus.requestFocusIfAttached()
                    onResume()
                },
                modifier = Modifier.downloadsStop(verbFocus, memory),
            )
        }
    }
}

/** What OK on the row does: plays a film, and goes into a series. No pack has a film or series glyph. */
@Composable
private fun DownloadKindGlyph(kind: DownloadTitleKind) {
    CloudStreamIcon(
        icon = if (kind == DownloadTitleKind.Film) CloudStreamIcons.Play else CloudStreamIcons.Disclosure,
        contentDescription = null,
        size = CloudStreamIconSize.Small,
        tint = CloudStreamTheme.colors.muted,
    )
}

@Composable
private fun rowState(title: DownloadTitle, chosen: Boolean, formatBytes: (Long) -> String): DownloadProgressRowState {
    val done = title.state == DownloadTitleState.Done
    val size = if (done) {
        formatBytes(title.bytesTotal)
    } else {
        UiString.Resource(Res.string.download_size_format, listOf(formatBytes(title.bytesDone), formatBytes(title.bytesTotal))).resolve()
    }
    val subtitle = when (title.kind) {
        DownloadTitleKind.Film ->
            UiString.Resource(Res.string.storage_size_format, listOf(stringResource(Res.string.movies_singular), size)).resolve()

        DownloadTitleKind.Series -> UiString.Resource(Res.string.extra_info_format, listOf(title.episodes,
            pluralStringResource(Res.plurals.episodes, title.episodes),
            size)).resolve()
    }
    return DownloadProgressRowState(
        title = title.name,
        subtitle = subtitle,
        value = when (title.state) {
            DownloadTitleState.Done -> null
            DownloadTitleState.Failed -> stringResource(Res.string.download_failed)
            DownloadTitleState.Paused -> stringResource(Res.string.download_paused)
            DownloadTitleState.Running -> title.progress?.let { "${(it * PERCENT).toInt()}%" }
        },
        progress = title.progress,
        failed = title.state == DownloadTitleState.Failed,
        selected = chosen,
    )
}

/** Loading: six rows at the row's geometry, so nothing moves when they arrive (6c's closing note). */
@Composable
internal fun DownloadsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .contentInset()
            .padding(top = DownloadsScreenTvDefaults.BandToRowGap),
        verticalArrangement = Arrangement.spacedBy(DownloadsScreenTvDefaults.RowGap),
    ) {
        repeat(SKELETON_ROWS) { SkeletonListRow(Modifier.fillMaxWidth()) }
    }
}

private const val PERCENT = 100
private const val SKELETON_ROWS = 6
