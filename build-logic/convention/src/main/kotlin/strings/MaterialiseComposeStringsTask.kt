package strings

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.MapProperty
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
 * Feeds `compose.resources.customDirectory` from an **authored** Compose values tree, adding the [localeCopies]
 * directories that cannot be checked in (plan S2).
 *
 * `:core:strings` holds its values under `src/commonMain/composeResources`, which is also where Compose
 * Multiplatform looks by convention — but a `customDirectory` for a source set *replaces* that path rather than
 * adding to it (`PrepareComposeResources.kt:36`, a `?:` over `customResourceDirectories[sourceSet.name]`). So the
 * authored tree reaches the artifact only through this task, and the convention path never contributes twice.
 *
 * The copies are the whole reason this task exists. `values-b+he` and `values-b+id` must be byte copies of
 * `values-b+iw` and `values-b+in` (Android resolves `he`/`id` as `iw`/`in`, Compose asks for what the platform
 * reports — see [LocaleCopies]), and a checked-in copy is a second file to keep in step with a translation nobody
 * edits twice. Generating them means they cannot drift.
 *
 * What it asserts, because a resource that silently fails to ship is the failure mode here:
 *
 * - the authored tree holds **nothing but** `values…/strings.xml`. This module is strings only; a drawable or a
 *   nested directory would be copied to a path Compose does not read, or dropped, with a green build either way.
 * - [LocaleCopies.plan] runs over the authored directory names, so a real `values-b+he` added to the tree is
 *   [FindingKind.LOCALE_COPY_COLLISION] — BROKEN, in every guard mode — rather than a translation overwritten by
 *   a copy of Hebrew.
 * - every copy's source directory exists under its canonical name. The authored tree is written by this repo's own
 *   pipeline, so a missing `values-b+iw` is a mistake to report, not a locale to go looking for.
 *
 * It does not parse or validate the XML: Compose's own `XmlValuesConverterTask` reads these files next and fails on
 * anything malformed, and `GeneratedResourcesParityTest` compares every packaged value with the generator's
 * aapt2-decoded oracle. A byte copy is enough here because [CmpStringsXml] output carries no directory name.
 */
@CacheableTask
abstract class MaterialiseComposeStringsTask : DefaultTask() {
    /** The authored Compose values tree. Only [sourceFiles] is read. */
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

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun materialise() {
        val authored = sortedMapOf<String, File>()
        sourceFiles.visit { if (!isDirectory) authored[relativePath.pathString] = file }
        if (authored.isEmpty()) {
            throw GradleException(
                "materialiseComposeStrings: no values files under ${sourceDirectory.get().asFile}",
            )
        }
        val stray = authored.keys.filterNot { it.matches(VALUES_STRINGS) }
        if (stray.isNotEmpty()) {
            throw GradleException(
                "materialiseComposeStrings: the authored tree must hold only values*/strings.xml, found " +
                    "${stray.size}:\n" + stray.joinToString("\n") { "  $it" },
            )
        }

        val directories = authored.keys.map { it.substringBefore('/') }
        val copies = try {
            LocaleCopies.plan(localeCopies.get(), directories, Findings(GuardMode.FAIL))
        } catch (e: StringsBuildException) {
            throw GradleException("materialiseComposeStrings: ${e.message}", e)
        }

        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        authored.forEach { (path, file) -> write(out, path, file.readBytes()) }
        copies.forEach { copy ->
            val source = authored["${copy.source}/strings.xml"]
                ?: throw GradleException(
                    "materialiseComposeStrings: locale copy ${copy.target} needs ${copy.source}/strings.xml, " +
                        "which the authored tree does not hold (it has ${directories.size} directories)",
                )
            write(out, "${copy.target}/strings.xml", source.readBytes())
        }

        logger.info(
            "materialiseComposeStrings: ${authored.size} authored directories, " +
                "${copies.size} locale copies (${copies.joinToString { "${it.target} <- ${it.source}" }})",
        )
    }

    private fun write(out: File, path: String, bytes: ByteArray) {
        File(out, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    private companion object {
        val VALUES_STRINGS = Regex("""values[^/]*/strings\.xml""")
    }
}
