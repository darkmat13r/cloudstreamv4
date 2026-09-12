import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * [cloudstream.kmp.library] plus Compose Multiplatform: the Compose Gradle plugin, the Kotlin
 * Compose compiler plugin, and the `compose` bundle from `gradle/libs.versions.toml`.
 *
 * The bundle is added as `api` rather than `implementation` because a design-system module's
 * public surface *is* Compose types (`Color`, `TextStyle`, `Dp`, `Shape`, `Modifier`); consumers
 * could not read that surface through an `implementation` edge.
 *
 * Note that `libs.bundles.compose` intentionally carries `compose-ui-tooling(-preview)` in main
 * rather than debug-only, matching `:shared`, so `@Preview` works in every module.
 */

plugins {
    id("cloudstream.kmp.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(composeCatalog.findBundle("compose").get())
        }
    }
}

dependencies {
    // AGP-9-KMP specific: this is what makes the tooling previews resolve on Android.
    "androidRuntimeClasspath"(composeCatalog.findLibrary("compose-ui-tooling").get())
}

compose.resources {
    // Must NOT be com.lagradost.cloudstream4.generated.resources — `:shared` owns that package
    // and two `Res` classes with the same FQN collide on the classpath.
    packageOfResClass = "com.lagradost.cloudstream4" + project.path.replace(':', '.') + ".generated.resources"
    publicResClass = false
}
