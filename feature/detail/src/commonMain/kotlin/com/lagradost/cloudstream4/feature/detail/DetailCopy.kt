package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.anime_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.asian_drama_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_book_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.cartoons_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.custom_media_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.documentaries_singular
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
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.core.strings.generated.resources.torrent_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.video_singular
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
        // The page has no state of its own for "nothing here": a source returns a title or fails.
        DetailLoad.Empty -> UiState.Empty(prompt(Res.string.source_error, Res.string.error_loading_links_toast, retry))
        is DetailLoad.Failed -> UiState.Error(
            prompt(Res.string.source_error, Res.string.error_loading_links_toast, retry),
            detail,
        )
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
