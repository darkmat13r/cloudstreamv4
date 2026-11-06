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
 * `None` is a choice (`TV Home Extras` 3h): it is kept under the old screen's own name for it, so either screen reads it the
 * same, and it reads back as [HomeSourceId.None]. `Random` is not one since the owner ruled it out as a source, so the old
 * screen's `Random` reads as never having chosen. Reading back only ever selects from what the app is offering, and anything
 * else falls through the same way.
 */
internal class DataStoreHomeSourceMemory(
    private val read: () -> String? = { DataStoreHelper.currentHomePage },
    private val write: (String) -> Unit = { DataStoreHelper.currentHomePage = it },
) : HomeSourceMemory {

    override fun last(): HomeSourceId? {
        val name = read()
        if (name == NONE) return HomeSourceId.None
        return name?.takeIf(::isChoice)?.let(::HomeSourceId)
    }

    override fun remember(id: HomeSourceId) {
        when {
            id == HomeSourceId.None -> write(NONE)
            isChoice(id.value) -> write(id.value)
        }
    }

    private fun isChoice(name: String) = name.isNotBlank() && name.length <= MAX_NAME_CHARS &&
        name !in NOT_SOURCES && name.none(Char::isISOControl)

    private companion object {
        const val MAX_NAME_CHARS = 256
        val NONE: String by lazy { APIRepository.noneApi.name }
        val NOT_SOURCES by lazy { setOf(APIRepository.noneApi.name, APIRepository.randomApi.name) }
    }
}
