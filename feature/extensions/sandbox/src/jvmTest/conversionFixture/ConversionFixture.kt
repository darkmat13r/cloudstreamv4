package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Source of `conversion-fixture.dex.b64`, our own code dexed by hand, and never third-party.
 *
 * It holds the two things that broke real plugins: `runCatching`, whose inlined body calls
 * `kotlin.Result.constructor-impl`, and branches, which need stack maps in class version 52.
 *
 * **This directory is not a source root, and that is the point.** A host loads parent-first, so a
 * class on the test classpath would shadow the converted one and the test would run the original,
 * proving nothing. Mutation found exactly that: with both converter flags removed it still passed.
 *
 * To regenerate: copy this file into `jvmTest/kotlin` for one build, then, on the two classes,
 *
 *     d8 --release --min-api 26 --no-desugaring --output out \
 *       ConversionFixturePlugin.class ConversionFixtureApi.class
 *     base64 -b 76 -i out/classes.dex > ../resources/conversion-fixture.dex.b64
 *
 * and remove the copy. `--min-api 26` is dex 038, the format of a plugin that failed verification.
 */
@CloudstreamPlugin
class ConversionFixturePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ConversionFixtureApi())
    }
}

class ConversionFixtureApi : MainAPI() {
    override var name = "ConversionFixture"
    override var mainUrl = "https://example.invalid"
    override val supportedTypes = setOf(TvType.Others)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val parsed = runCatching { query.toInt() }
        val label = if (parsed.isFailure) "failed" else if (parsed.getOrThrow() > 1) "big" else "small"
        return newSearchResponseList(listOf(newMovieSearchResponse(label, "$mainUrl/1", TvType.Others, fix = false)), false)
    }
}
