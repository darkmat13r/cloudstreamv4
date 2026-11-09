package strings

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Hands the generator's oracle directory to the parity jvmTest as `-Dstrings.oracles=<dir>`.
 *
 * The directory is a relative-path-sensitive input, so the absolute path in the JVM argument never enters the test
 * task's cache key and a cache hit relocates across checkouts.
 */
abstract class StringsOracleArgument : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val oracleDir: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-Dstrings.oracles=${oracleDir.get().asFile.absolutePath}")
}

/**
 * Hands the checked-in pre-flip baseline to the jvmTest as `-Dstrings.baseline=<dir>`, on the same terms as
 * [StringsOracleArgument]: a relative-path-sensitive input, so the absolute path never enters the cache key.
 *
 * Only `:core:strings` sets this; `:core:strings:fixtures` runs the same pipeline over its own adversarial
 * resources and has no baseline of its own.
 */
abstract class StringsBaselineArgument : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineDir: DirectoryProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dstrings.baseline=${baselineDir.get().asFile.absolutePath}")
}

/**
 * Hands the repository root to `StringResourceArgumentsGuardTest` as `-Dstrings.callSites=<root>` (`BUG-84`).
 *
 * The guard reads every main-source Kotlin file itself; [sources] declares those files as a relative-path-sensitive
 * input, so an edit to any of them re-runs the guard rather than leaving an up-to-date result from before the edit.
 * [root] is only the place to start reading, and is not part of the cache key.
 */
abstract class StringsCallSitesArgument : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val root: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-Dstrings.callSites=${root.get().asFile.absolutePath}")
}
