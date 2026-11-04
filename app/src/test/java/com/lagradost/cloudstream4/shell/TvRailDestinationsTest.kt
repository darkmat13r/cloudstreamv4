package com.lagradost.cloudstream4.shell

import com.lagradost.cloudstream3.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one part of [TvRail] that is not Android: which item a destination marks. The rest is `TvRailNavigationTest`. */
class TvRailDestinationsTest {

    @Test
    fun aTopLevelPageMarksItself() {
        listOf(
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_library,
            R.id.navigation_downloads,
            R.id.navigation_settings,
        ).forEach { assertEquals(it, railItemOf(it)) }
    }

    @Test
    fun aPageUnderDownloadsMarksDownloads() {
        listOf(R.id.navigation_download_child, R.id.navigation_download_queue)
            .forEach { assertEquals(R.id.navigation_downloads, railItemOf(it)) }
    }

    /** Every page `MainActivity.updateNavBar` lists under Settings for the XML rail. */
    @Test
    fun aPageUnderSettingsMarksSettings() {
        listOf(
            R.id.navigation_subtitles,
            R.id.navigation_chrome_subtitles,
            R.id.navigation_settings_player,
            R.id.navigation_settings_updates,
            R.id.navigation_settings_ui,
            R.id.navigation_settings_account,
            R.id.navigation_settings_providers,
            R.id.navigation_settings_general,
            R.id.navigation_settings_extensions,
            R.id.navigation_settings_plugins,
            R.id.navigation_test_providers,
        ).forEach { assertEquals(R.id.navigation_settings, railItemOf(it)) }
    }
}
