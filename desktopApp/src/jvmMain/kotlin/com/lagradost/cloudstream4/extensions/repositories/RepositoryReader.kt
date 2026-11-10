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
     */
    data class Read(
        val index: ExtensionRepositoryIndex,
        val listings: List<ExtensionListing>,
        val unreadableLists: Int,
        val droppedEntries: Int,
    ) : RepositoryRead

    data class Failed(val failure: RepositoryFailure) : RepositoryRead
}

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
        val parsed = when (val repository = RepositoryIndexParser.repository(bytes)) {
            is ParsedRepository.Read -> repository
            is ParsedRepository.Refused -> return RepositoryRead.Failed(repository.failure)
        }
        val lists = coroutineScope {
            parsed.index.pluginListUrls.map { listUrl ->
                async {
                    when (val fetched = fetch.fetch(listUrl, RepositoryLimits.MAX_PLUGIN_LIST_BYTES)) {
                        is Fetched.Bytes -> RepositoryIndexParser.pluginList(fetched.bytes)
                        is Fetched.Failed -> null
                    }
                }
            }.awaitAll()
        }
        val seen = HashSet<String>()
        val listings = lists.filterNotNull().flatMap { it.listings }.filter { seen.add(it.internalName) }
        val read = lists.filterNotNull()
        return RepositoryRead.Read(
            index = parsed.index,
            listings = listings,
            unreadableLists = lists.count { it == null },
            droppedEntries = parsed.droppedLists + read.sumOf { it.dropped } + (read.sumOf { it.listings.size } - listings.size),
        )
    }
}
