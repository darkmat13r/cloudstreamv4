package com.lagradost.cloudstream4.feature.library

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/** Which source a library is read from: the host's own name for it, opaque here. */
@JvmInline
value class LibrarySourceId(val value: String)

/**
 * One place a library comes from, as `TV Library` 5e lists it: the list on this device, or a tracking service's.
 *
 * @property local the list kept on this device, which [LibraryStore] reads. There is exactly one, it is always
 *   [connected], and reading it never needs a network.
 * @property connected whether its lists can be read: signed in, for a tracking service.
 * @property account the signed-in name, where the service gives one.
 * @property syncedAt when its lists were last read successfully, epoch milliseconds, **only as this app recorded it**.
 *   Null where no read has been recorded, which includes every read before the record existed: an age the app did
 *   not record is never shown.
 * @property titleCount how many titles it holds, where the host knows that without a network: the local list's.
 * @property sorts what its lists can be sorted by. A criterion it holds no data for is not among them, and its row in
 *   5d is disabled with the reason. Every criterion, the default, is a source with no such gap.
 */
@Immutable
data class LibrarySource(
    val id: LibrarySourceId,
    val name: String,
    val local: Boolean = false,
    val connected: Boolean = true,
    val account: String? = null,
    val syncedAt: Long? = null,
    val titleCount: Int? = null,
    val sorts: Set<LibrarySortCriterion> = LibrarySortCriterion.entries.toSet(),
)

/**
 * One of a tracking service's own lists, in that service's order, with the words it names it by.
 *
 * Not a [LibraryBucket]: a service's lists are its own. AniList has Rewatching, which no bucket is, and none has
 * Favourites. Which lists there are, and in what order, is the host's call, made in one place.
 *
 * @property key stable while the source is the same, so a tab survives a re-read.
 */
@Immutable
data class SyncedList(
    val key: String,
    val label: String,
    val titles: List<LibraryTitle>,
)

/**
 * The sources a library can be read from, and the one chosen. The host owns all of it: what is signed in, what
 * was chosen last time, and when each was last read.
 *
 * Like [LibraryStore], a call that fails **throws**, and the ViewModel turns that into a state.
 */
interface LibrarySources {
    /** Every source, in the order the panel lists them. */
    suspend fun sources(): List<LibrarySource>

    /** The source to open on: the one chosen last, where it can still be read, else the host's default. */
    suspend fun current(): LibrarySourceId

    /** Remembers [id] as the source to open on. */
    suspend fun choose(id: LibrarySourceId)

    /**
     * A tracking service's lists, read now. Records the time on success, which the next [sources] reports.
     * Never called for the [LibrarySource.local] source, whose lists are [LibraryStore]'s.
     */
    suspend fun lists(id: LibrarySourceId): List<SyncedList>

    /** The sort chosen last, for every source, or null where none was. A host that keeps none has the default. */
    suspend fun sort(): LibrarySort? = null

    /** Remembers [sort] as the choice for every source. */
    suspend fun sortBy(sort: LibrarySort) = Unit
}
