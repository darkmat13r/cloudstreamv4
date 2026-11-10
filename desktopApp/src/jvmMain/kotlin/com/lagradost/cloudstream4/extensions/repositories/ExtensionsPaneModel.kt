package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.extensions.ExtensionReviewState
import com.lagradost.cloudstream4.extensions.ReviewStatus
import com.lagradost.cloudstream4.extensions.SandboxedLoad
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a repository was not added (`KMP-110c`, board X2c). Kinds, never messages; [AlreadyAdded] names it as it named itself. */
sealed interface AddFailure {
    data object Invalid : AddFailure

    data object NotHttps : AddFailure

    data object Shortcode : AddFailure

    data object PrivateAddress : AddFailure

    data object TooLarge : AddFailure

    data object NewerFormat : AddFailure

    data object NotARepository : AddFailure

    data object Unreachable : AddFailure

    data class AlreadyAdded(val name: String) : AddFailure

    data object NotSaved : AddFailure
}

/** The "Add a repository" group: waiting, reading (board X2a), or what went wrong (X2b). */
sealed interface AddState {
    data object Idle : AddState

    data object Reading : AddState

    data class Failed(val failure: AddFailure) : AddState
}

/**
 * One repository in the list (board X3). [key] identifies it to the model and is a hash of its URL, because the URL,
 * which can carry a token, never leaves the model.
 *
 * @property readAtMillis when its list was last read, live or from the cache; null when it never has been.
 * @property reachable false when the last attempt to read it failed (X5a), and the list shown is the cached one.
 */
data class RepositoryRow(
    val key: String,
    val name: String,
    val extensions: Int,
    val installed: Int,
    val readAtMillis: Long?,
    val reachable: Boolean,
)

/** What one extension row says in its second line, and so which verb it offers (boards X4a and X4b). */
sealed interface RowState {
    data object Available : RowState

    /** [stated] is the size the repository gave, or null. */
    data class Installing(val received: Long, val stated: Long?) : RowState

    data class Installed(val verified: Boolean) : RowState

    data class UpdateAvailable(val from: Int, val to: Int) : RowState

    /** Off until the person chooses: installed from a repository and not allowed, or with warnings. */
    data object Held : RowState

    data object KeptOff : RowState

    data object Blocked : RowState

    /** The file on disk does not match its published hash: the review screen's own status. */
    data object HashMismatch : RowState

    /** The repository has it turned off, or offers it only to beta builds. Not installable. */
    data object NotOffered : RowState

    /** The last install did not happen, and why. */
    data class Failed(val failure: InstallFailure) : RowState
}

data class ExtensionRow(
    val internalName: String,
    val name: String,
    val version: Int,
    val language: String?,
    val tvTypes: List<String>,
    val sizeBytes: Long?,
    val state: RowState,
    /** False when the repository turns it off; an installed one keeps its state and says so. */
    val offered: Boolean,
)

/** One repository opened in the pane (board X4). */
data class OpenRepository(
    val key: String,
    val name: String,
    val readAtMillis: Long?,
    val reachable: Boolean,
    val rows: List<ExtensionRow>,
)

/**
 * The Settings → Extensions pane (`KMP-110c`).
 *
 * @property waiting extensions held for a choice, for X3c's row. Zero hides it.
 * @property offline every one of two or more repositories failed to answer on the last check (X5b). With one
 *   repository, offline and unreachable cannot be told apart, so it is said to be unreachable.
 * @property listsFromMillis when offline, the oldest time a list on screen was read.
 */
data class ExtensionsPaneState(
    val add: AddState = AddState.Idle,
    val repositories: List<RepositoryRow> = emptyList(),
    val open: OpenRepository? = null,
    val waiting: Int = 0,
    val offline: Boolean = false,
    val listsFromMillis: Long? = null,
    /** A removal could not be done, so nothing was removed: the installed list could not be read, or a file deleted. */
    val removeFailed: Boolean = false,
    /**
     * Goes up each time a repository is added. The pane empties its field on it (`#349` review): the link a person typed
     * can carry a token, and once it is added it is not shown again.
     */
    val added: Int = 0,
)

/** What the pane needs of the running extensions: `SandboxedExtensions`, in the app. */
interface PaneExtensions {
    suspend fun refresh(fileName: String): SandboxedLoad?

    fun forget(fileName: String)
}

/** What the pane needs of the review: `ExtensionApprovals`, in the app. */
interface PaneReviews {
    val state: StateFlow<ExtensionReviewState>

    fun track(load: SandboxedLoad)

    fun forget(fileName: String)
}

/**
 * The state and the verbs of the Extensions pane, with no Compose in it (`KMP-110c`). One per app, sharing the app's
 * one [RepositoryInstaller] and [InstalledFromRepositories].
 *
 * The order around a file is the review's: an install is followed by [PaneExtensions.refresh], which takes the new file
 * through the pipeline and comes back held; a removal is preceded by [PaneExtensions.forget], which stops it first.
 *
 * No URL a person added is ever in [state]: rows carry a [RepositoryRow.key], and the model keeps the URLs.
 */
class ExtensionsPaneModel(
    private val scope: CoroutineScope,
    private val store: RepositoryStore,
    private val cache: RepositoryCache,
    private val installer: RepositoryInstaller,
    private val installed: InstalledFromRepositories,
    private val extensions: PaneExtensions,
    private val reviews: PaneReviews,
    private val reader: RepositoryReader = RepositoryReader(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ExtensionsPaneState())
    val state: StateFlow<ExtensionsPaneState> = _state

    /** What is known of each repository, by URL. Guarded by being touched only from [scope]'s single updates. */
    private val known = MutableStateFlow<Map<String, Known>>(emptyMap())
    private val installing = MutableStateFlow<Map<Pair<String, String>, Installing>>(emptyMap())
    private val failures = MutableStateFlow<Map<Pair<String, String>, InstallFailure>>(emptyMap())
    private var adding: Job? = null
    private var openKey: String? = null
    private var offline = false
    private var loaded = false

    private class Known(val read: RepositoryRead.Read?, val readAtMillis: Long?, val reachable: Boolean)

    private class Installing(val job: Job, val received: Long, val stated: Long?)

    init {
        scope.launch { reviews.state.collect { publish() } }
    }

    /**
     * Reads every saved repository: the cache first, so the list shows at once, then each live. Once per model: coming
     * back to the pane does not fetch every repository again (`#349` review); [refresh] is the explicit way to.
     */
    fun load() {
        if (loaded) return
        loaded = true
        val saved = store.read().repositories
        known.value = saved.associate { repository ->
            val cached = cache.read(repository.url)
            repository.url to Known(cached?.read, cached?.readAtMillis, reachable = true)
        }
        publish()
        refresh()
    }

    /** Reads every saved repository again (X5a's Retry, and coming back online). */
    fun refresh() {
        val saved = store.read().repositories
        scope.launch {
            val results = coroutineScope { saved.map { repository -> async { repository.url to reader.read(repository.url) } }.awaitAll() }
            val unreachable = results.count { (_, read) -> read is RepositoryRead.Failed }
            offline = saved.size >= 2 && unreachable == saved.size
            known.update { current ->
                current + results.associate { (url, read) ->
                    url to when (read) {
                        is RepositoryRead.Read -> {
                            val at = now()
                            runCatching { cache.write(url, read, at) }
                            Known(read, at, reachable = true)
                        }
                        is RepositoryRead.Failed -> current[url]?.let { Known(it.read, it.readAtMillis, reachable = false) }
                            ?: Known(null, null, reachable = false)
                    }
                }
            }
            publish()
        }
    }

    /** Add's press, with what is in the field (X1, X2). */
    fun add(text: String) {
        val url = when (val link = parseRepositoryLink(text)) {
            is RepositoryLink.Https -> link.url
            RepositoryLink.Shortcode -> return failAdd(AddFailure.Shortcode)
            RepositoryLink.NotHttps -> return failAdd(AddFailure.NotHttps)
            RepositoryLink.Invalid -> return failAdd(AddFailure.Invalid)
        }
        val saved = store.read()
        saved.repositories.firstOrNull { it.url == url }?.let { return failAdd(AddFailure.AlreadyAdded(it.name)) }
        _state.update { it.copy(add = AddState.Reading) }
        adding?.cancel()
        adding = scope.launch {
            when (val read = reader.read(url)) {
                is RepositoryRead.Failed -> failAdd(read.failure.toAddFailure())
                is RepositoryRead.Read -> try {
                    if (!store.add(SavedRepository(url, read.index.name))) return@launch failAdd(AddFailure.AlreadyAdded(read.index.name))
                    val at = now()
                    runCatching { cache.write(url, read, at) }
                    known.update { it + (url to Known(read, at, reachable = true)) }
                    _state.update { it.copy(add = AddState.Idle, added = it.added + 1) }
                    publish()
                } catch (_: IOException) {
                    failAdd(AddFailure.NotSaved)
                }
            }
        }
    }

    /** Cancel, while a repository is read (X2a): ends the wait at once; the field keeps its link. */
    fun cancelAdd() {
        adding?.cancel()
        adding = null
        _state.update { it.copy(add = AddState.Idle) }
    }

    /** Typing in the field clears an add failure. */
    fun editing() {
        if (_state.value.add is AddState.Failed) _state.update { it.copy(add = AddState.Idle) }
    }

    fun open(key: String) {
        openKey = key
        publish()
    }

    fun close() {
        openKey = null
        publish()
    }

    /** Install or Update, or Retry after a failure. Not while its repository cannot be reached (X5a). */
    fun install(key: String, internalName: String) {
        val url = urlFor(key) ?: return
        val entry = known.value[url]?.takeIf { it.reachable } ?: return
        val listing = entry.read?.listings?.firstOrNull { it.internalName == internalName } ?: return
        val id = url to internalName
        if (id in installing.value) return
        failures.update { it - id }
        // `#345` review: recorded before it can run, so a fast finish cannot leave a permanent Installing behind it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = installer.install(url, listing) { received, stated ->
                installing.update { current -> current[id]?.let { current + (id to Installing(it.job, received, stated)) } ?: current }
                publish()
            }
            when (result) {
                is InstallResult.Installed -> {
                    result.replaced.forEach { name ->
                        extensions.forget(name)
                        reviews.forget(name)
                    }
                    extensions.refresh(result.fileName)?.let(reviews::track)
                }
                is InstallResult.Failed -> failures.update { it + (id to result.failure) }
            }
        }
        installing.update { it + (id to Installing(job, 0, listing.jarFileSizeBytes ?: listing.fileSizeBytes)) }
        job.invokeOnCompletion {
            installing.update { it - id }
            publish()
        }
        publish()
        job.start()
    }

    /** Cancel on an installing row (X4a): the download stops, and nothing is written or recorded. */
    fun cancelInstall(key: String, internalName: String) {
        val url = urlFor(key) ?: return
        installing.value[url to internalName]?.job?.cancel()
    }

    /** Remove on an installed row: stopped first, then deleted. */
    fun removeExtension(key: String, internalName: String) {
        val url = urlFor(key) ?: return
        scope.launch {
            _state.update { it.copy(removeFailed = false) }
            val list = installed.read() ?: return@launch removeFailed()
            if (!removeAll(list.filter { it.repositoryUrl == url && it.internalName == internalName })) removeFailed()
            publish()
        }
    }

    /**
     * Stops and deletes each of [entries]. A review row is dropped only for a file that really went.
     *
     * @return false when any could not be deleted.
     */
    private suspend fun removeAll(entries: List<InstalledFromRepository>): Boolean {
        var all = true
        entries.forEach { entry ->
            extensions.forget(entry.fileName)
            val removed = runCatching { installer.remove(entry.fileName) }.getOrDefault(false)
            if (removed) reviews.forget(entry.fileName) else all = false
        }
        return all
    }

    private fun removeFailed() {
        _state.update { it.copy(removeFailed = true) }
    }

    /** How many of its extensions a repository's removal takes with it, for X3b's sheet. */
    fun installedFrom(key: String): Int {
        val url = urlFor(key) ?: return 0
        return installed.read().orEmpty().count { it.repositoryUrl == url }
    }

    /** X3b's Remove: the repository, and every extension installed from it, each stopped first. */
    fun removeRepository(key: String) {
        val url = urlFor(key) ?: return
        scope.launch {
            _state.update { it.copy(removeFailed = false) }
            // `#345` review: a repository whose files cannot all be accounted for stays, or they would be left behind with
            // nothing to say where they came from.
            val list = installed.read() ?: return@launch removeFailed()
            if (!removeAll(list.filter { it.repositoryUrl == url })) return@launch removeFailed()
            if (runCatching { store.remove(url) }.isFailure) return@launch removeFailed()
            cache.remove(url)
            known.update { it - url }
            if (openKey == key) openKey = null
            publish()
        }
    }

    private fun failAdd(failure: AddFailure) {
        _state.update { it.copy(add = AddState.Failed(failure)) }
    }

    private fun urlFor(key: String): String? = known.value.keys.firstOrNull { keyOf(it) == key }

    /** Recomputes [state] from what is known. Cheap: a few hundred rows at most. */
    private fun publish() {
        val reviewState = reviews.state.value
        val byFile = reviewState.reviews.associateBy { it.fileName }
        val installedList = installed.read().orEmpty()
        val saved = store.read().repositories
        val rows = saved.mapNotNull { repository ->
            val entry = known.value[repository.url] ?: return@mapNotNull null
            RepositoryRow(
                key = keyOf(repository.url),
                name = entry.read?.index?.name ?: repository.name,
                extensions = entry.read?.listings?.size ?: 0,
                installed = installedList.count { it.repositoryUrl == repository.url },
                readAtMillis = entry.readAtMillis,
                reachable = entry.reachable,
            )
        }
        val open = openKey?.let { key ->
            val url = urlFor(key) ?: return@let null
            val entry = known.value[url] ?: return@let null
            val name = entry.read?.index?.name ?: saved.firstOrNull { it.url == url }?.name.orEmpty()
            OpenRepository(
                key = key,
                name = name,
                readAtMillis = entry.readAtMillis,
                reachable = entry.reachable,
                rows = entry.read?.listings.orEmpty().map { listing -> row(url, listing, installedList, byFile) },
            )
        }
        val oldest = if (offline) known.value.values.mapNotNull { it.readAtMillis }.minOrNull() else null
        _state.update {
            it.copy(
                repositories = rows,
                open = open,
                waiting = reviewState.reviews.count { review -> review.status == ReviewStatus.Held },
                offline = offline,
                listsFromMillis = oldest,
            )
        }
    }

    private fun row(
        url: String,
        listing: ExtensionListing,
        installedList: List<InstalledFromRepository>,
        byFile: Map<String, com.lagradost.cloudstream4.extensions.ExtensionReview>,
    ): ExtensionRow {
        val id = url to listing.internalName
        val offered = RepositoryInstaller.isOffered(listing.status)
        val entry = installedList.firstOrNull { it.repositoryUrl == url && it.internalName == listing.internalName }
        val state = installing.value[id]?.let { RowState.Installing(it.received, it.stated) }
            ?: failures.value[id]?.let { RowState.Failed(it) }
            ?: when {
                entry == null -> if (offered) RowState.Available else RowState.NotOffered
                else -> when (byFile[entry.fileName]?.status) {
                    ReviewStatus.Held, ReviewStatus.Starting -> RowState.Held
                    ReviewStatus.KeptOff -> RowState.KeptOff
                    ReviewStatus.Blocked -> RowState.Blocked
                    ReviewStatus.HashMismatch -> RowState.HashMismatch
                    else -> installer.updateFor(entry, listing)?.let { RowState.UpdateAvailable(entry.version, it) }
                        ?: RowState.Installed(entry.verified)
                }
            }
        return ExtensionRow(
            internalName = listing.internalName,
            name = listing.name,
            version = listing.version,
            language = listing.language,
            tvTypes = listing.tvTypes,
            sizeBytes = listing.jarFileSizeBytes ?: listing.fileSizeBytes,
            state = state,
            offered = offered,
        )
    }

    companion object {
        /** A repository's key: 16 hex digits of SHA-256 over its URL. Stable, and not the URL. */
        fun keyOf(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        private fun RepositoryFailure.toAddFailure(): AddFailure = when (this) {
            RepositoryFailure.NotHttps -> AddFailure.NotHttps
            RepositoryFailure.PrivateAddress -> AddFailure.PrivateAddress
            RepositoryFailure.TooLarge -> AddFailure.TooLarge
            RepositoryFailure.UnsupportedFormat -> AddFailure.NewerFormat
            RepositoryFailure.NotARepository, RepositoryFailure.NotFound -> AddFailure.NotARepository
            RepositoryFailure.Unreachable, RepositoryFailure.TooManyRedirects -> AddFailure.Unreachable
        }
    }
}
