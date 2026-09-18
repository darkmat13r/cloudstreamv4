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
        /** Reports the paths and the exception's message on `System.err`. Never the file's contents. */
        val STDERR: StorageCorruptionListener = StorageCorruptionListener { file, quarantined, cause ->
            System.err.println(
                "core:storage: $file could not be read (${cause::class.java.simpleName}: ${cause.message}); " +
                    "recovered as empty, damaged file " +
                    if (quarantined != null) "kept at $quarantined" else "left in place",
            )
        }
    }
}

/** A line of the store's file did not parse. Distinct from an I/O failure, which is not the file's fault. */
internal class StorageFormatException(message: String) : IOException(message)
