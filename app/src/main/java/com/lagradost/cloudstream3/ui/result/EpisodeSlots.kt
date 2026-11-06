package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse

/**
 * One playable part of a [LoadResponse], with the `Int` id `:app` keeps everything about it under.
 *
 * That id is the key for the stored position (`DataStoreHelper.setViewPos`), the watched marker
 * (`setVideoWatchState`), the download record and — through `ResultEpisode.id` reaching
 * `setViewPosAndResume` — the Continue Watching entry. It is **derived arithmetic, not a provider's
 * identifier**: see [episodeSlots] for the formula and why it cannot be re-invented per screen.
 *
 * @param dub the list an [AnimeLoadResponse] carried this part in. [DubStatus.None] for every other
 *   response type, which is also what `EpisodeIndexer` is keyed on for them.
 * @param source the provider's own episode, or null for a one-part response — a film, a live stream
 *   and a torrent each have a `dataUrl` and no [Episode] at all.
 * @param episode the resolved episode number, which is the provider's where it gave one and the
 *   1-based position in its own list where it did not.
 * @param index the position [buildResultEpisode] is given, counted within this part's own list and
 *   **before** de-duplication, so it is not always `slots.indexOf(this)`.
 */
data class EpisodeSlot(
    val dub: DubStatus,
    val source: Episode?,
    val season: Int?,
    val episode: Int,
    val data: String,
    val id: Int,
    val index: Int,
)

/**
 * This response's playable parts, in the order and with the ids the result page has always given them.
 *
 * Lifted out of `ResultViewModel2.postEpisodes`, where it was three inline expressions, so that a
 * second screen can reach the same ids instead of deriving its own. It has to be shared rather than
 * copied: the id is a **storage key**, so two derivations that differ by one do not render slightly
 * differently — one of them writes positions and Continue Watching entries under keys the other
 * screen, Home and the library never read, and the viewer sees their progress vanish.
 *
 * The formula, per response type and unchanged from `postEpisodes`:
 *
 * | Type | Id |
 * |---|---|
 * | [AnimeLoadResponse] | `mainId + episode + dub.id * 1_000_000 + season * 10_000` |
 * | [TvSeriesLoadResponse] | `mainId + season * 100_000 + episode + 1` |
 * | [MovieLoadResponse], [LiveStreamLoadResponse], [TorrentLoadResponse] | `mainId` |
 *
 * A one-part response's id being `mainId` itself is what makes a film's stored position the *title's*
 * position, which is why `getViewPos(titleId)` is a film's resume and an episode's is not.
 *
 * Two properties of this that look like detail and are not, because the id is built from them:
 *
 * - **A series is walked in sorted order, anime is not.** The `episode ?: index + 1` fallback numbers
 *   an unnumbered part by where it sits, so the walk order is part of the formula.
 * - **A repeated id is dropped, not renumbered.** Two parts that collide — same season and episode,
 *   which providers do send — leave one slot, and the second is gone rather than pushed to a free id.
 *   Anime shares one seen-set across its dub lists; the `dub.id * 1_000_000` stride is what keeps
 *   those lists from colliding with each other in it.
 *
 * Everything else about a part is the caller's: `postEpisodes` reads season names, fillers and the
 * total-episode index it needs for the old page, and the TV detail page reads almost none of them.
 *
 * @param mainId the title's own id, `LoadResponse.getId()`. Passed rather than read here so that this
 *   stays pure and testable: `getId` resolves the provider by name through `APIHolder`.
 * @return an empty list for a response type with no playable part, which is what `postEpisodes`'
 *   `else` arm produces.
 */
fun LoadResponse.episodeSlots(mainId: Int): List<EpisodeSlot> = when (this) {
    is AnimeLoadResponse -> animeSlots(mainId)
    is TvSeriesLoadResponse -> tvSeriesSlots(mainId)
    is MovieLoadResponse -> onePartSlot(mainId, dataUrl)
    is LiveStreamLoadResponse -> onePartSlot(mainId, dataUrl)
    // `torrent` first, as `postEpisodes` has it. The magnet is the fallback, not the preference.
    is TorrentLoadResponse -> onePartSlot(mainId, torrent ?: magnet ?: "")
    else -> emptyList()
}

/** One list per dub status, each walked in the order the provider filled it. */
private fun AnimeLoadResponse.animeSlots(mainId: Int): List<EpisodeSlot> {
    val existingEpisodes = HashSet<Int>()
    val slots = mutableListOf<EpisodeSlot>()
    episodes.forEach { (status, list) ->
        val idIndex = status.id
        for ((index, i) in list.withIndex()) {
            val episode = i.episode ?: (index + 1)
            val id = mainId + episode + idIndex * 1_000_000 + (i.season?.times(10_000) ?: 0)
            if (!existingEpisodes.add(id)) continue
            slots += EpisodeSlot(
                dub = status,
                source = i,
                season = i.season,
                episode = episode,
                data = i.data,
                id = id,
                index = index,
            )
        }
    }
    return slots
}

/**
 * Sorted by season then episode, on the `season * 10_000 + episode` key `postEpisodes` uses.
 *
 * The stride means a provider numbering past 10,000 within a season would sort into the next one.
 * Kept exactly as it was: the sort order feeds the `episode ?: index + 1` fallback and therefore the
 * ids, so a better comparator here would silently move every unnumbered episode's stored position.
 */
private fun TvSeriesLoadResponse.tvSeriesSlots(mainId: Int): List<EpisodeSlot> {
    val existingEpisodes = HashSet<Int>()
    val slots = mutableListOf<EpisodeSlot>()
    for ((index, episode) in episodes.sortedBy {
        (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
    }.withIndex()) {
        val episodeIndex = episode.episode ?: (index + 1)
        val id = mainId + (episode.season?.times(100_000) ?: 0) + episodeIndex + 1
        if (!existingEpisodes.add(id)) continue
        slots += EpisodeSlot(
            dub = DubStatus.None,
            source = episode,
            season = episode.season,
            episode = episodeIndex,
            data = episode.data,
            id = id,
            index = index,
        )
    }
    return slots
}

/** A film, a live stream or a torrent: one part, numbered 0, under the title's own id. */
private fun onePartSlot(mainId: Int, data: String): List<EpisodeSlot> = listOf(
    EpisodeSlot(
        dub = DubStatus.None,
        source = null,
        season = null,
        episode = 0,
        data = data,
        id = mainId,
        index = 0,
    )
)
