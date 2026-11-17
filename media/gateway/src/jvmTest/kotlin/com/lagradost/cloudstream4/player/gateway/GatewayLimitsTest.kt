package com.lagradost.cloudstream4.player.gateway

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/** `BUG-114`: the copy of the url length limit stays the sandbox's, which `KMP-113` read directly. */
class GatewayLimitsTest {
    @Test
    fun theUrlLimitIsTheSandboxPolicys() = assertEquals(SandboxPolicy.MAX_URL_CHARS, MAX_URL_CHARS)
}
