package com.lagradost.cloudstream4.compose

/**
 * The placeholder a preference subtitle template uses for its current value.
 *
 * It is `%s` because that is what every template in the tree already writes — the defaults in the
 * vendored Mihon preference types and the four `:app` settings screens that override them. The
 * spelling is kept rather than modernised so no caller has to change.
 */
const val VALUE_PLACEHOLDER: String = "%s"

/**
 * Substitutes [value] for the first [VALUE_PLACEHOLDER] in this template.
 *
 * ## Why this exists rather than `String.format`
 *
 * `kotlin.text.format` is `java.lang.String.format` and is **JVM-only**, so the four call sites in
 * `:core:mihon` that used it made that module impossible to compile for Kotlin/Native and kept the
 * iOS canary red (`KMP-10`). The migration plan has named those four sites since §7 Phase 5.
 *
 * ## This is not a general-purpose formatter, deliberately
 *
 * It understands one placeholder and no flags, widths, precision or escaping, and it is named for
 * the one job it does so nobody reaches for it as a `String.format` replacement. That is safe here
 * because the whole corpus was measured first: **every template that reaches it is exactly one
 * `%s`** — either `"%s"` or `"%s\n" + description`. There is one `"%s %d"` in the tree, in
 * `:app`'s legacy `SettingsAccount.kt`, and it is a view-binding fragment that never reaches a
 * preference subtitle.
 *
 * A template with no placeholder is returned unchanged, which is what `String.format` did with a
 * surplus argument, so the subtitles that pass a plain string keep working.
 *
 * `null` renders as `"null"`, again matching `String.format("%s", null)`. Callers that want
 * something else should say so before calling.
 */
fun String.withValue(value: Any?): String = replaceFirst(VALUE_PLACEHOLDER, value.toString())
