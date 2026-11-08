package com.lagradost.cloudstream4.settings

import com.lagradost.cloudstream4.feature.settings.SettingsStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The Mac's Settings, in one owner-only file: `~/.cloudstream4/settings.properties`, one `key=value` per line under
 * Android's key names (`KMP-90`).
 *
 * Only preferences live here. A token or a password never does: those go to the Keychain, when there is one.
 *
 * Read once at launch, at most [MAX_FILE_BYTES] + 1 bytes of it. A file that is missing, unreadable or larger than
 * that reads as empty, which is every default, and the next change replaces it. Within that, a line that is not a
 * setting is skipped, and a byte that is not UTF-8 decodes as U+FFFD, so it can only make its own line one.
 *
 * A write goes to a temporary file in the same directory and is moved over the old one, so a crash mid-write leaves the
 * previous settings, never half of them. A directory this creates is owner-only, as the file is.
 */
internal class FileSettingsStorage(
    private val file: File = File(File(System.getProperty("user.home"), ".cloudstream4"), FILE_NAME),
) : SettingsStorage {
    private val lock = Any()

    override fun load(): Map<String, String> {
        val bytes = runCatching {
            if (!file.isFile) null else file.inputStream().use { it.readNBytes(MAX_FILE_BYTES.toInt() + 1) }
        }.getOrNull() ?: return emptyMap()
        if (bytes.size > MAX_FILE_BYTES) return emptyMap()
        return parse(bytes.decodeToString())
    }

    override fun save(values: Map<String, String>) {
        synchronized(lock) { write(values) }
    }

    private fun write(values: Map<String, String>) {
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
            Files.writeString(next, format(values))
            Files.move(next, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }

    internal companion object {
        const val FILE_NAME = "settings.properties"

        /** Far more than every setting the window will ever hold; anything larger was not written by this app. */
        const val MAX_FILE_BYTES = 16L * 1024
        private const val OWNER_ONLY = "rw-------"
        private const val OWNER_DIR = "rwx------"
        private const val MAX_KEY_CHARS = 64
        private const val MAX_VALUE_CHARS = 256

        /** `key=value` lines. A line that is not one, or is too long, is skipped; the last of a repeated key wins. */
        fun parse(text: String): Map<String, String> = buildMap {
            text.lineSequence().forEach { line ->
                val at = line.indexOf('=')
                if (at <= 0) return@forEach
                val key = line.substring(0, at).trim()
                val value = line.substring(at + 1).trim()
                if (key.length <= MAX_KEY_CHARS && value.length <= MAX_VALUE_CHARS && key.all(::isKeyChar)) put(key, value)
            }
        }

        /** Sorted, so the file only changes where a value does. A value never contains a line break. */
        fun format(values: Map<String, String>): String = values.toSortedMap().entries.joinToString("") { (key, value) ->
            require(key.all(::isKeyChar) && key.length <= MAX_KEY_CHARS) { "bad settings key" }
            require(value.none { it == '\n' || it == '\r' } && value.length <= MAX_VALUE_CHARS) { "bad settings value" }
            "$key=$value\n"
        }

        private fun isKeyChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_'
    }
}
