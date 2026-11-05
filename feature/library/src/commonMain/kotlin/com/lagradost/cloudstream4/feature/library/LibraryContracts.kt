package com.lagradost.cloudstream4.feature.library

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * A title's identity in the library. The seam that keeps `:library` out of this module.
 *
 * ## The decision this file exists to record
 *
 * This is **the same opaque string the detail flow takes**. On desktop that is
 * `DesktopSearchSource.encodeId(apiName, url)` — the provider's own name and the title's URL, joined — so
 * a library row reopens a title by handing the id straight back to the screen a search hit would open.
 * Nothing here parses it, and nothing here hashes it.
 *
 * `:app` does it differently, and measurably worse: `getLoadResponseIdFromUrl` is a 32-bit
 * `String.hashCode()` of the provider-*relative* path with its slashes stripped, and the provider's name
 * is used only to find the prefix to strip — it is not part of what is hashed. So two providers that both
 * serve `/movie/dune` collide to one id, and therefore share one bucket, one record and one position. The
 * id also changes depending on whether the extension is currently loaded, because an unloaded provider
 * means the whole URL is hashed instead. None of that is acknowledged in a comment there.
 *
 * The two stores are **not shared** — a bookmark on the phone does not appear on the desktop. That is the
 * call `DesktopSearchHistory` already made and documented, for the same reason: reconciling them is a
 * sync question, not a storage one. When something does reconcile them, `(apiName, url)` is recoverable
 * from this id and the `Int` hash is not, so the mapping runs in the direction that can work.
 *
 * **What it costs, honestly:** an id dies when a provider changes its URLs. The row stays in the file, the
 * card still draws, and opening it fails the way any dead link fails. A hash would not have survived that
 * either — it would only have hidden which row broke.
 */
@JvmInline
value class LibraryTitleId(val value: String)

/**
 * Which list a title sits in.
 *
 * The first five and their order mirror `:app`'s `WatchType`, so the day something reconciles the two
 * stores it is a mapping and not a redesign. `WatchType.NONE` has no entry here: "in no list" is a null
 * bucket, which is also how `:app` stores it — it deletes the row.
 *
 * [Favourites] is a **departure**: in `:app` favourites are a separate store with their own record type
 * (`RESULT_FAVORITES_STATE_DATA`), not a `WatchType`. On this screen it behaves exactly like a bucket, so
 * it is one. `:app`'s *Subscribed* list is deliberately absent — it is about notifications for new
 * episodes, which desktop has no machinery for at all.
 */
enum class LibraryBucket {
    Watching,
    Completed,
    OnHold,
    Dropped,
    PlanToWatch,
    Favourites,
}

/**
 * A title the library knows about.
 *
 * A title gets a row here two ways: the user put it in a list, or they played it. The second case is
 * what makes a continue-watching list drawable at all — a [WatchPosition] carries no name and no poster,
 * and nothing should re-ask a provider to draw a row.
 *
 * `:app` solves the same problem with `DOWNLOAD_HEADER_CACHE`, a store that is **not** account-scoped
 * while every bookmark and position is, and that the download code prunes — which is why its own resume
 * row has a comment about restoring data for affected users. A row that carries its own display fields
 * cannot lose them that way.
 *
 * @param bucket which list, or null for a title that is only known because it was played.
 * @param keptAt epoch millis, from the store's clock. Orders a bucket; never shown to the user, because
 *   there is no relative-time string set to show it with (`DesktopSearchHistory` documents that gap).
 * @param title and [posterUrl] and [providerName] all came from a plugin and stay untrusted: the store
 *   sanitises them again on the way out, because the file was written from the same untrusted data.
 * @param typeLabel the kind of title, already in the user's language, or null where the host does not
 *   hold one. Desktop's store has no such field, so its cards draw no badge.
 * @param posterHeaders what [posterUrl] must be asked for with, where a provider requires it. Android's
 *   bookmarks keep these. Desktop's do not, and it passes null.
 */
@Immutable
data class LibraryTitle(
    val id: LibraryTitleId,
    val title: String,
    val bucket: LibraryBucket?,
    val keptAt: Long,
    val posterUrl: String? = null,
    val providerName: String? = null,
    val typeLabel: String? = null,
    val posterHeaders: Map<String, String>? = null,
)

/**
 * Where playback of one playable thing got to.
 *
 * @param episodeData the episode's opaque `data` — the provider's own handle, the same string that goes
 *   back to `DetailSource.links` unread — or null when the title itself is what plays. An episode
 *   therefore needs no identity of ours, and a provider that changes its episode handles orphans the
 *   position exactly as it orphans the id.
 *
 *   `:app` instead derives an `Int` episode id by *adding* to the title's hash
 *   (`mainId + episode + idIndex * 1_000_000 + season * 10_000` for anime,
 *   `mainId + season * 100_000 + index + 1` for series, and `mainId` itself for a movie — with the
 *   literal comment `HAS SAME ID`). Those wrap, the two formulas disagree, and only collisions *within*
 *   one title are guarded.
 * @param durationMillis what the player reported. Zero or less where it never did, which makes
 *   [fraction] meaningless and is why [resumable] refuses it.
 */
@Immutable
data class WatchPosition(
    val id: LibraryTitleId,
    val episodeData: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val watchedAt: Long,
) {
    /** How far through, `0f..1f`. Zero where the duration is unknown, so a bar cannot draw a lie. */
    val fraction: Float
        get() = if (durationMillis <= 0L) 0f else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

    /** Near enough to the end to count as watched. */
    val finished: Boolean get() = durationMillis > 0L && fraction >= FINISHED_FRACTION

    /**
     * Worth offering to resume: the player knew the length, there is something to come back to, and it is
     * not the first half-minute — reopening a title you opened by accident is not a feature.
     */
    val resumable: Boolean
        get() = durationMillis > 0L && !finished && positionMillis >= MINIMUM_RESUME_MILLIS

    companion object {
        /**
         * Past this, the title leaves the continue-watching list.
         *
         * `:app` uses 90% (`NEXT_WATCH_EPISODE_PERCENTAGE`) and spends it differently: at 90% it
         * *advances* its resume pointer to the next episode, and on the last episode drops the title.
         * Advancing needs an episode order this module does not have — `DetailEpisode.data` is opaque —
         * so here 95% simply means watched, and the next episode is one the user picks.
         */
        const val FINISHED_FRACTION = 0.95f

        /** Below this there is nothing to come back to. `:app` refuses to store a duration under 30 s. */
        const val MINIMUM_RESUME_MILLIS = 30_000L
    }
}

/**
 * Where the library lives.
 *
 * Every call reaches a file, so every call can fail, and these report it **by throwing** — the contract
 * `DetailSource` already uses. The ViewModel turns a throw into a state; a host's bug must not take the
 * screen down with it.
 */
interface LibraryStore {
    /**
     * Every row. **One id can appear twice, with different buckets**, where a host keeps its lists in
     * separate stores: on Android a title can be in Watching and in Favourites at once, because favourites
     * are their own record type (`LibraryBucket.Favourites` above). Desktop has one bucket per id.
     */
    suspend fun titles(): List<LibraryTitle>

    /**
     * Records what a title looks like, **keeping whatever list it is already in**. Called when a title is
     * played, so that a continue-watching row has a name and a poster without asking a provider again.
     */
    suspend fun remember(title: LibraryTitle)

    /**
     * Moves a title between lists. Null takes it out of every list; the row survives while it still has a
     * position, so removing a bookmark does not also forget where you were.
     */
    suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?)

    suspend fun positions(): List<WatchPosition>

    /** Replaces the position for the same id and `episodeData`. Called repeatedly while something plays. */
    suspend fun record(position: WatchPosition)

    /** Takes a title off the continue-watching list, and with it any row that was only there for it. */
    suspend fun clearPositions(id: LibraryTitleId)
}
