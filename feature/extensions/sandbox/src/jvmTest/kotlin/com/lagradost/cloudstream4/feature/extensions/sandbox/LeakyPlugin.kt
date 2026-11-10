package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/** `BUG-83`: a plugin that writes what it should not to stderr, then fails to load, with more of it in the message. */
@CloudstreamPlugin
class LeakyPlugin : BasePlugin() {
    override fun load() {
        println("stdout GET https://viewer:hunter2@media.example/watch?q=SECRET_QUERY")
        android.util.Log.e("Leaky", "token Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc for viewer.one@mail.example")
        throw IllegalStateException("load failed at https://media.example/?k=SECRET_QUERY from 192.0.2.44")
    }
}
