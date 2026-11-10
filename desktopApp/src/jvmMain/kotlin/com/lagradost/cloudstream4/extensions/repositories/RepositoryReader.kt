package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionRepositoryIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A repository and what it offers, or why it could not be read. */
sealed interface RepositoryRead {
    /**
     * @property unreadableLists plugin lists that could not be fetched or were not lists. Kept apart from an empty
     *   repository, which `:app` cannot tell from an unreachable one.
     * @property droppedEntries entries left out for their shape, plus plugin-list URLs that were not https or were past
     *   the limit.
     * @property raw the bytes this was read from, for [RepositoryCache] (`KMP-110c`), which keeps them rather than the
     *   result so a cached list is parsed again, under the same caps, when it is read back.
     */
    data class Read(
        val index: ExtensionRepositoryIndex,
        val listings: List<ExtensionListing>,
        val unreadableLists: Int,
        val droppedEntries: Int,
        val raw: RawRepository? = null,
    ) : RepositoryRead

    data class Failed(val failure: RepositoryFailure) : RepositoryRead
}

/** A repository's JSON as it arrived, and each of its plugin lists', in its order; null for a list that did not. */
class RawRepository(val repository: ByteArray, val lists: List<ByteArray?>)

/**
 * Reads a repository at an https URL: its index, then every plugin list it names, at once (`KMP-110`).
 *
 * Nothing here is logged or printed, and nothing in the result holds a URL other than those the repository itself
 * published: the URL a person added may carry a token, and is theirs.
 */
class RepositoryReader(private val fetch: Fetcher = RepositoryHttp().let { http -> Fetcher { url, maxBytes -> http.fetch(url, maxBytes) } }) {
    /** [RepositoryHttp.fetch]'s shape, so a test can answer https URLs without a TLS server. */
    fun interface Fetcher {
        suspend fun fetch(url: String, maxBytes: Long): Fetched
    }

    suspend fun read(url: String): RepositoryRead {
        val bytes = when (val fetched = fetch.fetch(url, RepositoryLimits.MAX_REPOSITORY_BYTES)) {
            is Fetched.Bytes -> fetched.bytes
            is Fetched.Failed -> return RepositoryRead.Failed(fetched.failure)
        }
        val index = when (val repository = RepositoryIndexParser.repository(bytes)) {
            is ParsedRepository.Read -> repository.index
            is ParsedRepository.Refused -> return RepositoryRead.Failed(repository.failure)
        }
        val lists = coroutineScope {
            index.pluginListUrls.map { listUrl ->
                async {
                    when (val fetched = fetch.fetch(listUrl, RepositoryLimits.MAX_PLUGIN_LIST_BYTES)) {
                        is Fetched.Bytes -> fetched.bytes
                        is Fetched.Failed -> null
                    }
                }
            }.awaitAll()
        }
        return parse(RawRepository(bytes, lists)) ?: RepositoryRead.Failed(RepositoryFailure.NotARepository)
    }

    companion object {
        /**
         * [raw] read as [read] reads a live answer, under the same caps: a repository past its limit, a list past its,
         * or more lists than the index names, is null. So is anything that is not a repository. For a cached copy, null
         * is "no cache".
         */
        fun parse(raw: RawRepository): RepositoryRead.Read? {
            if (raw.repository.size > RepositoryLimits.MAX_REPOSITORY_BYTES) return null
            val parsed = RepositoryIndexParser.repository(raw.repository) as? ParsedRepository.Read ?: return null
            if (raw.lists.size != parsed.index.pluginListUrls.size) return null
            if (raw.lists.any { it != null && it.size > RepositoryLimits.MAX_PLUGIN_LIST_BYTES }) return null
            val lists = raw.lists.map { bytes -> bytes?.let(RepositoryIndexParser::pluginList) }
            val seen = HashSet<String>()
            val read = lists.filterNotNull()
            val listings = read.flatMap { it.listings }.filter { seen.add(it.internalName) }
            return RepositoryRead.Read(
                index = parsed.index,
                listings = listings,
                unreadableLists = lists.count { it == null },
                droppedEntries = parsed.droppedLists + read.sumOf { it.dropped } + (read.sumOf { it.listings.size } - listings.size),
                raw = raw,
            )
        }
    }
}
