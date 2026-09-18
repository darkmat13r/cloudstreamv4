package com.lagradost.cloudstream4.core.strings

/**
 * The language Compose Multiplatform will resolve resources in, as a bare code (`en`, `apc`, `pt`).
 *
 * It exists for [CmpPluralGuard] alone, which has to know the language *before* it asks Compose for a plural — see
 * that object for why it cannot simply catch. Compose's own `ResourceEnvironment.language` is `internal`, and
 * `androidx.compose.ui.text.intl.Locale` is not on this module's compile classpath: `components-resources-desktop`
 * declares no dependencies, so Compose UI reaches neither `commonMain` nor the `jvm` canary. Three one-line actuals
 * are cheaper than putting Compose UI on the classpath of every future consumer of `:core:strings`.
 *
 * Each actual reads what that platform's `getSystemEnvironment()` reads, so the guard and Compose agree on the
 * language. Android may return the legacy `iw`/`in` codes; the generated resources carry `values-b+iw` and
 * `values-b+in` as well as the `values-b+he` / `values-b+id` copies, so either resolves.
 */
internal expect fun currentLanguage(): String
