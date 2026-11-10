package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_AUTHORS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_DESCRIPTION_CHARS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_HASH_CHARS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_INTERNAL_NAME_CHARS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_LANGUAGE_CHARS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_NAME_CHARS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_PLUGINS_PER_LIST
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_PLUGIN_LISTS
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.MAX_TV_TYPES
import com.lagradost.cloudstream4.extensions.repositories.RepositoryLimits.SUPPORTED_MANIFEST_VERSIONS
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionRepositoryIndex
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A repository's index, read: the index, or why it is not one. */
sealed interface ParsedRepository {
    /** [droppedLists] is how many plugin-list URLs were not https, or past [MAX_PLUGIN_LISTS]. */
    data class Read(val index: ExtensionRepositoryIndex, val droppedLists: Int) : ParsedRepository

    data class Refused(val failure: RepositoryFailure) : ParsedRepository
}

/** A plugin list, read. [dropped] is how many entries were left out, for a count on screen and nothing else. */
data class ParsedPluginList(val listings: List<ExtensionListing>, val dropped: Int)

/**
 * Reads a repository's JSON as what it is: input from a third party (`KMP-110`).
 *
 * The wire shapes are `:app`'s `Repository` and `SitePlugin`, which are published extension API and stay where they
 * are; this maps the same keys onto `:feature:extensions`' contracts. The differences from `:app`, each on purpose:
 *
 * - **One bad entry costs that entry**, not the list. `:app` parses a list as `Array<SitePlugin>`, so one entry of the
 *   wrong shape empties the whole repository.
 * - **Every URL** must be https with a host, no user info, no control characters and at most [MAX_LINK_CHARS]. A
 *   plugin whose download URL fails that is dropped; an optional URL that fails it is left out.
 * - **Identity is strict and display is bounded.** An entry with no `internalName`, `name`, `url`, `version` or `status`
 *   is dropped, as is one whose `internalName` has a control character or runs past [MAX_INTERNAL_NAME_CHARS]. Text
 *   shown to the person is cut to its limit rather than refused. The same `internalName` twice in one list keeps the
 *   first.
 * - **A hash that is there but cannot be read drops the entry.** Over [MAX_HASH_CHARS], or not a string: a repository
 *   that states a hash and then sends something no one could check gets no benefit of the doubt. A well-formed hash in
 *   an algorithm other than SHA-256 is kept, and `ExtensionHash.verify` says it is unverifiable.
 * - **Numbers are numbers.** `"3"` is not a version. `version` is a positive counter or `-1` (`:app`'s "always update").
 * - `apiVersion`, which `:app` documents as unused, defaults to 1 when it is absent.
 *
 * The bytes have already been capped by [RepositoryHttp]; kotlinx's parser reads deep nesting without recursing on the
 * stack, so a deeply nested body costs memory bounded by that cap, not a crash.
 */
object RepositoryIndexParser {
    private val json = Json { isLenient = false }

    fun repository(bytes: ByteArray): ParsedRepository {
        val root = parse(bytes) as? JsonObject ?: return ParsedRepository.Refused(RepositoryFailure.NotARepository)
        val name = root.string("name")?.let(::display)?.takeIf { it.isNotBlank() }
        val version = root.number("manifestVersion")?.intOrNull
        val lists = root["pluginLists"] as? JsonArray
        if (name == null || version == null || lists == null) {
            return ParsedRepository.Refused(RepositoryFailure.NotARepository)
        }
        if (version !in SUPPORTED_MANIFEST_VERSIONS) return ParsedRepository.Refused(RepositoryFailure.UnsupportedFormat)
        val urls = lists.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let(::httpsUrl) }
            .distinct()
        val kept = urls.take(MAX_PLUGIN_LISTS)
        return ParsedRepository.Read(
            ExtensionRepositoryIndex(
                name = name,
                description = root.string("description")?.let { display(it, MAX_DESCRIPTION_CHARS) },
                iconUrl = root.string("iconUrl")?.let(::httpsUrl),
                manifestVersion = version,
                pluginListUrls = kept,
            ),
            droppedLists = lists.size - kept.size,
        )
    }

    /** A plugin list; null when it is not a JSON array at all, which is a list that could not be read. */
    fun pluginList(bytes: ByteArray): ParsedPluginList? {
        val entries = parse(bytes) as? JsonArray ?: return null
        val seen = HashSet<String>()
        val listings = entries.asSequence().take(MAX_PLUGINS_PER_LIST)
            .mapNotNull { (it as? JsonObject)?.let(::listing) }
            .filter { seen.add(it.internalName) }
            .toList()
        return ParsedPluginList(listings, dropped = entries.size - listings.size)
    }

    private fun listing(entry: JsonObject): ExtensionListing? {
        val internalName = entry.string("internalName")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_INTERNAL_NAME_CHARS && it.none(Char::isISOControl) }
            ?: return null
        val name = entry.string("name")?.let(::display)?.takeIf { it.isNotBlank() } ?: return null
        val url = entry.string("url")?.let(::httpsUrl) ?: return null
        val version = entry.number("version")?.intOrNull?.takeIf { it > 0 || it == ALWAYS_UPDATE } ?: return null
        val status = entry.number("status")?.intOrNull?.let(::status) ?: return null
        val fileHash = hash(entry, "fileHash") ?: return null
        val jarHash = hash(entry, "jarHash") ?: return null
        return ExtensionListing(
            internalName = internalName,
            name = name,
            downloadUrl = url,
            status = status,
            version = version,
            apiVersion = entry.number("apiVersion")?.intOrNull ?: DEFAULT_API_VERSION,
            authors = (entry["authors"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let(::display) }
                .filter(String::isNotBlank)
                .take(MAX_AUTHORS),
            description = entry.string("description")?.let { display(it, MAX_DESCRIPTION_CHARS) },
            iconUrl = entry.string("iconUrl")?.let(::httpsUrl),
            repositoryUrl = entry.string("repositoryUrl")?.let(::httpsUrl),
            tvTypes = (entry["tvTypes"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                .filter { it.length <= MAX_NAME_CHARS && it.none(Char::isISOControl) }
                .take(MAX_TV_TYPES),
            language = entry.string("language")?.takeIf { it.length <= MAX_LANGUAGE_CHARS && it.none(Char::isISOControl) },
            fileSizeBytes = entry.number("fileSize")?.longOrNull?.takeIf { it >= 0 },
            fileHash = fileHash.value,
            jarUrl = entry.string("jarUrl")?.let(::httpsUrl),
            jarHash = jarHash.value,
            jarFileSizeBytes = entry.number("jarFileSize")?.longOrNull?.takeIf { it >= 0 },
        )
    }

    /** Absent or null is a known absence, `Hash(null)`; a hash that cannot be read is null, which drops the entry. */
    private fun hash(entry: JsonObject, key: String): Hash? = when (val value = entry[key]) {
        null, JsonNull -> Hash(null)
        is JsonPrimitive -> value.takeIf { it.isString && it.content.length <= MAX_HASH_CHARS }
            ?.let { Hash(it.content.trim().takeIf(String::isNotEmpty)) }
        else -> null
    }

    private class Hash(val value: String?)

    private fun status(code: Int): ExtensionStatus = when (code) {
        0 -> ExtensionStatus.Down
        1 -> ExtensionStatus.Ok
        2 -> ExtensionStatus.Slow
        3 -> ExtensionStatus.BetaOnly
        else -> ExtensionStatus.Unrecognised(code)
    }

    private fun parse(bytes: ByteArray): JsonElement? =
        runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.number(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it != JsonNull }

    /** Text the person will read: control characters out, cut to [limit]. Shown through `untrustedText` as well. */
    private fun display(text: String, limit: Int = MAX_NAME_CHARS): String =
        text.filterNot(Char::isISOControl).trim().take(limit)

    private fun httpsUrl(url: String): String? = url.trim().takeIf {
        it.length <= MAX_LINK_CHARS && it.startsWith("https://", ignoreCase = true) && SandboxPolicy.isHttpUrlShape(it)
    }

    /** `:app`'s `PLUGIN_VERSION_ALWAYS_UPDATE`. */
    const val ALWAYS_UPDATE = -1
    private const val DEFAULT_API_VERSION = 1
}
