import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import strings.GenerateComposeStringsTask
import strings.LocaleCopies
import strings.MaterialiseComposeStringsTask
import strings.StringsOracleArgument

/**
 * `:core:strings` and its fixtures module: Compose Multiplatform string resources generated from Android
 * `values…/strings.xml` (migration plan §7 Phase 3 S-a).
 *
 * - [cloudstream.kmp.library] (android + the jvm purity canary + the opt-in iOS canary), the Compose Gradle plugin
 *   (resource generation) and the Compose compiler. **Not** `cloudstream.kmp.compose`: commonMain exports only
 *   `compose-runtime` and `compose-resources`, never material3, coil or lifecycle.
 * - `androidResources { enable = true }`: without it no `composeResources` reach an APK (CMP-9547).
 * - `generateComposeStrings` ([GenerateComposeStringsTask]) turns Android res into a Compose values tree. Its
 *   inputs default to `app/src/main/res` (read as files; `:app` is never configured), `visible-donottranslate.txt`
 *   and `parity-requests.txt` in the module directory; a module overrides them on the task.
 * - `materialiseComposeStrings` ([MaterialiseComposeStringsTask]) feeds `compose.resources.customDirectory`, which
 *   carries the task dependency to every resource task, the accessors and IDE sync. By default it copies
 *   `generateComposeStrings`'s output through unchanged, with no `localeCopies` of its own — the generator has
 *   already applied them. A module with an **authored** values tree points `sourceDirectory` at it and hands over
 *   `localeCopies`, which is what `:core:strings` does; see that build file and the task's KDoc.
 * - `localeCopies` defaults to `values-b+he` <- `values-b+iw` and `values-b+id` <- `values-b+in` (Compose asks for
 *   `he`/`id` where Android asks for `iw`/`in`, see `strings.LocaleCopies`); a module sets its own map on the task.
 * - `-Pcloudstream.strings.guards=fail|report` (default `report`; pull requests pass `fail`), see `strings.GuardMode`.
 * - `Res` is public, in `com.lagradost.cloudstream4.core.strings.generated.resources`, generated `always`:
 *   `auto` compares an exact `components-resources` coordinate and silently stops generating on a catalog change.
 * - `jvmTest` gets the oracle directory and the pinned Compose Multiplatform version (`cmp.version`).
 */

plugins {
    id("cloudstream.kmp.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val stringsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    android {
        androidResources {
            enable = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(stringsCatalog.findLibrary("compose-runtime").get())
            api(stringsCatalog.findLibrary("compose-resources").get())
        }
        jvmTest.dependencies {
            implementation(stringsCatalog.findLibrary("kotlin-test").get())
            implementation(stringsCatalog.findLibrary("kotlinx-coroutines-core").get())
        }
    }
}

val androidComponents = extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()

val generateComposeStrings = tasks.register<GenerateComposeStringsTask>("generateComposeStrings") {
    group = "build"
    description = "Generates Compose string resources from Android values*/strings.xml through aapt2."
    aapt2.set(androidComponents.sdkComponents.aapt2)
    resDirectory.convention(layout.settingsDirectory.dir("app/src/main/res"))
    visibleDoNotTranslate.convention(layout.projectDirectory.file("visible-donottranslate.txt"))
    parityRequests.convention(layout.projectDirectory.file("parity-requests.txt"))
    localeCopies.convention(LocaleCopies.DEFAULTS)
    guardMode.set(providers.gradleProperty("cloudstream.strings.guards").orElse("report"))
    outputDir.set(layout.buildDirectory.dir("generated/composeStrings"))
    oracleDir.set(layout.buildDirectory.dir("stringsOracle"))
}

/**
 * The one directory Compose reads values from. Nothing else may be wired into `customDirectory`: it *replaces* the
 * conventional `src/<sourceSet>/composeResources` path rather than adding to it, so whatever this task writes is
 * the whole of the module's resources.
 */
val materialiseComposeStrings = tasks.register<MaterialiseComposeStringsTask>("materialiseComposeStrings") {
    group = "build"
    description = "Assembles the Compose values tree Compose Multiplatform packages, locale copies included."
    sourceDirectory.convention(generateComposeStrings.flatMap { it.outputDir })
    localeCopies.convention(emptyMap())
    outputDir.set(layout.buildDirectory.dir("generated/materialisedComposeStrings"))
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.lagradost.cloudstream4.core.strings.generated.resources"
    generateResClass = always
    customDirectory("commonMain", materialiseComposeStrings.flatMap { it.outputDir })
}

tasks.named<Test>("jvmTest") {
    jvmArgumentProviders.add(
        objects.newInstance<StringsOracleArgument>().apply {
            oracleDir.set(generateComposeStrings.flatMap { it.oracleDir })
        },
    )
    systemProperty("cmp.version", stringsCatalog.findVersion("composeMultiplatform").get().requiredVersion)
}
