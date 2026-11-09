package com.lagradost.cloudstream4.feature.home.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HomeHostFocus.grantedFromNothing] (`BUG-69`) marks the window's grant from nothing focused as the host's, and only
 * while a landing is owed. The screen's landing effect clearing the mark is `HomeScreenTvDeviceTest`'s, since only a
 * composition runs it.
 */
class HomeHostFocusTest {

    @Test
    fun aGrantFromNothingWithNoLandingOwedMarksNothing() {
        val host = HomeHostFocus()
        host.grantedFromNothing(landingOwed = false)
        assertFalse(host.requested, "after a press, an arrival from nothing is not the host's")

        // It leaves a mark it didn't make alone, so a `requested = landingOwed` would fail here.
        host.requested = true
        host.grantedFromNothing(landingOwed = false)
        assertTrue(host.requested, "the host's own request is still the host's")
    }

    @Test
    fun aGrantFromNothingWithALandingOwedIsTheHosts() {
        val host = HomeHostFocus()
        host.grantedFromNothing(landingOwed = true)
        assertTrue(host.requested, "nothing pressed yet, so the window's grant is the host's")
    }

    @Test
    fun aGrantFromNothingDoesNotMarkFocusTheWindowAlreadyGave() {
        val host = HomeHostFocus()
        host.grantedFromNothing(landingOwed = true)
        assertFalse(host.adopted, "adopted is for focus the page already held at resume, not for this arrival")
    }
}
