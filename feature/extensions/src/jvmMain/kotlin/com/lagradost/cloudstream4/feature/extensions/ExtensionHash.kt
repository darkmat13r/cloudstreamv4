package com.lagradost.cloudstream4.feature.extensions

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 over an extension artefact, in the exact string form the repositories publish.
 *
 * ## Why this is a third implementation of the same five lines, and why that is correct
 *
 * Two already exist and they already agree: the producer writes `"sha256-" + lowercase hex`
 * (`recloudstream/gradle`, `WriteCacheEntryTask.kt:85-97`) and `:app` reads it back with plain
 * `MessageDigest` (`RepositoryManager.kt:107-122`). Neither is reachable from here —
 * `RepositoryManager` is `:app`, Android, and **published extension API**, so it is not moved and
 * not called (`KMP-13` "must not touch"). Restating it is five lines; the alternative is a module
 * edge to `:app`, which does not exist and must not.
 *
 * Measured agreement rather than assumed, 2026-10-15: the official index publishes
 * `jarHash = sha256-0d83e79f196464e50b8b1aa4c13683b2428fc3556651a0d190981c1149b3ea41` for
 * `YoutubeProvider.jar`, and `shasum -a 256` over the downloaded 46,472-byte file prints the same
 * 64 hex digits. All 5 entries of that index carry `jarHash` in this form; no other algorithm
 * appears on the wire.
 *
 * ## It is `public`, which the seam's KDoc deliberately left room for
 *
 * `ExtensionRuntime` performs no verification on purpose — "making the seam perform the check would
 * hide a skipped check inside a success" (`ExtensionRuntime.kt:25-26`). So the *host* has to be able
 * to compute the outcome it then passes to [ExtensionRuntime.install], and a host that cannot reach
 * a hash function would have no honest way to produce anything but
 * [ExtensionVerification.Skipped]. That is why `:app` has never produced
 * [ExtensionVerification.Verified] (`ExtensionInstall.kt:69-79`).
 */
object ExtensionHash {

    /** The algorithm tag every published hash carries. Compared case-insensitively. */
    const val PREFIX: String = "sha256-"

    /** `sha256-<64 lowercase hex digits>` over [bytes]. */
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance(ALGORITHM).digest(bytes))

    /**
     * The same over a file, streamed.
     *
     * A jar is small — the five official ones are 46 KB to 126 KB — but a side-loaded one is
     * whatever a user dropped in a directory, and reading an arbitrary file whole into memory to
     * hash it is a choice with no upside. Buffer size matches `RepositoryManager.kt:113`.
     */
    fun of(file: File): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            var read = stream.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
        }
        return hex(digest.digest())
    }

    /** [verify] over bytes already in hand, e.g. a download that has not been written yet. */
    fun verify(bytes: ByteArray, expected: String?): ExtensionVerification =
        compare(expected) { of(bytes) }

    /**
     * Whether [file] is the artefact [expected] names.
     *
     * [expected] is an `ExtensionListing.jarHash` / `fileHash` — a string from a third-party index,
     * so every shape it could be has a named outcome:
     *
     * - absent or blank → [ExtensionVerification.Skipped]. Most repositories publish no hash at
     *   all: 230 of the 235 catalogue entries surveyed in `KMP-13` §2 carry no `jarUrl`, and the
     *   86-entry Phisher index has no hash key either.
     * - present but not `sha256-…` → [ExtensionVerification.Skipped], **not** a mismatch. "I cannot
     *   check this" and "this is the wrong file" are different facts, and a caller that treats an
     *   unreadable hash as tampering cannot ever adopt a second algorithm. The caller's policy
     *   decides what an unguaranteed artefact is worth; it is told which case it has.
     * - present and matching, ignoring case → [ExtensionVerification.Verified].
     * - present and not matching → [ExtensionVerification.HashMismatch], carrying both sides.
     */
    fun verify(file: File, expected: String?): ExtensionVerification =
        compare(expected) { of(file) }

    private inline fun compare(
        expected: String?,
        actual: () -> String,
    ): ExtensionVerification {
        val published = expected?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ExtensionVerification.Skipped("the repository published no hash")
        if (!published.startsWith(PREFIX, ignoreCase = true)) {
            return ExtensionVerification.Skipped(
                "published hash is not $PREFIX…, and no other algorithm is supported"
            )
        }
        val computed = actual()
        return when {
            published.equals(computed, ignoreCase = true) -> ExtensionVerification.Verified
            else -> ExtensionVerification.HashMismatch(expected = published, actual = computed)
        }
    }

    /**
     * Lowercase hex, written out rather than with `"%02x".format(it)`.
     *
     * `RepositoryManager.kt:121` uses the `format` route, which goes through `java.util.Formatter`
     * and is `java.lang.String.format` — the same JVM-only call `KMP-10` step 2 had to remove from
     * four vendored files to make them compile for Native. Nothing here compiles for Native today,
     * but the arithmetic is shorter than the explanation of why the convenient version is a
     * one-platform call, and `and 0xFF` is what makes it agree for bytes above 0x7F.
     */
    private fun hex(digest: ByteArray): String = buildString(PREFIX.length + digest.size * 2) {
        append(PREFIX)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private const val ALGORITHM = "SHA-256"
    private const val BUFFER_BYTES = 8192
    private const val HEX_DIGITS = "0123456789abcdef"
}
