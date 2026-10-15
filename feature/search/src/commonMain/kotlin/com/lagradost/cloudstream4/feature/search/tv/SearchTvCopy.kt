package com.lagradost.cloudstream4.feature.search.tv

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
 * ## The three lambdas
 *
 * [sourcesCount], [recentCount] and [resultCount] are functions because each is a **plural**, and
 * a plural is resolved per locale against a count. Handing the screen a finished string would push
 * `pluralStringResource` back into it; handing it a format string would hardcode where the number
 * goes. `:core:strings`'s plural guard exists because this is easy to get wrong.
 */
@Immutable
data class SearchTvCopy(
    /** The field's placeholder with nothing typed. Artboard 4c draws "Search all sources". */
    val fieldPlaceholder: String,
    /** The chrome row's source chip label — artboard 4a's "Sources". */
    val sourcesLabel: String,
    /** The chip's count, e.g. "12 / 48". Both numbers, because the ratio is the point. */
    val sourcesCount: (selected: Int, total: Int) -> String,
    /** Artboard 4c's rail header: "Recent searches". */
    val recentHeadline: String,
    /** 4c's header count, e.g. "8 SAVED". */
    val recentCount: (Int) -> String,
    /** 4c's foot row: "Clear all searches". */
    val clearAll: String,
    /** A provider rail's header count — artboard 4b's "21 RESULTS". */
    val resultCount: (Int) -> String,
)
