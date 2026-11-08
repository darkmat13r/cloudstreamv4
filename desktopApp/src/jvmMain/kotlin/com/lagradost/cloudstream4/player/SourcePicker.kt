package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_playing
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_resumes_at
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_switch
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_count
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.bestFirst
import org.jetbrains.compose.resources.stringResource

/**
 * What the player can switch to, and what it is on.
 *
 * @param links what the title's search has found, already through the same URL policy as every other link
 *   (`DesktopDetailSource`), with the one playing added if the search no longer lists it. Listed best first, the
 *   order the player tries them in ([bestFirst]).
 * @param failed the ones that have failed since the player opened, which can still be chosen again.
 * @param resumeAtMillis where a switch would start the next one.
 * @param onSelect only called for a change: the link already playing, unless it failed, just closes the list.
 */
internal class PlayerSources(
    links: List<StreamLink>,
    val current: StreamLink,
    val failed: List<StreamLink>,
    val resumeAtMillis: Long = 0L,
    private val onSelect: (StreamLink) -> Unit,
) {
    val links: List<StreamLink> = (if (links.any { it.isSameStream(current) }) links else listOf(current) + links).bestFirst()

    fun isFailed(link: StreamLink) = failed.any { it.isSameStream(link) }

    fun select(link: StreamLink) {
        if (!link.isSameStream(current) || isFailed(link)) onSelect(link)
    }
}

/** A later answer can carry the same stream under a new list of subtitles, so the whole link is not the test. */
internal fun StreamLink.isSameStream(other: StreamLink) = url == other.url && audioUrl == other.audioUrl

/** `Source One · 1080p`, which is how artboard 7d reads out a source and which of its rows says what. */
internal fun StreamLink.sourceName(): String = listOfNotNull(label, qualityLabel).joinToString(" · ")

/**
 * Artboard 7e: pressing a row is the commit, so there is no Apply for a dismiss to swallow, and each row says what
 * pressing it does. Departures are listed in `docs/plans/desktop-design.md`.
 */
@Composable
internal fun SourcePicker(
    links: List<StreamLink>,
    sources: PlayerSources,
    onSelect: (StreamLink) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CloudStreamTheme.colors
    // `DS-23` M4: the panel says how many there are to choose from under its title.
    val count = UiString.Plural(Res.plurals.player_source_count, links.size).resolve()
    PlayerListPanel(stringResource(Res.string.pick_source), onDismiss, subtitle = count) {
        if (sources.resumeAtMillis > 0L) {
            BasicText(
                text = stringResource(Res.string.player_source_resumes_at, clock(sources.resumeAtMillis)),
                style = CloudStreamTheme.typography.caption.copy(color = colors.ink2),
                modifier = Modifier.padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
            )
        }
        val currentRow = remember(links) { links.indexOfFirst { it.isSameStream(sources.current) }.coerceAtLeast(0) }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = currentRow)
        val focus = remember { FocusRequester() }
        // After a frame: a lazy list composes its rows during layout, so the row is not attached before that.
        LaunchedEffect(focus) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        // Room for the focus ring, which draws outside its row and a lazy list clips.
        LazyColumn(state = list, contentPadding = PaddingValues(all = PlayerScreenDefaults.PickerRingRoom)) {
            itemsIndexed(links) { index, link ->
                val failed = sources.isFailed(link)
                val playing = link.isSameStream(sources.current)
                SourceRow(
                    name = link.sourceName(),
                    reason = if (failed) stringResource(Res.string.source_error) else null,
                    verb = stringResource(
                        when {
                            failed -> Res.string.player_source_retry
                            playing -> Res.string.player_source_playing
                            else -> Res.string.player_source_switch
                        },
                    ),
                    selected = playing,
                    failed = failed,
                    onClick = { onSelect(link) },
                    modifier = if (index == currentRow) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

/**
 * Selection is the raised fill, the ink label and the muted marker, and the white ring is focus and only focus (Design
 * System v2). `live` is not spent here: it is playback progress. A failure is the words and a dimmed name.
 */
@Composable
private fun SourceRow(
    name: String,
    reason: String?,
    verb: String,
    selected: Boolean,
    failed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = modifier
            .fillMaxWidth()
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (selected || hovered) colors.raised else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = DesktopDimens.RowMinHeight)
            .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
    ) {
        Box(
            Modifier
                .size(PlayerScreenDefaults.SourceMarkerWidth, PlayerScreenDefaults.SourceMarkerHeight)
                .background(if (selected) colors.muted else Color.Transparent, CloudStreamTheme.shapes.small),
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = name,
                style = typography.label.copy(color = if (failed) colors.muted else colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (reason != null) {
                BasicText(
                    text = reason,
                    style = typography.caption.copy(color = colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BasicText(
            text = verb,
            style = typography.label.copy(color = if (selected) colors.ink else colors.ink2),
            maxLines = 1,
        )
    }
}
