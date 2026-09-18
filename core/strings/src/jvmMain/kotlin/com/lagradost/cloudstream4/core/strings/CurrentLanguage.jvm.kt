package com.lagradost.cloudstream4.core.strings

import java.util.Locale

/** Compose's `getSystemEnvironment()` on the JVM reads the same default. */
internal actual fun currentLanguage(): String = Locale.getDefault().language
