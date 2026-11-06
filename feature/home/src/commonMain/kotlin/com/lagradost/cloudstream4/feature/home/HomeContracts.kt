package com.lagradost.cloudstream4.feature.home

import androidx.compose.runtime.Immutable
// kotlin.jvm.* is a default import only on JVM; Kotlin/Native needs it named.
import kotlin.jvm.JvmInline

/** Which source's main page is showing. Opaque here; the host that made it resolves it. */
@JvmInline
value class HomeSourceId(val value: String) {
    companion object {
        /**
         * `None` (`TV Home Extras` 3h, 3i): no source, so no hero and no source rails, only what is kept on the device. A
         * choice of the person's, remembered like a source, and never a source a host offers. No host's id can be it: a
         * name has no NUL in it.
         */
        val None: HomeSourceId = HomeSourceId("\u0000none")
    }
}

/**
 * One choice in the source picker.
 *
 * @property sections how many sections of its main page there are to ask for.
 * @property sequentialDelayMillis when set, the source asks for its sections one at a time, this far
 *   apart, usually because its site rate-limits. Null when they can all be asked at once.
 */
@Immutable
data class HomeSourceOption(
    val id: HomeSourceId,
    val name: String,
    /** A language code, shown as written. */
    val language: String? = null,
    val sections: Int = 1,
    val sequentialDelayMillis: Long? = null,
)

/**
 * @property options the sources that have a main page.
 * @property installed every source, with or without one. A source that only searches is common, and
 *   "none has a main page" needs different words from "nothing is installed".
 * @property stillLoading the host has not finished bringing up its sources, so one that is missing now may yet
 *   arrive. On Android plugins load in stages behind a screen that is already drawn.
 */
@Immutable
data class HomeSources(
    val options: List<HomeSourceOption>,
    val installed: Int,
    val stillLoading: Boolean = false,
)

/**
 * One title. [id] is opaque, and is what the host's detail flow opens.
 *
 * Everything here came from a plugin and the host has cleaned it. [posterUrl] goes back to the host's
 * `artwork`, never to anything in this module.
 */
@Immutable
data class HomeItem(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    /** Headers the host needs to fetch [posterUrl]. Goes back to the host's `artwork`, like the address. */
    val posterHeaders: Map<String, String>? = null,
    /** What the host knows and already put in words, such as a quality or `Dub`. Null draws no badge. */
    val badge: String? = null,
)

/** A row as one page of one section reported it. */
@Immutable
data class HomeRowPage(val title: String, val landscape: Boolean, val items: List<HomeItem>)

/** How asking for one page of one section went. Failing is normal, so it is data and not a throw. */
sealed interface HomePageReply {
    /** @property hasNext what the source claims. It is not believed past a page that adds nothing. */
    data class Loaded(val rows: List<HomeRowPage>, val hasNext: Boolean) : HomePageReply

    /** [detail] is the machine-readable line a user can paste into a bug report. */
    data class Failed(val detail: String) : HomePageReply

    data object Offline : HomePageReply
}

/**
 * Where main pages come from: the seam that keeps `:library` out of this module.
 *
 * A section is an index below [HomeSourceOption.sections], and a page counts from 1.
 */
interface HomeSource {
    suspend fun sources(): HomeSources

    suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply

    /**
     * What [item]'s own page says about it, for the hero (`TV Home` 3c), or null where there is nothing to show. Asking is
     * a request to the source, so a host without a way to ask keeps the default and its home has no hero.
     *
     * Failing is normal, so a host returns null rather than throwing, though a throw is treated the same.
     */
    suspend fun featured(item: HomeItem): HomeFeatured? = null
}

/**
 * The facts the hero shows (`TV Home` 3c). Each is null or empty where the source gave none, and the hero leaves that fact
 * out rather than drawing a placeholder. Everything here came from a plugin and the host has cleaned it.
 *
 * @property id the same opaque id as [HomeItem.id], so opening it is the same flow as opening a card.
 * @property backdropUrl wide art for behind the hero, or the poster where the source has none. Goes back to the host's
 *   `artwork`, like a poster.
 * @property score already written as the source gives it, such as `8.7`. Which site it is from is not known.
 * @property contentRating a certificate such as `16+`, as the source writes it.
 */
@Immutable
data class HomeFeatured(
    val id: String,
    val title: String,
    val backdropUrl: String? = null,
    val backdropHeaders: Map<String, String>? = null,
    val year: Int? = null,
    val durationMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val score: String? = null,
    val contentRating: String? = null,
    val synopsis: String? = null,
)

/**
 * One title to go back to, from what this device has played (`TV Home` 3c's Continue watching).
 *
 * @property id the same opaque id as [HomeItem.id], so opening it is the same flow as opening a card.
 * @property progress how far through the part it resumes from, `0f..1f`. Zero is real: a series moves on to its next
 *   episode once one is nearly finished, and nothing of that one has played yet.
 * @property season and [episode] the part it resumes from, where the title has parts.
 * @property sourceName the source it will be opened from.
 */
@Immutable
data class HomeResume(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val posterHeaders: Map<String, String>? = null,
    val progress: Float = 0f,
    val season: Int? = null,
    val episode: Int? = null,
    val sourceName: String? = null,
)

/**
 * What this device remembers playing. Local, so it is there with no source and no network.
 *
 * @property resume Continue watching, newest first.
 * @property progress how far through each title is, `0f..1f`, by [HomeItem.id], for any title with a stored position.
 *   A title with none has no entry.
 */
@Immutable
data class HomeHistory(
    val resume: List<HomeResume> = emptyList(),
    val progress: Map<String, Float> = emptyMap(),
)

/**
 * Where [HomeHistory] comes from. A host reads the store its library already reads, rather than keeping a second one.
 *
 * Every call reaches storage, so it may throw; the screen keeps what it had.
 */
interface HomeWatchHistory {
    suspend fun read(): HomeHistory

    /** Remembers nothing, for a host with no history to show. */
    object None : HomeWatchHistory {
        override suspend fun read(): HomeHistory = HomeHistory()
    }
}

/** The source chosen last, kept by the host between launches. */
interface HomeSourceMemory {
    /** The source chosen last, which may no longer be installed. */
    fun last(): HomeSourceId?

    fun remember(id: HomeSourceId)

    /** Keeps nothing, for a host with nowhere to keep it. */
    object None : HomeSourceMemory {
        override fun last(): HomeSourceId? = null

        override fun remember(id: HomeSourceId) = Unit
    }
}
