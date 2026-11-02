package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList

/**
 * A provider in the app's `APIHolder` whose code is in another process.
 *
 * `DesktopSearchSource` reads `APIHolder.apis` and needs nothing else, so this is the whole of the
 * integration: the search screen cannot tell the provider is sandboxed. Every result is built here
 * from validated fields and no plugin class ever reaches this JVM.
 *
 * `load` and `loadLinks` stay at `MainAPI`'s defaults until desktop has a detail screen that needs a
 * full `LoadResponse`. [details] and [links] are the typed versions of the same two calls.
 */
class SandboxedProvider(
    private val remote: RemoteProvider,
    private val host: PluginHostClient,
) : MainAPI() {

    override var name: String = remote.name
    override var mainUrl: String = remote.mainUrl
    override var lang: String = remote.lang
    override val hasQuickSearch: Boolean = remote.hasQuickSearch
    override val supportedTypes: Set<TvType> =
        remote.supportedTypes.mapNotNullTo(mutableSetOf()) { it.toTvTypeOrNull() }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val found = host.search(remote.name, query, page)
        return newSearchResponseList(found.items.map { it.toResponse() }, found.hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        host.quickSearch(remote.name, query).items.map { it.toResponse() }

    suspend fun details(url: String): RemoteDetails = host.load(remote.name, url)

    suspend fun links(data: String): RemoteLinks = host.loadLinks(remote.name, data)

    /** The start of what the plugin wrote to stderr, for a failure report. */
    val hostOutput: String get() = host.hostOutput

    private fun RemoteSearchItem.toResponse(): SearchResponse =
        newMovieSearchResponse(name, url, type?.toTvTypeOrNull() ?: TvType.Others, fix = false) {
            posterUrl = this@toResponse.posterUrl
        }

    private fun String.toTvTypeOrNull(): TvType? = TvType.entries.firstOrNull { it.name == this }
}
