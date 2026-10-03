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
 * Proves the strings flip is loss-free before anything depends on it: the authored Compose tree, written back out
 * as Android `values…/strings.xml`, is read by aapt2 as the same values, and the **existing** forward generator run
 * over that Android tree reproduces the authored tree exactly.
 *
 * Four steps, all of them assertions:
 *
 * 1. **Authored tree.** [StringsPipeline] runs over [resDirectory] exactly as `GenerateComposeStringsTask` does;
 *    its emitted values are the authored tree. The `localeCopies` targets (`values-b+he`, `values-b+id`) are left
 *    out — they are Compose-only directories whose Android locales are `values-b+iw` / `values-b+in`, and feeding
 *    one back as a source directory is [FindingKind.LOCALE_COPY_COLLISION] by design. [additions] is then applied
 *    (see that file for why those values cannot be derived).
 * 2. **Re-encode and compile.** Every directory is written with [AndroidStringsXml] and compiled with the same
 *    `aapt2 compile --legacy` the pipeline uses. aapt2 errors are [Severity.BROKEN] and throw; an aapt2 **warning**
 *    is a failure here, because the authored tree is supposed to be the input aapt2 is happiest with.
 * 3. **Read back.** Every value of the recompiled table must be plain text and byte-equal to the authored value —
 *    not styled, not a reference, nothing lost, nothing invented.
 * 4. **Fixed point.** The forward generator runs again over the generated Android tree. It must emit every value
 *    from the directory that declares it ([CopyReason.OWN] and nothing else, [expectedOwn] of them), re-emit the
 *    authored Compose tree byte-for-byte, and resolve every parity request to the value [baselineLookup] froze
 *    before the flip. A [CopyReason.REFERENCE_IN_LOCALE] or [CopyReason.ANDROID_FALLBACK] in the second pass means
 *    a value is **missing** from the authored tree, and the lookup diff names it.
 *
 * The second pass gets an empty `visible-donottranslate` allowlist: the generated tree has no
 * `donottranslate-strings.xml`, so every visible key is a plain entry of `values/strings.xml`. It also inherits any
 * parity request whose directory emitted nothing and therefore does not exist in the generated tree (`values-arz`),
 * so the lookup table keeps the shape [baselineLookup] has.
 */
@CacheableTask
abstract class VerifyStringsRoundTripTask : DefaultTask() {
    /** AGP's Maven aapt2, as `GenerateComposeStringsTask.aapt2`. */
    @get:Nested
    abstract val aapt2: Property<Aapt2>

    /** The Android res directory the authored tree is derived from. Only [sourceFiles] is read. */
    @get:Internal
    abstract val resDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    val sourceFiles: FileTree
        get() = resDirectory.asFileTree.matching {
            include("values*/strings.xml", "values*/donottranslate-strings.xml")
        }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val visibleDoNotTranslate: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val parityRequests: RegularFileProperty

    /** Values the authored tree must carry that the forward pass does not emit; see the file's own header. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additions: RegularFileProperty

    /** `oracle-baseline/lookup.tsv`: what Android resolved for every request and key before the flip. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baselineLookup: RegularFileProperty

    /** As `GenerateComposeStringsTask.localeCopies`. */
    @get:Input
    abstract val localeCopies: MapProperty<String, String>

    /** `fail` or `report`, as `GenerateComposeStringsTask.guardMode`. */
    @get:Input
    abstract val guardMode: Property<String>

    /** The one [CopyReason.OWN] count the second pass must report, pinned in the build file. */
    @get:Input
    abstract val expectedOwn: Property<Int>

    /** Holds `res/` (the generated Android tree, for review) and `report.txt`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun verify() {
        val mode = GuardMode.parse(guardMode.get())
        val executable = aapt2.get().executable.get().asFile
        val lines = mutableListOf("aapt2 ${aapt2.get().version.get()} compile --legacy; guards=${guardMode.get()}")
        val problems = mutableListOf<String>()

        val forward = forwardPass(mode, executable)
        val authored = authoredTree(forward, problems, lines)
        val encoded = authored.mapValues { AndroidStringsXml.write(it.value) }
        writeRes(encoded)
        readBack(mode, executable, authored, encoded, problems, lines)
        link(executable, problems, lines)
        fixedPoint(mode, executable, forward, authored, encoded, problems, lines)

        lines += if (problems.isEmpty()) "round trip: OK" else "round trip: ${problems.size} problem(s)"
        File(outputDir.get().asFile, "report.txt")
            .writeText((lines + problems.map { "problem: $it" }).joinToString("\n", postfix = "\n"))
        lines.forEach { logger.lifecycle(it) }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "strings round trip:\n" + problems.take(PROBLEMS_SHOWN).joinToString("\n") { "  $it" } +
                    (if (problems.size > PROBLEMS_SHOWN) "\n  … ${problems.size - PROBLEMS_SHOWN} more" else "") +
                    "\nDo not relax the assertion: a missed fixed point means a value is missing from the authored " +
                    "tree. The full report is ${outputDir.get().asFile.resolve("report.txt")}.",
            )
        }
    }

    /** Step 1: the generator's own pass over `app/src/main/res`, whose emitted values are the authored tree. */
    private fun forwardPass(mode: GuardMode, executable: File): PipelineResult {
        val sources = ArrayList<SourceFile>()
        sourceFiles.visit { if (!isDirectory) sources += SourceFile(relativePath.pathString, file.readBytes()) }
        val findings = Findings(mode)
        val compiler = Aapt2Compiler(exec, executable, File(temporaryDir, "forward"), findings)
        return try {
            StringsPipeline(findings, compiler)
                .run(
                    sources,
                    StringsOracles.parseAllowlist(visibleDoNotTranslate.get().asFile.readText()),
                    StringsOracles.parseRequests(parityRequests.get().asFile.readText()),
                    localeCopies.get(),
                )
                .also { findings.throwIfGuardsFailed() }
        } catch (e: StringsBuildException) {
            throw GradleException("verifyStringsRoundTrip, forward pass: ${e.message}", e)
        }
    }

    private fun authoredTree(
        forward: PipelineResult,
        problems: MutableList<String>,
        lines: MutableList<String>,
    ): Map<String, Map<ResourceKey, TextValue>> {
        val copyTargets = localeCopies.get().keys
        val added = AuthoredAdditions.parse(additions.get().asFile.readText())
        val authored = AuthoredAdditions.apply(forward.emitted.filterKeys { it !in copyTargets }, added, problems)
        val values = authored.values.sumOf { it.size }
        lines += "authored tree: ${authored.size} directories, $values values " +
            "(${added.size} from ${additions.get().asFile.name}, ${copyTargets.sorted()} left to localeCopies)"
        if (values != expectedOwn.get()) {
            problems += "the authored tree holds $values values, expectedOwn is ${expectedOwn.get()}"
        }
        return authored
    }

    private fun writeRes(encoded: Map<String, String>) {
        val res = File(outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }, "res")
        encoded.forEach { (directory, text) ->
            File(res, "$directory/strings.xml").apply { parentFile.mkdirs() }.writeText(text, Charsets.UTF_8)
        }
    }

    /** Steps 2 and 3: compile the generated tree and compare every decoded value with the authored one. */
    private fun readBack(
        mode: GuardMode,
        executable: File,
        authored: Map<String, Map<ResourceKey, TextValue>>,
        encoded: Map<String, String>,
        problems: MutableList<String>,
        lines: MutableList<String>,
    ) {
        val findings = Findings(mode)
        val compiler = Aapt2Compiler(exec, executable, File(temporaryDir, "encoded"), findings)
        val decoded = compiler.compile(encoded.mapKeys { "${it.key}/strings.xml" })
        val warnings = findings.all.filter { it.kind == FindingKind.AAPT2_WARNING }
        warnings.forEach { problems += "aapt2 warning on the generated res: $it" }
        findings.all.filter { it.kind.severity == Severity.GUARD }
            .forEach { problems += "guard fired on the generated res: $it" }
        var compared = 0
        authored.forEach { (directory, expected) ->
            val location = "$directory/strings.xml"
            val actual = decoded[location].orEmpty().associate { ResourceKey(it.type, it.name) to it.value }
            (expected.keys - actual.keys).forEach { problems += "$location $it: lost by the round trip" }
            (actual.keys - expected.keys).forEach { problems += "$location $it: invented by the round trip" }
            expected.forEach { (key, value) ->
                val back = plainTextValue(actual[key] ?: return@forEach)
                compared++
                if (back != value) problems += "$location $key: ${describe(value)} -> ${describe(back)}"
            }
        }
        lines += "re-encoded and read back: $compared values in ${authored.size} files, " +
            "${warnings.size} aapt2 warnings"
    }

    /**
     * `aapt2 link` over the table [readBack] just compiled: the generated tree must be linkable, which `compile`
     * alone does not show — a duplicate entry across two files of one configuration, a configuration aapt2 refuses
     * to pack, or a table that overflows are all link-time errors. A synthetic manifest is enough and no
     * `android.jar` is needed, because the generated values hold no framework reference at all.
     *
     * Link is **not** the read-back oracle. `aapt2 dump resources` prints a value's own line breaks verbatim, so
     * its output cannot be parsed back into values; [readBack] compares the compiled table instead, through the
     * decode path the generator's own oracle uses.
     */
    private fun link(executable: File, problems: MutableList<String>, lines: MutableList<String>) {
        // Aapt2Compiler writes its zip as `compiled.zip` inside the work directory it is given.
        val compiled = File(temporaryDir, "encoded/compiled.zip")
        if (!compiled.isFile) {
            problems += "aapt2 compile left no table to link at $compiled"
            return
        }
        val manifest = File(temporaryDir, "link/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest " +
                    "xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$LINK_PACKAGE\" />\n",
            )
        }
        val apk = File(temporaryDir, "link/round-trip.apk").apply { delete() }
        val output = java.io.ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(
                executable.absolutePath, "link", "-o", apk.absolutePath,
                "--manifest", manifest.absolutePath, compiled.absolutePath,
            )
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val messages = output.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            .map { it.replace(temporaryDir.absolutePath, "<work>") }
        if (result.exitValue != 0) problems += "aapt2 link exited with ${result.exitValue}: $messages"
        messages.forEach { problems += "aapt2 link message on the generated res: $it" }
        lines += "linked: exit ${result.exitValue}, ${messages.size} messages, ${apk.length()} bytes"
    }

    /** Step 4: the forward generator over the generated tree must land on the authored tree and the baseline. */
    private fun fixedPoint(
        mode: GuardMode,
        executable: File,
        forward: PipelineResult,
        authored: Map<String, Map<ResourceKey, TextValue>>,
        encoded: Map<String, String>,
        problems: MutableList<String>,
        lines: MutableList<String>,
    ) {
        // A directory that emitted nothing has no directory in the generated tree and so loses its request; it is
        // carried over explicitly, or the lookup table would simply be missing that locale rather than differ in it.
        val carried = forward.requests.filter { it.note == DIRECTORY_LOCALE && it.id !in authored.keys }
        val findings = Findings(mode)
        val compiler = Aapt2Compiler(exec, executable, File(temporaryDir, "second"), findings)
        val sources = encoded.map { (directory, text) ->
            SourceFile("$directory/strings.xml", text.toByteArray(Charsets.UTF_8))
        }
        val second = try {
            StringsPipeline(findings, compiler).run(
                sources,
                emptyList(),
                StringsOracles.parseRequests(parityRequests.get().asFile.readText()) + carried,
                localeCopies.get(),
            )
        } catch (e: StringsBuildException) {
            throw GradleException("verifyStringsRoundTrip, second pass: ${e.message}", e)
        }
        if (carried.isNotEmpty()) {
            lines += "carried over ${carried.size} request(s) whose directory emits nothing: " +
                carried.joinToString { "${it.id} (${it.androidTag})" }
        }
        second.report.forEach { lines += "second pass: $it" }
        findings.all.filter { it.kind.severity != Severity.INFO }
            .forEach { problems += "second pass finding: $it" }

        val reasons = second.report.single { it.startsWith(REASONS) }
        val expected = "$REASONS {OWN=${expectedOwn.get()}}"
        if (reasons != expected) problems += "fixed point not reached: expected '$expected', got '$reasons'"

        compareTree(authored, second, problems, lines)
        compareLookup(second, problems, lines)
    }

    /** The second pass must re-emit the authored Compose tree, `localeCopies` and all, byte-for-byte. */
    private fun compareTree(
        authored: Map<String, Map<ResourceKey, TextValue>>,
        second: PipelineResult,
        problems: MutableList<String>,
        lines: MutableList<String>,
    ) {
        val expected = LinkedHashMap<String, Map<ResourceKey, TextValue>>(authored)
        localeCopies.get().toSortedMap().forEach { (target, source) ->
            authored[source]?.let { expected[target] = it }
        }
        (expected.keys - second.emitted.keys).forEach { problems += "second pass emits no $it" }
        (second.emitted.keys - expected.keys).forEach { problems += "second pass emits an unexpected $it" }
        var identical = 0
        expected.forEach { (directory, values) ->
            val actual = second.emitted[directory] ?: return@forEach
            if (CmpStringsXml.write(actual) == CmpStringsXml.write(values)) {
                identical++
            } else {
                val keys = (values.keys - actual.keys).map { "-$it" } + (actual.keys - values.keys).map { "+$it" } +
                    values.filter { (k, v) -> actual[k] != null && actual[k] != v }
                        .map { (k, v) -> "$k: ${describe(v)} -> ${describe(actual[k])}" }
                problems += "$directory/strings.xml differs from the authored tree: ${keys.take(4)}"
            }
        }
        lines += "authored Compose tree re-emitted byte-for-byte: $identical of ${expected.size} directories"
    }

    /** Every request x key cell must hold the value the pre-flip baseline froze. */
    private fun compareLookup(second: PipelineResult, problems: MutableList<String>, lines: MutableList<String>) {
        fun rows(text: String) = text.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBeforeLast('\t') to it.substringAfterLast('\t') }
        val baseline = rows(baselineLookup.get().asFile.readText())
        val live = rows(StringsOracles.lookup(second.lookup))
        val removed = (baseline.keys - live.keys).sorted()
        val added = (live.keys - baseline.keys).sorted()
        val changed = baseline.keys.intersect(live.keys).filter { baseline[it] != live[it] }.sorted()
        removed.forEach { problems += "lookup cell lost: $it (was '${baseline[it]}')" }
        added.forEach { problems += "lookup cell invented: $it ('${live[it]}')" }
        changed.forEach { problems += "lookup cell changed: $it: '${baseline[it]}' -> '${live[it]}'" }
        lines += "lookup diff against the frozen baseline: ${removed.size + added.size + changed.size} lines " +
            "(${baseline.size} baseline cells, ${live.size} round-trip cells)"
    }


    /** aapt2's decoded value, required to be plain text: a quoted value must never come back styled or a reference. */
    private fun plainTextValue(value: DecodedValue): TextValue? = when (value) {
        is DecodedValue.Single -> (value.item as? DecodedItem.Text)?.let { TextValue.Single(it.text) }
        is DecodedValue.Plural -> value.items
            .map { (category, item) -> category to (item as? DecodedItem.Text ?: return null).text }
            .let { TextValue.Plural(it.toMap()) }
        is DecodedValue.Other -> null
    }

    private fun describe(value: TextValue?): String = when (value) {
        null -> "<not plain text>"
        is TextValue.Single -> Tsv.escape(value.text)
        is TextValue.Plural -> value.items.entries.joinToString(" ") { "${it.key}=${Tsv.escape(it.value)}" }
    }

    private companion object {
        const val PROBLEMS_SHOWN = 20
        const val REASONS = "emitted values by reason:"
        const val DIRECTORY_LOCALE = "directory locale"
        const val LINK_PACKAGE = "com.lagradost.cloudstream4.core.strings.roundtrip"
    }
}
