package com.lagradost.cloudstream4.extensions.repositories

/**
 * How much of a repository the Mac will read (`KMP-110`). Each limit is set from a measurement, with room to spare.
 *
 * ## The measurement, 2026-11-10
 *
 * The 34 repositories in the public `recloudstream/cs-repos` list, `repos-db.json`, read with a script: 33 answered,
 * with 583 plugins between them. Declared sizes were checked a second way by downloading the largest of each kind and
 * counting its bytes; both matched their index exactly.
 *
 * | What | Largest seen | Limit |
 * |---|---|---|
 * | A repository's JSON | 400 bytes | [MAX_REPOSITORY_BYTES] 64 KiB |
 * | A plugin list | 69,046 bytes, 86 entries | [MAX_PLUGIN_LIST_BYTES] 1 MiB, [MAX_PLUGINS_PER_LIST] 2,000 |
 * | Plugin lists per repository | 1 | [MAX_PLUGIN_LISTS] 16 |
 * | A `.cs3` | 8,336,733 bytes | [MAX_PLUGIN_BYTES] 32 MiB |
 * | A `.jar` (6 plugins publish one) | 157,711 bytes | the same |
 * | A plugin's description | 776 characters | [MAX_DESCRIPTION_CHARS] 4,096 |
 * | A plugin's name / a repository's name | 32 / 42 characters | [MAX_NAME_CHARS] 100 |
 * | An `internalName` | 29 characters | [MAX_INTERNAL_NAME_CHARS] 128 |
 * | Authors | 3, of up to 32 characters | [MAX_AUTHORS] 10, [MAX_NAME_CHARS] each |
 * | `tvTypes` | 9 | [MAX_TV_TYPES] 32 |
 * | Any URL | 228 characters | [MAX_LINK_CHARS] 2,048 |
 *
 * **The plugin cap** is about four times the largest file seen, rounded to a power of two: 4 × 8,336,733 is
 * 33,346,932, and 32 MiB is 33,554,432. That largest file is from a repository of game ports; the largest streaming
 * plugin was 5,905,970 bytes. One cap serves both kinds, because six jars are too few to set a second one from.
 *
 * Other facts from the same run that the parser is built on:
 * - `manifestVersion` is 1 in 31 repositories and **2 in 2**, and those two have exactly the version 1 keys and types.
 *   Android never reads the field. So both are read, and anything else is [RepositoryFailure.UnsupportedFormat].
 * - 529 of the 583 plugins publish a `fileHash`; all 583 publish a `fileSize`.
 * - 5 `internalName`s have a space or a `+` in them (`18+`, for one). They are identities, not file names, so they are
 *   kept; a file name is derived from them, never taken.
 * - Every `version` is between 1 and 694, and no plugin URL is http.
 */
internal object RepositoryLimits {
    const val MAX_REPOSITORY_BYTES = 64L * 1024
    const val MAX_PLUGIN_LIST_BYTES = 1024L * 1024
    const val MAX_PLUGIN_BYTES = 32L * 1024 * 1024
    const val MAX_PLUGIN_LISTS = 16
    const val MAX_PLUGINS_PER_LIST = 2_000
    const val MAX_NAME_CHARS = 100
    const val MAX_DESCRIPTION_CHARS = 4_096
    const val MAX_INTERNAL_NAME_CHARS = 128
    const val MAX_AUTHORS = 10
    const val MAX_TV_TYPES = 32
    const val MAX_LANGUAGE_CHARS = 35

    /** `sha256-` and 64 hex digits is 71; anything much longer is not a hash anyone can check. */
    const val MAX_HASH_CHARS = 128

    /** The `manifestVersion`s read. See above for why 2 is here. */
    val SUPPORTED_MANIFEST_VERSIONS = setOf(1, 2)
}
