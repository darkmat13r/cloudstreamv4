package strings

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
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
