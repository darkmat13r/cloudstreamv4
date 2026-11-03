package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.utils.UiText

/**
 * `:app`'s account classes, as far as an extension links against them. There are no accounts in a plugin
 * host, and nobody is ever signed in.
 *
 * CineStream builds a `SyncRepo` in `CineSimklProvider`'s constructor and only reads it for its "Personal" home
 * rows, after asking [SyncRepo.authUser]. Without these, that constructor throws from inside the plugin's
 * `load()` and the providers it would have registered next are lost with it (`BUG-18`).
 */
abstract class AccountManager {
    companion object {
        val simklApi = SimklApi()
    }
}

/** Nothing here holds a token, and nothing here can reach the network. */
abstract class SyncAPI {
    data class LibraryMetadata(val allLibraryLists: List<LibraryList>)

    data class LibraryList(val name: UiText, val items: List<SearchResponse>)
}

class AuthUser

class SyncRepo(@Suppress("unused") private val api: SyncAPI) {
    fun authUser(): AuthUser? = null

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = Result.success(null)
}
