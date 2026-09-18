package strings

/**
 * The environment Compose Multiplatform builds from `Locale.current`: language, script and region as the platform
 * reports them (`ResourceEnvironment.kt:45-63`). On Android, `Locale("zh", "TW")` has an empty script and API < 35
 * reports `iw`/`in` where JDK 17+, iOS and API 35+ (targetSdk >= 35) report `he`/`id`.
 */
data class ComposeEnvironment(val language: String, val script: String, val region: String)

/**
 * Port of Compose Multiplatform 1.12.0 `Resource.getResourceItemByEnvironment` for items that carry only locale
 * qualifiers: `filterByLocale` (`components-resources` `ResourceEnvironment.kt:182-219`) with `filterBy`
 * (`:112-127`). The generator uses it to decide which values must be copied into a directory; the jvmTest checks the
 * real runtime, so a drift between this port and the runtime turns the parity test red rather than going unnoticed.
 *
 * Returns the chosen item, or null when nothing matches. Two or more survivors are a runtime error
 * ("has more than one file"), reported through [ambiguous].
 */
internal fun <T> selectCompose(
    environment: ComposeEnvironment,
    candidates: List<Pair<T, LocaleQualifier>>,
    ambiguous: (List<T>) -> Unit = {},
): T? {
    val noLocale = candidates.filter { it.second.isDefault }
    val withLanguage = candidates.filter { !it.second.isDefault && it.second.language == environment.language }
    val result = when {
        withLanguage.isEmpty() -> noLocale
        else -> {
            val withScript = withLanguage.filter {
                it.second.script.isNotEmpty() && it.second.script == environment.script
            }
            val byScriptAndRegion = withScript.filterByRegion(environment.region)
            val withDefaultScript = withLanguage.filter { it.second.script.isEmpty() }
            val byDefaultScriptAndRegion = withDefaultScript.filterByRegion(environment.region)
            when {
                byScriptAndRegion.isNotEmpty() -> byScriptAndRegion
                byDefaultScriptAndRegion.isNotEmpty() -> byDefaultScriptAndRegion
                environment.script.isEmpty() && withLanguage.filterByRegion(environment.region).isNotEmpty() ->
                    withLanguage.filterByRegion(environment.region)
                else -> noLocale
            }
        }
    }
    if (result.size > 1) ambiguous(result.map { it.first })
    return result.singleOrNull()?.first
}

/** `filterBy(qualifier)`: items with exactly this region, else items with no region qualifier at all. */
private fun <T> List<Pair<T, LocaleQualifier>>.filterByRegion(region: String): List<Pair<T, LocaleQualifier>> {
    val withRegion = filter { it.second.region.isNotEmpty() && it.second.region == region }
    return withRegion.ifEmpty { filter { it.second.region.isEmpty() } }
}
