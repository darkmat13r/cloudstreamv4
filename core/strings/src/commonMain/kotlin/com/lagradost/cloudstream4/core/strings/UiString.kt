package com.lagradost.cloudstream4.core.strings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * A piece of user-visible text a ViewModel can hold without a `Context`, a `@StringRes Int` or a platform
 * (migration plan §7 Phase 3 S-b). It is what `com.lagradost.cloudstream3.utils.UiText` becomes: state and effects
 * carry a `UiString`, and only the UI turns it into characters, in the composition's locale.
 *
 * ```kotlin
 * data class SearchState(val error: UiString? = null)
 * viewModel.state.error = UiString.Resource(Res.string.error_loading_links)
 * // in a composable
 * Text(state.error.resolve())
 * ```
 *
 * ## Resolving
 *
 * - [resolve] inside a composition. On Android that follows `Locale.current`, so it re-reads after
 *   `CommonActivity.setLocale` + `recreate()`.
 * - [load] outside one. It uses the **process default locale**, not the in-app one, so a service, a worker or a
 *   notification builder that must honour the in-app language keeps `context.getString(R.string.x)`.
 *
 * Both fetch the raw pattern from Compose and format it with [UiStringFormat]; see that class for why Compose's own
 * `stringResource(res, *args)` overloads must never be used.
 *
 * ## Equality
 *
 * All three are data classes, so a `UiString` in a state object compares structurally and a recomposition is not
 * triggered by an equal one. `StringResource.equals` compares only `"string:<name>"` with no module, so two
 * resources of the *same name* in different modules are equal — never rely on that across modules, and consolidate
 * `:shared`'s strings into `:core:strings` instead.
 */
sealed interface UiString {
    /** Text that is already in its final form: a title from a provider, a username, a formatted duration. */
    data class Raw(val value: String) : UiString

    /** A `<string>`, with the arguments its pattern takes. `args` is empty for a string with no `%` placeholders. */
    data class Resource(val res: StringResource, val args: List<Any> = emptyList()) : UiString

    /**
     * A `<plurals>` bag. [quantity] picks the item and, by default, is also its only argument — the Android idiom,
     * where `%d episodes` reads the same number it was selected by.
     */
    data class Plural(
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<Any> = listOf(quantity),
    ) : UiString
}

/** This text in the composition's locale. Use [load] outside a composition. */
@Composable
fun UiString.resolve(): String = when (this) {
    is UiString.Raw -> value
    is UiString.Resource -> UiStringFormat.format(stringResource(res), args)
    is UiString.Plural -> UiStringFormat.format(pluralPattern(res, quantity), args)
}

/** This text in the process default locale. Use [resolve] inside a composition. */
suspend fun UiString.load(): String = when (this) {
    is UiString.Raw -> value
    is UiString.Resource -> UiStringFormat.format(getString(res), args)
    is UiString.Plural -> UiStringFormat.format(loadPluralPattern(res, quantity), args)
}
