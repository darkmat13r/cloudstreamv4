package com.lagradost.cloudstream4.core.designsystem.icon

import compose.icons.AllIcons
import compose.icons.TablerIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pack was compiled against an old Compose and Kotlin 1.9.20 (`DEC-11`), so every vector has to be built
 * by the Compose this app runs. In `commonTest` so it also runs on Kotlin/Native (`INF-04`), where a broken
 * link is a runtime error that compiling a klib never shows.
 */
class IconPackBuildTest {

    @Test
    fun everyIconInThePackBuilds() {
        val empty = TablerIcons.AllIcons.filter { it.root.size == 0 }.map { it.name }
        assertEquals(emptyList(), empty)
        assertTrue(TablerIcons.AllIcons.size >= MINIMUM_ICONS, "${TablerIcons.AllIcons.size} icons")
    }

    private companion object {
        /** A floor rather than the count: `CloudStreamIconsTest` pins it against the jar on the JVM. */
        const val MINIMUM_ICONS = 1000
    }
}
