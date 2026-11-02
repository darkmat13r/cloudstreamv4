package com.lagradost.cloudstream4.extensions

/**
 * The official repository's cross-platform jars, pinned by hash, as measured on 2026-10-15.
 *
 * ## Why a table in source rather than the index this came from
 *
 * The data is the official index, `recloudstream/extensions@builds/plugins.json`, read with
 * `curl`: 5 entries, every one carrying `jarUrl`, `jarHash` and `jarFileSize`. Reading it *at
 * runtime* would need a JSON parser, and desktop has none available — every dependency `:library`
 * declares is `implementation` (`library/build.gradle.kts:55-69`), so neither Jackson nor
 * `kotlinx.serialization.json` is on a consumer's compile classpath, and putting one there is a
 * dependency decision for the owner rather than something to slip into this PR. `:feature:extensions`
 * is deliberately parser-free for the same kind of reason (`ExtensionIndex.kt`).
 *
 * So this is **not a catalogue** and must not grow into one. It exists so that a jar a user drops in
 * the extensions directory can be checked against a hash that a repository actually published,
 * instead of being loaded on trust — which is the difference between
 * `ExtensionVerification.Verified` and `ExtensionVerification.Skipped`, and `:app` has only ever
 * been able to produce the latter (`ExtensionInstall.kt:69-79`).
 *
 * ## It goes stale, and the failure mode is a refusal
 *
 * These hashes are of the files published at the moment above. When an author bumps a provider the
 * builds branch republishes the jar, the hash changes, and this runtime will **refuse to load it**
 * rather than accept a file it cannot account for. That is the right default for third-party code
 * and the wrong thing to work around by weakening the check: the documented way out is a
 * `<name>.jar.sha256` sidecar holding the `jarHash` from the current index, which
 * [SandboxedExtensions] prefers over anything here.
 *
 * Verified a second way rather than transcribed once: `shasum -a 256 YoutubeProvider.jar` over the
 * downloaded 46,472-byte file prints `0d83e79f…ea41`, matching the index's `jarHash` digits exactly.
 */
internal object DesktopExtensionPins {

    /** One official jar: what it is called on disk, what the index said about it. */
    internal data class Pin(
        val jarName: String,
        /** `ExtensionListing.version` — a monotonic counter, not a semantic version. */
        val version: Int,
        /** `sha256-<lowercase hex>`, exactly as the index publishes it. */
        val hash: String,
        /** Where to fetch it; only ever printed, never fetched from here. */
        val url: String,
        /** The index's `fileHash` for the `.cs3` of the same version, measured 2026-11-02. */
        val cs3Hash: String,
    )

    /** The pin for [jarName], or `null` for a jar this table has never heard of. */
    fun forJar(jarName: String): Pin? = PINS.firstOrNull { it.jarName == jarName }

    /** The pin for a `.cs3` called [cs3Name], or `null`. Its hash is [Pin.cs3Hash], not [Pin.hash]. */
    fun forCs3(cs3Name: String): Pin? =
        PINS.firstOrNull { it.jarName.removeSuffix(".jar") == cs3Name.removeSuffix(".cs3") }

    /** Every pin, in index order, for a host printing "here is where to get one". */
    fun all(): List<Pin> = PINS

    private const val BUILDS =
        "https://raw.githubusercontent.com/recloudstream/extensions/builds"

    private val PINS: List<Pin> = listOf(
        Pin(
            jarName = "DailymotionProvider.jar",
            version = 4,
            hash = "sha256-542c325f2e1e1f61db60952d161325b62ff58712b1b2020e6df50727fdd03bf2",
            url = "$BUILDS/DailymotionProvider.jar",
            cs3Hash = "sha256-9036525a64e8b3c8fe04f94e9fe89a744c13d69af684ed0d2ad2a7eb0f332cd9",
        ),
        Pin(
            jarName = "InternetArchiveProvider.jar",
            version = 1,
            hash = "sha256-765f2f7664630c343420019ee86345451f5c950177b0f0319623282bde9bd58f",
            url = "$BUILDS/InternetArchiveProvider.jar",
            cs3Hash = "sha256-54f3de560bebeff2e4a10d1163f6146313c59061d21c3a230125611f01b531eb",
        ),
        Pin(
            jarName = "InvidiousProvider.jar",
            version = 9,
            hash = "sha256-d38e909bceba6c65c47a028a8aadfe83d09da976ffaef9cb2dd35a7c227b8453",
            url = "$BUILDS/InvidiousProvider.jar",
            cs3Hash = "sha256-a0a2ff7e8484f69118598d347458774935733399ba0f76ef0d4921886a7db4a9",
        ),
        Pin(
            jarName = "TwitchProvider.jar",
            version = 2,
            hash = "sha256-88086809ed6ccc21cca611ce2ba4f2563b1082486ffda3daa6350da9126b548b",
            url = "$BUILDS/TwitchProvider.jar",
            cs3Hash = "sha256-a7cc95f77bbd00a311d122201c63babe70b63fb398641fa28bf2b2c77346150a",
        ),
        Pin(
            jarName = "YoutubeProvider.jar",
            version = 1,
            hash = "sha256-0d83e79f196464e50b8b1aa4c13683b2428fc3556651a0d190981c1149b3ea41",
            url = "$BUILDS/YoutubeProvider.jar",
            cs3Hash = "sha256-a246eb1584cec9e69ba8c50136b9e8dc11a2fc0ab225710c31ad66c2ec4f4efe",
        ),
    )
}
