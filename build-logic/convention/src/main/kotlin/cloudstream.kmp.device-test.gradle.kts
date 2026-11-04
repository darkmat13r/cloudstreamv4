import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty

/**
 * Gives a KMP module an `androidDeviceTest` source set: Compose UI tests and real D-pad traversal,
 * on a device, against the module's own screens with fakes (`INF-10`).
 *
 * ## Why a feature's tests live in the feature, not in `:app`
 *
 * The TV screens are in `:feature:*` `commonMain` and mostly have no Android host yet. A test here
 * builds a test APK of this module and its dependencies only, so it needs no `:app`, no extension
 * loading and no network. `:app`'s `androidTest` is for what only the real app can show: navigation.
 *
 * ## `ui-test-manifest` cannot reach a release from here
 *
 * It declares the empty `ComponentActivity` `createComposeRule` launches. It sits on
 * `androidDeviceTest`, which only ever goes into this module's test APK. `:app` does not take it at
 * all: its tests launch the real `MainActivity`.
 *
 * Not part of `check`, because it needs a device: see the sixth gate in `docs/HANDOVER.md`.
 */

plugins {
    id("cloudstream.kmp.base")
    id("org.jetbrains.kotlin.multiplatform")
}

val deviceTestCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    android {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        getByName("androidDeviceTest").dependencies {
            implementation(project(":core:devicetest"))
            implementation(deviceTestCatalog.findLibrary("compose-ui-test-manifest").get())
        }
    }
}

// Compose's resource plugin registers this copy task and gives it an output only through
// `sources.assets`, which is null in a module without `androidResources`. The test APK's resource
// packaging still depends on it, so the build fails on "outputDirectory doesn't have a configured
// value". A test source set has no resources of its own to copy, and enabling `androidResources`
// would change the module's main artifact to fix a test task, so it gets a directory and nothing else.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach {
    val output = property("outputDirectory") as DirectoryProperty
    if (!output.isPresent) output.set(layout.buildDirectory.dir("intermediates/deviceTestComposeAssets"))
}
