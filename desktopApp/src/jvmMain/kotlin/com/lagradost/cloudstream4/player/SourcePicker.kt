package com.lagradost.cloudstream4.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_failed
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_keeps
import com.lagradost.cloudstream4.core.strings.generated.resources.player_panel_quality_unknown
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_count
import com.lagradost.cloudstream4.core.strings.generated.resources.player_source_resumes_at
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.bestFirst
import com.lagradost.cloudstream4.feature.player.RowVerb
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
                text = UiString.Resource(Res.string.player_source_resumes_at, listOf(clock(sources.resumeAtMillis))).resolve(),
                style = CloudStreamTheme.typography.caption.copy(color = colors.ink2),
                modifier = Modifier.padding(horizontal = PlayerScreenDefaults.PanelWordsInset, vertical = DesktopDimens.RelatedGap),
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
        val keeps = sources.resumeAtMillis.takeIf { it > 0L }?.let { clock(it) }
        // Room for the focus ring, which draws outside its row and a lazy list clips.
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(all = PlayerScreenDefaults.PickerRingRoom),
            verticalArrangement = Arrangement.spacedBy(PlayerScreenDefaults.PanelRowGap),
        ) {
            itemsIndexed(links) { index, link ->
                val failed = sources.isFailed(link)
                val playing = link.isSameStream(sources.current)
                PlayerPanelRow(
                    title = link.sourceName(),
                    detail = sourceDetail(link, playing = playing, failed = failed, keeps = keeps)?.resolve(),
                    verb = when {
                        failed -> RowVerb.Retry
                        playing -> RowVerb.Playing
                        else -> RowVerb.Switch
                    },
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
 * `DS-23` M4's second line, from what the player knows, as TV's 7e rows say it (`KMP-66`): `failed`, `quality unknown`
 * or `keeps 42:13`. The row playing has none: the player knows the size it draws, not the size decoded, and a 4K
 * stream is drawn at most 1920×1080.
 */
internal fun sourceDetail(link: StreamLink, playing: Boolean, failed: Boolean, keeps: String?): UiString? = when {
    failed -> UiString.Resource(Res.string.player_panel_failed)
    playing -> null
    link.qualityLabel == null -> UiString.Resource(Res.string.player_panel_quality_unknown)
    keeps != null -> UiString.Resource(Res.string.player_panel_keeps, listOf(keeps))
    else -> null
}
