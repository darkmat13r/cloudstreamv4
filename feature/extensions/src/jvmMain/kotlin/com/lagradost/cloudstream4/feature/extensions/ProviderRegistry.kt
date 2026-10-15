package com.lagradost.cloudstream4.feature.extensions

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.extractorApis

/**
 * The three global registries a loaded plugin writes into, and the only way to take it back out.
 *
 * A plugin registers through `BasePlugin`, which is `:library` `commonMain` and needs nothing
 * Android: `registerMainAPI` stamps `sourcePlugin` from `filename` and appends to
 * `APIHolder.allProviders` + `APIHolder.apis` (`BasePlugin.kt:20-25`), `registerExtractorAPI` stamps
 * the same and appends to `extractorApis` (`:31-35`). `filename` is the only key that ties an entry
 * back to the plugin that made it, which is why a runtime has to set it **before** `load()` runs.
 *
 * ## Deliberately three registries and not four
 *
 * `PluginManager.unloadPlugin` clears a fourth — `VideoClickActionHolder.allVideoClickActions`
 * (`PluginManager.kt:716-718`). That holder is `:app` (`actions/VideoClickAction.kt:46`) and is not
 * reachable from this module, which cannot depend on `:app`. Nothing is leaked by the omission: a
 * jar that registered one would have to reference an `:app` class, which is not on a desktop host's
 * classpath at all, so it fails to resolve at discovery (see [ExtensionJar.findPluginClass]) or
 * throws `NoClassDefFoundError` inside `load()` — reported as a failure, with no registration having
 * happened. If an `:app` adapter for this seam is ever written (`KMP-11`'s open half), that fourth
 * registry is its business, since only `:app` can see it.
 */
internal object ProviderRegistry {

    /**
     * Removes every provider and extractor that [filename] registered.
     *
     * Mirrors `PluginManager.kt:703-714` exactly, including the lock discipline: `AtomicList`'s
     * iterators are deliberately unsynchronised and the class documents that a caller iterating must
     * hold [com.lagradost.cloudstream3.utils.AtomicList.withLock] for the duration
     * (`AtomicList.kt:9-10`, `:29`). `APIHolder.apis` is the exception — it is reassigned rather than
     * mutated, so the removal goes through `APIHolder.removePluginMapping`, which takes the lock and
     * rebuilds `apiMap` (`MainAPI.kt:141-146`). Rebuilding that map is why the mapping cannot simply
     * be filtered here.
     *
     * Safe to call for a `filename` that registered nothing, which is what makes unload idempotent
     * and what lets a half-finished `load()` be cleaned up by the same code.
     */
    fun purge(filename: String) {
        APIHolder.apis.filter { api -> api.sourcePlugin == filename }
            .forEach { api -> APIHolder.removePluginMapping(api) }

        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == filename }
        }

        extractorApis.withLock {
            extractorApis.removeAll { extractor -> extractor.sourcePlugin == filename }
        }
    }
}
