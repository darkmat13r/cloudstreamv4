package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream4.detail.extensionImageLoader
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * A poster is loaded as a backdrop is (`KMP-102`): by the one loader whose bounds `BackdropImageTest` checks, https on
 * every hop, the byte cap on the wire and the bounded decode.
 */
class PosterLoaderTest {

    @Test
    fun `posters load through the backdrop's bounded loader`() {
        assertSame(extensionImageLoader, posterLoader)
    }
}
