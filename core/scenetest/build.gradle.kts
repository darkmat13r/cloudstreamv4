/**
 * `:core:scenetest` — the scene a desktop UI test draws in (`INF-13`): `testScene` and `frame`, so a scene's
 * coroutines run only on the test thread (`BUG-108`).
 *
 * Test scope only, the jvm counterpart of `:core:devicetest`. Nothing depends on it except `jvmTest` source sets
 * (`:core:designsystem`, `:desktopApp`), and it ships nowhere. JVM only, like `:desktopApp`, because
 * `ImageComposeScene` is. Its package has no `com/lagradost/cloudstream3` directory, so `:app:makeJar` never merges it.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {}
    sourceSets {
        jvmMain.dependencies {
            // Skia and the desktop scene. `api`: a test gets the same versions by depending on this alone.
            api(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
