package strings

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `app/src/main/res/values…/strings.xml` from the **authored** Compose tree ([AndroidStringsXml]), or checks
 * that the committed res is what the authored tree would produce.
 *
 * This is the direction the strings flip reverses to: the Compose values under
 * `core/strings/src/commonMain/composeResources` are edited, and Android res is generated from them. It follows the
 * repo's existing one-shot pattern — `dumpExtensionApi` writes, `checkExtensionApi` guards — because the res has to
 * be committed: `R.string` field names are published extension ABI, and a generated-at-build-time res would make
 * every one of them invisible to review.
 *
 * ## Why it reads the authored tree rather than re-deriving it
 *
 * `verifyStringsRoundTrip` derives a tree by running the forward generator over `app/src/main/res`. Driving the
 * writer from *that* would be a loop with no fixed point to hold it: edit a value in the authored tree, and the
 * forward pass would still see the old one in res and write it straight back, silently undoing the edit. So this
 * task reads the committed Compose files through [CmpStringsReader] and nothing else.
 *
 * ## What it refuses
 *
 * - A directory in the authored tree that is a `localeCopies` **target** ([LocaleCopies]) is never written: those
 *   are Compose-only names for locales Android spells differently, and `values-b+he` appearing in Android res is
 *   [FindingKind.LOCALE_COPY_COLLISION] by construction. The authored tree holds none today, and this keeps it so.
 * - A `values…/strings.xml` in the res that the authored tree has no directory for is reported, not ignored —
 *   except the names in [unmanagedDirectories], which are listed explicitly so that "this one is not generated" is
 *   a decision in the build file rather than a silence here.
 */
abstract class AndroidStringsTask : DefaultTask() {
    /** The authored Compose values tree. Only [authoredFiles] is read. */
    @get:Internal
    abstract val authoredDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    val authoredFiles: FileTree
        get() = authoredDirectory.asFileTree.matching { include("values*/strings.xml") }

    /** `app/src/main/res`. Written in place when [check] is false. */
    @get:Internal
    abstract val resDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    val resFiles: FileTree
        get() = resDirectory.asFileTree.matching { include("values*/strings.xml") }

    /** The `@string/…` references the res holds, so it is written back as it was; [AuthoredAliases]. */
    @get:org.gradle.api.tasks.InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aliases: org.gradle.api.file.RegularFileProperty

    /** Target -> source, as `GenerateComposeStringsTask.localeCopies`. Only the target names are used. */
    @get:Input
    abstract val localeCopyTargets: SetProperty<String>

    /** `values*` directories of the res that this task does not generate, and why, in the build file. */
    @get:Input
    abstract val unmanagedDirectories: SetProperty<String>

    /** true: compare and fail on any difference. false: write the res. */
    @get:Input
    abstract val check: Property<Boolean>

    @get:OutputFile
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun run() {
        val authored = sortedMapOf<String, File>()
        authoredFiles.visit { if (!isDirectory) authored[relativePath.pathString.substringBefore('/')] = file }
        if (authored.isEmpty()) {
            throw GradleException("androidStrings: no values files under ${authoredDirectory.get().asFile}")
        }
        val copyTargets = localeCopyTargets.get()
        val forbidden = authored.keys.filter { it in copyTargets }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "androidStrings: the authored tree holds localeCopies targets, which must never become Android " +
                    "res directories: $forbidden",
            )
        }

        val aliasRows = AuthoredAliases.parse(aliases.get().asFile.readText())
        val unplaced = aliasRows.map { it.directory }.toSet() - authored.keys
        if (unplaced.isNotEmpty()) {
            throw GradleException(
                "androidStrings: ${aliases.get().asFile.name} names directories the authored tree does not " +
                    "have: ${unplaced.sorted()}",
            )
        }
        val encoded = authored.mapValues { (directory, file) ->
            AndroidStringsXml.write(
                CmpStringsReader.read(file.readText(Charsets.UTF_8)),
                AuthoredAliases.byKey(aliasRows, directory),
            )
        }
        val res = resDirectory.get().asFile
        val unmanaged = unmanagedDirectories.get()
        val existing = sortedSetOf<String>()
        resFiles.visit { if (!isDirectory) existing += relativePath.pathString.substringBefore('/') }

        val problems = mutableListOf<String>()
        val lines = mutableListOf<String>()
        (existing - encoded.keys - unmanaged).forEach {
            problems += "$it/strings.xml exists in the res but the authored tree has no such directory"
        }

        var written = 0
        var same = 0
        encoded.forEach { (directory, text) ->
            val target = File(res, "$directory/strings.xml")
            val current = if (target.isFile) target.readText(Charsets.UTF_8) else null
            when {
                current == text -> same++
                check.get() -> problems += if (current == null) {
                    "$directory/strings.xml is missing from the res"
                } else {
                    "$directory/strings.xml differs from the authored tree (${diffHint(current, text)})"
                }
                else -> {
                    target.parentFile.mkdirs()
                    target.writeText(text, Charsets.UTF_8)
                    written++
                }
            }
        }

        lines += if (check.get()) {
            "checked ${encoded.size} directories against the authored tree: $same identical, ${problems.size} problem(s)"
        } else {
            "wrote ${encoded.size} directories from the authored tree: $written changed, $same already identical"
        }
        if (unmanaged.isNotEmpty()) lines += "not generated, by configuration: ${unmanaged.sorted()}"
        reportFile.get().asFile.apply { parentFile.mkdirs() }
            .writeText((lines + problems.map { "problem: $it" }).joinToString("\n", postfix = "\n"))
        lines.forEach { logger.lifecycle(it) }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "androidStrings:\n" + problems.take(PROBLEMS_SHOWN).joinToString("\n") { "  $it" } +
                    (if (problems.size > PROBLEMS_SHOWN) "\n  … ${problems.size - PROBLEMS_SHOWN} more" else "") +
                    "\nThe authored Compose tree is the source. Run :core:strings:writeAndroidStrings and review " +
                    "the res diff; do not edit app/src/main/res/values*/strings.xml by hand.",
            )
        }
    }

    private fun diffHint(current: String, expected: String): String {
        val a = current.lines()
        val b = expected.lines()
        val first = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) }
        return "${a.size} lines -> ${b.size}, first differing line ${first?.plus(1) ?: "none"}"
    }

    private companion object {
        const val PROBLEMS_SHOWN = 20
    }
}
