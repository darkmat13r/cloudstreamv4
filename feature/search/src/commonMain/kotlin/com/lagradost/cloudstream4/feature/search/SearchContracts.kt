package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable
// kotlin.jvm.* is a default import only on JVM; Kotlin/Native needs it named. The iOS canary
// is what catches this — see the same import in :media:model's CaptionStyle.kt.
import kotlin.jvm.JvmInline

/**
 * One search hit, as a screen needs it.
 *
 * `:library`'s `SearchResponse` is the provider-facing type and is **published extension API** — it
 * is what every extension returns — so it stays exactly where it is. This restates the portable half
 * beside it, the way `:media:model` restates the player's types (`KMP-07`) and
 * `:feature:extensions` restates the plugin index (`KMP-11`).
 *
 * ## Why this is not `SearchResponse`
 *
 * Plan row 18 is annotated *"ViewModel needs Track L for iOS (rule 4)"* because `SearchViewModel`
 * consumes `SearchResponse`. A `commonMain` edge to `:library` would put this module into
 * `IOS_CANARY_EXEMPT`, and that list "may only shrink" (`cloudstream.kmp.library`'s KDoc). Restating
 * the fields the UI actually reads removes the edge, so **this module needs no exemption and no
 * Track L** — the same move that let `:feature:extensions` join the canary rather than leave it.
 *
 * Mapping `SearchResponse` onto this is `:app`'s job, at the edge, next to where the provider types
 * already live.
 *
 * ## What is deliberately not restated
 *
 * - **`TvType`, `DubStatus`, `SearchQuality`** — all `:library` enums, which is exactly the edge
 *   being avoided. [typeLabel] and [qualityLabel] are the already-resolved strings a card renders;
 *   deciding *which* string is `:app`'s mapping step, where those enums are.
 * - **`apiName` as an identity.** [providerName] is for display only. Routing a tap back to a
 *   provider is [SearchResultId]'s job, which is opaque for exactly that reason.
 * - **Image loading.** [posterUrl] is a `String`; a `Bitmap` or a Coil request is a platform concern.
 */
@Immutable
data class SearchResultItem(
    /** Opaque identity, assigned by whoever produced this. See [SearchResultId]. */
    val id: SearchResultId,
    val title: String,
    val posterUrl: String? = null,
    /** Which provider returned this hit. Display only. */
    val providerName: String,
    /** Resolved label for the media type, e.g. "Movie". `null` when the provider did not say. */
    val typeLabel: String? = null,
    /** Resolved label for the quality badge, e.g. "4K". `null` when there is none. */
    val qualityLabel: String? = null,
    /** Resolved label for a dub/sub badge. `null` when it does not apply. */
    val dubLabel: String? = null,
    /** `0f..1f` of the way through, when this is something the user has already started. */
    val watchProgress: Float? = null,
)

/**
 * Opaque handle for one search hit.
 *
 * It carries no structure on purpose. `:app` needs a provider name *and* a URL to reopen a result,
 * and both are `:library`'s shapes; encoding them here would either leak those types or invent a
 * parallel format that has to stay in step with them. The producer of a [SearchResultItem] is the
 * only thing that can resolve its id, which is the same contract `StreamSource.Local` and
 * `InstalledExtension.location` use.
 */
@JvmInline
value class SearchResultId(val value: String)

/**
 * Where results come from.
 *
 * The seam this module is built on, and the reason it needs no `:library` edge: `:app` implements
 * this over `APIRepository` and maps `SearchResponse` to [SearchResultItem] on the way out.
 *
 * Both calls return a [SearchOutcome] rather than throwing, because a search over many providers
 * **partially failing is the normal case**, not an exception — and a signature that can only
 * succeed or throw has nowhere to put "9 of 12 answered".
 */
interface SearchSource {

    /**
     * How many sources a search will query, and how many exist: the chrome row's `Sources 12 / 48`.
     *
     * On the seam rather than pushed in from outside because `:app` is the only thing that knows:
     * the selection lives in the user's provider preferences and changes behind the screen's back
     * when artboard 4d's side panel commits. Read once per screen, and again after the panel is
     * built.
     */
    suspend fun sources(): SearchSources

    /** The full search, for a submitted query. */
    suspend fun search(query: String): SearchOutcome

    /**
     * The cheap search, for a query still being typed.
     *
     * Separate because providers implement it separately — `MainAPI.hasQuickSearch` exists — and
     * because only this one is on the debounce path.
     */
    suspend fun quickSearch(query: String): SearchOutcome
}

/**
 * What a search over many providers came back with.
 *
 * [Partial] is the case the old UI had no way to express: `SearchViewModel` searched every provider
 * and a failure was a vanished `Toast`, which is the defect
 * `.claude/rules/mvi-viewmodel.md` cites. Here the shortfall is data, so a screen must decide what
 * to do with it.
 */
sealed interface SearchOutcome {
    /** Every provider answered. [items] may still be empty — that is a successful empty search. */
    data class Complete(val items: List<SearchResultItem>) : SearchOutcome

    /**
     * Some providers answered and some did not.
     *
     * @property items what did arrive, which is usable and should be shown.
     * @property answered how many providers answered.
     * @property total how many were asked.
     * @property detail machine-readable shortfall for the banner's mono line — a provider name, a
     *   host, a status code. This is the field that replaces a lost `Toast`.
     */
    data class Partial(
        val items: List<SearchResultItem>,
        val answered: Int,
        val total: Int,
        val detail: String,
    ) : SearchOutcome

    /** Nothing answered. [detail] is machine-readable, for the error state's mono line. */
    data class Failed(val detail: String) : SearchOutcome

    /** No network at all. Distinct from [Failed] because the user's next move differs. */
    data object Offline : SearchOutcome
}
