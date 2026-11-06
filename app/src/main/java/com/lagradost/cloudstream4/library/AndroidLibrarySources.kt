package com.lagradost.cloudstream4.library

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.syncproviders.providers.LocalList
import com.lagradost.cloudstream3.ui.library.LAST_SYNC_API_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream4.feature.library.LibrarySource
import com.lagradost.cloudstream4.feature.library.LibrarySourceId
import com.lagradost.cloudstream4.feature.library.LibrarySources
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.SyncedList
import com.lagradost.cloudstream4.search.encodeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One of `AccountManager.syncApis`, with the two facts about it that read the signed-in accounts. Those are lambdas so
 * a test can give a fake `SyncAPI` an account without the real account store.
 *
 * @param local whether this is the list on this device, `LocalList`, whose lists the library store reads.
 */
internal class SyncLibrarySource(
    val repo: SyncRepo,
    val connected: () -> Boolean = { repo.isAvailable },
    val account: () -> String? = { repo.authUser()?.name },
    val local: Boolean = repo.api is LocalList,
) {
    val id = LibrarySourceId(repo.name)
}

/** What the sources remember: the old screen's own key for the choice, and the time of each read. */
internal interface LibrarySourceRecords {
    fun chosen(): String?
    fun choose(name: String)
    fun syncedAt(name: String): Long?
    fun synced(name: String, at: Long)
}

/**
 * Under the current account, as the old screen's choice is (`"$currentAccount/last_sync_api"`). The time is a new key,
 * written only by [AndroidLibrarySources], so nothing that predates it has a time and no age is shown for it.
 */
internal object DataStoreLibrarySourceRecords : LibrarySourceRecords {
    override fun chosen(): String? = getKey<String>("$currentAccount/$LAST_SYNC_API_KEY")
    override fun choose(name: String) = setKey("$currentAccount/$LAST_SYNC_API_KEY", name)
    override fun syncedAt(name: String): Long? = getKey<Long>("$currentAccount/$SYNCED_AT_FOLDER", name)
    override fun synced(name: String, at: Long) = setKey("$currentAccount/$SYNCED_AT_FOLDER", name, at)

    private const val SYNCED_AT_FOLDER = "library_synced_at"
}

/** A title read from a tracking service, and what the old screen's opener needs to open it. */
internal data class SyncedTitle(val item: SyncAPI.LibraryItem, val syncName: SyncIdName?)

/**
 * [LibrarySources] over what `:app`'s library screen uses: `AccountManager.syncApis`, through `SyncRepo`.
 *
 * ## Read as the old screen reads
 *
 * - The source to open on is `LibraryViewModel.currentSyncApi`'s rule: the one chosen last, where it is still signed
 *   in, else the first that is.
 * - A read is `SyncRepo.library()`. A failure is the exception it holds, and a service that answers nothing is
 *   `"Unable to fetch library"`, the old screen's words.
 * - A service's own cache is honoured as `reloadPages(false)` does: while `requireLibraryRefresh` is false, the last
 *   read is shown again and no new time is recorded. A successful read clears that flag, as the old screen does.
 *
 * ## The owner's choices, each in one place
 *
 * Which sources are listed and in what order is [listed]. How a service's lists become tabs is [syncedLists]. What a
 * source that is not signed in does is the panel's (`LibrarySourcePanelTv`).
 *
 * @param labels resolves a service's `UiText` list names, in the app's language, per read. `UiText` is deprecated for
 *   `UiString`, and `SyncAPI` still names its lists with it, so this reads it as it comes.
 */
@Suppress("DEPRECATION")
internal class AndroidLibrarySources(
    private val all: () -> List<SyncLibrarySource>,
    private val records: LibrarySourceRecords,
    private val labels: () -> (UiText) -> String,
    private val typeLabels: () -> (TvType) -> String,
    private val localCount: suspend () -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibrarySources {

    constructor(context: Context, store: AndroidLibraryStore) : this(
        all = { AccountManager.syncApis.map { SyncLibrarySource(it) } },
        records = DataStoreLibrarySourceRecords,
        labels = { sourceContext(context).let { localized -> { text -> text.asString(localized) } } },
        typeLabels = { sourceTypeWords(context) },
        localCount = { store.titles().filter { it.bucket != null }.distinctBy { it.id }.size },
    )

    /** The last read per source, which a service's own cache lets this show again. */
    private val lastRead = ConcurrentHashMap<LibrarySourceId, List<SyncedList>>()

    /** What each title of a source's last read is, for the opener, kept beside [lastRead] so a cached read has its own. */
    private val titles = ConcurrentHashMap<LibrarySourceId, Map<LibraryTitleId, SyncedTitle>>()

    /** The service whose lists were asked for last, from a read or from the cache: the one shown. */
    @Volatile
    private var shown: LibrarySourceId? = null

    override suspend fun sources(): List<LibrarySource> = withContext(io) {
        val count = runCatching { localCount() }.getOrNull()
        listed(all()).map { source ->
            LibrarySource(
                id = source.id,
                name = source.repo.name,
                local = source.local,
                connected = source.local || source.connected(),
                account = if (source.local) null else source.account()?.sourceText()?.ifEmpty { null },
                syncedAt = if (source.local) null else records.syncedAt(source.id.value),
                titleCount = if (source.local) count else null,
            )
        }
    }

    override suspend fun current(): LibrarySourceId = withContext(io) {
        val available = all().filter { it.local || it.connected() }
        val last = records.chosen()
        (available.firstOrNull { it.repo.name == last } ?: available.firstOrNull() ?: all().first { it.local }).id
    }

    override suspend fun choose(id: LibrarySourceId) = withContext(io) { records.choose(id.value) }

    override suspend fun lists(id: LibrarySourceId): List<SyncedList> = withContext(io) {
        val source = all().first { it.id == id }
        val repo = source.repo
        lastRead[id]?.takeIf { !repo.requireLibraryRefresh }?.let {
            shown = id
            return@withContext it
        }
        val metadata = repo.library().getOrThrow() ?: throw IllegalStateException("Unable to fetch library")
        repo.requireLibraryRefresh = false
        records.synced(id.value, clock())
        val read = mutableMapOf<LibraryTitleId, SyncedTitle>()
        val lists = syncedLists(metadata, labels(), typeLabels()) { titleId, item ->
            read[titleId] = SyncedTitle(item, repo.syncIdName)
        }
        titles[id] = read
        lastRead[id] = lists
        shown = id
        lists
    }

    /** The title [id] was read as, where it is one of the shown service's. */
    fun synced(id: LibraryTitleId): SyncedTitle? = shown?.let { titles[it] }?.get(id)

    companion object {
        /**
         * Which sources the panel lists, and in what order: all five the app has (`Trakt`, drawn on 5e, is not one of
         * them, and Kitsu, which is, is not drawn), signed-in ones first, each group in the app's own order. 5e draws
         * the connected ones above the rest.
         */
        internal fun listed(sources: List<SyncLibrarySource>): List<SyncLibrarySource> {
            val (ready, rest) = sources.partition { it.local || it.connected() }
            return ready + rest
        }

        /**
         * A service's lists as tabs: each list it returns, in its order and its words, Rewatching included where it
         * has one. Folding one list into another is a change to this function only.
         */
        internal fun syncedLists(
            metadata: SyncAPI.LibraryMetadata,
            label: (UiText) -> String,
            typeLabel: (TvType) -> String,
            onTitle: (LibraryTitleId, SyncAPI.LibraryItem) -> Unit = { _, _ -> },
        ): List<SyncedList> = metadata.allLibraryLists.mapIndexed { index, list ->
            SyncedList(
                key = list.key(index),
                label = label(list.name).sourceText(),
                titles = list.items.mapNotNull { item ->
                    item.toTitle(typeLabel)?.also { onTitle(it.id, item) }
                },
            )
        }

        /** The list's own string, so a tab keeps its key across reads. An empty name falls back to its place. */
        private fun SyncAPI.LibraryList.key(index: Int): String = when (val text = name) {
            is UiText.StringResource -> "res:${text.resId}"
            is UiText.DynamicString -> "text:${text.value.ifEmpty { index.toString() }}"
        }

        /**
         * Sanitised on the way in, as the local store's rows are: a tracking service's text is as untrusted as a
         * plugin's. A title with no name or no address is left out, as the store leaves out a bookmark without one.
         */
        private fun SyncAPI.LibraryItem.toTitle(typeLabel: (TvType) -> String): LibraryTitle? {
            val title = name.sourceText().ifEmpty { return null }
            if (url.isBlank() || apiName.isBlank()) return null
            return LibraryTitle(
                id = LibraryTitleId(encodeId(apiName, url).value),
                title = title,
                bucket = null,
                keptAt = lastUpdatedUnixTime ?: 0L,
                posterUrl = posterUrl?.takeIf { it.isSourceUrl() },
                providerName = apiName.sourceText().ifEmpty { null },
                typeLabel = type?.let { typeLabel(it).sourceText().uppercase(Locale.getDefault()) },
                posterHeaders = posterHeaders
                    ?.filter { (key, value) -> key.isSourceHeaderSafe() && value.isSourceHeaderSafe() }
                    ?.ifEmpty { null },
                episodesWatched = episodesCompleted?.takeIf { it >= 0 },
                episodesTotal = episodesTotal?.takeIf { it > 0 },
                rating = personalRating?.let { runCatching { it.toStringNull(MIN_RATING, MAX_RATING) }.getOrNull() },
            )
        }

        /** Below this a service means "not rated": AniList and MAL send 0 for that. */
        private const val MIN_RATING = 0.1
        private const val MAX_RATING = 10
    }
}
