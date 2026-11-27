package com.lagradost.cloudstream4.player.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

/** `KMP-130`: a local file is walked with the very bounds the iPad's gateway walks a first url with. */
class LocalMediaCheckBoundsTest {
    @Test
    fun theBoundsAreTheGatewaysDefaults() {
        val limits = IosMediaGateway.Limits()
        assertEquals(limits.maxMoovBytes, LocalMediaCheck.MAX_MOOV_BYTES)
        assertEquals(limits.maxTopLevelBoxes, LocalMediaCheck.MAX_TOP_LEVEL_BOXES)
    }
}
