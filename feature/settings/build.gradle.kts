/**
 * `:feature:settings` — the Settings screens (`DS-24`), and the preferences other screens read from them.
 *
 * Screens live in `:feature:*` and storage in the host. `:core:settings` is a different thing: Android's `AppSettings`
 * over Mihon's `PreferenceStore`, with `:library` in its commonMain, so it is iOS-canary exempt. This module has
 * neither, so it stays in the canary, and a host hands it a [com.lagradost.cloudstream4.feature.settings.SettingsStorage].
 * Not merged into `classes.jar`: its package is `cloudstream4`, like every `:feature:*` module.
 */
plugins {
    id("cloudstream.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:ui"))
            implementation(project(":core:designsystem"))
            // Every word in the window comes from here (`I18N-20`), resolved where it is drawn.
            implementation(project(":core:strings"))
        }
        // Parsing, clamping and search are pure Kotlin, so these are the tests that reach iOS.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
