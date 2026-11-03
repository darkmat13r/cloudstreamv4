package com.lagradost.cloudstream4.feature.home

import androidx.compose.runtime.Immutable
// kotlin.jvm.* is a default import only on JVM; Kotlin/Native needs it named.
import kotlin.jvm.JvmInline

/** Which source's main page is showing. Opaque here; the host that made it resolves it. */
@JvmInline
value class HomeSourceId(val value: String)

/**
 * One choice in the source picker.
 *
 * @property sections how many sections of its main page there are to ask for.
 * @property sequentialDelayMillis when set, the source asks for its sections one at a time, this far
 *   apart, usually because its site rate-limits. Null when they can all be asked at once.
 */
@Immutable
data class HomeSourceOption(
    val id: HomeSourceId,
    val name: String,
    /** A language code, shown as written. */
    val language: String? = null,
    val sections: Int = 1,
    val sequentialDelayMillis: Long? = null,
)

/**
 * @property options the sources that have a main page.
 * @property installed every source, with or without one. A source that only searches is common, and
 *   "none has a main page" needs different words from "nothing is installed".
 */
@Immutable
data class HomeSources(val options: List<HomeSourceOption>, val installed: Int)

/**
 * One title. [id] is opaque, and is what the host's detail flow opens.
 *
 * Everything here came from a plugin and the host has cleaned it. [posterUrl] goes back to the host's
 * `artwork`, never to anything in this module.
 */
@Immutable
data class HomeItem(val id: String, val title: String, val posterUrl: String? = null)

/** A row as one page of one section reported it. */
@Immutable
data class HomeRowPage(val title: String, val landscape: Boolean, val items: List<HomeItem>)

/** How asking for one page of one section went. Failing is normal, so it is data and not a throw. */
sealed interface HomePageReply {
    /** @property hasNext what the source claims. It is not believed past a page that adds nothing. */
    data class Loaded(val rows: List<HomeRowPage>, val hasNext: Boolean) : HomePageReply

    /** [detail] is the machine-readable line a user can paste into a bug report. */
    data class Failed(val detail: String) : HomePageReply

    data object Offline : HomePageReply
}

/**
 * Where main pages come from: the seam that keeps `:library` out of this module.
 *
 * A section is an index below [HomeSourceOption.sections], and a page counts from 1.
 */
interface HomeSource {
    suspend fun sources(): HomeSources

    suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply
}
