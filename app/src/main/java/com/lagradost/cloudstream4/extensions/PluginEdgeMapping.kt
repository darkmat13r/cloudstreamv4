package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.PROVIDER_STATUS_BETA_ONLY
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.PROVIDER_STATUS_SLOW
import com.lagradost.cloudstream3.plugins.PLUGIN_VERSION_NOT_SET
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionRepositoryIndex
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.InstalledExtension
import java.io.File

/**
 * The edge between `:app`'s published plugin types and `:feature:extensions`' portable contracts.
 *
 * One way only, from the wire and storage types to the contracts, apart from the status code. The
 * published types stay the source of truth: `PluginManager` reads and writes `PluginData`, and
 * nothing here writes it back.
 */

/** Total, and an unknown code is kept. `PluginManager` itself only ever tests for `DOWN`. */
fun extensionStatusOf(code: Int): ExtensionStatus = when (code) {
    PROVIDER_STATUS_DOWN -> ExtensionStatus.Down
    PROVIDER_STATUS_OK -> ExtensionStatus.Ok
    PROVIDER_STATUS_SLOW -> ExtensionStatus.Slow
    PROVIDER_STATUS_BETA_ONLY -> ExtensionStatus.BetaOnly
    else -> ExtensionStatus.Unrecognised(code)
}

fun ExtensionStatus.toWireCode(): Int = when (this) {
    ExtensionStatus.Down -> PROVIDER_STATUS_DOWN
    ExtensionStatus.Ok -> PROVIDER_STATUS_OK
    ExtensionStatus.Slow -> PROVIDER_STATUS_SLOW
    ExtensionStatus.BetaOnly -> PROVIDER_STATUS_BETA_ONLY
    is ExtensionStatus.Unrecognised -> code
}

fun Repository.toExtensionRepositoryIndex(): ExtensionRepositoryIndex = ExtensionRepositoryIndex(
    name = name,
    description = description,
    iconUrl = iconUrl,
    manifestVersion = manifestVersion,
    pluginListUrls = pluginLists,
)

/**
 * The `jar*` fields stay `null`: `SitePlugin` is published and does not parse them, and a `.cs3` is
 * what Android loads.
 *
 * [SitePlugin.repositoryUrl] is the extension's own page. It is **not** the repository index URL
 * that `PluginManager.getPluginPath` hashes into the install path, and a listing does not carry
 * that one at all — which is the first reason `AndroidExtensionRuntime.install` refuses.
 */
fun SitePlugin.toExtensionListing(): ExtensionListing = ExtensionListing(
    internalName = internalName,
    name = name,
    downloadUrl = url,
    status = extensionStatusOf(status),
    version = version,
    apiVersion = apiVersion,
    authors = authors,
    description = description,
    iconUrl = iconUrl,
    repositoryUrl = repositoryUrl,
    tvTypes = tvTypes.orEmpty(),
    language = language,
    fileSizeBytes = fileSize,
    fileHash = fileHash,
)

/**
 * [PluginData.version] is carried raw, and so can be [PLUGIN_VERSION_NOT_SET]: `loadPlugin` stores
 * that for a plugin whose manifest has no version, and `downloadPlugin` for one saved but not loaded.
 * Clamping it would make the installed version disagree with what the update check compares.
 */
fun PluginData.toInstalledExtension(): InstalledExtension = InstalledExtension(
    internalName = internalName,
    location = filePath,
    version = version,
    isFromRepository = isOnline,
    sourceUrl = url,
)

/**
 * What the seam's `loaded` reports here: every path in [loadedPaths], in its order, joined
 * to its saved record.
 *
 * A loaded plugin can have no record, for example while `loadAllLocalPlugins` has just cleared
 * `PLUGINS_KEY_LOCAL`. Its providers are still live, so it is reported rather than dropped, in the
 * same shape `maybeLoadPlugin` gives a local file.
 */
internal fun loadedExtensions(
    loadedPaths: List<String>,
    online: List<PluginData>,
    local: List<PluginData>,
): List<InstalledExtension> = loadedPaths.map { path ->
    val record = online.firstOrNull { it.filePath == path }
        ?: local.firstOrNull { it.filePath == path }
    record?.toInstalledExtension() ?: InstalledExtension(
        internalName = File(path).name,
        location = path,
        version = PLUGIN_VERSION_NOT_SET,
        isFromRepository = false,
    )
}

/** The match `PluginManager.loadSinglePlugin` applies to a saved `internalName`. */
internal fun apiNameOf(internalName: String): String =
    internalName.replace("provider", "", ignoreCase = true)

/**
 * The `apiName` that makes `PluginManager.loadSinglePlugin` load exactly [location], or a refusal.
 *
 * `loadSinglePlugin` is the only public entry point that loads one plugin without downloading, and
 * it selects by name: the first match in online and then local wins. Two repositories may publish
 * the same `internalName`, and that is why the install path is salted with the repository URL, so a
 * name does not always identify a file. Loading some other repository's code than the one asked for
 * is the failure this prevents, so an ambiguous name is refused.
 */
internal fun apiNameForLoad(
    location: String,
    online: List<PluginData>,
    local: List<PluginData>,
): String {
    val record = online.firstOrNull { it.filePath == location }
        ?: local.firstOrNull { it.filePath == location }
    checkNotNull(record) { "no saved record for ${safeForLog(File(location).name)}" }

    val apiName = apiNameOf(record.internalName)
    val selected = online.firstOrNull { apiNameOf(it.internalName) == apiName }
        ?: local.firstOrNull { apiNameOf(it.internalName) == apiName }
    check(selected?.filePath == location) {
        "'${safeForLog(apiName)}' selects another installed plugin first, so this one cannot be " +
            "loaded by name"
    }
    return apiName
}

/**
 * Whether [location] is an absolute path strictly inside one of [roots], after resolving symlinks.
 *
 * Only ever a yes or no. `PluginManager` keys its maps on the uncanonicalised absolute path, and on
 * Android `/data/user/0/…` is itself a link, so a canonical path must never be passed on to it.
 */
internal fun isManagedLocation(location: String, roots: List<File>): Boolean {
    val file = File(location)
    if (!file.isAbsolute) return false
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return roots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
        canonicalRoot != null && canonical != canonicalRoot && canonical.startsWith(canonicalRoot)
    }
}

/** A name from a third-party index, reduced to what cannot act on a terminal or forge a log line. */
internal fun safeForLog(name: String): String =
    name.take(MAX_LOGGED_NAME).filter { it.isLetterOrDigit() || it in "._- " }

private const val MAX_LOGGED_NAME = 64
