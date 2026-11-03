package com.lagradost.cloudstream4.detail

import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailSource
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Keeps a title's ViewModel while the player is over it, so backing out of the player lands on the
 * same source list. There is no navigation library yet to scope it.
 */
internal object DesktopDetailViewModels {
    private val live = mutableMapOf<String, DetailViewModel>()
    private val source: DetailSource = DesktopDetailSource()

    fun get(id: String, scope: CoroutineScope): DetailViewModel =
        live.getOrPut(id) { DetailViewModel(id, source, scope) }

    /** Whatever it was still asking a plugin for stops here, because the scope it runs in outlives it. */
    fun forget(id: String) {
        live.remove(id)?.onAction(DetailAction.LinksDismissed)
    }
}
