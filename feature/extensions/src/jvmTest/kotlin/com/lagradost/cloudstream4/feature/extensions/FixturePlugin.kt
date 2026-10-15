package com.lagradost.cloudstream4.feature.extensions

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * A real extension, as small as one can be, so the loader can be tested without the network.
 *
 * It is the same shape as a published one — `@CloudstreamPlugin`, `extends BasePlugin`, registers a
 * `MainAPI` from inside `load()` — which is what `javap` reports for `recloudstream/YoutubePlugin`
 * in `YoutubeProvider.jar`. The test jars are built from **this class's own compiled bytes**
 * (`JvmExtensionRuntimeTest.buildJar`), so no fixture binary is committed and nothing is downloaded.
 *
 * ## What that fixture can and cannot prove
 *
 * This class is on the test classpath, so the parent loader resolves it first and the `URLClassLoader`
 * delegates rather than defining it from the jar. The tests therefore cover **discovery** (reading
 * the entries, finding the annotation, rejecting a jar without one), the **`filename`-before-`load()`
 * ordering**, **registration and purging**, and every **refusal path** — but not class definition
 * from a jar that the host does not already have. Nothing short of a jar built by a compiler can
 * prove that, which is what running the desktop app against a published provider does instead.
 *
 * [filenameAtLoad] is the assertion that matters: it records `filename` **as `load()` saw it**, which
 * is the only moment at which a wrong ordering is observable. Checking it afterwards would pass even
 * if the runtime set it one line too late.
 */
@CloudstreamPlugin
class FixturePlugin : BasePlugin() {

    override fun load() {
        filenameAtLoad = filename
        loads++
        registerMainAPI(FixtureApi())
    }

    override fun beforeUnload() {
        beforeUnloads++
    }

    companion object {
        var filenameAtLoad: String? = null
        var loads: Int = 0
        var beforeUnloads: Int = 0

        /** Called at the start of each test; the counters are JVM-global, like the registries. */
        fun reset() {
            filenameAtLoad = null
            loads = 0
            beforeUnloads = 0
        }
    }
}

/** The provider [FixturePlugin] registers. `MainAPI` has no abstract members, so this is all of it. */
class FixtureApi : MainAPI() {
    override var name: String = PROVIDER_NAME
    override var mainUrl: String = "https://fixture.invalid"

    companion object {
        const val PROVIDER_NAME = "Fixture"
    }
}
