package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.search.hasPosterUserInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop's library: two tab-separated files under the user's home directory.
 *
 * ## Why not `:core:storage`
 *
 * `:core:storage` has JVM implementations — `FileKeyValueJsonStore` is very nearly this class's shape —
 * and they are **unused**. `BUG-05` records their disclosed limits and `DEC-12` says to revisit "when
 * something first consumes `:core:storage`". This is the strongest candidate yet to be that something,
 * and it probably should be, but adopting it is a change to a module two decisions are parked on and it
 * is the owner's call, not this task's. Two files get the library working now without pre-empting it;
 * swapping them for `:core:storage` later touches only this class, exactly as `DesktopSearchHistory`
 * said when it made the same choice.
 *
 * ## Why not the Android format
 *
 * `:app` stores a bookmark as a JSON blob under `"<account>/result_watch_state_data"` keyed by an `Int`
 * hash, with the bucket in a second map and the title's name and poster in a *third* store that the
 * downloader prunes. None of that exists here, and the parts of it that are accidents — the hash, the
 * split across three stores — are not worth reproducing. The two stores are **not shared**; see
 * `LibraryTitleId`.
 *
 * ## Why no checksum, when `ExtensionDecisions` has one
 *
 * That file decides whether third-party code runs, so a half-written one must count as "nothing was
 * approved" — all or nothing is the only safe reading. This file decides what is on a list. A truncated
 * line here should cost that one row and not the library, so a line that does not parse is skipped and
 * the rest is kept. Writes are still atomic, so a crash leaves the old file or the new one.
 *
 * ## What is re-checked on the way out
 *
 * Everything in these files was written from what a plugin returned, so reading them is reading
 * untrusted data again: titles and provider names lose control and format characters and are truncated,
 * and a poster that is not plain `http(s)`, or that carries userinfo (`BUG-78`), is dropped before it can reach the
 * image loader. It is refused on the way in as well, so it never reaches the file. The id is
 * **not** sanitised — it is opaque and carries a NUL on purpose — only escaped.
 *
 * @param titlesFile defaults to `~/.cloudstream4/library.tsv`.
 * @param positionsFile defaults to `~/.cloudstream4/watch-positions.tsv`.
 * @param beforeMove runs after new content is on disk and before it replaces the old. For a test.
 */
class DesktopLibraryStore(
    private val titlesFile: File = defaultTitlesFile(),
    private val positionsFile: File = defaultPositionsFile(),
    private val beforeMove: () -> Unit = {},
) : LibraryStore {

    /** One writer at a time, and a read never sees a half-applied change. */
    private val lock = Mutex()

    override suspend fun titles(): List<LibraryTitle> = withContext(Dispatchers.IO) {
        lock.withLock { readTitles() }
    }

    override suspend fun remember(title: LibraryTitle) = withContext(Dispatchers.IO) {
        lock.withLock {
            val held = readTitles()
            val existing = held.firstOrNull { it.id == title.id }
            // Keeps the list it is already in, and the time it was put there: playing something is not
            // bookmarking it, and it is not re-bookmarking it either.
            val merged = title.copy(
                bucket = existing?.bucket ?: title.bucket,
                keptAt = existing?.keptAt ?: title.keptAt,
            )
            writeTitles(held.filterNot { it.id == title.id } + merged)
        }
    }

    override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val held = readTitles()
            val existing = held.firstOrNull { it.id == id } ?: return@withLock
            val moved = existing.copy(
                bucket = bucket,
                // Moving between lists is putting it in the new one now, which is what orders that list.
                keptAt = if (bucket != null && bucket != existing.bucket) clock() else existing.keptAt,
            )
            writeTitles(held.filterNot { it.id == id } + moved)
        }
    }

    override suspend fun positions(): List<WatchPosition> = withContext(Dispatchers.IO) {
        lock.withLock { readPositions() }
    }

    override suspend fun record(position: WatchPosition) = withContext(Dispatchers.IO) {
        lock.withLock {
            val held = readPositions().filterNot { it.id == position.id && it.episodeData == position.episodeData }
            writePositions(held + position)
        }
    }

    override suspend fun clearPositions(id: LibraryTitleId) = withContext(Dispatchers.IO) {
        lock.withLock {
            writePositions(readPositions().filterNot { it.id == id })
            // A row that was only there to draw a continue-watching card has nothing left to say.
            val held = readTitles()
            if (held.any { it.id == id && it.bucket == null }) writeTitles(held.filterNot { it.id == id })
        }
    }

    private fun readTitles(): List<LibraryTitle> = read(titlesFile, TITLES_HEADER) { fields ->
        if (fields.size != TITLE_FIELDS) return@read null
        val keptAt = fields[0].toLongOrNull() ?: return@read null
        val bucket = fields[1].takeIf { it.isNotEmpty() }?.let { word ->
            LibraryBucket.entries.firstOrNull { it.name == word } ?: return@read null
        }
        val id = fields[2].takeIf { it.isNotEmpty() } ?: return@read null
        val title = fields[3].safeText().ifEmpty { return@read null }
        LibraryTitle(
            id = LibraryTitleId(id),
            title = title,
            bucket = bucket,
            keptAt = keptAt,
            posterUrl = fields[4].takeIf { it.isNotEmpty() && it.isPublicHttp() },
            providerName = fields[5].safeText().ifEmpty { null },
        )
    }.distinctBy { it.id }

    private fun writeTitles(titles: List<LibraryTitle>) {
        // Newest first in the file as well as on the screen, so the cap drops the oldest rows.
        val rows = titles.sortedByDescending { it.keptAt }.take(MAX_TITLES)
        write(titlesFile, TITLES_HEADER, rows) { title ->
            listOf(
                title.keptAt.toString(),
                title.bucket?.name.orEmpty(),
                title.id.value,
                title.title,
                title.posterUrl?.takeIf { it.isPublicHttp() }.orEmpty(),
                title.providerName.orEmpty(),
            )
        }
    }

    private fun readPositions(): List<WatchPosition> = read(positionsFile, POSITIONS_HEADER) { fields ->
        if (fields.size != POSITION_FIELDS) return@read null
        val watchedAt = fields[0].toLongOrNull() ?: return@read null
        val position = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return@read null
        val duration = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@read null
        val id = fields[3].takeIf { it.isNotEmpty() } ?: return@read null
        WatchPosition(
            id = LibraryTitleId(id),
            episodeData = fields[4].takeIf { it.isNotEmpty() },
            positionMillis = position,
            durationMillis = duration,
            watchedAt = watchedAt,
        )
    }.distinctBy { it.id to it.episodeData }

    private fun writePositions(positions: List<WatchPosition>) {
        val rows = positions.sortedByDescending { it.watchedAt }.take(MAX_POSITIONS)
        write(positionsFile, POSITIONS_HEADER, rows) { position ->
            listOf(
                position.watchedAt.toString(),
                position.positionMillis.toString(),
                position.durationMillis.toString(),
                position.id.value,
                position.episodeData.orEmpty(),
            )
        }
    }

    /**
     * Reads [file], skipping any line [parse] refuses.
     *
     * A missing file is an empty library. A link is not followed: its content would be chosen by
     * whatever it points at. Anything past [MAX_BYTES] is ignored rather than read into memory.
     *
     * @throws IOException when the file is there and cannot be read, which is a state the screen shows.
     */
    private fun <T> read(file: File, header: String, parse: (List<String>) -> T?): List<T> {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("${file.name} is not a file")
        if (Files.size(path) > MAX_BYTES) throw IOException("${file.name} is too large")
        val lines = Files.readAllBytes(path).toString(Charsets.UTF_8).split('\n')
        if (lines.firstOrNull() != header) throw IOException("${file.name} is not a CloudStream library file")
        return lines.drop(1).mapNotNull { line ->
            if (line.isEmpty()) null else parse(line.split(SEPARATOR).map { it.unescape() })
        }
    }

    /** Atomic: a crash leaves the old file or the new one, never half of either. */
    private fun <T> write(file: File, header: String, rows: List<T>, fields: (T) -> List<String>) {
        val body = buildString {
            append(header).append('\n')
            rows.forEach { row -> append(fields(row).joinToString(SEPARATOR) { it.escape() }).append('\n') }
        }
        val parent = checkNotNull(file.absoluteFile.parentFile) { "no parent directory" }
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("cannot create ${parent.name}")
        // In the same directory, or the move would not be atomic. Created 0600 by the JDK.
        val staging = Files.createTempFile(parent.toPath(), ".${file.name}", STAGING_SUFFIX)
        try {
            FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(body.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeMove()
            Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    /**
     * A title's id carries a NUL — `DesktopSearchSource.ID_SEPARATOR` — and a plugin's title can carry
     * anything at all, so a line-and-tab format has to escape four characters and the escape itself.
     */
    private fun String.escape(): String = buildString(length) {
        this@escape.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u0000' -> append("\\0")
                else -> append(character)
            }
        }
    }

    /** The inverse. An unknown escape keeps its own character, so no input can be lost silently. */
    private fun String.unescape(): String {
        if ('\\' !in this) return this
        val out = StringBuilder(length)
        var index = 0
        while (index < length) {
            val character = this[index]
            if (character != '\\' || index == lastIndex) {
                out.append(character)
                index++
                continue
            }
            when (val next = this[index + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '0' -> out.append('\u0000')
                else -> out.append(next)
            }
            index += 2
        }
        return out.toString()
    }

    /** As `DesktopHomeSource` does it: a FORMAT character reorders the text around it. */
    private fun String.safeText(): String =
        filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(MAX_TEXT_CHARS)

    private fun String.isPublicHttp(): Boolean =
        HTTP_PREFIXES.any { startsWith(it, ignoreCase = true) } && !hasPosterUserInfo(this)

    private fun clock() = System.currentTimeMillis()

    companion object {
        private const val SEPARATOR = "\t"
        private const val TITLES_HEADER = "cloudstream4-library 1"
        private const val POSITIONS_HEADER = "cloudstream4-watch-positions 1"
        private const val STAGING_SUFFIX = ".part"
        private const val TITLE_FIELDS = 6
        private const val POSITION_FIELDS = 5
        private const val MAX_TEXT_CHARS = 200
        private val HTTP_PREFIXES = listOf("http://", "https://")

        /** Far more than anyone keeps, and still a file that is read whole in one go. */
        private const val MAX_TITLES = 2_000
        private const val MAX_POSITIONS = 2_000
        private const val MAX_BYTES = 4L * 1024 * 1024

        private fun home() = File(System.getProperty("user.home"), ".cloudstream4")

        fun defaultTitlesFile() = File(home(), "library.tsv")

        fun defaultPositionsFile() = File(home(), "watch-positions.tsv")
    }
}
