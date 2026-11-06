package com.lagradost.cloudstream4.media.download

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.div
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/** Where a download was when the state was written. */
internal enum class PersistedPhase(val text: String) {
    DOWNLOAD("download"),
    JOINING("joining"),
}

/**
 * One byte range of the file. [recordedDone] is a diagnostic written for humans and tools: it is never
 * used to choose an offset, because the part file on disk is the only truth (rule 1), so it is not
 * checked against the range either.
 */
internal data class PersistedPart(val id: Int, val start: Long, val endExclusive: Long, val recordedDone: Long)

/**
 * What survives a process death. Holds no URL, query, header or referer: signed URLs change between
 * resolves, so identity comes from [etag] and [modified] of a fresh probe.
 *
 * [etag] and [modified] are empty when the server sent none. [parts] are in ascending order of start.
 */
internal data class PersistedState(
    val total: Long,
    val etag: String,
    val modified: String,
    val phase: PersistedPhase,
    val parts: List<PersistedPart>,
) {
    /**
     * Whether this describes a real download: `total` not negative; parts non-empty, ordered, with no
     * gap and no overlap, the first starting at 0 and the last ending at `total` (and none at all for an
     * empty file); ids distinct and below [StateFile.MAX_PARTS]; at most [StateFile.MAX_PARTS] of them;
     * validators within their bounds and printable ASCII.
     */
    fun isValid(): Boolean {
        if (total < 0L || parts.size > StateFile.MAX_PARTS) return false
        if (!isValidator(etag, StateFile.MAX_ETAG) || !isValidator(modified, StateFile.MAX_MODIFIED)) return false
        if (parts.any { it.recordedDone < 0L }) return false
        if (total == 0L) return parts.isEmpty()
        if (parts.isEmpty()) return false
        var expectedStart = 0L
        val seen = HashSet<Int>()
        for (part in parts) {
            if (part.id < 0 || part.id >= StateFile.MAX_PARTS || !seen.add(part.id)) return false
            if (part.start != expectedStart || part.endExclusive <= part.start) return false
            expectedStart = part.endExclusive
        }
        return expectedStart == total
    }
}

private fun isValidator(value: String, max: Int): Boolean =
    value.length <= max && value.all { it in ' '..'~' } && value == value.trim()

/**
 * Cleans a validator header value for the state file: `""` when it is missing, too long or holds
 * anything but printable ASCII. A validator that cannot be stored is a validator the next run cannot
 * compare, which the engine's rule 12 already treats as no identity.
 */
internal fun storableValidator(value: String?, max: Int): String {
    val trimmed = value?.trim().orEmpty()
    return if (isValidator(trimmed, max)) trimmed else ""
}

/**
 * The state file's text format, strict in both directions.
 *
 * ```
 * csdl 1
 * total <n>
 * etag <value>
 * modified <value>
 * phase download|joining
 * part <id> <start> <endExclusive> <recordedDone>
 * ```
 * Lines end in `\n` only, in exactly that order, one space between tokens, decimal numbers with no sign,
 * no leading zero and no more than 19 digits, and nothing after the last line. Anything else, anything
 * over 64 KiB, and anything [PersistedState.isValid] refuses decodes to `null`, and the caller wipes the
 * temporary directory and starts over.
 */
internal object StateFile {
    const val MAX_BYTES: Int = 64 * 1024
    const val MAX_PARTS: Int = DownloadOptions.MAX_PART_FILES
    const val MAX_ETAG: Int = 256
    const val MAX_MODIFIED: Int = 64
    const val FILE_NAME: String = "state"
    const val TEMP_FILE_NAME: String = "state.tmp"

    /** UTF-8 text of [state]. Throws [IllegalArgumentException] when the state is not [PersistedState.isValid]: that is a bug, not data. */
    fun encode(state: PersistedState): ByteArray {
        require(state.isValid()) { "invalid state" }
        val text = buildString {
            append("csdl 1\n")
            append("total ").append(state.total).append('\n')
            append("etag").append(if (state.etag.isEmpty()) "" else " " + state.etag).append('\n')
            append("modified").append(if (state.modified.isEmpty()) "" else " " + state.modified).append('\n')
            append("phase ").append(state.phase.text).append('\n')
            for (part in state.parts) {
                append("part ").append(part.id).append(' ').append(part.start).append(' ')
                    .append(part.endExclusive).append(' ').append(part.recordedDone).append('\n')
            }
        }
        return text.encodeToByteArray()
    }

    /** The state in [bytes], or `null` for anything malformed, oversized, or not [PersistedState.isValid]. Never throws. */
    fun decode(bytes: ByteArray): PersistedState? {
        if (bytes.size > MAX_BYTES) return null
        return runCatching { decodeText(bytes.decodeToString(throwOnInvalidSequence = true)) }.getOrNull()
    }

    private fun decodeText(text: String): PersistedState? {
        if (!text.endsWith('\n')) return null
        val lines = text.substring(0, text.length - 1).split('\n')
        if (lines.size < 5 || lines.size > 5 + MAX_PARTS) return null
        if (lines[0] != "csdl 1") return null
        val total = number(lines[1], "total ") ?: return null
        val etag = optionalValue(lines[2], "etag") ?: return null
        val modified = optionalValue(lines[3], "modified") ?: return null
        val phase = PersistedPhase.entries.firstOrNull { lines[4] == "phase " + it.text } ?: return null
        val parts = ArrayList<PersistedPart>(lines.size - 5)
        for (i in 5 until lines.size) {
            val tokens = lines[i].split(' ')
            if (tokens.size != 5 || tokens[0] != "part") return null
            val id = parseNumber(tokens[1]) ?: return null
            if (id > Int.MAX_VALUE) return null
            parts += PersistedPart(
                id = id.toInt(),
                start = parseNumber(tokens[2]) ?: return null,
                endExclusive = parseNumber(tokens[3]) ?: return null,
                recordedDone = parseNumber(tokens[4]) ?: return null,
            )
        }
        val state = PersistedState(total, etag, modified, phase, parts)
        return state.takeIf { it.isValid() }
    }

    private fun number(line: String, prefix: String): Long? =
        if (line.startsWith(prefix)) parseNumber(line.substring(prefix.length)) else null

    /** `name` alone is empty, `name value` is the value, anything else is `null`. The value's own rules are checked by [PersistedState.isValid]. */
    private fun optionalValue(line: String, name: String): String? = when {
        line == name -> ""
        line.startsWith("$name ") && line.length > name.length + 1 -> line.substring(name.length + 1)
        else -> null
    }

    /** Decimal digits only, no leading zero except `0` itself, at most 19 digits, within [Long]. */
    internal fun parseNumber(text: String): Long? {
        if (text.isEmpty() || text.length > 19 || !text.all { it in '0'..'9' }) return null
        if (text.length > 1 && text[0] == '0') return null
        return text.toLongOrNull()
    }
}

/**
 * Reads the state in [directory], or `null` when there is none, it is over 64 KiB, or it does not
 * decode. The file is not deleted here: the caller decides to wipe.
 */
internal fun DownloadStorage.readStateFile(directory: PlatformFile): PersistedState? {
    val file = directory / StateFile.FILE_NAME
    if (!exists(file)) return null
    val size = size(file) ?: return null
    if (size > StateFile.MAX_BYTES) return null
    val bytes = runCatching {
        source(file).buffered().use { source ->
            if (source.request(StateFile.MAX_BYTES + 1L)) null else source.readByteArray()
        }
    }.getOrNull() ?: return null
    return StateFile.decode(bytes)
}

/**
 * Writes [state] to `state.tmp` and moves it over `state`, so a kill mid-write leaves the old state or
 * the new one, never half of either. Storage errors propagate: the engine classifies them.
 */
internal suspend fun DownloadStorage.writeStateFile(directory: PlatformFile, state: PersistedState) {
    val temp = directory / StateFile.TEMP_FILE_NAME
    val bytes = StateFile.encode(state)
    sink(temp, append = false).buffered().use { it.write(bytes) }
    atomicMove(temp, directory / StateFile.FILE_NAME)
}
