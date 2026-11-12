package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * JNA's native part for the media host, as a read-only file outside every host's directory (`BUG-96`, audit A-9).
 *
 * Left to itself, JNA unpacks `libjnidispatch` into its temp directory, which for a host is the one directory it may
 * write. Its policy then had to let that directory be mapped as code, which made it the one place a host can both write
 * and run native code. This copies the library once, from the JNA jar on this process's classpath, into [root], which
 * no host can write. The host is told `jna.nounpack=true` and given this directory as `jna.boot.library.path`, and its
 * own directory is never mapped as code.
 */
internal object JnaDispatch {
    /** The name JNA asks for on macOS (`System.mapLibraryName("jnidispatch")`). */
    const val LIBRARY = "libjnidispatch.dylib"

    /**
     * The directory holding this Mac's `libjnidispatch`, written if it is not there or not these exact bytes, or `null`
     * when this process has no JNA for this architecture.
     *
     * Named by the library's own hash, so two JNA versions never share a file, and checked against that hash each time,
     * so a file changed since it was written is replaced, never loaded.
     */
    fun prepare(root: File): File? {
        val resource = when (System.getProperty("os.arch")) {
            "aarch64" -> "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib"
            "x86_64", "amd64" -> "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib"
            else -> return null
        }
        val bytes = JnaDispatch::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() } ?: return null
        val hash = sha256(bytes)
        val directory = File(root, hash.take(HASH_CHARS))
        val library = File(directory, LIBRARY)
        if (!(library.isFile && sha256(library.readBytes()) == hash)) {
            check(directory.mkdirs() || directory.isDirectory) { "cannot create the native library directory" }
            library.setWritable(true)
            val staged = Files.createTempFile(directory.toPath(), "jnidispatch", ".part")
            try {
                Files.write(staged, bytes)
                Files.move(staged, library.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(staged)
            }
        }
        library.setReadOnly()
        return directory
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val HASH_CHARS = 16
}
