package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncRepo

/**
 * Source of `conversion-fixture.dex.b64`, our own code dexed by hand, and never third-party.
 *
 * It holds what broke real plugins: `runCatching`, whose inlined body calls
 * `kotlin.Result.constructor-impl`; branches, which need stack maps in class version 52; branches
 * that join two classes, whose stack map has to name a class that is really above both (`BUG-18`);
 * and a `SyncRepo` built as a provider's constructor builds it in CineStream.
 *
 * **This directory is not a source root, and that is the point.** A host loads parent-first, so a
 * class on the test classpath would shadow the converted one and the test would run the original,
 * proving nothing. Mutation found exactly that: with both converter flags removed it still passed.
 *
 * To regenerate: copy this file into `jvmTest/kotlin` for one build, then, on its `ConversionFixture` classes,
 *
 *     d8 --release --min-api 26 --no-desugaring --output out ConversionFixture*.class
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
        val flag = page == 1
        val joined = when (query) {
            "missing" -> ConversionFixtureMissing().answer(flag)
            "host-ancestor" -> ConversionFixtureHostAncestor().answer(flag)
            "child-first" -> ConversionFixtureChildFirst().answer(flag)
            "parent-first" -> ConversionFixtureParentFirst().answer(flag)
            "sync" -> ConversionFixtureSync().answer()
            else -> null
        }
        if (joined != null) {
            return newSearchResponseList(listOf(newMovieSearchResponse(joined, "$mainUrl/1", TvType.Others, fix = false)), false)
        }
        val parsed = runCatching { query.toInt() }
        val label = if (parsed.isFailure) "failed" else if (parsed.getOrThrow() > 1) "big" else "small"
        return newSearchResponseList(listOf(newMovieSearchResponse(label, "$mainUrl/1", TvType.Others, fix = false)), false)
    }
}

/*
 * The joins below are all the same shape, because it is the shape that broke real plugins. dex2jar casts a
 * value that stays in use to the type it is used as, so a join of two types only survives in a slot that is
 * no longer read: each branch uses its own value in that slot, and the next frame still has to describe it.
 */

/**
 * Named by [ConversionFixtureMissing] and left out of the dex on purpose, so it is on no classpath at all, like
 * `:app`'s `PluginData` in ViStream and `SyncRepo` in CineStream before its shim. Never instantiated.
 */
class AbsentFromEverything

/** A class nobody has and a host class: what dex2jar answers with `java/util/Object`. */
class ConversionFixtureMissing {
    private var seen = ""

    private fun see(value: Any?) {
        seen += value?.let { it::class.java.simpleName } + " "
    }

    private fun absent(): AbsentFromEverything? = null

    fun answer(flag: Boolean): String {
        if (flag) {
            val value = absent()
            see(value)
            see(value)
        } else {
            val value = StringBuilder("host")
            see(value)
            see(value)
        }
        see(this)
        return seen.trim()
    }
}

class ConversionFixtureStateError : IllegalStateException("state")

class ConversionFixtureArgumentError : IllegalArgumentException("argument")

/** Two plugin classes whose nearest common class can only be found through the host's classes. */
class ConversionFixtureHostAncestor {
    private var seen = ""

    private fun see(value: Any?) {
        seen += value?.let { it::class.java.simpleName } + " "
    }

    fun answer(flag: Boolean): String {
        if (flag) {
            val value = ConversionFixtureStateError()
            see(value)
            see(value)
        } else {
            val value = ConversionFixtureArgumentError()
            see(value)
            see(value)
        }
        see(this)
        return seen.trim()
    }
}

open class ConversionFixtureBase

class ConversionFixtureChild : ConversionFixtureBase()

/** A plugin class joined with its plugin parent. */
class ConversionFixtureChildFirst {
    private var seen = ""

    private fun see(value: Any?) {
        seen += value?.let { it::class.java.simpleName } + " "
    }

    fun answer(flag: Boolean): String {
        if (flag) {
            val value = ConversionFixtureChild()
            see(value)
            see(value)
        } else {
            val value = ConversionFixtureBase()
            see(value)
            see(value)
        }
        see(this)
        return seen.trim()
    }
}

/** The same the other way round, because a join's order is the compiler's. */
class ConversionFixtureParentFirst {
    private var seen = ""

    private fun see(value: Any?) {
        seen += value?.let { it::class.java.simpleName } + " "
    }

    fun answer(flag: Boolean): String {
        if (flag) {
            val value = ConversionFixtureBase()
            see(value)
            see(value)
        } else {
            val value = ConversionFixtureChild()
            see(value)
            see(value)
        }
        see(this)
        return seen.trim()
    }
}

/** Built like `CineSimklProvider`'s field, and read like its "Personal" rows. */
class ConversionFixtureSync {
    private val repo = SyncRepo(AccountManager.simklApi)

    suspend fun answer(): String = "signed in ${repo.authUser() != null}, library ${repo.library().getOrThrow()}"
}
