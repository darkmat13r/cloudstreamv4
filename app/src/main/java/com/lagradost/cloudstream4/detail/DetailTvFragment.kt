package com.lagradost.cloudstream4.detail

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph
import androidx.navigation.fragment.FragmentNavigator
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.NoVideoGenerator
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.tv.DetailScreenTv
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.search.decodeId
import com.lagradost.cloudstream4.search.encodeId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One title's page on the TV layout: the glue around `DetailScreenTv`, as `SearchTvFragment` and
 * `LibraryTvFragment` are for their screens. It owns the ViewModel, turns effects into navigation and
 * loads artwork; everything Android lives here and nothing else does.
 *
 * ## How TV reaches it
 *
 * [openDetailOnTvScreen] rewrites `navigation_results_tv`'s class name on the inflated graph, called
 * from `MainActivity` under `if (isLayout(TV))`. **Deleting that one call is the rollback** — the node
 * goes back to `ResultFragmentTv`, which stays in the tree for one release.
 *
 * The node is *not* repointed in `mobile_navigation.xml`, although `docs/plans/tv-screen-audit.md`
 * says it could be: `AppContextUtils.getResultsId()` returns that same node for
 * `isLayout(TV or EMULATOR)` (`:703-709`), so editing the XML would move the EMULATOR layout too, and
 * EMULATOR must not change. `Globals.layoutId` holds one value, so `isLayout(TV)` excludes EMULATOR.
 *
 * ## What it does not reproduce
 *
 * `docs/tasks/kmp.md` carries the full list. The two that are decisions rather than unbuilt work:
 *
 * - **Start over writes storage.** `GeneratorPlayer.newInstance` has no start-position parameter
 *   (`GeneratorPlayer.kt:151-164`) and `getPos()` computes the position from `getViewPos` itself
 *   (`:247-254`), so there is nowhere to pass a zero. This writes `setViewPos(episodeId, 0, duration)`
 *   before navigating instead — which means **pressing Start over and then backing out without
 *   watching loses the stored position**, and does nothing at all when the stored duration is under
 *   30 s, because `setViewPos` early-returns there (`DataStoreHelper.kt:683-687`). The alternative is
 *   an explicit start position on `GeneratorPlayer`/`VideoGenerator`, which are in the published ABI
 *   dump; that is the owner's call.
 * - **An episode's stored id.** See [DetailHostFacts]: this host only ever uses an id a seam gave it,
 *   so a part it has no id for is played with none, and the player then keeps no position for it.
 */
class DetailTvFragment : BaseComposeFragment() {

    /**
     * The opaque id for this title, in the search source's one format.
     *
     * `getStoredData()` is read **once**: it consumes `restart` and removes `startAction`/`startValue`
     * from the arguments as a side effect (`ResultFragment.kt:296-299`, `:306-307`). `startAction`
     * and `startValue` are dropped on the floor here — autostart is `ResultViewModel2.handleAutoStart`
     * and this page has no equivalent.
     */
    private val titleId: String? by lazy {
        getStoredData()?.let { stored -> encodeId(apiName = stored.apiName, url = stored.url).value }
    }

    /** Held as well as handed to the ViewModel, for the facts [DetailHostFacts] carries. */
    private val source by lazy { AndroidDetailSource(requireContext().applicationContext) }

    private val viewModel: DetailViewModel by viewModels {
        val id = titleId.orEmpty()
        val detailSource = source
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                DetailViewModel(id = id, source = detailSource, scope = scope)
                    .also { it.addCloseable(AutoCloseable { scope.cancel() }) }
            }
        }
    }

    /**
     * The page is back: a bookmark may have changed in the player, and this is the moment focus is
     * allowed to return. `DetailReturn.ready` waits for it because a fragment composes its view well
     * before it resumes, and focus taken in that gap lands on the rail (`KMP-34` measured it).
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wirePanelBack()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(DetailAction.Reappeared)
    }

    /**
     * Closes an open side panel on platform back, which is the wiring `SidePanelHost` asks its host for.
     *
     * It handles BACK itself only as a Compose key event on the focused panel, and says so: *"A host on
     * Android should also wire its platform back (the activity's `OnBackPressedDispatcher`) to the same
     * `onDismissRequest`, gated on `expanded`. This module deliberately does not."* On `Television_720p`
     * an injected BACK reaches the activity first and finished it, so without this the picker could only
     * be left by picking something and `DEC-17`'s "Back closes either" did not hold. This page is the
     * first `SidePanelHost` caller in the app, so it is where that gap surfaced; every later panel host
     * needs the same six lines, which is an argument for the design system owning it.
     *
     * `isEnabled` follows [DetailState.panelOpen], so BACK with no panel up falls through to navigation
     * and still leaves the screen.
     */
    private fun wirePanelBack() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                viewModel.onAction(DetailAction.PanelDismissed)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { callback.isEnabled = it.panelOpen }
        }
    }

    private fun onEffect(effect: DetailEffect) {
        when (effect) {
            is DetailEffect.OpenPlayer -> openPlayer(effect.data, effect.fromStart)
            DetailEffect.PlayTrailer -> playTrailer()
            is DetailEffect.OpenTitle -> openTitle(effect.id)
            // Desktop picks a stream from the source list itself and plays it. Android hands `data`
            // to `GeneratorPlayer`, which resolves links on its own, so nothing here emits this.
            is DetailEffect.Play -> Unit
        }
    }

    /**
     * The existing player, by the path `ResultViewModel2.handleEpisodeClickEvent` takes
     * (`:1602-1607`): a generator, an index and the sync ids, then the global player action.
     */
    private fun openPlayer(data: String, fromStart: Boolean) {
        val facts = source.hostFacts(titleId ?: return) ?: return
        val episodeId = episodeIdOf(data, facts)
        if (fromStart && episodeId != null) {
            // The stored duration is reused rather than passed as zero, because `setViewPos`
            // early-returns when the duration is under 30 s (`DataStoreHelper.kt:683-687`). Read
            // live: the player may have moved it since this page loaded.
            DataStoreHelper.getViewPos(episodeId)
                ?.let { stored -> DataStoreHelper.setViewPos(episodeId, 0L, stored.duration) }
        }
        openPlayer(DetailPlaybackGenerator(facts.apiName, data, episodeId), facts)
    }

    /**
     * The id `:app` keeps this part's position under, or null when this host does not know one.
     *
     * Only the two a seam answers for: the part the viewer was last on, and the title itself, whose
     * id a playable title's own position is kept under. Everything else is null on purpose — see
     * [DetailHostFacts].
     */
    private fun episodeIdOf(data: String, facts: DetailHostFacts): Int? {
        val detail = viewModel.state.value.detail
        return when (data) {
            detail?.resume?.data -> facts.resumeEpisodeId
            detail?.playData -> facts.titleId
            else -> null
        }
    }

    /** `ResultFragmentTv.kt:548-567`, which resolves the trailers and reuses the same player. */
    private fun playTrailer() {
        val facts = source.hostFacts(titleId ?: return) ?: return
        lifecycleScope.launch {
            val links = resolveTrailers(facts.trailers)
            if (links.isEmpty()) return@launch
            openPlayer(ExtractorLinkGenerator(links, emptyList()), facts = null)
        }
    }

    private fun openPlayer(generator: VideoGenerator<*>, facts: DetailHostFacts?) {
        val syncData = facts?.syncData?.let { HashMap(it) }
        activity.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(generator, FIRST_PART, syncData),
        )
    }

    /** Another title, through the one entry everything else uses. */
    private fun openTitle(id: String) {
        val target = decodeId(SearchResultId(id)) ?: return
        val title = viewModel.state.value.detail
            ?.recommendations?.firstOrNull { it.id == id }?.title.orEmpty()
        loadResult(url = target.url, apiName = target.apiName, name = title)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        // Read once the title is loaded, which is when the facts exist and when artwork is drawn.
        val posterHeaders = state.detail?.let { source.hostFacts(titleId.orEmpty())?.posterHeaders }
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { onEffect(it) }
        }
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                DetailScreenTv(
                    state = state,
                    onAction = viewModel::onAction,
                    onBack = { activity?.onBackPressedDispatcher?.onBackPressed() },
                    artwork = { url -> Artwork(url, posterHeaders) },
                )
            }
        }
    }

    private companion object {
        /** The generator holds one part, so the player opens at its only index. */
        const val FIRST_PART = 0
    }
}

/**
 * Sends the TV result destination to [DetailTvFragment]. Call once, with the graph just inflated.
 *
 * The node stays `ResultFragmentTv` in `mobile_navigation.xml`, for the reason on
 * `openSearchOnTvScreen`: a nav graph has no per-layout form because `isLayout(TV)` is a runtime
 * preference. A class-name swap also keeps the node's two actions, to quick-search and to the player.
 */
internal fun NavGraph.openDetailOnTvScreen() {
    (findNode(R.id.navigation_results_tv) as? FragmentNavigator.Destination)
        ?.setClassName(DetailTvFragment::class.java.name)
}

/**
 * One part's links, from the provider that owns it.
 *
 * `NoVideoGenerator` is the existing shape for "a generator with one known, or unknown, id" —
 * `ExtractorLinkGenerator` is its sibling — and its `id` being nullable is the point: the player reads
 * and writes a stored position under `generator.getId(index)`
 * (`GeneratorPlayer.kt:247-254`, `:1747`), so a null id makes it keep none rather than write one under
 * a key nothing else reads.
 *
 * `RepoLinkGenerator` is not used because it takes `List<ResultEpisode>`, and a `ResultEpisode` needs
 * the non-null `Int` id that only `ResultViewModel2.postEpisodes`' private arithmetic produces. What
 * this costs, and it is not nothing: that generator's twenty-minute link cache, the player's next and
 * previous episode buttons, and the `setLastWatched` write that puts a title in Continue Watching —
 * `setViewPosAndResume` makes it only for a `ResultEpisode` meta (`DataStoreHelper.kt:719-742`).
 */
private class DetailPlaybackGenerator(
    private val apiName: String,
    private val data: String,
    id: Int?,
) : NoVideoGenerator(id) {

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val api = APIHolder.getApiFromNameNull(apiName) ?: return false
        return APIRepository(api).loadLinks(
            data = data,
            isCasting = isCasting,
            subtitleCallback = { file -> subtitleCallback(PlayerSubtitleHelper.getSubtitleData(file)) },
            // The user's "which protocols may play" set, which `ExtractorLinkGenerator` also honours.
            callback = { link -> if (link.type in sourceTypes) callback(link to null) },
        )
    }
}

/**
 * The trailers as playable links, which is `ResultViewModel2.getTrailers` (`:2489-2541`) without the
 * per-trailer subtitle list no trailer button has ever shown.
 *
 * A `TrailerData.extractorUrl` is not a playable address: it goes through `loadExtractor`. When no
 * extractor claims it and the provider marked it `raw`, the URL itself becomes the one link, as the
 * original does. The limit is the original's, and it is a budget rather than a preference: each
 * trailer is a network round trip on a page the viewer is waiting on.
 */
internal suspend fun resolveTrailers(
    trailers: List<TrailerData>,
    limit: Int = MAX_TRAILERS,
): List<ExtractorLink> = withContext(Dispatchers.IO) {
    trailers.take(limit).flatMap { trailer ->
        val found = ArrayList<ExtractorLink>()
        val claimed = try {
            loadExtractor(trailer.extractorUrl, trailer.referer, {}, { link -> found.add(link) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!claimed && trailer.raw) listOf(trailer.asRawLink()) else found
    }
}

private suspend fun TrailerData.asRawLink(): ExtractorLink =
    newExtractorLink(source = "", name = TRAILER_SOURCE_NAME, url = extractorUrl, type = INFER_TYPE) {
        this.referer = this@asRawLink.referer.orEmpty()
        this.quality = Qualities.Unknown.value
        this.headers = this@asRawLink.headers
    }

/**
 * The hero and the posters, from the same loader and with the same headers as search's cards: the
 * app's user agent, which a provider's own may override.
 *
 * The provider's `posterHeaders` do not reach this through [com.lagradost.cloudstream4.feature.detail.TitleDetail],
 * which has no field for them — see the report on that gap. They come off [DetailHostFacts] instead.
 */
@Composable
private fun Artwork(url: String?, posterHeaders: Map<String, String>?) {
    val address = url?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    val request = remember(context, address, posterHeaders) {
        ImageRequest.Builder(context)
            .data(address)
            .httpHeaders(
                NetworkHeaders.Builder().also { headers ->
                    headers["User-Agent"] = USER_AGENT
                    posterHeaders?.forEach { (key, value) -> headers[key] = value }
                }.build(),
            )
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** `getTrailers`' own window, so a provider with twenty trailers costs three requests, not twenty. */
private const val MAX_TRAILERS = 3

/** What the original synthesises a raw trailer link's name as (`ResultViewModel2.kt:2518`). */
private const val TRAILER_SOURCE_NAME = "Trailer"
