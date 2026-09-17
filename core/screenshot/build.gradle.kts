/**
 * Preview screenshot tests for the design system.
 *
 * ## Status: wired correctly, blocked on the plugin
 *
 * `com.android.compose.screenshot:0.0.1-alpha16` — the latest published version, 2026-07-27 —
 * does not render anything on this toolchain (AGP 9.1.1, Gradle 9.4.1). Everything up to the
 * render works: the tasks register, the previews compile, `@Preview` is retained in the bytecode
 * as `RuntimeInvisibleAnnotations` on `public static final` methods, the engine resolves, and
 * `updateDebugScreenshotTest` reports "1 test, 0 failures". It writes no images.
 *
 * That was isolated rather than assumed. A preview containing nothing but `BasicText("hello")`,
 * with no dependency on any module here, renders nothing either — so it is the plugin, not our
 * Compose Multiplatform components.
 *
 * Two things follow, and neither is a configuration mistake to go hunting for:
 *
 *  - **The plugin does not support KMP modules.** Applied to `:core:designsystem`, which uses
 *    `com.android.kotlin.multiplatform.library`, it configures without error and contributes no
 *    tasks at all — it registers `validate*`/`update*` against AGP's variants, which that plugin
 *    does not expose. That is why this module exists and is a plain `com.android.library`.
 *  - **`failOnNoDiscoveredTests = false` is deliberately NOT set here.** It makes the task pass.
 *    It passes by rendering nothing, and a green check that proves nothing is worse than a red
 *    one that tells the truth. Leave the task failing until the plugin works.
 *
 * When a version lands that renders: run the record task, commit the reference images, and the
 * suite in `src/screenshotTest` starts guarding the design system with no further setup.
 *
 * Record:   ./gradlew :core:screenshot:updateDebugScreenshotTest
 * Verify:   ./gradlew :core:screenshot:validateDebugScreenshotTest
 * Report:   core/screenshot/build/reports/tests/updateDebugScreenshotTest/index.html
 *
 * ## Why the previews live here rather than beside the components
 *
 * They have to — see above. It has turned out to be the better arrangement anyway: the suite is
 * a curated set of states someone chose to hold still, rather than whatever `@Preview` functions
 * happen to exist for development convenience.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.lagradost.cloudstream4.core.screenshot"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures {
        compose = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    implementation(project(":core:theme"))
    implementation(project(":core:designsystem"))

    // The preview renderer is AndroidX, so the test source set needs AndroidX tooling rather
    // than the Compose Multiplatform alias the production modules use. The version is a floor:
    // the CMP dependency constrains it upward to match whatever it resolves on Android.
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
