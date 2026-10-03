package strings

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns the **lean** authored tree into the tree Compose Multiplatform packages, which means adding everything
 * Android gets for free and Compose does not.
 *
 * The authored source holds only what each directory declares — the same content as `app/src/main/res`. Compose's
 * resource lookup is not Android's: it never matches `zh-TW` against `zh`, it does not treat `fil` and `tl` as
 * equivalent, and it has no `@string/` indirection. So for a locale to read the way it does on Android, values have
 * to be **copied into** directories that do not declare them. [Materialiser] already decides exactly which, and this
 * task runs that same decision over the authored tree rather than over an aapt2 table:
 *
 * 1. read the committed Compose files ([CmpStringsReader]);
 * 2. present them to [AndroidResolver] as a resource table — every value is already plain text with its references
 *    resolved, so each one is a one-entry table cell;
 * 3. [Materialiser.run] over the keys each directory declares: step 1 reproduces the authored values, step 2 adds
 *    the copies wherever Compose's fallback would otherwise differ from Android's;
 * 4. [LocaleCopies] adds `values-b+he` / `values-b+id` as exact copies of `values-b+iw` / `values-b+in`.
 *
 * **Why the copies are not committed.** They are a Compose concern. Android resolves those same values without
 * them — `verifyStringsRoundTrip` holds the Android lookup to a zero-line diff either way — so writing them into
 * `app/src/main/res` adds hundreds of entries Android does not need, and makes locale files declare plurals holding
 * only the categories their *source* had. Android lint rejects that, correctly: a copied `one`/`other` pair does not
 * carry the `zero`/`two`/`few`/`many` that Arabic requires. Keeping them derived keeps the res exactly as lean as it
 * has always been, and keeps that lint signal pointed at real translation gaps instead of at generated copies.
 *
 * A `customDirectory` **replaces** the conventional `src/<sourceSet>/composeResources` path rather than adding to it
 * (`PrepareComposeResources.kt:36`), so what this task writes is the whole of the module's resources.
 */
@CacheableTask
abstract class MaterialiseComposeStringsTask : DefaultTask() {
    /** The lean authored Compose values tree. Only [sourceFiles] is read. */
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    /** Everything in the tree, not just the files this task expects: a stray file has to be visible to fail on. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    val sourceFiles: FileTree
        get() = sourceDirectory.asFileTree

    /** Target -> source directory, as `GenerateComposeStringsTask.localeCopies`. */
    @get:Input
    abstract val localeCopies: MapProperty<String, String>

    /** The `@string/…` references the res holds; restored here so they resolve per locale ([AuthoredAliases]). */
    @get:org.gradle.api.tasks.InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aliases: org.gradle.api.file.RegularFileProperty

    /** `fail` or `report`, as `GenerateComposeStringsTask.guardMode`. */
    @get:Input
    abstract val guardMode: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun materialise() {
        val files = sortedMapOf<String, File>()
        sourceFiles.visit { if (!isDirectory) files[relativePath.pathString] = file }
        if (files.isEmpty()) {
            throw GradleException(
                "materialiseComposeStrings: no values files under ${sourceDirectory.get().asFile}",
            )
        }
        val stray = files.keys.filterNot { it.matches(VALUES_STRINGS) }
        if (stray.isNotEmpty()) {
            throw GradleException(
                "materialiseComposeStrings: the authored tree must hold only values*/strings.xml, found " +
                    "${stray.size}:\n" + stray.joinToString("\n") { "  $it" },
            )
        }

        val findings = Findings(GuardMode.parse(guardMode.get()))
        val authored = files.entries.associate { (path, file) ->
            path.substringBefore('/') to CmpStringsReader.read(file.readText(Charsets.UTF_8))
        }
        val qualifiers = authored.keys.associateWith { directory ->
            LocaleQualifier.parseDirectory(directory)
                ?: throw GradleException("materialiseComposeStrings: '$directory' is not a modelled locale directory")
        }
        val copies = try {
            LocaleCopies.plan(localeCopies.get(), authored.keys.toList(), findings)
        } catch (e: StringsBuildException) {
            throw GradleException("materialiseComposeStrings: ${e.message}", e)
        }

        // Every authored value is plain text with its references already resolved, so the table AndroidResolver
        // reads is just the authored tree restated — no aapt2 run, and no second opinion about what a value is.
        val aliasRows = AuthoredAliases.parse(aliases.get().asFile.readText())
        val table = authored.mapValues { (directory, values) ->
            val byKey = AuthoredAliases.byKey(aliasRows, directory)
            values.mapValues { (key, value) ->
                val decoded = byKey[key]?.let { AuthoredAliases.decoded(key, it) } ?: asDecoded(value)
                TableEntry("$directory/strings.xml", decoded)
            }
        }
        val visible = authored.values.flatMap { it.keys }.toSortedSet()
        val resolver = AndroidResolver(table, qualifiers, findings)
        val materialiser = Materialiser(qualifiers, visible, resolver, findings)
        materialiser.run(authored.mapValues { it.value.keys })

        val report = mutableListOf(
            "authored: ${authored.size} directories, ${authored.values.sumOf { it.size }} values",
            "materialised by reason: ${materialiser.reasons.toSortedMap()}",
        )
        report += LocaleCopies.apply(copies, materialiser, qualifiers)

        // The authored values must survive untouched: step 1 writes Android's value for each directory's own
        // locale, and for a tree whose references are already resolved that has to be the authored value itself.
        val problems = mutableListOf<String>()
        val aliasedKeys = aliasRows.groupBy({ it.directory }, { it.key }).mapValues { it.value.toSet() }
        authored.forEach { (directory, values) ->
            values.forEach { (key, value) ->
                // An aliased key resolves per locale, so its emitted value is the target's value for this
                // directory and need not equal the literal the authored tree carries for it.
                if (key in aliasedKeys[directory].orEmpty()) return@forEach
                val out = materialiser.emitted[directory]?.get(key)
                if (out != value) problems += "$directory $key: authored value was changed to ${out ?: "nothing"}"
            }
        }
        findings.all.filter { it.kind.severity != Severity.INFO }
            .forEach { problems += "finding: $it" }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "materialiseComposeStrings:\n" + problems.take(PROBLEMS_SHOWN).joinToString("\n") { "  $it" } +
                    (if (problems.size > PROBLEMS_SHOWN) "\n  … ${problems.size - PROBLEMS_SHOWN} more" else ""),
            )
        }

        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        materialiser.emitted.filterValues { it.isNotEmpty() }.forEach { (directory, entries) ->
            File(out, "$directory/strings.xml").apply { parentFile.mkdirs() }
                .writeText(CmpStringsXml.write(entries), Charsets.UTF_8)
        }
        report += "written: ${materialiser.emitted.count { it.value.isNotEmpty() }} directories, " +
            "${materialiser.emitted.values.sumOf { it.size }} values"
        report.forEach { logger.info(it) }
    }

    private fun asDecoded(value: TextValue): DecodedValue = when (value) {
        is TextValue.Single -> DecodedValue.Single(DecodedItem.Text(value.text))
        is TextValue.Plural -> DecodedValue.Plural(value.items.map { it.key to DecodedItem.Text(it.value) })
    }

    private companion object {
        val VALUES_STRINGS = Regex("""values[^/]*/strings\.xml""")
        const val PROBLEMS_SHOWN = 20
    }
}
