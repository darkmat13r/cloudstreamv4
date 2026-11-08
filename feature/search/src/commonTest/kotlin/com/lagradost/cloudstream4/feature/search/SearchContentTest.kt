package com.lagradost.cloudstream4.feature.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [groupByProvider]'s two promises: one rail per provider, and **first-seen order**.
 *
 * ## Backtick names here may not contain a comma
 *
 * This suite is in `commonTest` so `iosSimulatorTest` (`INF-04`) runs it, and Kotlin/Native
 * rejects a backticked name containing `,` — `Name contains illegal characters: ","`. Every
 * JVM-only suite in this project uses commas freely, so the first thing that breaks when a test
 * moves to `commonTest` is its own name. The JVM compiler accepts them, so nothing warns you.
 *
 * The order is the part worth a test. Sorting by count instead would reshuffle the rails as late
 * replies arrive, which moves a focused card out from under the user — the same class of failure
 * `SingleActiveQuery` prevents at the request level.
 */
class SearchContentTest {

    private fun item(title: String, provider: String) = SearchResultItem(
        id = SearchResultId("$provider/$title"),
        title = title,
        providerName = provider,
    )

    @Test
    fun `one group per provider in the order the providers first appear`() {
        val groups = listOf(
            item("Tide", "Source B"),
            item("Tide", "Source A"),
            item("Tide Part Two", "Source B"),
        ).groupByProvider()

        assertEquals(listOf("Source B", "Source A"), groups.map { it.providerName })
        assertEquals(listOf(2, 1), groups.map { it.items.size })
    }

    @Test
    fun `the header count cannot disagree with the rail because it is the group's own size`() {
        val items = List(21) { item("Hit $it", "Source A") }
        val groups = items.groupByProvider()

        assertEquals(1, groups.size)
        assertEquals(21, groups.single().items.size)
        assertEquals(items, groups.single().items, "the group is the whole list, not a window")
    }

    @Test
    fun `an empty outcome groups to nothing rather than one empty rail`() {
        assertTrue(emptyList<SearchResultItem>().groupByProvider().isEmpty())
    }
}
