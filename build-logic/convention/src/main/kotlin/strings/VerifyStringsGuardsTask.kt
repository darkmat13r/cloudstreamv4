package strings

import com.android.build.api.variant.Aapt2
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Proves the generator's guards on inputs that must not pass: every directory under [cases] is one case with a
 * `res/` tree and an `expect.txt` naming the [FindingKind] it must raise (`kind=UNICODE_WHITESPACE`) and, optionally,
 * text the failure message must contain (`message=…`). Optional `visible-donottranslate.txt` and
 * `parity-requests.txt` next to them are used as the generator's inputs, with [localeCopies].
 *
 * Each case runs through the real pipeline and aapt2 twice:
 * - `fail` mode must throw, naming the kind;
 * - `report` mode must throw for [Severity.BROKEN] kinds and must complete with the finding recorded otherwise.
 *
 * A case whose `expect.txt` says `check=roundtrip` instead is the opposite shape — an input that must pass — and
 * runs [runRoundTrip] in both modes rather than the pipeline.
 */
@CacheableTask
abstract class VerifyStringsGuardsTask : DefaultTask() {
    @get:Nested
    abstract val aapt2: Property<Aapt2>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cases: DirectoryProperty

    /** As `GenerateComposeStringsTask.localeCopies`. */
    @get:Input
    abstract val localeCopies: MapProperty<String, String>

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun verify() {
        val executable = aapt2.get().executable.get().asFile
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        cases.get().asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { case ->
            val expect = File(case, "expect.txt").readLines().filter { '=' in it }.associate {
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            }
            if (expect["check"] == "roundtrip") {
                for (mode in GuardMode.entries) {
                    val (summary, problems) = runRoundTrip(case, mode, executable, expect)
                    lines += summary
                    problems.take(3).forEach { lines += "    | $it" }
                    if (problems.isNotEmpty()) failures += "$summary\n" + problems.joinToString("\n")
                }
                return@forEach
            }
            val kind = FindingKind.valueOf(expect.getValue("kind"))
            val message = expect["message"]
            for (mode in GuardMode.entries) {
                val outcome = runCase(case, mode, executable)
                val thrown = outcome.first
                val recorded = outcome.second.any { it.kind == kind }
                val mustThrow = mode == GuardMode.FAIL || kind.severity == Severity.BROKEN
                val ok = when {
                    mustThrow -> thrown != null && kind.name in thrown && (message == null || message in thrown)
                    else -> thrown == null && recorded
                }
                val summary = "${case.name} [${mode.name.lowercase()}] expect ${kind.name}: " +
                    (if (thrown != null) "threw" else "completed") +
                    ", recorded=$recorded -> ${if (ok) "OK" else "WRONG"}"
                lines += summary
                thrown?.lines()?.filter { it.isNotBlank() }?.take(3)?.forEach { lines += "    | $it" }
                if (!ok) failures += "$summary\n${thrown ?: outcome.second.joinToString("\n")}"
            }
        }
        resultFile.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        lines.forEach { logger.lifecycle(it) }
        if (failures.isNotEmpty()) throw GradleException("strings guard fixtures:\n" + failures.joinToString("\n\n"))
    }

    /**
     * A `check=roundtrip` case: proves [AndroidStringsXml] is a total inverse of aapt2's decode over the case's res.
     * The res is compiled, every decoded text is written straight back through the encoder, the result is compiled
     * again, and the two decoded tables are compared value by value — so the only thing under test is the encoder.
     *
     * A reference or an unsupported value has no text to re-encode and is counted instead; `values=` and `skipped=`
     * in `expect.txt` pin both counts, so the case cannot quietly stop covering a hazard. A styled value is
     * round-tripped as its plain text, which is what the generator already emits, and the comparison requires the
     * re-decoded value to be plain text: a quoted value must never come back styled or as a reference.
     */
    private fun runRoundTrip(
        case: File,
        mode: GuardMode,
        executable: File,
        expect: Map<String, String>,
    ): Pair<String, List<String>> {
        val findings = Findings(mode)
        val res = File(case, "res")
        val sources = res.walkTopDown().filter { it.isFile && it.extension == "xml" }
            .map { SourceFile(it.relativeTo(res).invariantSeparatorsPath, it.readBytes()) }
            .sortedBy { it.location }.toList()
        val work = File(temporaryDir, "${case.name}-${mode.name.lowercase()}")
        val compiler = Aapt2Compiler(exec, executable, work, findings)
        val skipped = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val decoded = compiler.compile(
            sources.associate { it.location to ValuesSanitizer.sanitise(it.location, it.bytes, findings).text },
        )
        val before = decoded.mapValues { (location, entries) ->
            entries.mapNotNull { entry ->
                val text = roundTrippable(entry.value)
                if (text == null) skipped += "$location ${entry.type}/${entry.name}"
                text?.let { ResourceKey(entry.type, entry.name) to it }
            }.toMap()
        }
        val after = compiler.compile(before.mapValues { AndroidStringsXml.write(it.value) })
            .mapValues { (_, entries) -> entries.associate { ResourceKey(it.type, it.name) to it.value } }
        before.forEach { (location, expected) ->
            val actual = after[location].orEmpty()
            (expected.keys - actual.keys).forEach { problems += "$location $it: lost by the round trip" }
            (actual.keys - expected.keys).forEach { problems += "$location $it: invented by the round trip" }
            expected.forEach { (key, value) ->
                val back = plainTextValue(actual[key] ?: return@forEach)
                if (back != value) problems += "$location $key: ${describe(value)} -> ${describe(back)}"
            }
        }
        findings.all.filter { it.kind.severity == Severity.GUARD }.forEach { problems += "guard fired: $it" }
        val values = before.values.sumOf { it.size }
        expect["values"]?.toInt()?.takeIf { it != values }
            ?.let { problems += "expect.txt says values=$it, the case round-tripped $values" }
        expect["skipped"]?.toInt()?.takeIf { it != skipped.size }
            ?.let { problems += "expect.txt says skipped=$it, the case skipped ${skipped.size}: ${skipped.take(8)}" }
        val summary = "${case.name} [${mode.name.lowercase()}] roundtrip $values values in ${before.size} files, " +
            "${skipped.size} skipped -> ${if (problems.isEmpty()) "OK" else "WRONG"}"
        return summary to problems
    }

    /** aapt2's decoded value as text, or null when there is none: a reference, `@null`, or an unsupported item. */
    private fun roundTrippable(value: DecodedValue): TextValue? = when (value) {
        is DecodedValue.Single -> text(value.item)?.let { TextValue.Single(it) }
        is DecodedValue.Plural -> value.items
            .map { (category, item) -> category to (text(item) ?: return null) }
            .let { TextValue.Plural(it.toMap()) }
        is DecodedValue.Other -> null
    }

    /** As [roundTrippable], but a styled or referenced value is a mismatch rather than a skip. */
    private fun plainTextValue(value: DecodedValue): TextValue? = when (value) {
        is DecodedValue.Single -> (value.item as? DecodedItem.Text)?.let { TextValue.Single(it.text) }
        is DecodedValue.Plural -> value.items
            .map { (category, item) -> category to (item as? DecodedItem.Text ?: return null).text }
            .let { TextValue.Plural(it.toMap()) }
        is DecodedValue.Other -> null
    }

    private fun text(item: DecodedItem): String? = when (item) {
        is DecodedItem.Text -> item.text
        is DecodedItem.Styled -> item.text
        else -> null
    }

    private fun describe(value: TextValue?): String = when (value) {
        null -> "<not plain text>"
        is TextValue.Single -> Tsv.escape(value.text)
        is TextValue.Plural -> value.items.entries.joinToString(" ") { "${it.key}=${Tsv.escape(it.value)}" }
    }

    private fun runCase(case: File, mode: GuardMode, executable: File): Pair<String?, List<Finding>> {
        val findings = Findings(mode)
        val res = File(case, "res")
        val sources = res.walkTopDown().filter { it.isFile && it.extension == "xml" }
            .map { SourceFile(it.relativeTo(res).invariantSeparatorsPath, it.readBytes()) }.toList()
        val work = File(temporaryDir, "${case.name}-${mode.name.lowercase()}")
        val compiler = Aapt2Compiler(exec, executable, work, findings)
        fun optional(name: String) = File(case, name).takeIf { it.isFile }?.readText() ?: ""
        return try {
            StringsPipeline(findings, compiler).run(
                sources,
                StringsOracles.parseAllowlist(optional("visible-donottranslate.txt")),
                StringsOracles.parseRequests(optional("parity-requests.txt")),
                localeCopies.get(),
            )
            findings.throwIfGuardsFailed()
            null to findings.all
        } catch (e: StringsBuildException) {
            (e.message ?: "") to findings.all
        }
    }
}
