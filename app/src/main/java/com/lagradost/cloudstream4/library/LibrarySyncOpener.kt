package com.lagradost.cloudstream4.library

import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.openBrowser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.library.LIBRARY_FOLDER
import com.lagradost.cloudstream3.ui.library.LibraryOpener
import com.lagradost.cloudstream3.ui.library.LibraryOpenerType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount

/** What opening a title from a tracking service's list does. */
internal sealed interface SyncedOpen {
    /** The title's page, from the provider it names. */
    data class Page(val item: SyncAPI.LibraryItem) : SyncedOpen

    /** A search for its name, across the providers. */
    data class Search(val name: String) : SyncedOpen

    /** The title's page, from a provider the user chose for it. */
    data class Provider(val url: String, val apiName: String, val name: String) : SyncedOpen

    data class Browser(val url: String) : SyncedOpen

    /** The user chose for it to open nothing. */
    data object Nothing : SyncedOpen
}

/**
 * The old screen's rule (`LibraryFragment.loadLibraryItem`): the opener saved for that title, unless that one is the
 * default, else the one saved for the whole service, else the default.
 *
 * The default is the title's page where a provider of that name is loaded, and a search by name otherwise, which is
 * what a service's own titles get: "AniList" is not a provider. The saved openers are read, never written: choosing
 * one is the old screen's long-press popup, which no board draws for TV.
 *
 * One departure: the old screen opens nothing from a service with no `syncIdName`. Every service the app has names
 * one, and a title from one that did not would get the default here rather than nothing.
 *
 * @param saved the opener stored under a key in the library's folder, or null.
 */
internal fun syncedOpen(
    title: SyncedTitle,
    saved: (key: String) -> LibraryOpener?,
    providerLoaded: (apiName: String) -> Boolean,
): SyncedOpen {
    val item = title.item
    val opener = title.syncName?.let { syncName ->
        saved(item.syncId)?.takeIf { it.openType != LibraryOpenerType.Default } ?: saved(syncName.name)
    }
    return when (opener?.openType) {
        null, LibraryOpenerType.Default ->
            if (providerLoaded(item.apiName)) SyncedOpen.Page(item) else SyncedOpen.Search(item.name)

        LibraryOpenerType.None -> SyncedOpen.Nothing
        // The old screen does nothing where a provider opener has no provider, and so does this.
        LibraryOpenerType.Provider -> opener.providerData?.apiName
            ?.let { SyncedOpen.Provider(item.url, it, item.name) } ?: SyncedOpen.Nothing

        LibraryOpenerType.Browser -> SyncedOpen.Browser(item.url)
        LibraryOpenerType.Search -> SyncedOpen.Search(item.name)
    }
}

/** Opens [title] through [syncedOpen], with the old screen's own calls and keys. */
internal fun FragmentActivity.openSyncedTitle(title: SyncedTitle) {
    val open = syncedOpen(
        title = title,
        saved = { key -> getKey<LibraryOpener>("$currentAccount/$LIBRARY_FOLDER", key) },
        providerLoaded = { APIHolder.getApiFromNameNull(it) != null },
    )
    when (open) {
        is SyncedOpen.Page -> loadSearchResult(open.item)
        is SyncedOpen.Search -> QuickSearchFragment.pushSearch(this, open.name)
        is SyncedOpen.Provider -> loadResult(open.url, open.apiName, open.name)
        is SyncedOpen.Browser -> openBrowser(open.url, this)
        SyncedOpen.Nothing -> Unit
    }
}
