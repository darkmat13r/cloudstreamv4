/**
 * `:core:devicetest` — what device tests share: the D-pad driver and its focus graph (`INF-10`).
 *
 * Test scope only. Nothing depends on it except `androidDeviceTest` source sets (through
 * `cloudstream.kmp.device-test`) and `:app`'s `androidTestImplementation`. The libraries are `api`
 * so a test gets the same versions by depending on this and nothing else.
 *
 * Android-only because `UiDevice` is, and its package has no `com/lagradost/cloudstream3` directory,
 * so `:app:makeJar` never merges it.
 */
plugins {
    id("cloudstream.kmp.android-only")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            api(libs.compose.ui.test.junit4)
            api(libs.androidx.test.uiautomator)
            api(libs.androidx.test.runner)
            api(libs.androidx.test.rules)
            api(libs.ext.junit)
        }
    }
}
