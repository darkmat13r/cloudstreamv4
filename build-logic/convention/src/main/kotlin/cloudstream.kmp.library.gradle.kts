import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Base convention for every Kotlin Multiplatform library module in CloudStream.
 *
 * It exists so the ~25 modules the migration plan calls for do not each restate
 * `compileSdk` / `minSdk` / `jvmTarget` / opt-ins — drift between hand-copied blocks is what
 * produces the exact runtime failures `gradle/libs.versions.toml` already warns about
 * (`jvmTarget = 11` or `AbstractMethodError` in Compose; `minSdk = 23` for Android TV sticks).
 *
 * ## The `jvm()` target is a purity canary, not a shipping target
 *
 * Nothing ships from the `jvm` target of a `:core:*` module today. It is declared so that the
 * *build fails* the moment an `android.*` import reaches `commonMain`: `commonMain` must compile
 * against the intersection of `android` and `jvm`, and the JVM target has no `android.*` on its
 * classpath. Removing `jvm()` silently turns every `commonMain` source set back into an
 * Android-only source set and the migration becomes cosmetic. Do not remove it.
 */

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.android.lint")
}

val LIBRARY_MODULE_PATH = ":library"
val LIBRARY_OPT_IN_MARKERS = listOf(
    "com.lagradost.cloudstream3.InternalAPI",
    "com.lagradost.cloudstream3.Prerelease",
)

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaTarget = JvmTarget.fromTarget(catalog.findVersion("jvmTarget").get().requiredVersion)

kotlin {
    android {
        // Must be unique per module, or the generated R / BuildConfig classes collide.
        // `:core:theme` -> com.lagradost.cloudstream4.core.theme
        namespace = "com.lagradost.cloudstream4" + project.path.replace(':', '.')

        compileSdk = catalog.findVersion("compileSdk").get().requiredVersion.toInt()
        minSdk = catalog.findVersion("minSdk").get().requiredVersion.toInt()

        compilerOptions {
            jvmTarget.set(javaTarget)
        }

        lint {
            targetSdk = catalog.findVersion("targetSdk").get().requiredVersion.toInt()
        }
    }

    // The purity canary. See the KDoc above before touching this line.
    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")

        // The two markers `:library`, `:shared` and `:app` all opt into, so a module that depends
        // on `:library` never has to remember them.
        //
        // Applied lazily and only when `:library` is actually on the classpath: the markers are
        // declared *in* `:library`, so opting into them from a module that does not depend on it
        // produces two "Opt-in requirement marker ... is unresolved" warnings on every compile.
        // Across ~25 modules that is 50 warnings a build, which is how real warnings get ignored.
        optIn.addAll(
            providers.provider {
                if (dependsOnLibraryModule()) LIBRARY_OPT_IN_MARKERS else emptyList()
            }
        )
    }
}

/** True when this module declares a dependency on `:library` in any source set. */
fun Project.dependsOnLibraryModule(): Boolean = configurations.any { configuration ->
    configuration.dependencies.any { it is ProjectDependency && it.path == LIBRARY_MODULE_PATH }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(javaTarget)
    }
}
