package com.lagradost.cloudstream4.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.library.DesktopLibraryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** One profile as the file keeps it. [name] null is the first profile's default name, shown in the current language. */
@Serializable
internal data class StoredProfile(val id: Int, val name: String? = null)

/**
 * The same file as [StoredProfiles], read with no defaults and no unknown keys, for the one use that must not guess:
 * deciding which libraries are no longer anyone's. A key missing or extra, or a field of another type, fails it.
 */
@Serializable
private data class StrictProfiles(val version: Int, val current: Int, val profiles: List<StrictProfile>)

@Serializable
private data class StrictProfile(val id: Int, val name: String?)

/** Every profile, and which one is watching. Profile [FIRST] is always there. */
@Serializable
internal data class StoredProfiles(
    val version: Int = VERSION,
    val current: Int = FIRST,
    val profiles: List<StoredProfile> = listOf(StoredProfile(FIRST)),
) {
    internal companion object {
        const val VERSION = 1

        /** The first profile: Android's account index 0, and the way back in when a PIN is forgotten (`DS-25` A6c). */
        const val FIRST = 0
    }
}

/**
 * The Mac's profiles (`KMP-95`, `DS-25`), in one owner-only file: `~/.cloudstream4/profiles.json`.
 *
 * A profile is a separate watch history, not a login (8d). It separates the library only, as Android's accounts do:
 * bookmarks, watch positions and resume (the PM's ruling, 2026-11-08). Settings, downloads, extensions, the home
 * source, the volume and the picture fit are the Mac's, shared by every profile.
 *
 * **No migration.** Profile [StoredProfiles.FIRST] reads and writes the library files the Mac already has, so a person
 * who never adds a profile sees nothing move. Profile `n` has its own `library-n.tsv` and `watch-positions-n.tsv`.
 *
 * Read once at launch, at most [MAX_FILE_BYTES]. A file that is missing, unreadable, too large or not this format reads
 * as the first profile alone, which is what the Mac had before profiles; the next change replaces it. Within a file
 * that parses, a profile with a negative or repeated id is dropped, a name loses control and format characters and is
 * cut to [MAX_NAME_CHARS], the list is cut to [MAX_PROFILES], and the first profile is put back if it is missing.
 *
 * A write goes to an owner-only temporary file in the same directory and is moved over the old one, as
 * `FileSettingsStorage` does, so a crash leaves the previous file whole. A directory this creates is owner-only.
 */
internal class DesktopProfileFile(
    private val file: File = File(DesktopLibraryStore.defaultTitlesFile().parentFile, FILE_NAME),
) {
    fun load(): StoredProfiles = read() ?: StoredProfiles()

    /** The file's profiles, read leniently and cleaned, or null when it is missing, unreadable, too large or not JSON. */
    fun read(): StoredProfiles? {
        val bytes = runCatching {
            if (!file.isFile) null else file.inputStream().use { it.readNBytes(MAX_FILE_BYTES.toInt() + 1) }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_FILE_BYTES) return null
        return runCatching { json.decodeFromString(StoredProfiles.serializer(), bytes.decodeToString()) }.getOrNull()?.cleaned()
    }

    /**
     * The file's profile ids, only when it is exactly a list this version wrote: every key present and none unknown,
     * [StoredProfiles.VERSION], and nothing [cleaned] would change about the ids (no repeat, none out of range, no more
     * than [MAX_PROFILES], the first profile there). Otherwise null. **This, not [read], is what may remove a library**
     * (the reviewer of #281): `{}`, `{"version":1}`, a later version's list or a damaged one all decode leniently to the
     * first profile alone, and a sweep over that would remove every other profile's library.
     */
    fun strictIds(): Set<Int>? {
        val bytes = runCatching {
            if (!file.isFile) null else file.inputStream().use { it.readNBytes(MAX_FILE_BYTES.toInt() + 1) }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_FILE_BYTES) return null
        val raw = runCatching { strictJson.decodeFromString(StrictProfiles.serializer(), bytes.decodeToString()) }.getOrNull()
            ?: return null
        if (raw.version != StoredProfiles.VERSION) return null
        val ids = raw.profiles.map { it.id }
        val cleaned = StoredProfiles(raw.version, raw.current, raw.profiles.map { StoredProfile(it.id, it.name) }).cleaned()
        return if (cleaned.profiles.map { it.id } == ids) ids.toSet() else null
    }

    fun save(profiles: StoredProfiles) {
        val directory = checkNotNull(file.parentFile).toPath()
        if (!Files.isDirectory(directory)) {
            val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_DIR))
            try {
                Files.createDirectories(directory, ownerOnly)
            } catch (e: UnsupportedOperationException) {
                Files.createDirectories(directory) // a volume with no POSIX permissions
            }
        }
        val next = Files.createTempFile(directory, file.name, ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(next, PosixFilePermissions.fromString(OWNER_ONLY)) }
            Files.writeString(next, json.encodeToString(StoredProfiles.serializer(), profiles))
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    internal companion object {
        const val FILE_NAME = "profiles.json"
        const val MAX_FILE_BYTES = 64L * 1024
        /** In code points. */
        const val MAX_NAME_CHARS = 64

        /** Far more than a household; a file with more was not written by this app. */
        const val MAX_PROFILES = 32

        /**
         * Ids are below this. A file with a larger one was not written by this app, and a hostile `Int.MAX_VALUE` would
         * otherwise make the next id wrap negative.
         */
        const val MAX_ID = 10_000
        private const val OWNER_ONLY = "rw-------"
        private const val OWNER_DIR = "rwx------"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = true }

        fun StoredProfiles.cleaned(): StoredProfiles {
            val seen = HashSet<Int>()
            val kept = profiles
                .filter { it.id in 0 until MAX_ID && seen.add(it.id) }
                .map { it.copy(name = it.name?.let(::cleanName)?.ifEmpty { null }) }
                .take(MAX_PROFILES)
            val withFirst = if (kept.any { it.id == StoredProfiles.FIRST }) kept else listOf(StoredProfile(StoredProfiles.FIRST)) + kept
            return StoredProfiles(
                current = current.takeIf { id -> withFirst.any { it.id == id } } ?: StoredProfiles.FIRST,
                profiles = withFirst.take(MAX_PROFILES),
            )
        }

        /**
         * As the library does it: a FORMAT character reorders the text around it, and a control has no place in a name.
         * Cut by code points, so a character outside the BMP is kept whole or not at all, never half a surrogate pair.
         */
        fun cleanName(name: String): String {
            val kept = name.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim()
            val points = kept.codePointCount(0, kept.length)
            return if (points <= MAX_NAME_CHARS) kept else kept.substring(0, kept.offsetByCodePoints(0, MAX_NAME_CHARS))
        }
    }
}

/**
 * The profiles while the window is open (`KMP-95a`, `95b`): who is watching, the library each one reads, and adding,
 * renaming and deleting them.
 *
 * @param onSaveFailed told when a change could not be written, or a deleted profile's library file could not be removed.
 *   The change still holds until the app quits.
 */
internal class DesktopProfiles(
    private val file: DesktopProfileFile = DesktopProfileFile(),
    private val libraryDirectory: File = DesktopLibraryStore.defaultTitlesFile().parentFile,
    private val onSaveFailed: (Throwable) -> Unit = {},
    /**
     * `KMP-95c`: each profile's PIN and its wrong-PIN count. These defaults are an unsigned build's; the app passes
     * `AccountStorage.forBuild`'s, which are the Keychain's on a signed build (`KMP-95f`).
     */
    val pins: DesktopPins = DesktopPins(libraryDirectory, onSaveFailed = onSaveFailed),
    /** `KMP-95d`: each profile's sign-ins. In memory on an unsigned build (D3), the Keychain on a signed one (`95f`). */
    val signIns: SignInStore = InMemorySignInStore(),
) {
    var stored: StoredProfiles by mutableStateOf(StoredProfiles())
        private set

    /** Bumped when a PIN is set or removed, so the cards, which read [pins], are drawn again. */
    var pinChanges: Int by mutableStateOf(0)
        private set

    init {
        stored = file.load()
        // A deleted profile's history should not sit on disk until its id is taken again (the reviewer of #281). Only
        // over a strict read: anything else reads leniently as the first profile alone, and trusting that would remove
        // every other profile's library.
        file.strictIds()?.let { kept ->
            clearLibrariesNotIn(kept)
            // The same rule for a deleted profile's PIN and count, and its sign-ins: only over a strict read.
            pins.retainOnly(kept)
            signIns.retainOnly(kept)
        }
    }

    /** The launch picker shows only with two profiles or more, the default counted (`KMP-73`, A1). */
    val picksAtLaunch: Boolean get() = stored.profiles.size >= 2

    /** Makes [id] the profile watching, and remembers it for the next launch. An id that is not a profile is ignored. */
    fun choose(id: Int) {
        if (stored.profiles.none { it.id == id } || stored.current == id) return
        update(stored.copy(current = id))
    }

    /** Whether another profile fits under [DesktopProfileFile.MAX_PROFILES]; Add is offered only while it does. */
    val canAdd: Boolean get() = stored.profiles.size < DesktopProfileFile.MAX_PROFILES

    /**
     * Adds a profile named [name] and returns its id, or null when there is no room (`KMP-95b`, A6b) or when a PIN
     * left under its id could not be forgotten, because the PINs file could not be read or written (the reviewer of
     * `#286`): then nothing is added, so the new profile can never inherit that PIN once the file reads. The same for
     * sign-ins left under its id, which outlive a quit on a signed build (`KMP-95f`). It does not become the one
     * watching. A blank name becomes [fallback], so a profile always has words on its card.
     *
     * Its id is one above the highest there is. Any library files already under that id are deleted first, so a new
     * profile never starts with what a deleted one left behind (a delete whose file removal failed).
     */
    fun add(name: String, fallback: String): Int? {
        if (!canAdd) return null
        val id = stored.profiles.maxOf { it.id } + 1
        if (id >= DesktopProfileFile.MAX_ID) return null
        // A reused id starts clean: no library, PIN or wrong-PIN count of a profile deleted before (the reviewer of #286).
        // The PIN first: if it cannot be forgotten, nothing is added or touched. Then any sign-ins under that id.
        if (!pins.forget(id)) return null
        if (!signIns.forget(id)) return null
        clearLibrary(id)
        val cleaned = DesktopProfileFile.cleanName(name).ifEmpty { DesktopProfileFile.cleanName(fallback) }
        update(stored.copy(profiles = stored.profiles + StoredProfile(id, cleaned.ifEmpty { null })))
        return id
    }

    /**
     * Renames [id] (A6). A blank name keeps the one it had, except on the first profile, where it goes back to the
     * default name in the current language. An id that is not a profile is ignored.
     */
    fun rename(id: Int, name: String) {
        if (stored.profiles.none { it.id == id }) return
        val cleaned = DesktopProfileFile.cleanName(name)
        val profiles = stored.profiles.map { profile ->
            when {
                profile.id != id -> profile
                cleaned.isNotEmpty() -> profile.copy(name = cleaned)
                id == StoredProfiles.FIRST -> profile.copy(name = null)
                else -> profile
            }
        }
        if (profiles != stored.profiles) update(stored.copy(profiles = profiles))
    }

    /**
     * Deletes [id] and its library from this Mac (A8). The first profile is never deleted: it is the way back in. If
     * [id] was watching, the first profile is. The profile leaves the file before its library files go, so a crash
     * between the two leaves files that no profile reads, which the next [add] under that id clears.
     *
     * @return false when nothing was deleted: the first profile, or an id that is not a profile.
     */
    fun delete(id: Int): Boolean {
        if (!canDelete(id)) return false
        val saved = update(
            stored.copy(
                current = if (stored.current == id) StoredProfiles.FIRST else stored.current,
                profiles = stored.profiles.filterNot { it.id == id },
            ),
        )
        // Only once the file no longer lists it: a profile whose delete could not be written comes back at the next
        // launch, and must come back with its library (the reviewer of #281).
        if (saved) {
            clearLibrary(id)
            pins.forget(id)
        }
        // Whether or not the file was written: A8 says the sign-ins leave with the profile. On a signed build a forget the
        // Keychain refuses leaves them under an id no profile has; the next add under it, or the launch sweep, retries.
        signIns.forget(id)
        return true
    }

    /** Whether [delete] would delete [id]: every profile but the first. */
    fun canDelete(id: Int): Boolean = id != StoredProfiles.FIRST && stored.profiles.any { it.id == id }

    private fun clearLibrary(id: Int) {
        if (id == StoredProfiles.FIRST) return
        listOf(titlesFile(id), positionsFile(id)).forEach { runCatching { Files.deleteIfExists(it.toPath()) }.onFailure(onSaveFailed) }
    }

    /** Holds [next] until the app quits, and returns whether it was also written. A failure is reported. */
    private fun update(next: StoredProfiles): Boolean {
        stored = next
        return runCatching { file.save(next) }.onFailure(onSaveFailed).isSuccess
    }

    /** Removes `library-n.tsv` and `watch-positions-n.tsv` for every `n` not in [kept]. */
    private fun clearLibrariesNotIn(kept: Set<Int>) {
        libraryDirectory.listFiles().orEmpty().forEach { file ->
            val id = LIBRARY_FILE.matchEntire(file.name)?.groupValues?.get(2)?.toIntOrNull() ?: return@forEach
            if (id != StoredProfiles.FIRST && id !in kept) runCatching { Files.deleteIfExists(file.toPath()) }.onFailure(onSaveFailed)
        }
    }

    /** Sets [id]'s PIN (A7, A7b). The first profile is never locked. False when it could not be written: nothing changed. */
    fun setPin(id: Int, pin: String): Boolean = pins.set(id, pin).also { pinChanges++ }

    /** Removes [id]'s PIN, once its current one was given (A7c). False when it could not be written: still locked. */
    fun removePin(id: Int): Boolean = pins.remove(id).also { pinChanges++ }

    /**
     * The cards the picker and the sidebar's list draw. A profile with a PIN is locked. Its line names the sync services
     * it is signed in to (`KMP-95d`), read by presence; the subtitle services are not sync, so they are not named.
     */
    fun cards(defaultName: String): List<ProfileCard> {
        // Read, so a composition drawing the cards follows a PIN being set or removed.
        @Suppress("UNUSED_VARIABLE") val changes = pinChanges
        return stored.profiles.map { profile ->
            ProfileCard(
                id = profile.id,
                name = profile.name ?: defaultName,
                current = profile.id == stored.current,
                locked = pins.hasPin(profile.id),
                syncedWith = DesktopService.entries.filter { !it.subtitles && signIns.get(profile.id, it) != null }.map { it.displayName },
            )
        }
    }

    private companion object {
        /** A profile's own library file: `library-3.tsv`, `watch-positions-3.tsv`. */
        val LIBRARY_FILE = Regex("""(library|watch-positions)-(0|[1-9]\d{0,3})\.tsv""")
    }

    /** [id]'s library. The first profile's is the files the Mac had before profiles. */
    fun libraryFor(id: Int): LibraryStore = DesktopLibraryStore(titlesFile(id), positionsFile(id))

    fun titlesFile(id: Int): File =
        if (id == StoredProfiles.FIRST) DesktopLibraryStore.defaultTitlesFile().let { File(libraryDirectory, it.name) }
        else File(libraryDirectory, "library-$id.tsv")

    fun positionsFile(id: Int): File =
        if (id == StoredProfiles.FIRST) DesktopLibraryStore.defaultPositionsFile().let { File(libraryDirectory, it.name) }
        else File(libraryDirectory, "watch-positions-$id.tsv")
}
