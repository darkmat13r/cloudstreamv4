package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostContext

/**
 * The plugin host's `Plugin`: the supertype of most published extensions, off Android.
 *
 * `:app` owns the real class, and this restates the parts an extension links against. 72 of 89
 * sampled `.cs3` files extend it and only 17 extend `BasePlugin` (`KMP-14`), so without it most
 * extensions fail to load before running a single line. It can only ever be seen by a plugin host:
 * this module is not on `:app`'s classpath and `:app:makeJar` never merges it.
 *
 * ## `load()` and `load(Context)` call each other, and a host starts from `load()`
 *
 * On Android the loader calls `load(context)`, whose default calls `load()`.
 * `JvmExtensionRuntime` only knows `BasePlugin`, so it calls `load()`. Overriding that here reaches
 * an extension's `load(context)` too. The guard is for an extension that overrides neither, where
 * the two defaults would otherwise recurse.
 */
abstract class Plugin : BasePlugin() {
    private var loading = false

    @Throws(Throwable::class)
    open fun load(context: Context) {
        if (!loading) load()
    }

    @Throws(Throwable::class)
    override fun load() {
        if (loading) return
        loading = true
        try {
            load(HostContext.current)
        } finally {
            loading = false
        }
    }

    /** Always null here. There is no Android resource table for a `requiresResources` extension. */
    var resources: Resources? = null

    /** Stored and never called. Desktop has no way to show an Android settings screen. */
    var openSettings: ((context: Context) -> Unit)? = null
}
