package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.player.deleteHostDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Runs one conversion in its own empty directory, then brings the result into the store.
 *
 * A conversion points dex2jar, a large parser, at hostile bytes, so its process is treated as possibly
 * taken over. Given write access to the store itself, it could replace another extension's jar between
 * that jar's scan and its start. So it gets a directory holding nothing, and the result is:
 *
 * - **refused unless it is a regular file**, because a link would bring in a file the host chose;
 * - **copied, not moved**, because a hard link or a process left running with the file open would still
 *   reach a moved file after it was scanned. A copy is a new file only this process has opened.
 *
 * @param target where the result belongs. Its parent is the store.
 * @param workRoot where the empty directory is made. Must not be inside the store.
 * @param convert runs the conversion, writing to the file it is given, in the directory it is given.
 *   Returns its failure, or null.
 */
internal suspend fun convertIntoStore(
    target: File,
    workRoot: File,
    convert: suspend (output: File, directory: File) -> String?,
) {
    check(workRoot.mkdirs() || workRoot.isDirectory) { "cannot create the conversion directory" }
    // Canonical, because the sandbox policy is written against the real path and /tmp is a link on macOS.
    val directory = Files.createTempDirectory(workRoot.toPath(), "convert").toFile().canonicalFile
    val staging = File(target.parentFile, "${target.name}.staging")
    try {
        val output = File(directory, target.name)
        convert(output, directory)?.let { failure -> error("conversion failed: $failure") }
        check(Files.isRegularFile(output.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "conversion did not produce a regular file"
        }
        Files.copy(
            output.toPath(),
            staging.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        )
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        staging.delete()
        deleteHostDirectory(directory.toPath())
    }
}
