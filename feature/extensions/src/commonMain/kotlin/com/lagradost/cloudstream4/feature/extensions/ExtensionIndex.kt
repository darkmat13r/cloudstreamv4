package com.lagradost.cloudstream4.feature.extensions

/**
 * The portable half of an extension repository's published index.
 *
 * `:app`'s `plugins.Repository` and `plugins.SitePlugin` are the wire types, and they are **published
 * extension API** — `app/api/classes-jar.api` lists both — so they stay exactly where they are. These
 * are new `cloudstream4` contracts for the same data, the way `:media:model` restates the portable
 * half of the player's types rather than moving them (`KMP-07`).
 *
 * ## What is deliberately not restated, and why
 *
 * - **The Jackson and kotlinx annotations.** The wire types carry `@JsonProperty` *and* `@SerialName`
 *   on every field, because `:app` parses with Jackson and serialises with kotlinx. Which parser runs
 *   is the platform's business; a contract that names one has chosen a dependency for its callers.
 *   This module is annotation-free and parser-free.
 * - **`tvTypes` stays a list of raw strings**, as it is on the wire, rather than becoming
 *   `List<TvType>`. `TvType` lives in `:library`, and an edge from this `commonMain` to `:library`
 *   would make the module iOS-canary exempt under plan §4.1 rule 4 — and `IOS_CANARY_EXEMPT` "may
 *   only shrink". Keeping the wire shape costs nothing here and keeps the module iOS-clean.
 *   Mapping those strings is `:app`'s job, at the edge, where `TvType` already is. The wire comment
 *   on `SitePlugin.tvTypes` says they "are yet to be mapped and used" in any case.
 * - **`PluginWrapper`** has no equivalent. It bundles a repository, its UI-layer `RepositoryData` and
 *   one plugin so a list adapter can show all three; that is a view model's shape, not a contract.
 */
data class ExtensionRepositoryIndex(
    /** Display name of the repository. */
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    /**
     * The index format's own version. `:app` has only ever published `1`; a reader that does not
     * recognise a version should refuse the index rather than guess at its fields.
     */
    val manifestVersion: Int,
    /** URLs of the plugin lists this repository publishes. A repository may split its catalogue. */
    val pluginListUrls: List<String>,
)

/**
 * How a repository reports an extension's health, so it can disable one remotely without
 * republishing the app.
 *
 * On the wire this is a bare `Int` documented in a comment above `SitePlugin`: `0` down, `1` ok,
 * `2` slow, `3` beta only. Naming the cases makes the mapping `:app`'s job and total in both
 * directions, and [Unrecognised] keeps a future value from being read as one of these — the same
 * reasoning as `CaptionEdge` in `:media:model`.
 */
sealed interface ExtensionStatus {
    /** `0` — the repository has turned this extension off. Do not offer it. */
    data object Down : ExtensionStatus

    /** `1` — normal. */
    data object Ok : ExtensionStatus

    /** `2` — works, but the repository is warning that it is slow. */
    data object Slow : ExtensionStatus

    /** `3` — offered only to beta builds. */
    data object BetaOnly : ExtensionStatus

    /**
     * A code this build does not know.
     *
     * Not an error: the repository is a third party and may add codes. A reader should treat an
     * unrecognised status as [Down] for safety and keep [code] for diagnostics.
     */
    data class Unrecognised(val code: Int) : ExtensionStatus
}

/**
 * One extension as a repository advertises it — the catalogue entry, not an installed thing.
 *
 * [InstalledExtension] is the installed counterpart. The two are kept apart because almost nothing is
 * true of both: a listing has a download URL, authors and a declared size; an installation has a
 * location on disk and a loaded-or-not state.
 */
data class ExtensionListing(
    /**
     * Stable identity. Separate from [name] precisely so a repository can change the display name or
     * the [downloadUrl] without the app treating it as a different extension.
     */
    val internalName: String,
    /** Display name. */
    val name: String,
    /** Where the `.cs3` file is fetched from. */
    val downloadUrl: String,
    val status: ExtensionStatus,
    /**
     * Monotonic version counter, greater than zero. Any increase triggers an auto-update in `:app`,
     * so this is an ordering, not a semantic version — there is nothing to parse.
     */
    val version: Int,
    /**
     * The extension API generation this was built against. `:app` sets `1` everywhere and does not
     * yet read it; it exists so a future break can be detected rather than crashed into.
     */
    val apiVersion: Int,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val iconUrl: String? = null,
    /** The repository's own page for this extension, if it publishes one. */
    val repositoryUrl: String? = null,
    /** Raw wire strings. See the KDoc above for why these are not `TvType`. */
    val tvTypes: List<String> = emptyList(),
    /** Usually a BCP-47 tag such as `en` or `zh-TW`. */
    val language: String? = null,
    /** Declared size of the `.cs3`, generated by the extension's Gradle plugin. */
    val fileSizeBytes: Long? = null,
    /** Declared hash of the `.cs3`. [ExtensionVerification] is what decides whether to trust it. */
    val fileHash: String? = null,
    /**
     * Where the plain **`.jar`** is fetched from, for a cross-platform extension. `null` otherwise,
     * and that is the normal case.
     *
     * This is the one field that decides whether an extension can run anywhere but Android, because
     * a `.cs3` is dex-only — `manifest.json` + `classes.dex`, no `.class` entry ever added
     * (`KMP-13` §1) — and no JVM or Native host can load Dalvik bytecode. The producer writes this
     * trio only when `isCrossPlatform = true` put a jar beside the `.cs3`
     * (`WriteCacheEntryTask.kt:51`, `:72-74`; mirrors `PluginEntry.kt:19-22`).
     *
     * **Expect it to be absent.** Measured 2026-10-15 over the first 14 of the 31 repositories in
     * `recloudstream/cs-repos`: 5 of 235 catalogue entries carry it, and all 5 are the official
     * repository — whose index does carry all three keys on every entry. A reader that treats a
     * missing `jarUrl` as a defect will call almost the whole catalogue broken.
     */
    val jarUrl: String? = null,
    /**
     * Declared hash of that jar, as `sha256-<lowercase hex>`.
     *
     * Verifiable, unlike most of this type: the producer's digest
     * (`WriteCacheEntryTask.kt:85-97`) and `:app`'s reader (`RepositoryManager.kt:107-122`) are two
     * independent implementations that already agree, and `shasum -a 256` over a downloaded
     * `YoutubeProvider.jar` reproduces the published value byte for byte. A host that compares it can
     * return [ExtensionVerification.Verified] rather than [ExtensionVerification.Skipped], which
     * `:app` — carrying [fileHash] and never comparing it — has never done.
     */
    val jarHash: String? = null,
    /**
     * Declared size of that jar, in bytes.
     *
     * Named like [fileSizeBytes] rather than after the wire key `jarFileSize`, for the reason that
     * field is: a bare `Size` says nothing about its unit, and these two are read side by side.
     */
    val jarFileSizeBytes: Long? = null,
)
