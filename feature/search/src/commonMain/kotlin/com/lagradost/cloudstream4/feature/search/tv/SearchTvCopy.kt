package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * Every word the TV search screen puts on screen, supplied from outside.
 *
 * The same seam as `SearchPrompts`, for the same reason and with the same open question. This
 * module is `commonMain` with no string loader, `:core:designsystem` "never holds display
 * strings", and `docs/plans/kmp-migration.md` Phase 7 says a ViewModel exposes `UiString` and
 * never a resource id. So the strings are resolved by whoever mounts the screen — the Android
 * glue from `stringResource(...)`, a preview or a test from literals.
 *
 * **This is the second place the pattern appears and it is still awaiting the owner's view.** The
 * alternative is for `:feature:search` to depend on `:core:strings` and call `stringResource`
 * directly in `commonMain`, which is what `I18N-01` implies and which would make this type
 * unnecessary. That is a decision about the whole Phase 7 series — seven verticals — rather than
 * about this screen, so it is raised rather than taken here.
 *
 * ## The three lambdas are `@Composable`, and that is load-bearing
 *
 * [sourcesCount], [recentCount] and [resultCount] are functions because each is a **plural**, and
 * a plural is resolved per locale against a count. Handing the screen a finished string would push
 * `pluralStringResource` back into it; handing it a format string would hardcode where the number
 * goes. `:core:strings`'s plural guard exists because this is easy to get wrong.
 *
 * They are `@Composable` because on desktop that is the only way to resolve one at all. Compose
 * resources publishes exactly two accessors — `@Composable stringResource(...)` and
 * `suspend getString(...)` — with no synchronous path, so a plain `(Int) -> String` built outside
 * composition cannot produce "21 RESULTS" from `:core:strings`. The screen calls all three from
 * inside composition, so marking them costs nothing and makes the type work on both platforms.
 * On Android either shape would have worked, because `Context.resources` is synchronous; that
 * asymmetry is the whole reason this seam is still an open question (see [SearchPrompts]).
 */
@Immutable
data class SearchTvCopy(
    /** The field's placeholder with nothing typed. Artboard 4c draws "Search all sources". */
    val fieldPlaceholder: String,
    /** The chrome row's source chip label — artboard 4a's "Sources". */
    val sourcesLabel: String,
    /** The chip's count, e.g. "12 / 48". Both numbers, because the ratio is the point. */
    val sourcesCount: @Composable (selected: Int, total: Int) -> String,
    /** Artboard 4c's rail header: "Recent searches". */
    val recentHeadline: String,
    /** 4c's header count, e.g. "8 SAVED". */
    val recentCount: @Composable (Int) -> String,
    /** 4c's foot row: "Clear all searches". */
    val clearAll: String,
    /** A provider rail's header count — artboard 4b's "21 RESULTS". */
    val resultCount: @Composable (Int) -> String,
)
