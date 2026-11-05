package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.actor_background
import com.lagradost.cloudstream4.core.strings.generated.resources.actor_main
import com.lagradost.cloudstream4.core.strings.generated.resources.actor_supporting
import com.lagradost.cloudstream4.core.strings.generated.resources.anime_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.asian_drama_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_book_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.cartoons_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.custom_media_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.detail_load_failed_body
import com.lagradost.cloudstream4.core.strings.generated.resources.detail_load_failed_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.documentaries_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.duration_format
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_short
import com.lagradost.cloudstream4.core.strings.generated.resources.error_loading_links_toast
import com.lagradost.cloudstream4.core.strings.generated.resources.live_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.movies_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.music_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.no_links_found_toast
import com.lagradost.cloudstream4.core.strings.generated.resources.nsfw_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.other_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.ova_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.podcast_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.rating_format
import com.lagradost.cloudstream4.core.strings.generated.resources.resume_time_left
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.strings.generated.resources.status_completed
import com.lagradost.cloudstream4.core.strings.generated.resources.status_ongoing
import com.lagradost.cloudstream4.core.strings.generated.resources.torrent_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.type_completed
import com.lagradost.cloudstream4.core.strings.generated.resources.type_dropped
import com.lagradost.cloudstream4.core.strings.generated.resources.type_none
import com.lagradost.cloudstream4.core.strings.generated.resources.type_on_hold
import com.lagradost.cloudstream4.core.strings.generated.resources.type_plan_to_watch
import com.lagradost.cloudstream4.core.strings.generated.resources.type_watching
import com.lagradost.cloudstream4.core.strings.generated.resources.video_singular
import com.lagradost.cloudstream4.core.strings.resolve
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The page's [DetailLoad] as the design system draws it, in the composition's locale. */
@Composable
fun DetailLoad<TitleDetail>.toPageUiState(onAction: (DetailAction) -> Unit): UiState<TitleDetail> {
    val latest by rememberUpdatedState(onAction)
    val retry = remember { { latest(DetailAction.Retry) } }
    return when (this) {
        DetailLoad.Loading -> UiState.Loading
        is DetailLoad.Loaded -> UiState.Content(value)
        // The page has no state of its own for "nothing here": a source returns a title or fails. Both
        // read the same, and neither shows the failure's text: it is a class name and a plugin's words
        // (BUG-21). The host logs it where the failure is caught.
        DetailLoad.Empty -> UiState.Empty(titlePrompt(retry))
        is DetailLoad.Failed -> UiState.Error(titlePrompt(retry))
    }
}

/** The source list's [DetailLoad] as the design system draws it, in the composition's locale. */
@Composable
fun DetailLoad<List<StreamLink>>.toLinksUiState(onAction: (DetailAction) -> Unit): UiState<List<StreamLink>> {
    val latest by rememberUpdatedState(onAction)
    val retry = remember { { latest(DetailAction.RetryLinks) } }
    return when (this) {
        DetailLoad.Loading -> UiState.Loading
        is DetailLoad.Loaded -> UiState.Content(value)
        DetailLoad.Empty -> UiState.Empty(prompt(Res.string.no_links_found_toast, Res.string.source_error, retry))
        is DetailLoad.Failed -> UiState.Error(
            prompt(Res.string.error_loading_links_toast, Res.string.source_error, retry),
            detail,
        )
    }
}

/** The title could not be opened. This says nothing of links: none have been asked for yet. */
@Composable
private fun titlePrompt(retry: () -> Unit) =
    prompt(Res.string.detail_load_failed_headline, Res.string.detail_load_failed_body, retry)

@Composable
private fun prompt(headline: StringResource, body: StringResource, retry: () -> Unit) = StatePrompt(
    headline = stringResource(headline),
    body = stringResource(body),
    action = StateAction(stringResource(Res.string.search_state_retry), retry),
)

/** A provider's type name as words. Null for one with no copy, which then shows nothing. */
@Composable
internal fun typeLabel(typeName: String): String? = TYPE_LABELS[typeName]?.let { stringResource(it) }

/** `S1:E2`, from the translated letters. An episode with neither number takes its place in the list. */
@Composable
internal fun DetailEpisode.code(index: Int): String {
    val seasonShort = stringResource(Res.string.season_short)
    val episodeShort = stringResource(Res.string.episode_short)
    val number = episode ?: (index + 1).takeIf { season == null }
    return listOfNotNull(
        season?.let { "$seasonShort$it" },
        number?.let { "$episodeShort$it" },
    ).joinToString(":")
}

private val TYPE_LABELS: Map<String, StringResource> = mapOf(
    "Movie" to Res.string.movies_singular,
    "AnimeMovie" to Res.string.movies_singular,
    "TvSeries" to Res.string.tv_series_singular,
    "Cartoon" to Res.string.cartoons_singular,
    "Anime" to Res.string.anime_singular,
    "OVA" to Res.string.ova_singular,
    "Torrent" to Res.string.torrent_singular,
    "Documentary" to Res.string.documentaries_singular,
    "AsianDrama" to Res.string.asian_drama_singular,
    "Live" to Res.string.live_singular,
    "NSFW" to Res.string.nsfw_singular,
    "Others" to Res.string.other_singular,
    "Music" to Res.string.music_singular,
    "AudioBook" to Res.string.audio_book_singular,
    "CustomMedia" to Res.string.custom_media_singular,
    "Audio" to Res.string.audio_singular,
    "Podcast" to Res.string.podcast_singular,
    "Video" to Res.string.video_singular,
)

/** How the boards join the legs of a meta run, and the provider-and-type label. */
internal const val META_SEPARATOR = " \u00b7 "

/**
 * The run under the title on 7a and 7b, from only the legs the provider filled.
 *
 * Order is 7a's: score, tags, year, certification, then the last leg. 7b prints the same legs in a
 * different order, which is mockup copy rather than a rule, so one order serves both.
 *
 * The last leg is the runtime where there is one and the status otherwise — 7b's `2h 16m` against 7a's
 * `Ongoing`. A status identifier the copy does not know contributes nothing, the way [typeLabel]
 * returns null for a type it does not know, because `statusName` is a provider's own word.
 */
@Composable
internal fun metaRun(detail: TitleDetail): String {
    val score = detail.score?.let { UiString.Resource(Res.string.rating_format, listOf(oneDecimal(it))).resolve() }
    val runtime = detail.durationMinutes?.let { UiString.Resource(Res.string.duration_format, listOf(it)).resolve() }
    val status = detail.statusName?.let { statusLabel(it) }
    return listOfNotNull(
        score,
        detail.tags.takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR),
        detail.year?.toString(),
        detail.contentRating,
        runtime ?: status,
    ).joinToString(META_SEPARATOR)
}

/**
 * A score to one decimal, as `rating_format` (`%s/10.0`) expects it.
 *
 * The separator is a literal `.` because the resource's own `10.0` is one: writing a locale comma on one
 * side of the slash and a point on the other reads worse than writing neither. Formatting the whole
 * fraction through `%f` would need a resource that does not exist.
 */
private fun oneDecimal(score: Float): String {
    val tenths = (score.coerceAtLeast(0f) * TENTHS).roundToInt()
    return "${tenths / TENTHS_PER_UNIT}.${tenths % TENTHS_PER_UNIT}"
}

/** `Ongoing` or `Completed` for a status the copy knows. Null for anything else, as [typeLabel] does. */
@Composable
internal fun statusLabel(statusName: String): String? = STATUS_LABELS[statusName]?.let { stringResource(it) }

/**
 * `24m\nremaining`, the two mono lines of 7a's caption.
 *
 * Null when nothing usable is stored, which empties the slot without changing its height. The newline is
 * the resource's, which is why the slot is two lines tall: a one-line box clips it.
 */
@Composable
internal fun resumeCaption(resume: DetailResume?): String? =
    resume?.remainingMinutes?.let { UiString.Resource(Res.string.resume_time_left, listOf(it)).resolve() }

/** A watch list as the picker names it. */
@Composable
internal fun DetailWatchStatus.label(): String = stringResource(
    when (this) {
        DetailWatchStatus.Watching -> Res.string.type_watching
        DetailWatchStatus.Completed -> Res.string.type_completed
        DetailWatchStatus.OnHold -> Res.string.type_on_hold
        DetailWatchStatus.Dropped -> Res.string.type_dropped
        DetailWatchStatus.PlanToWatch -> Res.string.type_plan_to_watch
        DetailWatchStatus.None -> Res.string.type_none
    },
)

/**
 * A cast member's second line.
 *
 * `ActorAdaptor`'s precedence: the localised word where the provider sent one of the three role
 * identifiers, and the provider's own text otherwise — a character name is already in its final form and
 * there is nothing to translate it into.
 */
@Composable
internal fun roleLabel(role: String): String = ROLE_LABELS[role]?.let { stringResource(it) } ?: role

/**
 * The initials on the disc of a person with no headshot — 7a's cast item 5.
 *
 * Derived here rather than in `Avatar`, whose KDoc says deriving them is the caller's because it is locale
 * work. One letter per name, at most two, so the 20sp label never has to shrink.
 */
internal fun initialsOf(name: String): String = name
    .split(' ')
    .filter { it.isNotBlank() }
    .take(MAX_INITIALS)
    .joinToString("") { it.first().uppercaseChar().toString() }

/**
 * The one season the action row may name, or null where naming one would be a claim the page cannot
 * make.
 *
 * On the board `Season 1` and `Episodes · 10` are a pair: the second is **that season's**
 * [DetailSeason.episodeCount], not the title's whole episode list, and both open the season panel
 * (board 6a, unbuilt). With the panel missing there is no selector and no "showing season N" state, so
 * a title with several seasons would get `Season 1` beside a count drawn from one of them while the
 * list underneath shows every season — two numbers that contradict each other in a row whose whole
 * point is that it never reshuffles.
 *
 * So the row names a season only when the provider named exactly one. Beyond that the episode list
 * carries the seasons itself: every row shows its own `S1:E4` through [DetailEpisode.code].
 */
internal fun TitleDetail.namedSeason(): DetailSeason? = seasons.singleOrNull()

private const val TENTHS = 10f
private const val TENTHS_PER_UNIT = 10
private const val MAX_INITIALS = 2

private val STATUS_LABELS: Map<String, StringResource> = mapOf(
    "Ongoing" to Res.string.status_ongoing,
    "Completed" to Res.string.status_completed,
)

private val ROLE_LABELS: Map<String, StringResource> = mapOf(
    "Main" to Res.string.actor_main,
    "Supporting" to Res.string.actor_supporting,
    "Background" to Res.string.actor_background,
)
