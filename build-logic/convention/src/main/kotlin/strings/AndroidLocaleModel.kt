package strings

/**
 * The locale part of a values directory: `values` (all empty), `values-fr`, `values-pt-rBR`, `values-b+zh+Hant+TW`.
 * Language is lower case, script title case, region upper case (alpha-2 only).
 */
data class LocaleQualifier(val language: String, val script: String, val region: String) {
    val isDefault: Boolean get() = language.isEmpty()

    /** The BCP 47 tag Android is asked for when this directory's own locale is the device locale. */
    val bcp47: String get() = listOf(language, script, region).filter { it.isNotEmpty() }.joinToString("-")

    companion object {
        private val LANGUAGE = Regex("[a-z]{2,3}")
        private val SCRIPT = Regex("[A-Z][a-z]{3}")
        private val REGION = Regex("[A-Z]{2}")

        /**
         * Parses the directory names both Android and Compose Multiplatform 1.12.0 read the same way
         * (`GeneratedResClassSpec.kt` `addQualifiers`/`expandBcpQualifier`), or returns null for anything else:
         * other qualifiers (`-night`, `-v21`), numeric regions (CMP keeps only the last two digits of `r419`),
         * variants and extensions.
         */
        fun parseDirectory(name: String): LocaleQualifier? {
            if (name == "values") return LocaleQualifier("", "", "")
            val qualifier = name.removePrefix("values-").takeIf { it != name } ?: return null
            if (qualifier.startsWith("b+")) {
                val tags = qualifier.removePrefix("b+").split('+')
                val language = tags[0].takeIf { it.matches(LANGUAGE) } ?: return null
                var script = ""
                var region = ""
                for (tag in tags.drop(1)) when {
                    script.isEmpty() && region.isEmpty() && tag.matches(SCRIPT) -> script = tag
                    region.isEmpty() && tag.matches(REGION) -> region = tag
                    else -> return null
                }
                return LocaleQualifier(language, script, region)
            }
            val parts = qualifier.split('-')
            val language = parts[0].takeIf { it.matches(LANGUAGE) } ?: return null
            return when {
                parts.size == 1 -> LocaleQualifier(language, "", "")
                parts.size == 2 && parts[1].startsWith('r') && parts[1].substring(1).matches(REGION) ->
                    LocaleQualifier(language, "", parts[1].substring(1))
                else -> null
            }
        }
    }
}

/**
 * A `ResTable_config` reduced to its locale fields, as AOSP `frameworks/base/libs/androidfw/ResourceTypes.cpp`
 * (main) compares them. Line numbers below refer to that file.
 *
 * [scriptWasComputed] is `localeScriptWasComputed`: false for a resource directory (aapt2 stores the script only when
 * the directory names one), true for a request whose tag had no script (`setBcp47Locale`, :3342-3366).
 */
internal data class AndroidConfig(
    val language: String,
    val script: String,
    val region: String,
    val scriptWasComputed: Boolean,
) {
    private val hasLocale get() = language.isNotEmpty() || region.isNotEmpty()

    /** `ResTable_config::match` (:2909-2960), locale part: the only qualifiers these directories carry. */
    fun matches(request: AndroidConfig): Boolean {
        if (!hasLocale) return true
        if (!langsAreEquivalent(language, request.language)) return false
        var countriesMustMatch = false
        var script = script
        if (request.script.isEmpty()) {
            countriesMustMatch = true
        } else if (this.script.isEmpty() && !scriptWasComputed) {
            script = AndroidLocaleData.computeScript(language, region)
            if (script.isEmpty()) countriesMustMatch = true
        }
        return if (countriesMustMatch) region.isEmpty() || region == request.region else script == request.script
    }

    /**
     * `ResTable_config::isBetterThan` (:2641-2907) with only locale qualifiers set: it returns
     * `isLocaleBetterThan` (:2520-2629) and `false` otherwise, because `requested` is non-null.
     * Variants and numbering systems are never set by [LocaleQualifier] or [AndroidRequest], so they compare equal.
     */
    fun isBetterThan(other: AndroidConfig, request: AndroidConfig): Boolean {
        if (!request.hasLocale) return false
        if (!hasLocale && !other.hasLocale) return false
        if (!langsAreEquivalent(language, other.language)) {
            // :2544-2574: one of the two has no language; en-US-like requests prefer the default directory.
            if (request.language == "en") {
                if (request.region == "US") {
                    return if (language.isNotEmpty()) {
                        region.isEmpty() || region == "US"
                    } else {
                        !(other.region.isEmpty() || other.region == "US")
                    }
                } else if (AndroidLocaleData.isCloseToUsEnglish(request.region)) {
                    return if (language.isNotEmpty()) {
                        AndroidLocaleData.isCloseToUsEnglish(region)
                    } else {
                        !AndroidLocaleData.isCloseToUsEnglish(other.region)
                    }
                }
            }
            return language.isNotEmpty()
        }
        // :2587-2593
        val regionComparison = AndroidLocaleData.compareRegions(
            region, other.region, request.language, request.script, request.region,
        )
        if (regionComparison != 0) return regionComparison > 0
        // :2617-2626: identical beats merely equivalent (tl vs fil).
        return language == request.language && other.language != request.language
    }

    companion object {
        /** `langsAreEquivalent` (:2514-2518): identical, or Tagalog and Filipino. */
        fun langsAreEquivalent(a: String, b: String): Boolean =
            a == b || (a == "tl" && b == "fil") || (a == "fil" && b == "tl")

        fun ofDirectory(qualifier: LocaleQualifier) =
            AndroidConfig(qualifier.language, qualifier.script, qualifier.region, scriptWasComputed = false)
    }
}

/**
 * The configuration Android resolves resources against for a single-locale device or app locale on API 24+.
 *
 * `ResourcesImpl.updateConfiguration` passes `adjustLanguageTag(locale.toLanguageTag())` to the AssetManager, which
 * maps `he` -> `iw`, `id` -> `in` and `yi` -> `ji`; `setBcp47Locale` then computes a missing script. A locale list
 * with more than one entry first goes through `LocaleList.getFirstMatchWithEnglishSupported`, which is not modelled
 * (Compose also reads only the first locale). API 23 devices (minSdk) use the pre-N matcher and can differ.
 */
internal object AndroidRequest {
    private val LEGACY_LANGUAGE = mapOf("he" to "iw", "id" to "in", "yi" to "ji")

    fun of(tag: String): AndroidConfig {
        val parts = tag.split('-')
        val language = parts[0].lowercase().let { LEGACY_LANGUAGE[it] ?: it }
        val script = parts.drop(1).firstOrNull { it.length == 4 }
            ?.let { it[0].uppercase() + it.substring(1).lowercase() } ?: ""
        val region = parts.drop(1).firstOrNull { it.length == 2 }?.uppercase() ?: ""
        return if (script.isEmpty()) {
            AndroidConfig(language, AndroidLocaleData.computeScript(language, region), region, scriptWasComputed = true)
        } else {
            AndroidConfig(language, script, region, scriptWasComputed = false)
        }
    }
}

/** Result of choosing among directories that define a resource. */
internal data class Selection<T>(val chosen: T?, val tiedWith: T?)

/**
 * Picks the directory Android reads a resource from: `AssetManager2::FindEntryInternal` / `ResTable::getEntry`
 * (:6728, :6776) keep the first matching configuration and replace it only when another one `isBetterThan` it.
 * A candidate that is neither better nor worse than the best is reported as a tie instead of silently depending on
 * the table's configuration order.
 */
internal fun <T> selectAndroid(request: AndroidConfig, candidates: List<Pair<T, AndroidConfig>>): Selection<T> {
    var best: Pair<T, AndroidConfig>? = null
    var tie: T? = null
    for (candidate in candidates) {
        if (!candidate.second.matches(request)) continue
        val current = best
        if (current == null || candidate.second.isBetterThan(current.second, request)) {
            best = candidate
            tie = null
        } else if (!current.second.isBetterThan(candidate.second, request) && current.second != candidate.second) {
            tie = candidate.first
        }
    }
    return Selection(best?.first, tie)
}
