package com.lagradost.cloudstream4.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.episodes
import com.lagradost.cloudstream4.core.strings.generated.resources.error_loading_links_toast
import com.lagradost.cloudstream4.core.strings.generated.resources.go_back
import com.lagradost.cloudstream4.core.strings.generated.resources.no_episodes_found
import com.lagradost.cloudstream4.core.strings.generated.resources.no_links_found_toast
import com.lagradost.cloudstream4.core.strings.generated.resources.pick_source
import com.lagradost.cloudstream4.core.strings.generated.resources.play_episode
import com.lagradost.cloudstream4.core.strings.generated.resources.play_movie_button
import com.lagradost.cloudstream4.core.strings.generated.resources.anime_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.asian_drama_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_book_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.audio_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.cartoons_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.custom_media_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.documentaries_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.episode_short
import com.lagradost.cloudstream4.core.strings.generated.resources.live_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.movies_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.music_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.nsfw_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.other_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.ova_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.podcast_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.season_short
import com.lagradost.cloudstream4.core.strings.generated.resources.torrent_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.tv_series_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.video_singular
import com.lagradost.cloudstream4.core.strings.generated.resources.search_state_retry
import com.lagradost.cloudstream4.core.strings.generated.resources.source_error
import com.lagradost.cloudstream4.feature.detail.DetailPrompts
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.tv.DetailTvCopy
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource

/**
 * The screen's words, all existing strings. Nothing new was authored, because a new key moves
 * `core/strings/oracle-baseline`.
 */
@Composable
internal fun rememberDesktopDetailCopy(): Pair<DetailTvCopy, DetailPrompts> {
    val retry = stringResource(Res.string.search_state_retry)
    val sourceError = stringResource(Res.string.source_error)
    val linksFailed = stringResource(Res.string.error_loading_links_toast)
    val noLinks = stringResource(Res.string.no_links_found_toast)
    val typeLabels = mapOf(
        "Movie" to stringResource(Res.string.movies_singular),
        "AnimeMovie" to stringResource(Res.string.movies_singular),
        "TvSeries" to stringResource(Res.string.tv_series_singular),
        "Cartoon" to stringResource(Res.string.cartoons_singular),
        "Anime" to stringResource(Res.string.anime_singular),
        "OVA" to stringResource(Res.string.ova_singular),
        "Torrent" to stringResource(Res.string.torrent_singular),
        "Documentary" to stringResource(Res.string.documentaries_singular),
        "AsianDrama" to stringResource(Res.string.asian_drama_singular),
        "Live" to stringResource(Res.string.live_singular),
        "NSFW" to stringResource(Res.string.nsfw_singular),
        "Others" to stringResource(Res.string.other_singular),
        "Music" to stringResource(Res.string.music_singular),
        "AudioBook" to stringResource(Res.string.audio_book_singular),
        "CustomMedia" to stringResource(Res.string.custom_media_singular),
        "Audio" to stringResource(Res.string.audio_singular),
        "Podcast" to stringResource(Res.string.podcast_singular),
        "Video" to stringResource(Res.string.video_singular),
    )
    val copy = DetailTvCopy(
        back = stringResource(Res.string.go_back),
        playMovie = stringResource(Res.string.play_movie_button),
        playEpisode = stringResource(Res.string.play_episode),
        episodes = stringResource(Res.string.episodes),
        noEpisodes = stringResource(Res.string.no_episodes_found),
        sources = stringResource(Res.string.pick_source),
        seasonShort = stringResource(Res.string.season_short),
        episodeShort = stringResource(Res.string.episode_short),
        typeLabel = typeLabels::get,
    )
    val prompts = remember(retry, sourceError, linksFailed, noLinks) {
        object : DetailPrompts {
            override fun failed(detail: String, onRetry: () -> Unit) = UiState.Error(
                StatePrompt(sourceError, linksFailed, StateAction(retry, onRetry)),
                detail,
            )

            override fun linksFailed(detail: String, onRetry: () -> Unit) = UiState.Error(
                StatePrompt(linksFailed, sourceError, StateAction(retry, onRetry)),
                detail,
            )

            override fun noLinks(onRetry: () -> Unit) = UiState.Empty(
                StatePrompt(noLinks, sourceError, StateAction(retry, onRetry)),
            )
        }
    }
    return copy to prompts
}

/**
 * Keeps a title's ViewModel while the player is over it, so backing out of the player lands on the
 * same source list. There is no navigation library yet to scope it.
 */
internal object DesktopDetailViewModels {
    private val live = mutableMapOf<String, DetailViewModel>()
    private val source: DetailSource = DesktopDetailSource()

    fun get(id: String, prompts: DetailPrompts, scope: CoroutineScope): DetailViewModel =
        live.getOrPut(id) { DetailViewModel(id, source, prompts, scope) }

    fun forget(id: String) {
        live.remove(id)
    }
}
