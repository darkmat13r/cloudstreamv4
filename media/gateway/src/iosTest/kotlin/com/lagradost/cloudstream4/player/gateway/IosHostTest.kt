package com.lagradost.cloudstream4.player.gateway

import kotlin.test.Test
import kotlin.test.assertNull

/** `BUG-114`: the iPad does not convert a host that is not ASCII (the corpus's named divergence; `BUG-111`'s rule). */
class IosHostTest {
    @Test
    fun aHostThatIsNotAsciiIsNotConverted() = assertNull(UriOps.asciiHost("ex\u00e4mple.com"))
}
