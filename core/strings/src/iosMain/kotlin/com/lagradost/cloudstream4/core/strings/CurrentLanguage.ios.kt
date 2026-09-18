package com.lagradost.cloudstream4.core.strings

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages

/** The order Compose's `getSystemEnvironment()` uses on iOS: the first preferred language, then the current locale. */
internal actual fun currentLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.let { NSLocale(it).languageCode }
        ?: NSLocale.currentLocale.languageCode
