package com.lagradost.cloudstream4.search

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.feature.search.SearchPrompts
import com.lagradost.cloudstream4.feature.search.tv.SearchTvCopy
import java.util.Locale

/*
 * The Android end of `:feature:search`'s two copy seams.
 *
 * `:feature:search` is `commonMain` with no string loader, so every user-facing word is injected —
 * see the KDoc on `SearchPrompts` and `SearchTvCopy` for why that seam exists and what it is still
 * waiting on. This file is the only place in the search vertical that names a resource id.
 *
 * Two seams, two resolution routes, because they are constructed in different places.
 * `SearchTvCopy` is handed to a composable, so `rememberSearchTvCopy` resolves it in composition
 * from `LocalContext` and holds no `Context` in a field. `SearchPrompts` is built for
 * `SearchViewModel`'s constructor, outside composition and outliving the Activity, so it can
 * neither capture `LocalContext` nor call `stringResource`: `AndroidSearchPrompts` takes a
 * `Context`, keeps only the application one, and re-derives a `Resources` per call.
 *
 * Both are `internal`, because nothing outside `:app` constructs either. That keeps them out of
 * the *Kotlin* API an extension author can resolve, not out of `app/api/classes-jar.api` — Kotlin
 * compiles an `internal` class to `ACC_PUBLIC` and `ExtensionApiDump` reads access flags, so this
 * file still moves that dump and `./gradlew :app:dumpExtensionApi` has to be re-run.
 */

/**
 * Every non-content state's copy, resolved against the user's locale at the moment it is asked for.
 *
 * ## Why a `Resources` per call rather than one held field
 *
 * This object is owned by a `ViewModel`, so it outlives the Activity and must not hold it. The
 * application `Context` is the leak-free one, but on its own it is also the *wrong* one: the in-app
 * language is applied by `CommonActivity.setLocale` (`CommonActivity.kt:220-236`), which calls
 * `Locale.setDefault` and then `resources.updateConfiguration` **on the context it is handed** —
 * which is always an Activity (`MainActivity.kt:483`, `:1201`, `:2048`). The application context's
 * own configuration never learns about the choice, so resolving from `appContext.resources`
 * directly gives a user with a non-system app language the wrong words.
 *
 * `Locale.setDefault` is the half of that pair that *is* process-wide, so [resources] rebuilds a
 * configuration context from it on every call. That is an allocation, and it is deliberate: a
 * prompt is built once per state transition — at most a handful per search — and caching it would
 * need either a locale-keyed field mutated from whichever dispatcher the search ran on, or a stale
 * answer after a language change. The allocation is cheaper than either.
 *
 * `CommonActivity.setLocale` carries its own `// FIXME this should be replaced`; when it becomes
 * `AppCompatDelegate.setApplicationLocales`, the per-app locale lands in the application context's
 * configuration and [resources] collapses to `appContext.resources`.
 *
 * @param onBrowse what [idle]'s action does. `SearchPrompts.idle()` takes no lambda — unlike every
 *   other method here, whose action is the ViewModel's own `Retry` — because the first-run empty
 *   state's move is to go and find something, which is navigation the ViewModel does not own.
 *   `StateAction` is required, so this is required too.
 */
internal class AndroidSearchPrompts(
    context: Context,
    private val onBrowse: () -> Unit,
) : SearchPrompts {

    private val appContext: Context = context.applicationContext

    private val resources: Resources
        get() {
            // setLocale rather than setLocales: `LocaleList` is API 24 and minSdk is 23, and a
            // single locale is all `Locale.setDefault` can have told us about anyway.
            val configuration = Configuration(appContext.resources.configuration)
            configuration.setLocale(Locale.getDefault())
            return appContext.createConfigurationContext(configuration).resources
        }

    /**
     * First run: nothing typed and no history.
     *
     * **This copy is not drawn.** `design/TV Search.dc.html` 4c draws the *populated* blank-query
     * case and the cluster has no artboard for a new install, so these three strings are written to
     * the voice of the ones that are — short headline, one sentence that says what will happen, an
     * action that is the move rather than a retry — and they match the `Idle` preview in
     * `SearchScreenTvPreviews.kt`, which is the only other place the state is spelled out.
     */
    override fun idle(): UiState.Empty = resources.let { res ->
        UiState.Empty(
            prompt = StatePrompt(
                headline = res.getString(R.string.search_state_idle_headline),
                body = res.getString(R.string.search_state_idle_body),
                action = StateAction(
                    label = res.getString(R.string.search_state_idle_action),
                    onActivate = onBrowse,
                ),
            ),
        )
    }

    /**
     * `design/TV Search Suggestions.dc.html` 8c.
     *
     * The body and the `detail` line are resolved from the same [queried] in one call, which is the
     * property that artboard's note is built on: *"The status line and the rows have to agree, and
     * they did not."* Nothing here can read a second, staler count.
     *
     * ## Three decisions worth stating
     *
     * **The number is a digit, not a word.** 8c reads *"All nine selected sources answered"*, and a
     * spelled-out numeral cannot be produced from an `Int` by a resource: it needs ICU's
     * `spellout-cardinal` rules, and in most languages the word then has to agree in gender and
     * case with the noun after it, which `%d` substitution cannot do. The artboard could spell it
     * because it was drawing a fixed 9. So the body is a plural over digits, and the `one` category
     * carries the whole re-worded sentence rather than *"All 1 selected sources"*.
     *
     * **[queried] can be 0, and then the plural is unusable.** `SearchViewModel` reads
     * `sources.selected`, which is `SearchSources()`'s default 0 until `SearchSource.sources()`
     * returns — a search that completes first renders *"All 0 selected sources answered"*. Below 1
     * the count is dropped and a count-free sentence is used instead; the detail line is dropped
     * with it, because a status line of zeroes claims the search never ran.
     *
     * **The action's label is 8c's, and it overstates what the lambda does.** 8c labels it "Search
     * everything" because its binding clears the provider *and* the type filter. The lambda handed
     * in is the ViewModel's `Retry`, which re-runs the same query over the same source set.
     * Reported rather than papered over with a weaker label.
     *
     * [query] is unused: 8c quotes no query. (8e does — *"none of them carry “dunnne”"* — but that
     * is the Quick Search panel, a different surface.) It stays in the signature because it is the
     * natural key for the state and because 8b's wording, if that screen is built on this seam,
     * needs it.
     */
    override fun noResults(query: String, queried: Int, onRetry: () -> Unit): UiState.Empty =
        resources.let { res ->
            val counted = queried >= 1
            UiState.Empty(
                prompt = StatePrompt(
                    headline = res.getString(R.string.search_state_no_results_headline),
                    body = if (counted) {
                        res.getQuantityString(R.plurals.search_state_no_results_body, queried, queried)
                    } else {
                        res.getString(R.string.search_state_no_results_body_unknown)
                    },
                    action = StateAction(
                        label = res.getString(R.string.search_state_no_results_action),
                        onActivate = onRetry,
                    ),
                ),
                detail = if (counted) {
                    res.getString(R.string.search_state_no_results_detail, queried, queried, 0, 0)
                } else {
                    null
                },
            )
        }

    /**
     * Every provider failed.
     *
     * No artboard draws it — 4b draws the *partial* case and 8c the empty one — so the copy follows
     * the design system's own error contract (`Design System v2.dc.html` §05: *"What failed + which
     * provider + Retry. Never a Toast."*). The "which provider" half is [detail], produced by
     * `:app`'s `SearchSource` and passed through verbatim: it is the line a user can paste into a
     * bug report, and translating a status code would make it worse. The body says nothing about
     * counts because this signature has none.
     */
    override fun failed(detail: String, onRetry: () -> Unit): UiState.Error = resources.let { res ->
        UiState.Error(
            prompt = StatePrompt(
                headline = res.getString(R.string.search_state_failed_headline),
                body = res.getString(R.string.search_state_failed_body),
                action = StateAction(
                    label = res.getString(R.string.search_state_retry),
                    onActivate = onRetry,
                ),
            ),
            detail = detail,
        )
    }

    /**
     * No network.
     *
     * `R.string.no_internet_connection` is the existing key for this and was not reused: it is
     * three paragraphs written for an `AlertDialog`, and `StatePrompt.body` is *"the 14sp
     * explanation. One or two sentences"* measured to `StateViewDefaults.MaxTextWidth`. The second
     * clause keeps the one piece of that copy a search user needs — downloads still work offline.
     */
    override fun offline(onRetry: () -> Unit): UiState.Offline = resources.let { res ->
        UiState.Offline(
            prompt = StatePrompt(
                headline = res.getString(R.string.search_state_offline_headline),
                body = res.getString(R.string.search_state_offline_body),
                action = StateAction(
                    label = res.getString(R.string.search_state_retry),
                    onActivate = onRetry,
                ),
            ),
        )
    }

    /**
     * `design/TV Search.dc.html` 4b: "3 sources failed" / "TIMEOUT 10S" / "Retry failed".
     *
     * The headline counts the *shortfall*, `total - answered`, because that is the number the
     * artboard prints and the one the user acts on; [answered] and [total] arrive separately
     * because `SearchOutcome.Partial` carries them that way. Clamped at 0: a provider set that
     * shrinks mid-search could otherwise produce "-1 sources failed".
     *
     * "Retry failed" is 4b's label and is left count-free on purpose, which costs nothing here —
     * `SearchAction.Retry` re-runs the whole query, so 8a's count-bound *"Retry 3 sources"* would
     * be a promise this seam cannot keep.
     */
    override fun partial(
        answered: Int,
        total: Int,
        detail: String,
        onRetry: () -> Unit,
    ): FailureNotice = resources.let { res ->
        val failed = (total - answered).coerceAtLeast(0)
        FailureNotice(
            headline = res.getQuantityString(R.plurals.search_state_partial_headline, failed, failed),
            detail = detail,
            retry = StateAction(
                label = res.getString(R.string.search_state_retry_failed),
                onActivate = onRetry,
            ),
        )
    }
}

/**
 * The TV screen's own words: `design/TV Search.dc.html` 4a and 4c.
 *
 * Resolved in composition rather than through [AndroidSearchPrompts], because the screen is handed
 * this directly and composition already re-reads string resources when the configuration changes.
 *
 * The three count lambdas capture a `Resources` and resolve a plural per call, which is what
 * `SearchTvCopy`'s KDoc asks for: `pluralStringResource` is `@Composable` and so cannot be called
 * from inside a lambda the screen invokes while laying out a rail header.
 *
 * ## Why the mono counts are authored in capitals
 *
 * "8 SAVED" and "21 RESULTS" are typed in capitals on the artboard — there is no
 * `text-transform` anywhere in `TV Search.dc.html`, so the capitals are the string and not a
 * treatment. They stay in the resource rather than becoming a `.uppercase()` here: that call
 * takes the default locale's casing rules, which turns `i` into `İ` in Turkish and mangles a
 * final sigma in Greek, and several scripts the app ships have no case at all. Casing is the
 * translator's to decide per locale, which is only possible if the capitals live in the value.
 *
 * ## The remember keys
 *
 * Keyed on the four finished strings rather than on `LocalConfiguration`, which is what makes an
 * in-place locale change work. `CommonActivity.setLocale` mutates a `Configuration` it already
 * owns, so a configuration instance is not reliably a new value afterwards — but `stringResource`
 * re-resolves regardless, so the moment the words change the keys change, the lambdas are rebuilt,
 * and the `Resources` they capture now answers in the new locale.
 */
@Composable
internal fun rememberSearchTvCopy(): SearchTvCopy {
    val resources = LocalContext.current.resources
    val fieldPlaceholder = stringResource(R.string.search_tv_field_placeholder)
    val sourcesLabel = stringResource(R.string.search_tv_sources_label)
    val recentHeadline = stringResource(R.string.search_tv_recent_headline)
    val clearAll = stringResource(R.string.search_tv_clear_all)
    return remember(resources, fieldPlaceholder, sourcesLabel, recentHeadline, clearAll) {
        SearchTvCopy(
            fieldPlaceholder = fieldPlaceholder,
            sourcesLabel = sourcesLabel,
            // Selects on `total`: the ratio has no noun in English, and 4d's longer form of the
            // same fact — "12 OF 48 ENABLED" — agrees with the second number, not the first.
            sourcesCount = { selected, total ->
                resources.getQuantityString(
                    R.plurals.search_tv_sources_count,
                    total,
                    selected,
                    total,
                )
            },
            recentHeadline = recentHeadline,
            recentCount = { count ->
                resources.getQuantityString(R.plurals.search_tv_recent_count, count, count)
            },
            clearAll = clearAll,
            resultCount = { count ->
                resources.getQuantityString(R.plurals.search_tv_result_count, count, count)
            },
        )
    }
}
