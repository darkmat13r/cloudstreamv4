package com.lagradost.cloudstream4.library

import android.content.Context
import android.content.res.Configuration
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen
import java.util.Locale

/*
 * What `AndroidLibraryStore` does to a plugin's text, for a tracking service's. **Repeated, not shared:** the store's
 * copies live in its companion object, and moving them out removes two lines (`MAX_TEXT_CHARS` and the companion's
 * synthetic accessor) from `classes-jar.api`, which may only grow. The day that dump allows it, these and the store's
 * become one.
 */

private const val MAX_TEXT_CHARS = 200

/** Without control or format characters, trimmed and capped, as the store's `cleanText`. */
internal fun String.sourceText(): String =
    filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(MAX_TEXT_CHARS)

/** An `http(s)` address with nothing in it that could break a request, as the store's `isPlainHttp`. */
internal fun String.isSourceUrl(): Boolean =
    none { it.isISOControl() || it.isWhitespace() } &&
        (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true))

internal fun String.isSourceHeaderSafe(): Boolean = none { it.isISOControl() }

/** [context] in the app's language. See `labelContext` in `ApiRepositorySearchSource` for why. */
@Suppress("DEPRECATION")
internal fun sourceContext(context: Context): Context {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.getDefault())
    return context.createConfigurationContext(configuration)
}

/** `SettingsProvidersScreen.toStringRes()` in the app's language, as the store's `typeWords`. */
@Suppress("DEPRECATION")
internal fun sourceTypeWords(context: Context): (TvType) -> String {
    val localized = sourceContext(context)
    return { type -> localized.getString(with(SettingsProvidersScreen) { type.toStringRes() }) }
}
