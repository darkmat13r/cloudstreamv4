package strings

import com.android.build.api.variant.Aapt2
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Generates Compose Multiplatform string resources from Android `values…/strings.xml` (plan S1: Android res and
 * Weblate stay the source).
 *
 * 1. **Sanitise** each file ([ValuesSanitizer]): remove stray top-level text, reject DOCTYPE, no re-serialisation.
 * 2. **Compile** with AGP's own aapt2 (`aapt2 compile --legacy`, [Aapt2Compiler]); aapt2's decoded value is the
 *    oracle and Android escaping is never re-implemented.
 * 3. **Read** the compiled tables ([AaptContainerReader]) and check the entry count against the XML.
 * 4. **Resolve and materialise** ([AndroidResolver], [Materialiser]): references resolve in the requesting locale
 *    and values are copied into locale directories wherever Compose's fallback would otherwise differ from
 *    Android's (`ResTable_config::match` / `isLocaleBetterThan`, API 24+). Then [localeCopies] (`values-b+he`,
 *    `values-b+id`) are added as exact copies of `values-b+iw` / `values-b+in` ([LocaleCopies]).
 * 5. **Write** one `values…/strings.xml` per directory in Compose's escaping ([CmpStringsXml]) to [outputDir], and
 *    the oracles (`decoded.tsv`, `emitted.tsv`, `requests.tsv`, `lookup.tsv`, `findings.tsv`, `report.txt`) to
 *    [oracleDir] for the parity jvmTest.
 *
 * Content guards follow [guardMode] (`-Pcloudstream.strings.guards=fail|report`), see [GuardMode].
 */
@CacheableTask
abstract class GenerateComposeStringsTask : DefaultTask() {
    /** AGP's Maven aapt2 (`sdkComponents.aapt2`): its version is an input, the executable path is not. */
    @get:Nested
    abstract val aapt2: Property<Aapt2>

    /** The Android res directory. Only the files in [sourceFiles] are read. */
    @get:Internal
    abstract val resDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    val sourceFiles: FileTree
        get() = resDirectory.asFileTree.matching {
            include("values*/strings.xml", "values*/donottranslate-strings.xml")
        }

    /** Names of `values/donottranslate-strings.xml` that are user-visible text; the rest are keys and tags. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val visibleDoNotTranslate: RegularFileProperty

    /** Locale requests beyond the directories' own locales, see [ParityRequest]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val parityRequests: RegularFileProperty


    /**
     * Target -> source directory copies for languages Compose and Android name differently, see [LocaleCopies].
     * `cloudstream.kmp.strings` sets [LocaleCopies.DEFAULTS]; a target that exists in the res fails the build.
     */
    @get:Input
    abstract val localeCopies: MapProperty<String, String>

    /** `fail` or `report`. */
    @get:Input
    abstract val guardMode: Property<String>

    /** The materialised tree Compose packages: [leanDir] plus the copies that make its fallback match Android's. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * The **lean** tree — only what each directory declares, references resolved. This is the shape the authored
     * source is committed in and the shape `app/src/main/res` is written back in; see [PipelineResult.lean].
     */
    @get:OutputDirectory
    abstract val leanDir: DirectoryProperty

    /** Every `@string/…` reference the res holds, so the res can be written back as it was; [AuthoredAliases]. */
    @get:org.gradle.api.tasks.OutputFile
    abstract val aliasesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val oracleDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        val mode = GuardMode.parse(guardMode.get())
        val findings = Findings(mode)
        val sources = ArrayList<SourceFile>()
        sourceFiles.visit { if (!isDirectory) sources += SourceFile(relativePath.pathString, file.readBytes()) }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val lean = leanDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val oracles = oracleDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val aapt2 = aapt2.get()
        val compiler = Aapt2Compiler(exec, aapt2.executable.get().asFile, File(temporaryDir, "aapt2"), findings)

        val result = try {
            StringsPipeline(findings, compiler).run(
                sources,
                StringsOracles.parseAllowlist(visibleDoNotTranslate.get().asFile.readText()),
                StringsOracles.parseRequests(parityRequests.get().asFile.readText()),
                localeCopies.get(),
            )
        } catch (e: StringsBuildException) {
            File(oracles, "findings.tsv").writeText(findings.tsv())
            throw GradleException("generateComposeStrings: ${e.message}", e)
        }

        val emitted = result.emitted
        val leanTree = result.lean
        emitted.forEach { (directory, entries) ->
            File(out, "$directory/strings.xml").apply { parentFile.mkdirs() }.writeText(CmpStringsXml.write(entries))
        }
        aliasesFile.get().asFile.apply { parentFile.mkdirs() }.writeText(AuthoredAliases.format(result.aliases))
        leanTree.forEach { (directory, entries) ->
            File(lean, "$directory/strings.xml").apply { parentFile.mkdirs() }.writeText(CmpStringsXml.write(entries))
        }
        File(oracles, "decoded.tsv").writeText(StringsOracles.decoded(result.decoded))
        File(oracles, "emitted.tsv").writeText(StringsOracles.emitted(emitted))
        File(oracles, "requests.tsv").writeText(StringsOracles.requests(result.requests))
        File(oracles, "lookup.tsv").writeText(StringsOracles.lookup(result.lookup))
        File(oracles, "findings.tsv").writeText(findings.tsv())
        val report = buildList {
            add("aapt2 ${aapt2.version.get()} compile --legacy; guards=${guardMode.get()}")
            addAll(result.report)
            add("emitted directories: ${emitted.size}, values: ${emitted.values.sumOf { it.size }}")
            add("lean directories: ${leanTree.size}, values: ${leanTree.values.sumOf { it.size }}")
            add("@string references preserved: ${result.aliases.size}")
            findings.all.groupingBy { it.kind }.eachCount().toSortedMap()
                .forEach { (kind, n) -> add("finding $kind: $n") }
        }
        File(oracles, "report.txt").writeText(report.joinToString("\n", postfix = "\n"))
        report.forEach { logger.info(it) }
        findings.all.filter { it.kind.severity == Severity.GUARD }.forEach { logger.warn("w: strings guard: $it") }
        try {
            findings.throwIfGuardsFailed()
        } catch (e: StringsBuildException) {
            throw GradleException(e.message ?: "strings guards failed", e)
        }
    }
}
