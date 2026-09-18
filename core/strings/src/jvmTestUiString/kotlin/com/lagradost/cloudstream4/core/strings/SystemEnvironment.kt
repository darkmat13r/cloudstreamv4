package com.lagradost.cloudstream4.core.strings

import java.util.Locale
import org.jetbrains.compose.resources.ResourceEnvironment

/**
 * Runs a block as if the machine were in one locale, for both halves of what `UiString.load()` reads.
 *
 * Two things have to move together:
 * - `java.util.Locale.getDefault()`, which is what `currentLanguage()` reads on the JVM, and so what the plural
 *   guard reads;
 * - Compose's `getResourceEnvironment`, the `internal var` its own KDoc calls "overridden for tests". Its desktop
 *   actual asks skiko for the system theme, and skiko's native library does not load in this headless `jvmTest`
 *   JVM (`NoClassDefFoundError: SystemThemeHelper`), so every `getString(resource)` would fail for a reason that
 *   has nothing to do with strings. `ComposeRuntime` already builds environments reflectively for the same reason.
 */
internal object SystemEnvironment {
    private val field = Class.forName("org.jetbrains.compose.resources.ResourceEnvironmentKt")
        .getDeclaredField("getResourceEnvironment").apply { isAccessible = true }

    private var language = ""

    // The field holds `::getSystemEnvironment`, so it is typed `kotlin.reflect.KFunction`, not `Function0`:
    // only a callable reference can be written into it.
    private fun current(): ResourceEnvironment = ComposeRuntime.environment(language, "", "")

    fun <T> of(language: String, block: () -> T): T {
        val previousEnvironment = field.get(null)
        val previousLanguage = this.language
        val previousLocale = Locale.getDefault()
        this.language = language
        field.set(null, ::current)
        Locale.setDefault(Locale.forLanguageTag(language))
        try {
            return block()
        } finally {
            field.set(null, previousEnvironment)
            this.language = previousLanguage
            Locale.setDefault(previousLocale)
        }
    }
}
