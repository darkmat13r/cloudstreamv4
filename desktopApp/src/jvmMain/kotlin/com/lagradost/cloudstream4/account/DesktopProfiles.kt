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
    fun load(): StoredProfiles {
        val bytes = runCatching {
            if (!file.isFile) null else file.inputStream().use { it.readNBytes(MAX_FILE_BYTES.toInt() + 1) }
        }.getOrNull() ?: return StoredProfiles()
        if (bytes.size > MAX_FILE_BYTES) return StoredProfiles()
        val read = runCatching { json.decodeFromString(StoredProfiles.serializer(), bytes.decodeToString()) }.getOrNull()
            ?: return StoredProfiles()
        return read.cleaned()
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
        private const val OWNER_ONLY = "rw-------"
        private const val OWNER_DIR = "rwx------"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun StoredProfiles.cleaned(): StoredProfiles {
            val seen = HashSet<Int>()
            val kept = profiles
                .filter { it.id >= 0 && seen.add(it.id) }
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
 * The profiles while the window is open (`KMP-95a`): who is watching, and the library each one reads.
 *
 * @param onSaveFailed told when a choice could not be written; the choice still holds until the app quits.
 */
internal class DesktopProfiles(
    private val file: DesktopProfileFile = DesktopProfileFile(),
    private val libraryDirectory: File = DesktopLibraryStore.defaultTitlesFile().parentFile,
    private val onSaveFailed: (Throwable) -> Unit = {},
) {
    var stored: StoredProfiles by mutableStateOf(file.load())
        private set

    /** The launch picker shows only with two profiles or more, the default counted (`KMP-73`, A1). */
    val picksAtLaunch: Boolean get() = stored.profiles.size >= 2

    /** Makes [id] the profile watching, and remembers it for the next launch. An id that is not a profile is ignored. */
    fun choose(id: Int) {
        if (stored.profiles.none { it.id == id } || stored.current == id) return
        stored = stored.copy(current = id)
        runCatching { file.save(stored) }.onFailure(onSaveFailed)
    }

    /** The cards the picker and the sidebar's list draw. Nothing is locked and nothing syncs until `KMP-95c` and `95d`. */
    fun cards(defaultName: String): List<ProfileCard> = stored.profiles.map { profile ->
        ProfileCard(
            id = profile.id,
            name = profile.name ?: defaultName,
            current = profile.id == stored.current,
            locked = false,
        )
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
