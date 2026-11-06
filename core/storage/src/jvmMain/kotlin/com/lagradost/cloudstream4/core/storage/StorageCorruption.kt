package com.lagradost.cloudstream4.core.storage

import java.io.IOException
import java.nio.file.Path

/**
 * Told when a store found a file it could not read, so the app can surface it instead of silently
 * starting from empty.
 *
 * It is handed the paths and the cause and **never the contents**: a corrupt settings or record
 * file can hold OAuth tokens, and this module does not put those in a log.
 *
 * @see StorageCorruptionListener.STDERR the default — one line on `System.err`, no contents.
 */
fun interface StorageCorruptionListener {

    /**
     * @param file the store's own path, which now reads as empty.
     * @param quarantined where the damaged file was moved, or `null` when it could not be moved
     * (it is then left exactly as it was, and the store is empty **in memory only** until the next
     * write replaces it).
     */
    fun onCorruption(file: Path, quarantined: Path?, cause: Throwable)

    companion object {
        /**
         * Reports the file names and the exception's class on `System.err`. Never the file's
         * contents, its full path (it holds the user's home directory) or the exception's message
         * (an I/O message quotes the path).
         */
        val STDERR: StorageCorruptionListener = StorageCorruptionListener { file, quarantined, cause ->
            System.err.println(
                "core:storage: ${file.fileName} could not be read (${cause::class.java.simpleName}); " +
                    "recovered as empty, damaged file " +
                    if (quarantined != null) "kept beside it as ${quarantined.fileName}" else "left in place",
            )
        }
    }
}

/** A line of the store's file did not parse. Distinct from an I/O failure, which is not the file's fault. */
internal class StorageFormatException(message: String) : IOException(message)
