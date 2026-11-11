package com.lagradost.cloudstream4.feature.detail.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButton
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopButtonTone
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopDimens
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopPageBar
import com.lagradost.cloudstream4.core.designsystem.desktop.DesktopIconButton
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopFeedback
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.action_add_to_bookmarks
import com.lagradost.cloudstream4.core.strings.generated.resources.detail_start_over
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.no_episodes_found
import com.lagradost.cloudstream4.core.strings.generated.resources.play_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.play_movie_button
import com.lagradost.cloudstream4.core.strings.generated.resources.rating
import com.lagradost.cloudstream4.core.strings.generated.resources.resume
import com.lagradost.cloudstream4.core.strings.generated.resources.season
import com.lagradost.cloudstream4.core.strings.generated.resources.season_other
import com.lagradost.cloudstream4.core.strings.generated.resources.season_specials
import com.lagradost.cloudstream4.core.strings.generated.resources.synopsis
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEpisode
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.META_SEPARATOR
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.code
import com.lagradost.cloudstream4.feature.detail.episodeSubtitle
import com.lagradost.cloudstream4.feature.detail.episodeWatchLabel
import com.lagradost.cloudstream4.feature.detail.label
import com.lagradost.cloudstream4.feature.detail.oneDecimal
import com.lagradost.cloudstream4.feature.detail.resumeCaption
import com.lagradost.cloudstream4.feature.detail.runtimeLabel
import com.lagradost.cloudstream4.feature.detail.statusLabel
import com.lagradost.cloudstream4.feature.detail.toPageUiState
import com.lagradost.cloudstream4.feature.detail.typeLabel
import com.lagradost.cloudstream4.feature.detail.watchReadout
import org.jetbrains.compose.resources.stringResource

/**
 * A title, for a resizable window: `DS-21`'s boards E1 to E6, and `docs/plans/desktop-design.md`.
 *
 * A bar with Back and where the page was opened from, then the title's header: poster, name, its run of facts and a
 * synopsis that opens where it is (E1). Under that the actions: Resume or Play with how far through and how long is
 * left, Start over, and the watch list. The episodes are on the page under a button per season, because a window has
 * the room and a separate screen would be one more step. A row's verb shows while the pointer or focus is on it (E2).
 *
 * The source list does not replace the page, as it does on TV. It slides in from the right over a dimmed page (E3), and
 * a click outside dismisses it.
 *
 * **Every action that plays sends [DetailAction.PlaybackRequested]**, saying whether from the start. The host turns it
 * into its own way of playing (desktop asks for the sources first, Android opens its player), so this screen never
 * decides that.
 *
 * @param origin the name of the screen the page was opened from, for the bar. Null draws Back alone.
 * @param artwork draws the poster; the host owns image loading.
 * @param thumbnail draws a cast photo or a recommendation's poster (`KMP-104`), each only while its line is on screen.
 *   The host bounds them more tightly than the poster above.
 * @param episodeTrailing draws the host's cell at the end of an episode's row, after its words and before its Play
 *   (`KMP-109`): the episode, and whether the row is hovered or focused, which is when a row shows its verbs. Null
 *   draws nothing there.
 */
@Composable
fun DetailScreenDesktop(
    state: DetailState,
    onAction: (DetailAction) -> Unit,
    onBack: () -> Unit,
    artwork: @Composable (String?) -> Unit,
    modifier: Modifier = Modifier,
    origin: String? = null,
    backdrop: @Composable (String) -> Unit = {},
    thumbnail: @Composable (String) -> Unit = {},
    episodeTrailing: (@Composable (DetailEpisode, shown: Boolean) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(origin, state.detail?.title, onBack)
            when (val page = state.content.toPageUiState(onAction)) {
                is UiState.Content ->
                    TitlePage(page.value, state.synopsisOpen, onAction, artwork, backdrop, thumbnail, episodeTrailing)
                is UiState.Empty -> Failure(page.prompt, onBack)
                is UiState.Error -> Failure(page.prompt, onBack)
                is UiState.Offline -> Failure(page.prompt, onBack)
                // A page has no partial state: it is the title or it is not.
                else -> Skeleton()
            }
        }
        DetailPanelsDesktop(state, onAction)
    }
}

/** Back, and where it goes back to, then this title. The way back is also the name of where it leads. */
@Composable
private fun TopBar(origin: String?, title: String?, onBack: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    // KMP-98: the page bar every Mac page starts with, its hairline included.
    DesktopPageBar(contentPadding = DesktopDimens.WindowMargin - DesktopDimens.RelatedGap) {
        DesktopIconButton(
            CloudStreamIcons.Back,
            stringResource(Res.string.go_back),
            onBack,
            tone = DesktopButtonTone.Quiet,
        )
        if (origin != null) {
            Crumb(origin, onBack)
            CloudStreamIcon(CloudStreamIcons.Disclosure, null, size = CloudStreamIconSize.Small, tint = colors.muted)
        }
        if (title != null) {
            BasicText(
                text = title,
                style = typography.label.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Where the page was opened from, muted until the pointer is on it: pressing it goes back there. */
@Composable
private fun Crumb(label: String, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .tvFocusRing(focused = focused, shape = shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.RelatedGap),
    ) {
        BasicText(
            text = label,
            style = CloudStreamTheme.typography.label.copy(color = if (hovered) colors.ink else colors.muted),
            maxLines = 1,
        )
    }
}

@Composable
private fun TitlePage(
    detail: TitleDetail,
    synopsisOpen: Boolean,
    onAction: (DetailAction) -> Unit,
    artwork: @Composable (String?) -> Unit,
    backdrop: @Composable (String) -> Unit,
    thumbnail: @Composable (String) -> Unit,
    episodeTrailing: (@Composable (DetailEpisode, shown: Boolean) -> Unit)?,
) {
    val seasons = remember(detail.episodes) { seasonGroups(detail.episodes) }
    // DS-26: More like this opens on one line, and each Load more opens one more.
    var moreLines by remember(detail.recommendations) { mutableStateOf(1) }
    // The season Resume is in, else the one Play starts (KMP-92's S1E1), so the row it will play is on screen;
    // else the first.
    var chosen by remember(detail.episodes) {
        mutableStateOf(
            detail.resume?.episode?.let(::groupOf)
                ?: detail.episodes.firstOrNull { it.data == detail.primaryData }?.let(::groupOf)
                ?: seasons.firstOrNull(),
        )
    }
    val shown = remember(detail.episodes, chosen) {
        if (seasons.size <= 1) detail.episodes.withIndex().toList()
        else detail.episodes.withIndex().filter { groupOf(it.value) == chosen }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < DetailScreenDesktopDefaults.CompactBelow
        val list = rememberLazyListState()
        detail.backdropUrl?.let { url -> Backdrop(url, list, backdrop) }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                end = DesktopDimens.WindowMargin - DesktopDimens.RowPadding,
                top = DesktopDimens.SectionGap,
                bottom = DesktopDimens.SectionGap,
            ),
        ) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = DesktopDimens.RowPadding)) {
                    Header(detail, compact, synopsisOpen, onAction, artwork)
                    Spacer(Modifier.height(DesktopDimens.WindowMargin))
                    Actions(detail, onAction)
                }
            }
            if (detail.episodes.isNotEmpty()) {
                item(key = "episodes") {
                    EpisodesHeader(seasons, chosen) { chosen = it }
                }
                items(shown.size, key = { "${shown[it].index}:${shown[it].value.data}" }) { position ->
                    val (index, episode) = shown[position]
                    EpisodeRow(episode, index, onAction, episodeTrailing)
                }
            }
            // DS-26, under the episodes: each only when it has something, never a header over nothing.
            val width = maxWidth - DesktopDimens.WindowMargin * 2
            castSection(detail.cast, DetailCastDesktopDefaults.columns(width, DetailCastDesktopDefaults.PersonMinWidth), thumbnail)
            recommendationsSection(
                recommendations = detail.recommendations,
                columns = DetailCastDesktopDefaults.columns(width, DetailCastDesktopDefaults.CardMinWidth),
                shownLines = moreLines,
                onMore = { moreLines++ },
                onAction = onAction,
                poster = thumbnail,
            )
        }
    }
}

/**
 * E1 and E3's wash (`KMP-100`): the title's backdrop across the page under the bar, [DetailScreenDesktopDefaults.BackdropHeight]
 * tall, under a scrim that is the ground at its foot, so the header's words keep their contrast and the page below
 * starts on plain ground. It scrolls away with the header. Without a backdrop nothing is drawn: no poster in its place.
 */
@Composable
private fun Backdrop(url: String, list: LazyListState, backdrop: @Composable (String) -> Unit) {
    val ground = CloudStreamTheme.colors.bg
    Box(
        Modifier
            .fillMaxWidth()
            .height(DetailScreenDesktopDefaults.BackdropHeight)
            .graphicsLayer {
                translationY = if (list.firstVisibleItemIndex == 0) -list.firstVisibleItemScrollOffset.toFloat() else -size.height
            },
    ) {
        backdrop(url)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to ground.copy(alpha = DetailScreenDesktopDefaults.WashTopAlpha),
                    DetailScreenDesktopDefaults.WashMiddleAt to ground.copy(alpha = DetailScreenDesktopDefaults.WashMiddleAlpha),
                    1f to ground,
                ),
            ),
        )
    }
}

/** E1's header: the poster, then the name, its facts and the synopsis beside it. */
@Composable
private fun Header(
    detail: TitleDetail,
    compact: Boolean,
    synopsisOpen: Boolean,
    onAction: (DetailAction) -> Unit,
    artwork: @Composable (String?) -> Unit,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val shape = CloudStreamTheme.shapes.large
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.WindowMargin),
    ) {
        Box(
            Modifier
                .size(DetailScreenDesktopDefaults.PosterWidth, DetailScreenDesktopDefaults.PosterHeight)
                .clip(shape)
                .background(colors.surface),
        ) {
            artwork(detail.posterUrl)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            // Not filled: a weight fixes the width, and prose stops at the reading width however wide the window is.
            modifier = Modifier.weight(1f, fill = false).widthIn(max = DesktopDimens.ReadingWidth),
        ) {
            BasicText(
                text = detail.title,
                // E6: a size down in the smallest window, so a long name still leaves room for its facts.
                style = (if (compact) typography.h1 else typography.display).copy(color = colors.ink),
                maxLines = DetailScreenDesktopDefaults.TitleLines,
                overflow = TextOverflow.Ellipsis,
            )
            FactsLine(detail)
            GenresLine(detail.tags)
            detail.plot?.takeIf { it.isNotBlank() }?.let { plot -> Synopsis(plot, synopsisOpen, onAction) }
        }
    }
}

/**
 * E1's facts under the name (`KMP-82`): the score with its star, the certification in an outlined badge, the year, the
 * runtime, the status, then the kind of title and where it came from. Each only where the source gave it, so a title
 * with none draws no line and leaves no gap. The line wraps rather than cuts, so no fact is lost off its end, and a
 * line never starts with a separator ([SeparatedLines]).
 */
@Composable
private fun FactsLine(detail: TitleDetail) {
    val colors = CloudStreamTheme.colors
    val style = CloudStreamTheme.typography.body.copy(color = colors.ink2)
    val words = listOfNotNull(
        detail.year?.toString(),
        detail.durationMinutes?.let { runtimeLabel(it) },
        detail.statusName?.let { statusLabel(it) },
        detail.typeName?.let { typeLabel(it) },
        detail.providerName.takeIf { it.isNotBlank() },
    )
    val score = detail.score
    val certification = detail.contentRating?.takeIf { it.isNotBlank() }
    val legs = buildList<@Composable () -> Unit> {
        if (score != null) add { Score(score, style) }
        if (certification != null) add { Certification(certification) }
        words.forEach { word ->
            add { BasicText(word, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (legs.isEmpty()) return
    SeparatedLines(legs) {
        // Decoration between two facts: a screen reader reads the facts, not the dots.
        BasicText(META_SEPARATOR, style = style, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * [items] in lines, as wide as there is room for, with a [separator] only between two items on the same line: a line
 * never starts or ends with one. An item wider than a whole line gets the line to itself, at most that wide, so a text
 * item set to ellipsize cuts rather than overflowing. Items are centred on their line.
 */
@Composable
private fun SeparatedLines(items: List<@Composable () -> Unit>, separator: @Composable () -> Unit) {
    Layout(
        content = {
            items.forEach { item -> Box { item() } }
            repeat(items.size - 1) { separator() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = measurables.take(items.size).map { it.measure(loose) }
        val separators = measurables.drop(items.size).map { it.measure(loose) }
        // Each line: the item placeables and, for every item after the first, the separator before it.
        val lines = mutableListOf(mutableListOf<Placeable>())
        var x = 0
        placed.forEachIndexed { index, item ->
            val before = if (index == 0) null else separators[index - 1]
            if (before != null && x + before.width + item.width > constraints.maxWidth) {
                lines += mutableListOf<Placeable>()
                x = 0
            } else if (before != null) {
                lines.last() += before
                x += before.width
            }
            lines.last() += item
            x += item.width
        }
        val widths = lines.map { line -> line.sumOf { it.width } }
        val heights = lines.map { line -> line.maxOf { it.height } }
        val width = widths.max().coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, heights.sum()) {
            var y = 0
            lines.forEachIndexed { row, line ->
                var left = 0
                line.forEach { placeable ->
                    placeable.placeRelative(left, y + (heights[row] - placeable.height) / 2)
                    left += placeable.width
                }
                y += heights[row]
            }
        }
    }
}

/** `☆ 8.4`: the Tabler star, an outline, and the score to one decimal, read as "Rating 8.4". */
@Composable
private fun Score(score: Float, style: TextStyle) {
    val described = "${stringResource(Res.string.rating)} ${oneDecimal(score)}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap),
        modifier = Modifier.clearAndSetSemantics { contentDescription = described },
    ) {
        CloudStreamIcon(CloudStreamIcons.Star, null, size = CloudStreamIconSize.Small, tint = style.color)
        BasicText(oneDecimal(score), style = style, maxLines = 1)
    }
}

/** `PG-13` in a hairline outline: a label as the source spelled it, never translated. */
@Composable
private fun Certification(text: String) {
    val colors = CloudStreamTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = BadgeDefaults.Height)
            .border(CloudStreamDimens.Hairline, colors.muted, CloudStreamTheme.shapes.small)
            .padding(horizontal = BadgeDefaults.HorizontalPadding),
    ) {
        BasicText(text, style = CloudStreamTheme.typography.labelMono.copy(color = colors.ink2), maxLines = 1, softWrap = false)
    }
}

/** Up to [DetailScreenDesktopDefaults.GenreCount] of the source's genres, quieter than the facts, on one line. */
@Composable
private fun GenresLine(tags: List<String>) {
    val shown = tags.filter { it.isNotBlank() }.take(DetailScreenDesktopDefaults.GenreCount)
    if (shown.isEmpty()) return
    BasicText(
        text = shown.joinToString(META_SEPARATOR),
        style = CloudStreamTheme.typography.label.copy(color = CloudStreamTheme.colors.muted),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Two lines, and the whole block opens and closes in place on a press. No "More" word is drawn after the cut: there
 * is no such key, and the block itself is the target, named for a screen reader by `synopsis`.
 */
@Composable
private fun Synopsis(plot: String, open: Boolean, onAction: (DetailAction) -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.small
    BasicText(
        text = plot,
        style = CloudStreamTheme.typography.body.copy(color = colors.muted),
        maxLines = if (open) Int.MAX_VALUE else DetailScreenDesktopDefaults.SynopsisLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .tvFocusRing(focused = focused, shape = shape)
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = stringResource(Res.string.synopsis),
                role = Role.Button,
                onClick = { onAction(DetailAction.SynopsisToggled) },
            ),
    )
}

/**
 * E1's actions. Resume where there is somewhere to come back to, else Play, with its rule and what is left under it;
 * Start over beside a Resume; then the watch list, as one glyph whose shape says whether the title is on one.
 */
@Composable
private fun Actions(detail: TitleDetail, onAction: (DetailAction) -> Unit) {
    val primary = detail.primaryData
    if (primary == null) {
        BasicText(
            text = stringResource(Res.string.no_episodes_found),
            style = CloudStreamTheme.typography.label.copy(color = CloudStreamTheme.colors.muted),
        )
        return
    }
    val resume = detail.resume
    val progress = resume?.fraction
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.width(DetailScreenDesktopDefaults.PrimaryWidth),
        ) {
            DesktopButton(
                label = primaryLabel(detail),
                onClick = { onAction(DetailAction.PlaybackRequested(primary, fromStart = false)) },
                primary = true,
                icon = CloudStreamIcons.Play,
                modifier = Modifier.fillMaxWidth(),
            )
            if (progress != null) {
                // On the page, not on a poster: the track is the line colour, so how much is left reads too.
                ProgressRule(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = CloudStreamTheme.colors.line,
                )
                // `resume_time_left` is two lines on TV's caption. One line here: the rule already says where.
                resumeCaption(resume)?.let { left ->
                    BasicText(
                        text = left.replace('\n', ' '),
                        style = CloudStreamTheme.typography.caption.copy(color = CloudStreamTheme.colors.muted),
                        maxLines = 1,
                    )
                }
            }
        }
        // The same episode, or the same film, from its beginning: what was being resumed, not the first episode.
        if (resume != null && progress != null) {
            DesktopButton(
                label = stringResource(Res.string.detail_start_over),
                onClick = { onAction(DetailAction.PlaybackRequested(resume.data, fromStart = true)) },
                icon = CloudStreamIcons.StartOver,
            )
        }
        val status = detail.watchStatus
        if (status != null) {
            // The glyph's shape says whether the title is on a list. A screen reader is told which one, or None, on the
            // button's own node: "Set watch status, Watching".
            val state = status.label()
            DesktopIconButton(
                icon = if (status != DetailWatchStatus.None) CloudStreamIcons.Done else CloudStreamIcons.Add,
                contentDescription = stringResource(Res.string.action_add_to_bookmarks),
                onClick = { onAction(DetailAction.WatchStatusPickerOpened) },
                modifier = Modifier.semantics { stateDescription = state },
            )
        }
    }
}

/** `Resume S1:E3` where the episode is known, `Resume` where it is not, and Play with nothing to come back to. */
@Composable
private fun primaryLabel(detail: TitleDetail): String {
    val resume = detail.resume
    if (resume?.fraction == null) {
        return stringResource(if (detail.playData != null) Res.string.play_movie_button else Res.string.play_episode)
    }
    val word = stringResource(Res.string.resume)
    val episode = resume.episode ?: return word
    return "$word ${episode.code(detail.episodes.indexOf(episode))}"
}

/** A season the episodes name, or the ones they do not. */
internal sealed interface SeasonGroup {
    data class Numbered(val number: Int) : SeasonGroup

    /** Episodes the provider gave no season, placed last so no episode is ever hidden (`I18N-12`). */
    data object Other : SeasonGroup
}

internal fun groupOf(episode: DetailEpisode): SeasonGroup =
    episode.season?.let { SeasonGroup.Numbered(it) } ?: SeasonGroup.Other

/** In the order the numbers go, with Other last. */
internal fun seasonGroups(episodes: List<DetailEpisode>): List<SeasonGroup> {
    val numbered = episodes.mapNotNull { it.season }.distinct().sorted().map { SeasonGroup.Numbered(it) }
    val other = if (episodes.any { it.season == null }) listOf(SeasonGroup.Other) else emptyList()
    return numbered + other
}

@Composable
private fun SeasonGroup.label(): String = when (this) {
    SeasonGroup.Other -> stringResource(Res.string.season_other)
    is SeasonGroup.Numbered ->
        if (number == 0) stringResource(Res.string.season_specials) else "${stringResource(Res.string.season)} $number"
}

/** "Episodes", then a button per season where there is more than one. The one shown has an outline. */
@Composable
private fun EpisodesHeader(seasons: List<SeasonGroup>, chosen: SeasonGroup?, onChoose: (SeasonGroup) -> Unit) {
    val colors = CloudStreamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DesktopDimens.RowPadding, top = DesktopDimens.SectionGap, bottom = DesktopDimens.GroupGap),
    ) {
        BasicText(
            text = stringResource(Res.string.episodes),
            style = CloudStreamTheme.typography.railTitle.copy(color = colors.ink),
            maxLines = 1,
        )
        if (seasons.size > 1) {
            Spacer(Modifier.width(DesktopDimens.GroupGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                seasons.forEach { group -> SeasonButton(group.label(), group == chosen) { onChoose(group) } }
            }
        }
    }
}

/** A season: quiet until chosen, then outlined, and said as selected. */
@Composable
private fun SeasonButton(label: String, chosen: Boolean, onClick: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .semantics { selected = chosen }
            .desktopFeedback(source, lift = false)
            .tvFocusRing(focused = focused, shape = shape)
            .background(if (chosen || hovered) colors.raised else colors.bg, shape)
            .border(CloudStreamDimens.Hairline, if (chosen) colors.muted else colors.bg, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .heightIn(min = DesktopDimens.ButtonHeight)
            .padding(horizontal = DesktopDimens.ButtonPadding),
    ) {
        BasicText(text = label, style = CloudStreamTheme.typography.label.copy(color = colors.ink), maxLines = 1)
    }
}

/**
 * One episode: its number, its name and its second line. The row plays it; while the pointer or focus is on the row,
 * Play shows at its end too (E2), so a row says what pressing it does. [trailing], the host's cell, sits between the
 * words and Play (`KMP-109`).
 */
@Composable
private fun EpisodeRow(
    episode: DetailEpisode,
    index: Int,
    onAction: (DetailAction) -> Unit,
    trailing: (@Composable (DetailEpisode, shown: Boolean) -> Unit)?,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    val code = episode.code(index)
    val play = { onAction(DetailAction.PlaybackRequested(episode.data, fromStart = false)) }
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier.hoverable(hover),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusRing(focused = focused, shape = shape)
                .background(if (hovered) colors.raised else colors.bg, shape)
                .border(CloudStreamDimens.Hairline, if (hovered) colors.line else colors.bg, shape)
                .clickable(interactionSource = source, indication = null, onClick = play)
                .heightIn(min = DetailScreenDesktopDefaults.EpisodeRowHeight)
                .padding(horizontal = DesktopDimens.RowPadding, vertical = DesktopDimens.RelatedGap),
        ) {
            BasicText(
                text = (episode.episode ?: (index + 1)).toString(),
                style = typography.captionMono.copy(color = colors.muted, textAlign = TextAlign.End),
                maxLines = 1,
                modifier = Modifier.width(DetailScreenDesktopDefaults.EpisodeNumberWidth),
            )
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = episode.name ?: code,
                    style = typography.label.copy(color = colors.ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // KMP-63: Watched, or the minutes left, where the runtime was; the runtime when neither.
                val watch = episodeWatchLabel(episode.watchReadout())
                episodeSubtitle(episode, code, codeInTitle = episode.name == null, watch = watch)?.let { line ->
                    BasicText(
                        text = line,
                        style = typography.caption.copy(color = colors.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(episode, hovered || focused)
            // Holds the verb's width whether or not it shows, so the words never move under the pointer.
            Spacer(Modifier.width(DesktopDimens.ButtonHeight))
        }
        if (hovered || focused) {
            DesktopIconButton(
                icon = CloudStreamIcons.Play,
                contentDescription = stringResource(Res.string.play_episode),
                onClick = play,
                tone = DesktopButtonTone.Quiet,
                modifier = Modifier.padding(end = DesktopDimens.RowPadding),
            )
        }
    }
}

/**
 * E5: the title could not be opened. What happened is not drawn (`BUG-21`): the host logs it, without an address.
 * Try again, and Go back, which the bar also offers, here where the eye already is.
 */
@Composable
private fun Failure(prompt: StatePrompt, onBack: () -> Unit) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap),
            modifier = Modifier.widthIn(max = DesktopDimens.ReadingWidth).padding(DesktopDimens.WindowMargin),
        ) {
            BasicText(
                text = prompt.headline,
                style = typography.railTitle.copy(color = colors.ink, textAlign = TextAlign.Center),
            )
            BasicText(
                text = prompt.body,
                style = typography.body.copy(color = colors.muted, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(DesktopDimens.RelatedGap))
            Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.RelatedGap)) {
                DesktopButton(label = prompt.action.label, onClick = prompt.action.onActivate, icon = prompt.action.icon)
                DesktopButton(label = stringResource(Res.string.go_back), onClick = onBack, icon = CloudStreamIcons.Back)
            }
        }
    }
}

/** E4: where each part of the page will land, so nothing moves when it does. */
@Composable
private fun Skeleton() {
    val colors = CloudStreamTheme.colors
    val block = CloudStreamTheme.shapes.small
    Column(
        verticalArrangement = Arrangement.spacedBy(DesktopDimens.WindowMargin),
        modifier = Modifier.fillMaxSize().padding(horizontal = DesktopDimens.WindowMargin, vertical = DesktopDimens.SectionGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.WindowMargin)) {
            Box(
                Modifier
                    .size(DetailScreenDesktopDefaults.PosterWidth, DetailScreenDesktopDefaults.PosterHeight)
                    .clip(CloudStreamTheme.shapes.large)
                    .background(colors.surface),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(DesktopDimens.GroupGap),
                modifier = Modifier.padding(top = DesktopDimens.SectionGap),
            ) {
                Bar(DetailScreenDesktopDefaults.SkeletonTitleWidth, DetailScreenDesktopDefaults.SkeletonTitleHeight, block)
                DetailScreenDesktopDefaults.SkeletonLineWidths.forEach { Bar(it, DesktopDimens.GroupGap, block) }
            }
        }
        Bar(DetailScreenDesktopDefaults.PrimaryWidth, DesktopDimens.ButtonHeight, CloudStreamTheme.shapes.medium)
        repeat(DetailScreenDesktopDefaults.SkeletonRows) {
            Bar(DetailScreenDesktopDefaults.SkeletonRowWidth, DesktopDimens.GroupGap, block)
        }
    }
}

@Composable
private fun Bar(width: Dp, height: Dp, shape: androidx.compose.ui.graphics.Shape) {
    Box(Modifier.size(width, height).clip(shape).background(CloudStreamTheme.colors.surface))
}

object DetailScreenDesktopDefaults {
    /** E1's poster, 144 by 216: 2:3 on the step. */
    val PosterWidth: Dp = 144.dp
    val PosterHeight: Dp = 216.dp

    /** E6: below this the name is a size down. Home's compact hero turns at the same width. */
    val CompactBelow: Dp = 720.dp

    /** E1 and E3's backdrop: 296 tall under the bar, down to the action row's middle, on the boards. */
    val BackdropHeight: Dp = 296.dp

    /**
     * The scrim over it, from the boards' `linear-gradient(0deg, #000 0%, rgba(0,0,0,0.6) 48%, rgba(0,0,0,0.3) 100%)`,
     * read top to bottom: the ground at 0.3 at the top, 0.6 just past the middle, and the whole ground at the foot.
     */
    const val WashTopAlpha: Float = 0.3f
    const val WashMiddleAt: Float = 0.52f
    const val WashMiddleAlpha: Float = 0.6f

    /** At most this many of the source's genres under the facts (`KMP-82`): enough to place a title, not a tag cloud. */
    const val GenreCount: Int = 5

    const val TitleLines = 2
    const val SynopsisLines = 2

    /** E1's Resume, wide enough for `Resume S1:E3` in the board's language, and its rule under it. */
    val PrimaryWidth: Dp = 176.dp

    /** An episode row with its two lines and the room the board gives it. */
    val EpisodeRowHeight: Dp = 56.dp

    /** Three digits of episode number, in the mono face. */
    val EpisodeNumberWidth: Dp = 24.dp

    const val ScrimAlpha = 0.6f

    /** Past this a translation gives way, so the panel's title and Cancel keep their places. */
    val ArrivingLabelMaxWidth: Dp = 160.dp

    val SkeletonTitleWidth: Dp = 480.dp
    val SkeletonTitleHeight: Dp = 40.dp
    val SkeletonLineWidths: List<Dp> = listOf(360.dp, 560.dp, 520.dp)
    val SkeletonRowWidth: Dp = 320.dp
    const val SkeletonRows = 4
}
