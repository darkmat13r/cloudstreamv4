package strings

/**
 * Directories written as exact copies of another emitted directory, for languages Android and Compose name
 * differently (plan S2). Android looks `he` up as `iw` and `id` as `in` ([AndroidRequest], `adjustLanguageTag`), so
 * `values-b+iw` / `values-b+in` serve Hebrew and Indonesian users; Compose asks for the language the platform
 * reports, `he` / `id` on JDK 17+, iOS and Android API 35+ (targetSdk >= 35), and would fall back to English.
 *
 * - Each entry is target -> source directory (`values-b+he` -> `values-b+iw`, [DEFAULTS]); a module overrides them
 *   with `GenerateComposeStringsTask.localeCopies`.
 * - [plan] runs before aapt2: a target whose locale a source directory already has (`values-b+he` or `values-he`
 *   added to the res) fails the build in every guard mode ([FindingKind.LOCALE_COPY_COLLISION]); a copy never
 *   overwrites a translation.
 * - [apply] runs after materialisation: the target gets exactly what was emitted for the source, and joins the
 *   Compose lookup model, so `emitted.tsv`, the parity prediction and the jvmTest all see it. A source that is not
 *   a modelled directory (the fixtures have no `b+in`) is skipped with a report line.
 */
internal object LocaleCopies {
    val DEFAULTS: Map<String, String> = mapOf("values-b+he" to "values-b+iw", "values-b+id" to "values-b+in")

    data class Copy(
        val target: String,
        val targetLocale: LocaleQualifier,
        val source: String,
        val sourceLocale: LocaleQualifier,
    )

    fun plan(copies: Map<String, String>, sourceDirectories: List<String>, findings: Findings): List<Copy> {
        val planned = copies.toSortedMap().map { (target, source) ->
            val targetLocale = locale(target)
            val sourceLocale = locale(source)
            if (targetLocale == sourceLocale) {
                throw StringsBuildException("localeCopies: $target and $source are one locale")
            }
            Copy(target, targetLocale, source, sourceLocale)
        }
        planned.groupBy { it.targetLocale }.values.filter { it.size > 1 }.forEach { same ->
            throw StringsBuildException("localeCopies: ${same.joinToString { it.target }} are one locale")
        }
        planned.forEach { copy ->
            sourceDirectories.filter { LocaleQualifier.parseDirectory(it) == copy.targetLocale }.forEach { existing ->
                findings.add(
                    FindingKind.LOCALE_COPY_COLLISION, existing, "",
                    "localeCopies writes ${copy.target} as a copy of ${copy.source} and would overwrite this " +
                        "directory: remove ${copy.target} from localeCopies to ship it, or delete the directory",
                )
            }
        }
        return planned
    }

    fun apply(copies: List<Copy>, materialiser: Materialiser, qualifiers: Map<String, LocaleQualifier>): List<String> =
        copies.map { copy ->
            val source = qualifiers.entries.firstOrNull { it.value == copy.sourceLocale }?.key
            if (source == null) {
                "locale copy ${copy.target}: ${copy.source} is not among the sources, nothing written"
            } else {
                val values = materialiser.copyDirectory(copy.target, copy.targetLocale, source)
                "locale copy ${copy.target} <- $source: $values values"
            }
        }

    private fun locale(directory: String): LocaleQualifier =
        LocaleQualifier.parseDirectory(directory)?.takeUnless { it.isDefault }
            ?: throw StringsBuildException("localeCopies: '$directory' is not a modelled locale directory")
}
