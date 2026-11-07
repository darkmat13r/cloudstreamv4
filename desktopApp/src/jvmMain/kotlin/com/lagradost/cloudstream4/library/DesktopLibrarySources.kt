package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibrarySource
import com.lagradost.cloudstream4.feature.library.LibrarySourceId
import com.lagradost.cloudstream4.feature.library.LibrarySources
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.SyncedList
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread

/**
 * Where the desktop library comes from: only the list on this computer. There is no tracking-service sign-in on
 * desktop, so there is nothing else to offer, and offering services that cannot be connected would be a dead end.
 *
 * It exists for what it says about that list (`DS-22`'s L4): the store keeps no rating and no release year, so a
 * local list here sorts by when it was changed and by name, and the other two criteria are shown greyed with the
 * reason rather than offered and doing nothing.
 *
 * @param sortMemory where the chosen sort is kept between launches.
 */
internal class DesktopLibrarySources(
    private val store: LibraryStore,
    private val sortMemory: LibrarySortMemory = LibrarySortMemory.None,
) : LibrarySources {

    override suspend fun sources(): List<LibrarySource> {
        // Titles in a list, each once: a title only known because it was played is not kept.
        val count = runCatching { store.titles().filter { it.bucket != null }.distinctBy { it.id }.size }.getOrNull()
        return listOf(LibrarySource(LOCAL, LOCAL_NAME, local = true, titleCount = count, sorts = LOCAL_SORTS))
    }

    override suspend fun current(): LibrarySourceId = LOCAL

    override suspend fun choose(id: LibrarySourceId) = Unit

    /** Never called: the local list's titles are the store's. */
    override suspend fun lists(id: LibrarySourceId): List<SyncedList> = emptyList()

    override suspend fun sort(): LibrarySort? = sortMemory.last()

    override suspend fun sortBy(sort: LibrarySort) = sortMemory.remember(sort)

    internal companion object {
        val LOCAL = LibrarySourceId("Local")

        /** The name Android's `LocalList` gives the same list, so the two apps call it the same thing. */
        const val LOCAL_NAME = "Local"

        /** What `DesktopLibraryStore` keeps: when a title was changed, and its name. */
        val LOCAL_SORTS = setOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Title)
    }
}

/** Keeps the library's sort between launches. */
internal interface LibrarySortMemory {
    fun last(): LibrarySort?

    fun remember(sort: LibrarySort)

    /** Keeps nothing: the default, so a window made in a test never writes to the real home directory. */
    object None : LibrarySortMemory {
        override fun last(): LibrarySort? = null

        override fun remember(sort: LibrarySort) = Unit
    }
}

/**
 * One line in `~/.cloudstream4/library-sort`: a criterion's name and a direction, `Title asc`.
 *
 * Anything that is not exactly that reads as never having chosen, so a damaged or hand-edited file costs the choice
 * and nothing else. The write is atomic, owner-only where the file system has permissions, and off the caller's
 * thread, because the caller is a click, as `FileHomeSourceMemory`'s is.
 */
internal class FileLibrarySortMemory(
    private val file: File = File(File(System.getProperty("user.home"), ".cloudstream4"), "library-sort"),
) : LibrarySortMemory {
    private val lock = Any()

    @Volatile
    private var latest: LibrarySort? = null

    override fun last(): LibrarySort? {
        latest?.let { return it }
        val text = runCatching { if (file.length() > MAX_FILE_BYTES) null else file.readText() }.getOrNull()
        return text?.let(::parse)
    }

    override fun remember(sort: LibrarySort) {
        latest = sort
        thread(isDaemon = true, name = "library-sort") {
            synchronized(lock) { runCatching { write() } }
        }
    }

    /** Whatever is latest by now, so two quick choices that run out of order still end on the second. */
    private fun write() {
        val value = latest ?: return
        val directory = checkNotNull(file.parentFile).toPath()
        Files.createDirectories(directory)
        val next = Files.createTempFile(directory, file.name, ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(next, PosixFilePermissions.fromString(OWNER_ONLY)) }
            Files.writeString(next, format(value))
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    internal companion object {
        private const val MAX_FILE_BYTES = 256L
        private const val OWNER_ONLY = "rw-------"
        private const val ASCENDING = "asc"
        private const val DESCENDING = "desc"

        fun format(sort: LibrarySort): String =
            "${sort.criterion.name} ${if (sort.descending) DESCENDING else ASCENDING}"

        /** Only a criterion's exact name and one of the two directions; anything else is null. */
        fun parse(text: String): LibrarySort? {
            val parts = text.trim().split(' ')
            if (parts.size != 2) return null
            val criterion = LibrarySortCriterion.entries.firstOrNull { it.name == parts[0] } ?: return null
            val descending = when (parts[1]) {
                DESCENDING -> true
                ASCENDING -> false
                else -> return null
            }
            return LibrarySort(criterion, descending)
        }
    }
}
