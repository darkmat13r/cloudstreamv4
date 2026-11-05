package com.lagradost.cloudstream4.home

import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceMemory

/**
 * The source chosen last, in the key the old screen uses: `DataStoreHelper.currentHomePage`, per account.
 *
 * One key, because the old screen is the rollback for a release and both must agree on what was chosen. It is also what
 * `MainActivity` reads to load that one plugin ahead of the others, so a new key would lose that too.
 *
 * The old screen can leave `None` or `Random` there, which name no source. They read as never having chosen. Reading it
 * back only ever selects from what the app is offering, and anything else falls through the same way.
 */
internal class DataStoreHomeSourceMemory(
    private val read: () -> String? = { DataStoreHelper.currentHomePage },
    private val write: (String) -> Unit = { DataStoreHelper.currentHomePage = it },
) : HomeSourceMemory {

    override fun last(): HomeSourceId? = read()?.takeIf(::isChoice)?.let(::HomeSourceId)

    override fun remember(id: HomeSourceId) {
        if (isChoice(id.value)) write(id.value)
    }

    private fun isChoice(name: String) = name.isNotBlank() && name.length <= MAX_NAME_CHARS &&
        name !in NOT_SOURCES && name.none(Char::isISOControl)

    private companion object {
        const val MAX_NAME_CHARS = 256
        val NOT_SOURCES by lazy { setOf(APIRepository.noneApi.name, APIRepository.randomApi.name) }
    }
}
