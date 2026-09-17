package strings

/**
 * How content guards behave: `cloudstream.strings.guards=fail|report` (default `report`).
 *
 * - [FAIL] (pull requests: `pull_request.yml` passes it): any [Severity.GUARD] finding fails the build after every finding was
 *   collected, so one run lists all of them.
 * - [REPORT] (the default: local builds, IDE sync, pushes to `master` where Weblate commits through
 *   `update_locales.yml`, and any release build once `:app` depends on `:core:strings`): guard findings are written to
 *   `findings.tsv` and logged, and the build goes on. An entry with no trustworthy value (unresolvable reference,
 *   unsupported value, invalid name, unmodelled directory) is left out; an entry whose value is only suspect
 *   (Unicode whitespace, `&#13;`) keeps aapt2's value.
 *
 * [Severity.BROKEN] findings fail in both modes: they mean `:app` cannot build either, or that the generator cannot
 * produce a trustworthy result.
 */
enum class GuardMode {
    FAIL,
    REPORT,
    ;

    companion object {
        fun parse(value: String): GuardMode = when (value) {
            "fail" -> FAIL
            "report" -> REPORT
            else -> throw IllegalArgumentException(
                "cloudstream.strings.guards must be 'fail' or 'report', was '$value'",
            )
        }
    }
}

enum class Severity { BROKEN, GUARD, INFO }

enum class FindingKind(val severity: Severity, val summary: String) {
    AAPT2_FAILED(Severity.BROKEN, "aapt2 compile failed"),
    MALFORMED_XML(Severity.BROKEN, "values file is not well-formed UTF-8 XML"),
    DOCTYPE(Severity.BROKEN, "DOCTYPE/DTD in a values file (external entities are never resolved)"),
    DUPLICATE_DEFINITION(Severity.BROKEN, "same type and name defined twice in one directory"),
    SANITISER_MISMATCH(Severity.BROKEN, "stray text could not be removed without changing the document"),
    DECODE_COUNT_MISMATCH(Severity.BROKEN, "decoded entry count differs from the XML element count"),

    UNICODE_WHITESPACE(Severity.GUARD, "non-ASCII whitespace native aapt2 and AGP's aaptcompiler treat differently"),
    CR_CHARACTER_REFERENCE(Severity.GUARD, "&#13; character reference, whose value AGP's merger re-serialises"),
    UNMODELLED_QUALIFIER(Severity.GUARD, "values directory outside the modelled locale qualifiers"),
    AMBIGUOUS_LOCALE(Severity.GUARD, "two directories tie for a locale lookup"),
    UNRESOLVED_REFERENCE(Severity.GUARD, "reference that cannot be resolved to a string"),
    UNSUPPORTED_VALUE(Severity.GUARD, "value or element that is not a string or plurals text"),
    INVALID_NAME(Severity.GUARD, "resource name that is not a Kotlin identifier"),
    UNKNOWN_ALLOWLIST_NAME(Severity.GUARD, "visible-donottranslate name not in values/donottranslate-strings.xml"),
    PREDICTED_PARITY_GAP(Severity.GUARD, "a parity request expected to match is predicted to differ"),

    ORPHAN_KEY(Severity.INFO, "locale key that base does not define as a visible entry of the same type"),
    STRAY_TEXT_REMOVED(Severity.INFO, "stray text directly under <resources> removed, as AGP's merger does"),
    STYLED_AS_PLAIN_TEXT(Severity.INFO, "styled value emitted as plain text (Compose string resources have no spans)"),
    AAPT2_WARNING(Severity.INFO, "aapt2 warning"),
}

/** [location] is always relative to the res directory (`values-b+af/strings.xml`), never an absolute path. */
data class Finding(val kind: FindingKind, val location: String, val key: String, val message: String) {
    fun tsvRow(): String = listOf(kind.severity.name, kind.name, location, key, Tsv.escape(message)).joinToString("\t")

    override fun toString(): String = "${kind.name} $location${if (key.isEmpty()) "" else " $key"}: $message"
}

class StringsBuildException(message: String) : RuntimeException(message)

class Findings(val mode: GuardMode) {
    private val list = mutableListOf<Finding>()
    private val seen = HashSet<Finding>()

    val all: List<Finding> get() = list

    /** Records a finding once (repeated lookups report the same problem). BROKEN findings throw at once. */
    fun add(kind: FindingKind, location: String, key: String, message: String) {
        val finding = Finding(kind, location, key, message)
        if (!seen.add(finding)) return
        list += finding
        if (kind.severity == Severity.BROKEN) {
            throw StringsBuildException("${kind.summary}\n  $finding")
        }
    }

    /** Throws in [GuardMode.FAIL] when any guard fired. Call after the outputs were written. */
    fun throwIfGuardsFailed() {
        val guards = list.filter { it.kind.severity == Severity.GUARD }
        if (mode == GuardMode.FAIL && guards.isNotEmpty()) {
            throw StringsBuildException(
                buildString {
                    appendLine("${guards.size} strings content guard(s) fired (cloudstream.strings.guards=fail):")
                    guards.forEach { appendLine("  ${it.kind.summary}: $it") }
                    append("Only pull requests run with -Pcloudstream.strings.guards=fail; everywhere else these are reported.")
                },
            )
        }
    }

    fun tsv(): String = list.sortedWith(compareBy({ it.kind.severity }, { it.kind }, { it.location }, { it.key }))
        .joinToString("") { it.tsvRow() + "\n" }
}

/** TSV cell escaping shared by every oracle file: `\\`, `\n`, `\t`, `\r`. */
object Tsv {
    fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }
}
