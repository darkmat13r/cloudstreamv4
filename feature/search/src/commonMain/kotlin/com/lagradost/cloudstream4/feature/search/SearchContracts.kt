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
    /**
     * Headers the poster has to be fetched with, as the provider gave them.
     *
     * A provider that hotlink-protects its images answers a plain request with a 403, so a card
     * without these draws its placeholder. `null` when the provider set none.
     */
    val posterHeaders: Map<String, String>? = null,
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
     * when artboard 4d's side panel commits. Read once per screen, and again after anything that
     * can change it.
     */
    suspend fun sources(): SearchSources

    /**
     * The rows of artboard 4d's side panel, in the order they are drawn, each saying whether it is on.
     *
     * Empty by default, for a source with no selection of its own: desktop has no picker, and every
     * one of its providers is always asked.
     */
    suspend fun choices(): List<SearchSourceChoice> = emptyList()

    /**
     * What the panel's Done commits, [ids] being the rows left on, out of those [choices] offered.
     *
     * A provider [choices] did not offer keeps its state: a type filter can hide a row, and hiding one
     * must not turn it off. Returns once the choice is stored, so a search after it sees it.
     */
    suspend fun selectSources(ids: Set<String>) {}

    /**
     * The next [page] of one provider's answer to [query], for a rail's trailing card.
     *
     * [SearchOutcome.Complete.morePages] says whether another follows. Nothing by default, so a source that
     * has never named a provider there is never asked.
     */
    suspend fun searchMore(query: String, providerName: String, page: Int): SearchOutcome =
        SearchOutcome.Complete(emptyList())

    /** The type chips this source can honour, and which are lit. By default none, so none are drawn. */
    suspend fun typeFilters(): SearchTypeFilters = SearchTypeFilters()

    /** A chip was pressed. Returns once stored, like [selectSources]. */
    suspend fun selectTypes(types: Set<SearchTypeFilter>) {}

    /** 8c's *Search everything*: every eligible provider on, every type chip off. Returns once stored. */
    suspend fun clearFilters() {}

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
 * One row of artboard 4d's panel.
 *
 * @property id opaque: what [SearchSource.selectSources] takes back. Stable across reads.
 * @property name display only.
 * @property detail the row's mono line, already resolved, like `EN · MOVIES, TV · PINNED`.
 * @property selected whether a search asks it.
 */
@Immutable
data class SearchSourceChoice(
    val id: String,
    val name: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val health: SearchSourceHealth = SearchSourceHealth.Unknown,
)

/** The row's trailing marker. `.sg`, `.sg.ok`, `.sg.fail`: filled, filled in ink, hollow. */
enum class SearchSourceHealth { Unknown, Ok, Failed }

/**
 * One type chip: `TV Search` 4a's row, and what `:app` maps each onto.
 *
 * Restated rather than `TvType` for the reason [SearchResultItem] gives, and coarser, because a chip is
 * not always one type: Anime covers three. Order is the order they are drawn, the old screen's.
 */
enum class SearchTypeFilter {
    Movies, TvSeries, Anime, AsianDrama, Cartoons, Documentaries, Live, Torrents, Nsfw, Others,
}

/**
 * The chip row's model.
 *
 * @property available the chips drawn: the types some eligible provider serves. A chip that could never
 *   match is not drawn, which is what the old screen's `bindChips` did.
 * @property selected the lit ones. None lit means no narrowing at all.
 */
@Immutable
data class SearchTypeFilters(
    val available: List<SearchTypeFilter> = emptyList(),
    val selected: Set<SearchTypeFilter> = emptySet(),
)

/**
 * What a search over many providers came back with.
 *
 * [Partial] is the case the old UI had no way to express: `SearchViewModel` searched every provider
 * and a failure was a vanished `Toast`, which is the defect
 * `.claude/rules/mvi-viewmodel.md` cites. Here the shortfall is data, so a screen must decide what
 * to do with it.
 */
sealed interface SearchOutcome {
    /**
     * Every provider answered. [items] may still be empty — that is a successful empty search.
     *
     * @property morePages the providers with another page to give.
     */
    data class Complete(
        val items: List<SearchResultItem>,
        val morePages: Set<String> = emptySet(),
    ) : SearchOutcome

    /**
     * Some providers answered and some did not.
     *
     * @property items what did arrive, which is usable and should be shown.
     * @property answered how many providers answered.
     * @property total how many were asked.
     * @property detail machine-readable shortfall for the banner's mono line — a provider name, a
     *   host, a status code. This is the field that replaces a lost `Toast`.
     * @property morePages the providers with another page to give.
     */
    data class Partial(
        val items: List<SearchResultItem>,
        val answered: Int,
        val total: Int,
        val detail: String,
        val morePages: Set<String> = emptySet(),
    ) : SearchOutcome

    /** Nothing answered. [detail] is machine-readable, for the error state's mono line. */
    data class Failed(val detail: String) : SearchOutcome

    /** No network at all. Distinct from [Failed] because the user's next move differs. */
    data object Offline : SearchOutcome
}
