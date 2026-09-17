import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * [cloudstream.kmp.library] plus Compose Multiplatform: the Compose Gradle plugin, the Kotlin
 * Compose compiler plugin, and the `compose` bundle from `gradle/libs.versions.toml`.
 *
 * The bundle is added as `api` rather than `implementation` because a design-system module's
 * public surface *is* Compose types (`Color`, `TextStyle`, `Dp`, `Shape`, `Modifier`); consumers
 * could not read that surface through an `implementation` edge.
 *
 * ## `compose-ui-tooling` is not in `commonMain`
 *
 * `libs.bundles.compose` carries `compose-ui-tooling(-preview)` in main rather than debug-only,
 * matching `:shared`, so `@Preview` works in every module. This plugin adds every entry of the
 * bundle to `commonMain` **except `compose-ui-tooling`**, which goes to `jvmMain` and to
 * `androidRuntimeClasspath` instead:
 *
 *  - `org.jetbrains.compose.ui:ui-tooling` publishes only Android and desktop variants — no iOS,
 *    no native. In `commonMain` it makes every module on this plugin unable to add an iOS target:
 *    the `commonMain` dependency fails to resolve for that platform.
 *  - Nothing in `commonMain` needs it. `@Preview` comes from `compose-ui-tooling-preview`, which
 *    does publish every platform and stays in `commonMain`. `ui-tooling` is the preview
 *    *renderer*, which only the IDE and preview tooling load.
 *
 * Consequence: `ui-tooling` is no longer on the Android *compile* classpath of these modules, nor
 * exported to their Android consumers. Consumers that render previews declare their own tooling
 * (`:core:screenshot` does).
 */

plugins {
    id("cloudstream.kmp.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val composeUiTooling = composeCatalog.findLibrary("compose-ui-tooling").get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The bundle minus ui-tooling: no iOS variant. See the KDoc above.
            val toolingModule = composeUiTooling.get().module
            composeCatalog.findBundle("compose").get().get()
                .filterNot { it.module == toolingModule }
                .forEach { api(it) }
        }
        jvmMain.dependencies {
            // Desktop preview rendering, which commonMain used to supply.
            api(composeUiTooling)
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
