package strings

import com.android.build.api.variant.Aapt2
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
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
 * `parity-requests.txt` next to them are used as the generator's inputs.
 *
 * Each case runs through the real pipeline and aapt2 twice:
 * - `fail` mode must throw, naming the kind;
 * - `report` mode must throw for [Severity.BROKEN] kinds and must complete with the finding recorded otherwise.
 */
@CacheableTask
abstract class VerifyStringsGuardsTask : DefaultTask() {
    @get:Nested
    abstract val aapt2: Property<Aapt2>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cases: DirectoryProperty

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
            )
            findings.throwIfGuardsFailed()
            null to findings.all
        } catch (e: StringsBuildException) {
            (e.message ?: "") to findings.all
        }
    }
}
